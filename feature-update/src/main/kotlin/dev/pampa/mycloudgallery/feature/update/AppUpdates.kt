package dev.pampa.mycloudgallery.feature.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.pampa.mycloudgallery.feature.update.install.AndroidInstallPermission
import dev.pampa.mycloudgallery.feature.update.install.AndroidMeteredNetwork
import dev.pampa.mycloudgallery.feature.update.install.AndroidPackageFacts
import dev.pampa.mycloudgallery.feature.update.install.EngineApkTransfer
import dev.pampa.mycloudgallery.feature.update.install.GuardedUpdateInstaller
import dev.pampa.mycloudgallery.feature.update.install.InstallPermission
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.install.UpdateRunner
import dev.pampa.mycloudgallery.feature.update.net.GuardedUpdateHttp
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.policy.UpdateRules
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// L'AGGIORNAMENTO DENTRO L'APP, IL CUORE (pacchetto U, 12/11/2026; docs/aggiornamenti-in-app.md). Uno per processo (lo crea `:app`): lo stato che la
// pagina Impostazioni -> Aggiornamenti, l'avviso all'apertura, la voce delle Impostazioni e la pagina «versione fermata» guardano.
//
// QUANDO CONTROLLA: all'apertura dell'app, in silenzio, al piu' una volta ogni 24 ore ([checkIfDue], ora dell'ultimo tentativo salvata anche se non
// riesce: un telefono offline non bussa a GitHub a ogni apertura); a mano con «Controlla adesso» ([checkNow]) e quando si cambia canale. Mai un lavoro
// periodico in background. Il controllo e' una GET dell'elenco (pochi KB): parte anche sui dati mobili.
//
// LO SCARICAMENTO (~110 MB) parte solo dal tocco su «Aggiorna» ([install]); su una rete a consumo prima si chiede ([UpdatesState.askMetered]).
// Il permesso «installa app sconosciute» si chiede quando serve, e al ritorno dalla pagina di Android si riparte da soli ([onResumed]).
//
// LA VERSIONE CORRENTE e' il `versionName` INTERO (con il suffisso beta): una `1.1.0-beta.2` vede la stabile `1.1.0` (update.md, trappole).

/** Il controllo dell'elenco. */
sealed interface CheckState {
  data object Idle : CheckState

  data object Checking : CheckState

  /** Nessuna versione piu' nuova sul canale scelto. */
  data object UpToDate : CheckState

  /** L'elenco non si e' letto (rete, 404 finche' il repository e' privato, risposta non valida): «Non riesco a controllare adesso». */
  data object Unavailable : CheckState

  /** C'e' una versione piu' nuova, installabile. */
  data class Found(val update: AvailableAppUpdate) : CheckState

  /** C'e' una versione piu' nuova, ma l'elenco non la descrive come serve (senza sha256 o peso, nomi, indirizzo): non si offre. */
  data class Unusable(val update: AvailableAppUpdate, val problem: UpdateProblem) : CheckState
}

/** Lo stato dell'aggiornamento per chi lo mostra. */
data class UpdatesState(
  /** Il `versionName` installato, intero. */
  val installedVersion: String,
  val channel: UpdateChannel = UpdateChannel.STABLE,
  val check: CheckState = CheckState.Idle,
  /** L'installazione in corso o l'ultima finita; null = nessuna. */
  val install: InstallStep? = null,
  /** Si aspetta la risposta a «sei sui dati mobili: scarico lo stesso?». */
  val askMetered: Boolean = false,
) {
  /** La versione che «Aggiorna» installerebbe. */
  val found: AvailableAppUpdate? get() = (check as? CheckState.Found)?.update

  /** Un'installazione e' in corso (un secondo tocco non riparte, il canale non cambia). */
  val installing: Boolean get() = install.isActive()
}

/** Vero per i passi che lavorano ancora (non per il permesso da dare, un errore o la fine). */
fun InstallStep?.isActive(): Boolean = when (this) {
  InstallStep.Preparing, is InstallStep.Downloading, InstallStep.Verifying, InstallStep.Installing, InstallStep.AwaitingUser -> true
  null, InstallStep.NeedsPermission, InstallStep.Installed, is InstallStep.Failed -> false
}

/** Chi legge l'elenco: `EngineAppUpdater.check` in produzione. */
fun interface UpdateChecker {
  suspend fun check(currentVersionName: String, channel: UpdateChannel): Result<AvailableAppUpdate?>
}

