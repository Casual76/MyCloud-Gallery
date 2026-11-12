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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// L'INSTALLAZIONE CON LE GARANZIE DELL'APP (pacchetto U, 12/11/2026; docs/sicurezza.md C480, C481, C484, C485, C487). L'engine 3.0.4 fa in `install()`
// scaricamento, sha256 (solo se c'e'), pacchetto, versionName e sessione in un colpo solo: non c'e' un punto per controllare versionCode e firma, ne' per
// fermarsi fra il file pronto e la sessione. Qui si usano le sue due meta' pubbliche (`download`, `installFile`, dalla 2.10.0), in DUE TEMPI:
//
// [prepare] (dal tocco su «Aggiorna»):
//  1. l'offerta: sha256 e peso OBBLIGATORI, forma della versione, nomi, indirizzo del repository delle release (`UpdateRules.offerProblem`);
//  2. il permesso «installa app sconosciute»: se manca ci si ferma PRIMA dello scaricamento (la pagina lo chiede e riprende al ritorno); l'engine
//     aprirebbe le impostazioni da solo, senza spiegare;
//  3. lo scaricamento dell'engine (con il client avvolto: host ammessi, tetti);
//  4. sha256 del file contro l'elenco, poi pacchetto = questa app, versionName = quello annunciato, versionCode STRETTAMENTE maggiore, certificati di
//     firma = quelli dell'app installata, PRIMA di dare il file a qualunque sessione;
//  5. [InstallStep.Ready]: il file verificato resta nella cache privata e si aspetta (giro 1, UU-1: se l'app non e' davanti, «Pronta» con «Installa»).
//
// [commit] (subito dopo [prepare], dal tocco su «Installa», al ritorno dal permesso): gli stessi controlli del punto 4 di nuovo sul file, poi IL
// CANCELLO (giro 2): solo con l'activity dell'app RESUMED ([inFront]) il file va a `installFile` dell'engine (che ricontrolla sha256, pacchetto e
// versionName) e alla conferma di Android; altrimenti resta «Pronta». La lettura del cancello e l'ingresso nella porta sono un passo solo sul main
// (giro 3). Ogni messaggio dell'engine diventa un [UpdateProblem]
// ([EngineMessages]): nessuna frase dell'engine arriva a chi usa l'app (UU-6). «Annullata» nella finestra di Android non e' un guasto: si torna a «Pronta».
//
// Il file sta in `cacheDir/engine_updates/` (privato dell'app) e si cancella quando non serve piu' (installata, un problema del file, «Annulla»);
// se Android termina il processo mentre installa (l'aggiornamento riuscito fa proprio questo) lo toglie `UpdateCache.clear` all'avvio dopo. Nessuna
// installazione parte senza un tocco: [prepare] lo avvia solo `AppUpdates.install`, dal tasto «Aggiorna».

/** Dove sta un'installazione, per la pagina e per l'avviso. */
sealed interface InstallStep {
  data object Preparing : InstallStep

  /** Manca il permesso d'installare da questa app: Android lo chiede una volta. Il flusso si ferma qui; riparte al ritorno. */
  data object NeedsPermission : InstallStep

  /** [progress] 0..1 sul peso dell'elenco (sempre noto: e' obbligatorio). */
  data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : InstallStep

  data object Verifying : InstallStep

  /**
   * Scaricata e controllata: il file verificato ([file]) aspetta «Installa». [interrupted]: Android ha chiuso la sua finestra senza installare (l'utente ha
   * toccato «Annulla», oppure Play Protect l'ha fermata: Android non dice quale dei due).
   */
  data class Ready(val file: File, val interrupted: Boolean = false) : InstallStep

  data object Installing : InstallStep

  /** Android mostra la sua conferma: niente si muove finche' l'utente non risponde. */
  data object AwaitingUser : InstallStep

  data object Installed : InstallStep

  /** Fermata: [problem] dice perche' (le parole sono dell'app, mai dell'engine). */
  data class Failed(val problem: UpdateProblem) : InstallStep
}

/** Chi esegue un'installazione: [GuardedUpdateInstaller] in produzione, un finto nelle prove. */
interface UpdateInstaller {
  /** Dal tocco su «Aggiorna» al file verificato: finisce con [InstallStep.Ready], [InstallStep.NeedsPermission] o [InstallStep.Failed]. */
  fun prepare(update: AvailableAppUpdate): Flow<InstallStep>

