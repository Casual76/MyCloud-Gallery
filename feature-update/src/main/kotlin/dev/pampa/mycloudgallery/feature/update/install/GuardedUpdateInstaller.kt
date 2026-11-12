package dev.pampa.mycloudgallery.feature.update.install

import dev.antigravity.fluidengine.foundation.AppUpdateErrorKind
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.update.AppUpdateInstaller
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.policy.UpdateRules
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// L'INSTALLAZIONE CON LE GARANZIE DELL'APP (pacchetto U, 12/11/2026; docs/sicurezza.md C480, C481, C484, C485). L'engine 3.0.4 fa in `install()`
// scaricamento, sha256 (solo se c'e'), pacchetto, versionName e sessione in un colpo solo: non c'e' un punto per controllare versionCode e firma.
// Qui si usano le sue due meta' pubbliche (`download`, `installFile`, dalla 2.10.0) e in mezzo i controlli dell'app:
//
//  1. l'offerta: sha256 e peso OBBLIGATORI, nomi, indirizzo del repository delle release (`UpdateRules.offerProblem`), prima di scaricare un byte;
//  2. il permesso «installa app sconosciute»: se manca ci si ferma PRIMA dello scaricamento e lo si dice (la pagina lo chiede con una riga e riprende
//     al ritorno); l'engine aprirebbe le impostazioni da solo, senza spiegare;
//  3. lo scaricamento dell'engine (con il client avvolto: host ammessi, tetti);
//  4. sha256 del file contro l'elenco, PRIMA di dare il file da leggere ad Android;
//  5. pacchetto = questa app, versionCode STRETTAMENTE maggiore, certificati di firma = quelli dell'app installata;
//  6. solo allora `installFile` dell'engine (che ricontrolla sha256, pacchetto e versionName) e la conferma di Android.
//
// Il file sta nella cache dell'app (`cacheDir/engine_updates/`) e si cancella a ogni esito che chiude il flusso; se Android termina il processo
// mentre si installa (l'aggiornamento riuscito fa proprio questo) lo toglie `UpdateCache.clear` all'avvio dopo. Nessuna installazione parte senza
// un tocco: questo flusso lo avvia solo `AppUpdates.install`, dal tasto «Aggiorna».

/** Dove sta un'installazione, per la pagina e per l'avviso. */
sealed interface InstallStep {
  data object Preparing : InstallStep

  /** Manca il permesso d'installare da questa app: Android lo chiede una volta. Il flusso si ferma qui; riparte al ritorno. */
  data object NeedsPermission : InstallStep

  /** [progress] 0..1 sul peso dell'elenco (sempre noto: e' obbligatorio). */
  data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : InstallStep

  data object Verifying : InstallStep

  data object Installing : InstallStep

  /** Android mostra la sua conferma: niente si muove finche' l'utente non risponde. */
  data object AwaitingUser : InstallStep

  data object Installed : InstallStep

  /** Fermata: [problem] dice perche'; [detail] il messaggio dell'engine quando e' Android a non installare. */
  data class Failed(val problem: UpdateProblem, val detail: String? = null) : InstallStep
}

/** Chi esegue un'installazione: [GuardedUpdateInstaller] in produzione, un finto nelle prove della pagina. */
fun interface UpdateRunner {
  fun run(update: AvailableAppUpdate): Flow<InstallStep>
}

class GuardedUpdateInstaller(
  private val transfer: ApkTransfer,
  private val facts: PackageFactsSource,
  private val permission: InstallPermission,
  private val checksum: FileChecksum = Sha256Files,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateRunner, AppUpdateInstaller {

  override fun run(update: AvailableAppUpdate): Flow<InstallStep> = channelFlow {
    send(InstallStep.Preparing)
    UpdateRules.offerProblem(update)?.let { problem ->
      send(InstallStep.Failed(problem))
      return@channelFlow
    }
    if (!permission.granted()) {
      send(InstallStep.NeedsPermission)
      return@channelFlow
    }
    val file = try {
      transfer.download(update) { step -> trySend(InstallStep.Downloading(step.progress, step.downloadedBytes, step.totalBytes)) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      send(InstallStep.Failed(UpdateProblem.DOWNLOAD_FAILED))
      return@channelFlow
    }
    send(InstallStep.Verifying)
    val problem = try {
      withContext(io) {
        UpdateRules.checksumProblem(update.sha256, checksum.sha256(file))
          ?: UpdateRules.archiveProblem(facts.installed(), facts.archive(file))
      }
    } catch (cancelled: CancellationException) {
      discard(file)
      throw cancelled
    } catch (failure: Exception) {
      UpdateProblem.UNREADABLE
    }
    if (problem != null) {
      discard(file)
      send(InstallStep.Failed(problem))
      return@channelFlow
    }
    try {
      transfer.installFile(file, expectedVersionName = update.version, sha256 = update.sha256).collect { state ->
        when (state) {
          is AppUpdateInstallState.Downloading -> Unit
          is AppUpdateInstallState.Verifying -> send(InstallStep.Verifying)
          is AppUpdateInstallState.Installing -> send(InstallStep.Installing)
          is AppUpdateInstallState.AwaitingUserAction -> send(InstallStep.AwaitingUser)
          is AppUpdateInstallState.Installed -> {
            discard(file)
            send(InstallStep.Installed)
          }
          is AppUpdateInstallState.Error -> {
            discard(file)
            send(
              if (state.kind == AppUpdateErrorKind.InstallPermission) {
                InstallStep.NeedsPermission
              } else {
                InstallStep.Failed(UpdateProblem.INSTALL_FAILED, state.message.ifBlank { null })
              },
            )
          }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      discard(file)
      send(InstallStep.Failed(UpdateProblem.INSTALL_FAILED))
    }
  }

  /**
   * Lo stesso flusso con gli stati dell'engine: chi chiamasse `EngineAppUpdater.install` (che delega qui) passa dagli stessi controlli. L'app usa
   * [run]; gli errori dell'app hanno qui per messaggio il loro codice stabile.
   */
  override fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> = run(update).map { step ->
    when (step) {
      InstallStep.Preparing -> AppUpdateInstallState.Verifying("")
      InstallStep.NeedsPermission -> AppUpdateInstallState.Error("", AppUpdateErrorKind.InstallPermission)
      is InstallStep.Downloading -> AppUpdateInstallState.Downloading(step.progress, step.downloadedBytes, step.totalBytes)
      InstallStep.Verifying -> AppUpdateInstallState.Verifying("")
      InstallStep.Installing -> AppUpdateInstallState.Installing("")
      InstallStep.AwaitingUser -> AppUpdateInstallState.AwaitingUserAction("")
      InstallStep.Installed -> AppUpdateInstallState.Installed("")
      is InstallStep.Failed -> AppUpdateInstallState.Error(step.detail ?: step.problem.code)
    }
  }

  private fun discard(file: File) {
    runCatching { file.delete() }
  }
}

/** La cartella dei download dell'engine nella cache dell'app, svuotata all'avvio: un APK lasciato da un'installazione di prima non resta. */
object UpdateCache {
  const val DIR: String = "engine_updates"

  fun clear(cacheDir: File): Int {
    val dir = File(cacheDir, DIR)
    val files = dir.listFiles().orEmpty()
    return files.count { it.isFile && it.delete() }
  }
}