class AppUpdates(
  private val checker: UpdateChecker,
  private val runner: UpdateRunner,
  private val store: UpdateStore,
  private val installedVersion: String,
  private val permission: InstallPermission,
  private val metered: () -> Boolean,
  /** L'ora per le 24 ore fra un controllo e l'altro: la da' `:app` (questo modulo non legge l'orologio). */
  private val now: () -> Long,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {
  private val mutable = MutableStateFlow(UpdatesState(installedVersion = installedVersion))
  val state: StateFlow<UpdatesState> = mutable.asStateFlow()

  private val promptOpen = MutableStateFlow(false)

  /**
   * L'avviso all'apertura: vero quando un controllo SILENZIOSO ha trovato una versione mai annunciata (una volta per versione, [UpdateRules.shouldPrompt]:
   * la versione si segna subito). Resta vero finche' l'utente non tocca «Dopo», anche mentre l'aggiornamento si scarica dall'avviso.
   */
  val prompt: StateFlow<Boolean> = promptOpen.asStateFlow()

  private val loaded = CompletableDeferred<Unit>()
  private val checks = Mutex()
  private var installJob: Job? = null

  @Volatile
  private var resumeAfterPermission = false

  init {
    // Le preferenze si leggono fuori dal thread principale: nascere non costa niente al primo disegno.
    scope.launch(io) {
      try {
        val channel = store.channel()
        mutable.update { it.copy(channel = channel) }
      } finally {
        loaded.complete(Unit)
      }
    }
  }

  /** All'apertura dell'app (dopo il primo disegno): un controllo silenzioso se l'ultimo ha piu' di 24 ore. Mai un errore visibile. */
  fun checkIfDue(): Job = scope.launch { runCheck(manual = false) }

  /** «Controlla adesso»: sempre, e un errore si vede («Non riesco a controllare adesso»). */
  fun checkNow(): Job = scope.launch { runCheck(manual = true) }

  private suspend fun runCheck(manual: Boolean) {
    loaded.await()
    checks.withLock {
      if (state.value.installing) return
      if (!manual && !UpdateRules.isDue(withContext(io) { store.lastCheckedAt() }, now())) return
      val before = state.value.check
      val channel = state.value.channel
      mutable.update { it.copy(check = CheckState.Checking) }
      val result = try {
        checker.check(installedVersion, channel)
      } catch (cancelled: CancellationException) {
        mutable.update { if (it.check == CheckState.Checking) it.copy(check = before) else it }
        throw cancelled
      } catch (failure: Exception) {
        Result.failure(failure)
      }
      withContext(io) { store.setLastCheckedAt(now()) }
      // Il canale e' cambiato mentre si leggeva: questo esito non vale piu' (il cambio ha chiesto il suo controllo).
      if (state.value.channel != channel) return
      result.fold(
        onSuccess = { offered -> settle(offered, manual) },
        onFailure = {
          val shown = if (manual) CheckState.Unavailable else before.takeUnless { it == CheckState.Checking } ?: CheckState.Idle
          mutable.update { it.copy(check = shown) }
        },
      )
    }
  }

  private suspend fun settle(offered: AvailableAppUpdate?, manual: Boolean) {
    if (offered == null) {
      mutable.update { it.copy(check = CheckState.UpToDate) }
      return
    }
    val problem = UpdateRules.offerProblem(offered)
    if (problem != null) {
      // Pubblicata male (per esempio senza sha256): non si offre e non si annuncia. La pagina lo dice in piccolo, per chi pubblica.
      mutable.update { it.copy(check = CheckState.Unusable(offered, problem)) }
      return
    }
    mutable.update { current ->
      // Un'installazione finita male della stessa versione resta da leggere finche' non si riprova.
      val keep = (current.install as? InstallStep.Failed)?.takeIf { current.found?.version == offered.version }
      current.copy(check = CheckState.Found(offered), install = keep)
    }
    if (UpdateRules.shouldPrompt(offered.version, withContext(io) { store.promptedVersion() })) {
      withContext(io) { store.setPromptedVersion(offered.version) }
      if (!manual) promptOpen.value = true
    }
  }

  /** Il canale scelto in Impostazioni: si salva su questo dispositivo e si controlla subito. Non durante un'installazione. */
  fun chooseChannel(channel: UpdateChannel) {
    val current = state.value
    if (current.installing || current.channel == channel) return
    mutable.update { it.copy(channel = channel, check = CheckState.Idle, install = null, askMetered = false) }
    scope.launch {
      loaded.await()
      withContext(io) { store.setChannel(channel) }
      runCheck(manual = true)
    }
  }

  /**
   * «Aggiorna»: l'unico punto da cui parte un'installazione (un tocco dell'utente). Su una rete a consumo, la prima volta chiede ([acceptMetered]
   * falso): «Scarica» la richiama con vero. Un secondo tocco mentre lavora non fa niente.
   */
  fun install(acceptMetered: Boolean = false) {
    val update = state.value.found ?: return
    if (state.value.installing || installJob?.isActive == true) return
    if (!acceptMetered && metered()) {
      mutable.update { it.copy(askMetered = true) }
      return
    }
    resumeAfterPermission = false
    mutable.update { it.copy(askMetered = false, install = InstallStep.Preparing) }
    installJob = scope.launch {
      runner.run(update)
        // Lo scaricamento, l'impronta e la lettura dell'APK (anche quella dell'engine, che non cambia filo) mai sul thread principale.
        .flowOn(io)
        .catch { failure ->
          if (failure is CancellationException) throw failure
          emit(InstallStep.Failed(UpdateProblem.INSTALL_FAILED))
        }
        .collect { step ->
          if (step == InstallStep.NeedsPermission) resumeAfterPermission = true
          mutable.update { it.copy(install = step) }
        }
    }
  }

  /** «Annulla» sulla domanda dei dati mobili. */
  fun cancelMetered() {
    mutable.update { it.copy(askMetered = false) }
  }

  /** «Annulla» durante lo scaricamento: il file a meta' si cancella (il client si ferma al blocco dopo). */
  fun cancelInstall() {
    val job = installJob ?: return
    if (state.value.install !is InstallStep.Downloading && state.value.install != InstallStep.Preparing) return
    job.cancel()
    installJob = null
    mutable.update { it.copy(install = null) }
  }

  /** «Consenti»: la pagina di Android del permesso. Al ritorno [onResumed] riparte da solo. */
  fun openInstallPermission() {
    resumeAfterPermission = true
    permission.open()
  }

  /** L'app torna davanti (anche dalla pagina del permesso): se il permesso ora c'e', l'installazione riparte senza un altro tocco. */
  fun onResumed() {
    if (!resumeAfterPermission || state.value.install != InstallStep.NeedsPermission) return
    if (!permission.granted()) return
    resumeAfterPermission = false
    install(acceptMetered = true)
  }

  /** «Dopo» sull'avviso: non torna per questa versione (gia' segnata). */
  fun dismissPrompt() {
    promptOpen.value = false
  }

  /** Chiude il messaggio di un'installazione finita male (la versione resta, «Aggiorna» riprova). */
  fun dismissFailure() {
    mutable.update { if (it.install is InstallStep.Failed || it.install == InstallStep.Installed) it.copy(install = null) else it }
  }

  companion object {
    /**
     * Quello vero (`:app`, uno per processo): il client avvolto ([UpdateHttp]) per l'elenco e per l'APK, `EngineAppUpdater` per il controllo,
     * [GuardedUpdateInstaller] sopra `AndroidAppUpdateInstaller` per l'installazione. [versionName] e' il `versionName` intero dell'app.
     */
    fun create(context: Context, versionName: String, now: () -> Long, scope: CoroutineScope): AppUpdates {
      val app = context.applicationContext
      val http = UpdateHttp.create(versionName)
      val permission = AndroidInstallPermission(app)
      val runner = GuardedUpdateInstaller(
        transfer = EngineApkTransfer(AndroidAppUpdateInstaller(app, http)),
        facts = AndroidPackageFacts(app),
        permission = permission,
      )
      val updater = EngineAppUpdater(http, UpdateSourceConfig.engineSource(app.packageName), runner)
      return AppUpdates(
        checker = { current, channel -> updater.check(currentVersionName = current, channel = channel, ignoredVersion = "") },
        runner = runner,
        store = PrefsUpdateStore(app),
        installedVersion = versionName,
        permission = permission,
        metered = AndroidMeteredNetwork(app)::isMetered,
        now = now,
        scope = scope,
      )
    }
  }
}

/** Il client dell'aggiornamento: l'`EngineHttp` dell'engine avvolto (host ammessi a ogni salto, tetti, niente cookie). L'unico modo di ottenerlo. */
object UpdateHttp {
  fun create(versionName: String): EngineHttp = GuardedUpdateHttp(
    agent = UpdateSourceConfig.userAgent(versionName),
    manifestHosts = UpdateSourceConfig.MANIFEST_HOSTS,
    downloadHosts = UpdateSourceConfig.DOWNLOAD_HOSTS,
    maxManifestBytes = UpdateSourceConfig.MAX_MANIFEST_BYTES,
    maxDownloadBytes = UpdateSourceConfig.MAX_APK_BYTES,
    maxRedirects = UpdateSourceConfig.MAX_REDIRECTS,
  )
}