  /**
   * Il file pronto, ricontrollato, alla sessione di Android, solo se l'app e' davanti DOPO i controlli; altrimenti finisce con [InstallStep.Ready].
   * Passato il cancello: [InstallStep.Installing], poi gli stati di Android.
   */
  fun commit(update: AvailableAppUpdate, file: File): Flow<InstallStep>

  /** Toglie il file («Annulla» su «Pronta», una versione che non vale piu'). */
  fun discard(file: File)
}

class GuardedUpdateInstaller(
  private val transfer: ApkTransfer,
  private val facts: PackageFactsSource,
  private val permission: InstallPermission,
  /** Il cancello ([ForegroundGate.inFront]): vero solo con l'activity dell'app RESUMED. Si guarda subito prima di `installFile`, mai prima. */
  private val inFront: () -> Boolean,
  private val checksum: FileChecksum = Sha256Files,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /**
   * Il thread del ciclo di vita (giro 3, US3-1): la lettura finale del cancello e l'ingresso in `installFile` ci stanno in un solo passo, senza
   * sospensioni in mezzo. ON_PAUSE e' un compito dello stesso thread: o arriva prima della lettura, o dopo l'ingresso, mai fra i due.
   */
  private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : UpdateInstaller, AppUpdateInstaller {

  override fun prepare(update: AvailableAppUpdate): Flow<InstallStep> = channelFlow {
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
    val problem = verify(update, file)
    if (problem != null) {
      discard(file)
      send(InstallStep.Failed(problem))
      return@channelFlow
    }
    send(InstallStep.Ready(file))
  }

  override fun commit(update: AvailableAppUpdate, file: File): Flow<InstallStep> = channelFlow {
    if (!file.isFile) {
      send(InstallStep.Failed(UpdateProblem.DOWNLOAD_FAILED))
      return@channelFlow
    }
    if (!permission.granted()) {
      send(InstallStep.NeedsPermission)
      return@channelFlow
    }
    send(InstallStep.Verifying)
    // Di nuovo, sul file che va davvero alla sessione: fra «Pronta» e «Installa» puo' passare del tempo.
    val problem = verify(update, file)
    if (problem != null) {
      discard(file)
      send(InstallStep.Failed(problem))
      return@channelFlow
    }
    // IL CANCELLO, uno solo per tutte le strade (fine dello scaricamento, «Installa», ritorno dal permesso), DOPO le verifiche: anche 110 MB di
    // SHA-256 richiedono tempo, e l'utente puo' premere Home o spegnere lo schermo nel frattempo. Con l'app dietro il file resta «Pronta» e nessuna
    // sessione parte (giro 2, US2-1). Giro 3 (US3-1): la lettura e l'ingresso nella porta sono UN passo sul main, senza sospensioni dell'app in mezzo
    // (le verifiche restano su IO, sopra). Il tratto dentro l'engine dopo questo punto non e' dell'app: docs/sicurezza.md L616.
    withContext(main) {
      if (!inFront()) {
        send(InstallStep.Ready(file))
        return@withContext
      }
      // Da qui il file e' di Android: «Sto installando» (giro 2, UU2-1), mai piu' «Pronta» finche' non arriva un esito o il tempo dell'attesa. Senza
      // sospendere: `trySend` non apre una finestra fra il cancello e la porta (il canale ha posto).
      trySend(InstallStep.Installing)
      try {
        transfer.installFile(file, expectedVersionName = update.version, sha256 = update.sha256).collect { state ->
          when (state) {
            is AppUpdateInstallState.Downloading, is AppUpdateInstallState.Verifying -> Unit
            is AppUpdateInstallState.Installing -> send(InstallStep.Installing)
            is AppUpdateInstallState.AwaitingUserAction -> send(InstallStep.AwaitingUser)
            is AppUpdateInstallState.Installed -> {
              discard(file)
              send(InstallStep.Installed)
            }
            is AppUpdateInstallState.Error -> send(afterEngineError(state, file))
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        send(InstallStep.Failed(UpdateProblem.INSTALL_FAILED))
      }
    }
  }

  /** Un errore dell'engine nelle parole dell'app: il permesso tolto nel frattempo, «annullata» (si torna a «Pronta»), un problema. */
  private fun afterEngineError(state: AppUpdateInstallState.Error, file: File): InstallStep {
    if (state.kind == AppUpdateErrorKind.InstallPermission) return InstallStep.NeedsPermission
    val problem = EngineMessages.problemOf(state.message) ?: return InstallStep.Ready(file, interrupted = true)
    if (!problem.keepsFile) discard(file)
    return InstallStep.Failed(problem)
  }

  /** sha256, poi pacchetto, versione, versionCode e firma. Un file che Android non legge e' [UpdateProblem.UNREADABLE]. */
  private suspend fun verify(update: AvailableAppUpdate, file: File): UpdateProblem? = try {
    withContext(io) {
      UpdateRules.checksumProblem(update.sha256, checksum.sha256(file))
        ?: UpdateRules.archiveProblem(facts.installed(), facts.archive(file), expectedVersionName = update.version)
    }
  } catch (cancelled: CancellationException) {
    discard(file)
    throw cancelled
  } catch (failure: Exception) {
    UpdateProblem.UNREADABLE
  }

  override fun discard(file: File) {
    runCatching { file.delete() }
  }

  /**
   * Lo stesso flusso con gli stati dell'engine: chi chiamasse `EngineAppUpdater.install` (che delega qui) passa dagli stessi controlli, in un colpo solo.
   * L'app usa [prepare] e [commit]; gli errori dell'app hanno qui per messaggio il loro codice stabile.
   */
  override fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> = flow {
    var ready: File? = null
    prepare(update).collect { step ->
      if (step is InstallStep.Ready) ready = step.file else emit(step)
    }
    ready?.let { file -> commit(update, file).collect { emit(it) } }
  }.map { step ->
    when (step) {
      InstallStep.Preparing -> AppUpdateInstallState.Verifying("")
      InstallStep.NeedsPermission -> AppUpdateInstallState.Error("", AppUpdateErrorKind.InstallPermission)
      is InstallStep.Downloading -> AppUpdateInstallState.Downloading(step.progress, step.downloadedBytes, step.totalBytes)
      InstallStep.Verifying -> AppUpdateInstallState.Verifying("")
      is InstallStep.Ready -> AppUpdateInstallState.Error(INTERRUPTED_CODE)
      InstallStep.Installing -> AppUpdateInstallState.Installing("")
      InstallStep.AwaitingUser -> AppUpdateInstallState.AwaitingUserAction("")
      InstallStep.Installed -> AppUpdateInstallState.Installed("")
      is InstallStep.Failed -> AppUpdateInstallState.Error(step.problem.code)
    }
  }

  private companion object {
    const val INTERRUPTED_CODE = "interrupted"
  }
}

/**
 * I messaggi di `AndroidAppUpdateInstaller` (engine 3.0.4, fissi in italiano) e che cosa vogliono dire per l'app. Si riconoscono per testo perche'
 * l'engine non da' un codice; un messaggio che non si riconosce (anche quello di sistema di Android, in inglese) diventa
 * [UpdateProblem.INSTALL_FAILED], che a schermo e' una frase dell'app con «Riprova». Con un engine nuovo, rileggere `toInstallFailureMessage`.
 */
internal object EngineMessages {
  const val ABORTED: String = "Installazione interrotta: annullata, oppure bloccata da Play Protect."
  const val BLOCKED: String = "Android ha bloccato l'installazione dell'APK."
  const val CONFLICT: String = "Conflitto di firma o versione con l'app installata."
  const val INCOMPATIBLE: String = "L'APK non e' compatibile con questo dispositivo."
  const val INVALID: String = "Android considera l'APK non valido o non installabile."
  const val STORAGE: String = "Spazio insufficiente per completare l'installazione."
  const val UNREADABLE: String = "Android non riesce a leggere l'APK scaricato."
  const val OTHER_APP: String = "L'APK scaricato non appartiene a questa app."
  const val VERSION_PREFIX: String = "Versione APK inattesa"
  const val CHECKSUM: String = "Il file ricevuto non corrisponde a quello pubblicato."

  /** Il problema di [message]; null = annullata (l'utente o Play Protect): non e' un guasto. */
  fun problemOf(message: String): UpdateProblem? {
    val text = message.trim()
    return when {
      text == ABORTED -> null
      text == BLOCKED -> UpdateProblem.BLOCKED
      text == CONFLICT -> UpdateProblem.OTHER_SIGNATURE
      text == INCOMPATIBLE -> UpdateProblem.INCOMPATIBLE
      text == INVALID -> UpdateProblem.UNREADABLE
      text == STORAGE -> UpdateProblem.NO_SPACE
      text == UNREADABLE -> UpdateProblem.UNREADABLE
      text == OTHER_APP -> UpdateProblem.OTHER_APP
      text.startsWith(VERSION_PREFIX) -> UpdateProblem.WRONG_VERSION
      text == CHECKSUM -> UpdateProblem.CHECKSUM_MISMATCH
      else -> UpdateProblem.INSTALL_FAILED
    }
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
