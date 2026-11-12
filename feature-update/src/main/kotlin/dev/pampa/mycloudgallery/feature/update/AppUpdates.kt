package dev.pampa.mycloudgallery.feature.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.pampa.mycloudgallery.feature.update.install.AndroidInstallPermission
import dev.pampa.mycloudgallery.feature.update.install.AndroidMeteredNetwork
import dev.pampa.mycloudgallery.feature.update.install.AndroidPackageFacts
import dev.pampa.mycloudgallery.feature.update.install.EngineApkTransfer
import dev.pampa.mycloudgallery.feature.update.install.ForegroundGate
import dev.pampa.mycloudgallery.feature.update.install.GuardedUpdateInstaller
import dev.pampa.mycloudgallery.feature.update.install.InstallPermission
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.install.UpdateCache
import dev.pampa.mycloudgallery.feature.update.install.UpdateInstaller
import dev.pampa.mycloudgallery.feature.update.net.GuardedUpdateHttp
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.policy.UpdateRules
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

// L'AGGIORNAMENTO DENTRO L'APP, IL CUORE (pacchetto U, 12/11/2026; giro 1 delle critiche; docs/aggiornamenti-in-app.md). Uno per processo (lo crea
// `:app`): lo stato che la pagina Impostazioni -> Aggiornamenti, l'avviso sopra la barra delle schede, la voce delle Impostazioni e la pagina «versione
// fermata» guardano.
//
// QUANDO CONTROLLA: all'apertura dell'app, in silenzio, al piu' una volta ogni 24 ore ([checkIfDue]); all'apertura della pagina, con la stessa regola
// ([refreshIfDue]); a mano con «Controlla adesso» ([checkNow]) e quando si cambia canale. Mai un lavoro periodico in background. L'ultima versione trovata
// si ricorda ([SavedOffer]): dopo un riavvio la pagina e il segno la mostrano subito, senza aspettare la rete.
//
// L'AVVISO compare quando c'e' una versione da offrire e il promemoria lo permette (`UpdateRules.shouldPrompt`): «Dopo» lo rimanda di tre giorni; al
// terzo «Dopo» di fila tace trenta giorni, poi il conto riparte (giro 2: mai spento per sempre). Alla prima apertura dopo un aggiornamento riuscito
// dice «Aggiornata» ([UpdatesState.justUpdated]),
// confrontando la versione del tentativo salvato con quella che gira: l'evento `Installed` dell'engine non arriva quasi mai (Android chiude l'app).
//
// L'INSTALLAZIONE parte solo dal tocco su «Aggiorna» ([install]), in due tempi (`UpdateInstaller.prepare` e `commit`):
//  - sui dati mobili prima si chiede; il consenso vale per QUEL tentativo ([Attempt.meteredConsent], US-2): al ritorno dal permesso di Android si
//    riguarda la rete e, se e' a consumo senza consenso, si chiede di nuovo;
//  - il permesso «installa app sconosciute» si chiede quando serve; il tentativo si salva, cosi' se Android chiude l'app per darlo (Android 11) al
//    ritorno si riprende (UU-3);
//  - il file scaricato e verificato va alla sessione di Android solo con l'activity RESUMED: il cancello ([ForegroundGate]) lo guarda l'installatore,
//    una volta sola, dopo le verifiche e subito prima di `installFile`, su tutte le strade; altrimenti «Pronta» con «Installa» (UU-1, giro 2 US2-1);
//  - passato il cancello e' «Sto installando». Se l'app torna davanti senza un esito (il «si'» nella finestra di Android, oppure una finestra chiusa
//    senza scelta o mai aperta) resta «Sto installando» per [UpdateRules.OUTCOME_WAIT_MILLIS] di app davanti; un esito che arriva vince; senza
//    esito si torna a «Pronta», e «Installa» riprova senza riscaricare (il file si ricontrolla). Giro 2, UU2-1. Giro 3 (UU3-1): il tempo corre anche se
//    l'app non va mai dietro (la finestra non si apre, Android installa senza finestra, schermo diviso); con la finestra di Android sopra l'app e' in
//    pausa e il tempo e' fermo;
//  - un tentativo alla volta (giro 3, US3-3): «Installa» e il ritorno dal permesso passano dalla stessa ammissione ([launchCommit]).
//
// LA VERSIONE CORRENTE e' il `versionName` INTERO (con il suffisso beta): una `1.1.0-beta.02` vede la stabile `1.1.0` (update.md, trappole).

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

  /** C'e' una versione piu' nuova, ma l'elenco non la descrive come serve (senza sha256 o peso, versione, nomi, indirizzo): non si offre. */
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
  /** L'app si e' appena aggiornata a questa versione: la prima apertura dopo lo dice, una volta. */
  val justUpdated: String? = null,
) {
  /** La versione che «Aggiorna» installerebbe. */
  val found: AvailableAppUpdate? get() = (check as? CheckState.Found)?.update

  /** Un'installazione e' in corso (un secondo tocco non riparte, il canale non cambia). */
  val installing: Boolean get() = install.isActive()
}

/** Vero per i passi che lavorano ancora (non per il permesso da dare, il file pronto, un errore o la fine). */
fun InstallStep?.isActive(): Boolean = when (this) {
  InstallStep.Preparing, is InstallStep.Downloading, InstallStep.Verifying, InstallStep.Installing, InstallStep.AwaitingUser -> true
  null, InstallStep.NeedsPermission, is InstallStep.Ready, InstallStep.Installed, is InstallStep.Failed -> false
}

/** Chi legge l'elenco: `EngineAppUpdater.check` in produzione. */
fun interface UpdateChecker {
  suspend fun check(currentVersionName: String, channel: UpdateChannel): Result<AvailableAppUpdate?>
}

class AppUpdates(
  private val checker: UpdateChecker,
  private val installer: UpdateInstaller,
  private val store: UpdateStore,
  private val installedVersion: String,
  private val permission: InstallPermission,
  private val metered: () -> Boolean,
  /** Il cancello che l'installatore guarda prima di `installFile`: lo aprono e chiudono [onResumed] e [onPaused] (il ciclo di vita dell'activity). */
  private val gate: ForegroundGate,
  /** L'ora per le 24 ore, i tre giorni del promemoria e il tentativo salvato: la da' `:app` (questo modulo non legge l'orologio). */
  private val now: () -> Long,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /** Svuota la cartella dei download (`UpdateCache`): all'avvio, prima di ogni scaricamento. */
  private val clearCache: () -> Unit = {},
) {
  private val mutable = MutableStateFlow(UpdatesState(installedVersion = installedVersion))
  val state: StateFlow<UpdatesState> = mutable.asStateFlow()

  private val promptOpen = MutableStateFlow(false)

  /**
   * L'avviso sopra la barra delle schede: una versione da offrire (con il promemoria che lo permette), il permesso da dare per un tentativo partito,
   * oppure «Aggiornata» dopo un aggiornamento riuscito. Resta finche' l'utente non risponde, anche mentre scarica dall'avviso.
   */
  val prompt: StateFlow<Boolean> = promptOpen.asStateFlow()

  private val loaded = CompletableDeferred<Unit>()
  private val checks = Mutex()
  private var job: Job? = null

  /** L'attesa dell'esito dopo il passaggio ad Android, con l'app davanti ([awaitOutcome]). */
  private var outcomeWait: Job? = null

  /** Il file verificato e la sua versione: «Installa» e «Riprova» non riscaricano finche' c'e'. */
  @Volatile private var readyFile: File? = null

  @Volatile private var readyVersion: String? = null

  /** Il tentativo partito dal tocco su «Aggiorna» (salvato quando serve: permesso, installazione). */
  @Volatile private var attempt: Attempt? = null

  @Volatile private var reminder = Reminder(forInstalled = installedVersion)

  init {
    // Le preferenze si leggono fuori dal thread principale: nascere non costa niente al primo disegno.
    scope.launch(io) {
      try {
        runCatching(clearCache)
        restore()
      } finally {
        loaded.complete(Unit)
      }
    }
  }

  private fun restore() {
    val channel = store.channel()
    val offer = store.offer()?.takeIf { usable(it, channel) }
    if (offer == null) store.setOffer(null)
    reminder = store.reminder().let { saved -> if (saved.forInstalled == installedVersion) saved else Reminder(forInstalled = installedVersion) }
    store.setReminder(reminder)
    val saved = store.attempt()
    var justUpdated: String? = null
    var install: InstallStep? = null
    when {
      saved == null -> Unit
      saved.stage == AttemptStage.INSTALLING && saved.version == installedVersion -> {
        justUpdated = installedVersion
        store.setAttempt(null)
      }
      saved.stage == AttemptStage.PERMISSION && offer?.update?.version == saved.version && UpdateRules.attemptFresh(saved.startedAt, now()) -> {
        attempt = saved
        install = InstallStep.NeedsPermission
      }
      else -> store.setAttempt(null)
    }
    mutable.update { current ->
      current.copy(
        channel = channel,
        check = offer?.let { CheckState.Found(it.update) } ?: current.check,
        install = install ?: current.install,
        justUpdated = justUpdated,
      )
    }
    promptOpen.value = justUpdated != null || install != null || offer != null && promptAllowed()
  }

  /** Una versione salvata vale ancora: dello stesso canale, scritta bene, piu' nuova di quella che gira. */
  private fun usable(offer: SavedOffer, channel: UpdateChannel): Boolean =
    offer.channel == channel && UpdateRules.offerProblem(offer.update) == null && compareVersions(offer.update.version, installedVersion) > 0

  private fun promptAllowed(): Boolean = UpdateRules.shouldPrompt(hasOffer = true, now = now(), snoozedUntil = reminder.snoozedUntil, declines = reminder.declines)

  /** All'apertura dell'app (dopo il primo disegno): un controllo silenzioso se l'ultimo ha piu' di 24 ore. Mai un errore visibile; puo' aprire l'avviso. */
  fun checkIfDue(): Job = scope.launch { runCheck(CheckMode.APP) }

  /** All'apertura della pagina Aggiornamenti: come [checkIfDue], senza avviso (la pagina lo mostra gia'). */
  fun refreshIfDue(): Job = scope.launch { runCheck(CheckMode.PAGE) }

  /** «Controlla adesso»: sempre, e un errore si vede («Non riesco a controllare adesso»). */
  fun checkNow(): Job = scope.launch { runCheck(CheckMode.MANUAL) }

  private enum class CheckMode { APP, PAGE, MANUAL }

  private suspend fun runCheck(mode: CheckMode) {
    loaded.await()
    checks.withLock {
      val current = state.value
      // Un'installazione partita (o un file pronto) non si disturba: l'elenco si rilegge dopo.
      if (current.installing || current.install is InstallStep.Ready) return
      val manual = mode == CheckMode.MANUAL
      if (!manual && !UpdateRules.isDue(withContext(io) { store.lastCheckedAt() }, now())) return
      val before = current.check
      val channel = current.channel
      // Una versione gia' nota resta a schermo mentre si rilegge in silenzio; a mano si vede che controlla.
      if (manual || before !is CheckState.Found) mutable.update { it.copy(check = CheckState.Checking) }
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
        onSuccess = { offered -> settle(offered, channel, mayPrompt = mode == CheckMode.APP) },
        onFailure = {
          val shown = if (manual) CheckState.Unavailable else before.takeUnless { it == CheckState.Checking } ?: CheckState.Idle
          mutable.update { it.copy(check = shown) }
        },
      )
    }
  }

  private suspend fun settle(offered: AvailableAppUpdate?, channel: UpdateChannel, mayPrompt: Boolean) {
    if (offered == null) {
      withContext(io) { store.setOffer(null) }
      dropReady()
      mutable.update { it.copy(check = CheckState.UpToDate, install = it.install.takeIf { step -> step is InstallStep.Failed }) }
      return
    }
    val clean = UpdateRules.cleanOffer(offered)
    val problem = UpdateRules.offerProblem(clean)
    if (problem != null) {
      // Pubblicata male (per esempio senza sha256): non si offre e non si annuncia. La pagina dice «non ancora pronta».
      withContext(io) { store.setOffer(null) }
      mutable.update { it.copy(check = CheckState.Unusable(clean, problem)) }
      return
    }
    withContext(io) { store.setOffer(SavedOffer(channel, clean)) }
    if (readyVersion != null && readyVersion != clean.version) dropReady()
    mutable.update { current ->
      // Il passo di un tentativo della stessa versione resta (il permesso da dare, un guasto da leggere); una versione nuova riparte da capo.
      val keep = current.install.takeIf { current.found?.version == clean.version }
      current.copy(check = CheckState.Found(clean), install = keep)
    }
    if (mayPrompt && promptAllowed()) promptOpen.value = true
  }

  /** Il canale scelto in Impostazioni: si salva su questo dispositivo e si controlla subito. Non durante un'installazione. */
  fun chooseChannel(channel: UpdateChannel) {
    val current = state.value
    if (current.installing || current.channel == channel) return
    mutable.update { it.copy(channel = channel) }
    scope.launch {
      loaded.await()
      dropReady()
      clearAttempt()
      mutable.update { it.copy(channel = channel, check = CheckState.Idle, install = null, askMetered = false) }
      withContext(io) {
        store.setChannel(channel)
        store.setOffer(null)
      }
      runCheck(CheckMode.MANUAL)
    }
  }

  /**
   * «Aggiorna» (e «Riprova», «Installa»): l'unico punto da cui parte un'installazione, un tocco dell'utente. Se c'e' gia' il file verificato di questa
   * versione va diritto alla sessione (senza riscaricare). Altrimenti un tentativo nuovo: su una rete a consumo prima si chiede ([acceptMetered] falso);
   * «Scarica lo stesso» la richiama con vero, e quel consenso vale solo per questo tentativo. Un secondo tocco mentre lavora non fa niente.
   */
  fun install(acceptMetered: Boolean = false) {
    val update = state.value.found ?: return
    if (busy()) return
    val ready = readyFile?.takeIf { it.isFile && readyVersion == update.version }
    if (ready != null) {
      val consent = acceptMetered || attempt?.meteredConsent == true
      attempt = Attempt(update.version, AttemptStage.INSTALLING, consent, now(), installedVersion)
      launchCommit(update, ready)
      return
    }
    startAttempt(update, consent = acceptMetered)
  }

  /** Un tentativo lavora gia' (lo stato dice che lavora, o il suo lavoro e' partito): un secondo non entra. Tutto sul main. */
  private fun busy(): Boolean = state.value.installing || job?.isActive == true

  /**
   * L'ammissione del secondo tempo, l'unica (giro 3, US3-3): «Installa» e il ritorno dal permesso passano di qui. Lo stato attivo si mette SUBITO,
   * prima del salvataggio del tentativo (che sospende): un secondo ON_RESUME ravvicinato, o un secondo tocco, trova il tentativo gia' in corso.
   */
  private fun launchCommit(update: AvailableAppUpdate, file: File) {
    if (busy()) return
    mutable.update { it.copy(install = InstallStep.Verifying) }
    job = scope.launch { commit(update, file) }
  }

  private fun startAttempt(update: AvailableAppUpdate, consent: Boolean) {
    if (busy()) return
    if (!consent && metered()) {
      mutable.update { it.copy(askMetered = true, install = null) }
      return
    }
    attempt = Attempt(update.version, AttemptStage.PERMISSION, consent, now(), installedVersion)
    mutable.update { it.copy(askMetered = false, install = InstallStep.Preparing) }
    // L'avviso sopra la barra delle schede segue il tentativo (partito dalla pagina, dall'avviso o dal manifest in sola lettura); la «×» lo nasconde.
    promptOpen.value = true
    job = scope.launch {
      var ready: File? = null
      installer.prepare(update)
        // Lo scaricamento, l'impronta e la lettura dell'APK mai sul thread principale.
        .flowOn(io)
        .failSafe(UpdateProblem.DOWNLOAD_FAILED)
        .collect { step ->
          when (step) {
            is InstallStep.Ready -> ready = step.file
            InstallStep.NeedsPermission -> saveAttempt(AttemptStage.PERMISSION)
            is InstallStep.Failed -> clearAttempt()
            else -> Unit
          }
          if (step !is InstallStep.Ready) mutable.update { it.copy(install = step) }
        }
      val file = ready ?: return@launch
      readyFile = file
      readyVersion = update.version
      // Sempre al secondo tempo: il cancello e' li', dopo i controlli. Con l'app dietro torna «Pronta» e si aspetta «Installa» (UU-1, US2-1).
      commit(update, file)
    }
  }

  /** Il file pronto alla sessione di Android. Il tentativo si salva prima: se l'app riparte con la versione nuova, e' riuscito. */
  private suspend fun commit(update: AvailableAppUpdate, file: File) {
    saveAttempt(AttemptStage.INSTALLING)
    installer.commit(update, file)
      .flowOn(io)
      .failSafe(UpdateProblem.INSTALL_FAILED)
      .collect { step ->
        when (step) {
          is InstallStep.Ready -> clearAttempt()
          InstallStep.NeedsPermission -> saveAttempt(AttemptStage.PERMISSION)
          is InstallStep.Failed -> {
            if (!step.problem.keepsFile) forgetReady()
            clearAttempt()
          }
          InstallStep.Installed -> forgetReady()
          else -> Unit
        }
        mutable.update { it.copy(install = step) }
        when {
          // Il file e' di Android e l'app e' davanti: il tempo corre anche se l'app non va mai dietro (giro 3, UU3-1). Se la finestra di Android
          // si apre, l'app va in pausa e il tempo si ferma; al ritorno riparte da capo ([awaitOutcome]).
          step == InstallStep.Installing || step == InstallStep.AwaitingUser -> if (gate.inFront()) startWaiting()
          // Un esito (annullata, un guasto, installata, il permesso tolto) vince sull'attesa.
          !step.isActive() -> stopWaiting()
        }
      }
  }

  private fun Flow<InstallStep>.failSafe(problem: UpdateProblem): Flow<InstallStep> = catch { failure ->
    if (failure is CancellationException) throw failure
    emit(InstallStep.Failed(problem))
  }

  /** «Annulla» sulla domanda dei dati mobili. */
  fun cancelMetered() {
    clearAttempt()
    mutable.update { it.copy(askMetered = false) }
  }

  /** «Annulla» durante lo scaricamento: il file a meta' si cancella (il client si ferma al blocco dopo). */
  fun cancelInstall() {
    val current = state.value.install
    if (current !is InstallStep.Downloading && current != InstallStep.Preparing) return
    job?.cancel()
    job = null
    clearAttempt()
    mutable.update { it.copy(install = null) }
  }

  /** «Annulla» su «Pronta»: il file verificato si cancella, si torna alla versione nuova con «Aggiorna». */
  fun cancelReady() {
    if (state.value.install !is InstallStep.Ready) return
    dropReady()
    clearAttempt()
    mutable.update { it.copy(install = null) }
  }

  /** «Consenti»: la pagina di Android del permesso. Al ritorno [onResumed] riprende il tentativo. */
  fun openInstallPermission() {
    permission.open()
  }

  /**
   * L'activity di [owner] e' RESUMED: il cancello si apre. Con il permesso da dare: se ora c'e', il tentativo riprende (il tocco l'ha gia' dato),
   * riguardando la rete (US-2). Con il file gia' passato ad Android: «Sto installando» e l'attesa dell'esito ([awaitOutcome]). [owner]: un gettone per
   * activity (giro 3, US3-2); di serie quello unico, per chi ha una finestra sola.
   */
  fun onResumed(owner: Any = SoleOwner) {
    gate.open(owner)
    scope.launch {
      loaded.await()
      when (state.value.install) {
        InstallStep.NeedsPermission -> resumeAfterPermission()
        InstallStep.Installing, InstallStep.AwaitingUser -> awaitOutcome()
        else -> Unit
      }
    }
  }

  /**
   * L'activity di [owner] non e' piu' RESUMED (Home, schermo spento, la finestra di Android sopra) o se ne va: esce dal cancello (piu' volte non fa
   * danni). Il cancello si chiude e l'attesa si ferma solo quando non resta nessuna activity davanti (giro 3, US3-2).
   */
  fun onPaused(owner: Any = SoleOwner) {
    if (gate.close(owner)) stopWaiting()
  }

  private fun resumeAfterPermission() {
    if (!permission.granted() || busy()) return
    val update = state.value.found ?: return
    val current = attempt?.takeIf { it.version == update.version } ?: return
    val ready = readyFile?.takeIf { it.isFile && readyVersion == update.version }
    if (ready != null) {
      launchCommit(update, ready)
      return
    }
    startAttempt(update, consent = current.meteredConsent)
  }

  /**
   * Giro 2, UU2-1: il file e' gia' di Android e l'app e' davanti senza un esito. Il «si'» nella finestra di Android riporta l'app davanti mentre Android
   * installa: qui non si torna a «Pronta» (un secondo «Installa» aprirebbe un'altra sessione) ma si dice «Sto installando» e si aspetta. Solo dopo
   * [UpdateRules.OUTCOME_WAIT_MILLIS] di app davanti senza niente (la finestra si era chiusa senza scelta, o non si era mai aperta) «Pronta».
   */
  private fun awaitOutcome() {
    if (!gate.inFront()) return
    mutable.update { if (it.install == InstallStep.AwaitingUser) it.copy(install = InstallStep.Installing) else it }
    startWaiting()
  }

  /** Il tempo dell'attesa, da capo: [UpdateRules.OUTCOME_WAIT_MILLIS] con l'app davanti, poi «Pronta» se non e' arrivato un esito. */
  private fun startWaiting() {
    outcomeWait?.cancel()
    outcomeWait = scope.launch {
      delay(UpdateRules.OUTCOME_WAIT_MILLIS)
      val step = state.value.install
      if (step == InstallStep.Installing || step == InstallStep.AwaitingUser) backToReady()
    }
  }

  private fun stopWaiting() {
    outcomeWait?.cancel()
    outcomeWait = null
  }

  /** Nessun esito: il flusso dell'engine si lascia e il file resta, ricontrollato al prossimo «Installa» (senza riscaricare). */
  private fun backToReady() {
    outcomeWait = null
    job?.cancel()
    job = null
    val file = readyFile?.takeIf { it.isFile }
    mutable.update { it.copy(install = file?.let { ready -> InstallStep.Ready(ready) }) }
  }

  /**
   * «Dopo» sull'avviso: lo chiude e lo rimanda di tre giorni; al terzo di fila, trenta (`UpdateRules.declined`). Un permesso che si aspettava o un
   * guasto si lasciano: il tentativo finisce. Con «Aggiornata» e' solo «Ok». («Chiudi» su un guasto non passa di qui: giro 3, UU3-6.)
   */
  fun later() {
    promptOpen.value = false
    if (state.value.justUpdated != null) {
      mutable.update { it.copy(justUpdated = null) }
      return
    }
    val install = state.value.install
    if (install == InstallStep.NeedsPermission || install is InstallStep.Failed) {
      clearAttempt()
      mutable.update { it.copy(install = null) }
    }
    val next = UpdateRules.declined(now(), reminder.snoozedUntil, reminder.declines)
    reminder = Reminder(snoozedUntil = next.until, declines = next.declines, forInstalled = installedVersion)
    val saved = reminder
    scope.launch(io) { store.setReminder(saved) }
  }

  /** La «×» dell'avviso mentre lavora: lo nasconde e basta (lo scaricamento continua, la pagina Aggiornamenti lo mostra). */
  fun hidePrompt() {
    promptOpen.value = false
  }

  /**
   * «Chiudi»: il messaggio di un'installazione finita male, o il permesso che non si vuole dare adesso (il tentativo finisce). La versione resta,
   * «Aggiorna» riprova; un file verificato che il guasto ha lasciato resta per non riscaricare.
   */
  fun dismiss() {
    val install = state.value.install
    if (install !is InstallStep.Failed && install != InstallStep.Installed && install != InstallStep.NeedsPermission) return
    clearAttempt()
    mutable.update { it.copy(install = null) }
  }

  /** Salva il tentativo al passo [stage] e aspetta che sia su disco: prima della sessione di Android, che chiude l'app. */
  private suspend fun saveAttempt(stage: AttemptStage) {
    val current = attempt ?: return
    val next = current.copy(stage = stage, startedAt = now())
    attempt = next
    withContext(io) { store.setAttempt(next) }
  }

  private fun clearAttempt() {
    if (attempt == null) return
    attempt = null
    scope.launch(io) { store.setAttempt(null) }
  }

  /** Il file pronto non serve piu': si cancella. */
  private fun dropReady() {
    readyFile?.let(installer::discard)
    forgetReady()
  }

  private fun forgetReady() {
    readyFile = null
    readyVersion = null
  }

  /** Il proprietario di chi ha una finestra sola (le prove, chi non distingue): il cancello lo tratta come ogni altro. */
  private object SoleOwner

  companion object {
    /**
     * Quello vero (`:app`, uno per processo): il client avvolto ([UpdateHttp]) per l'elenco e per l'APK, `EngineAppUpdater` per il controllo,
     * [GuardedUpdateInstaller] sopra `AndroidAppUpdateInstaller` per l'installazione. [versionName] e' il `versionName` intero dell'app.
     */
    fun create(context: Context, versionName: String, now: () -> Long, scope: CoroutineScope): AppUpdates {
      val app = context.applicationContext
      val http = UpdateHttp.create(versionName)
      val permission = AndroidInstallPermission(app)
      // Lo stesso cancello: AppUpdates lo apre e chiude con il ciclo di vita dell'activity, l'installatore lo guarda prima di `installFile`.
      val gate = ForegroundGate()
      val installer = GuardedUpdateInstaller(
        transfer = EngineApkTransfer(AndroidAppUpdateInstaller(app, http)),
        facts = AndroidPackageFacts(app),
        permission = permission,
        inFront = gate::inFront,
      )
      val updater = EngineAppUpdater(http, UpdateSourceConfig.engineSource(app.packageName), installer)
      return AppUpdates(
        checker = { current, channel -> updater.check(currentVersionName = current, channel = channel, ignoredVersion = "") },
        installer = installer,
        store = PrefsUpdateStore(app),
        installedVersion = versionName,
        permission = permission,
        metered = AndroidMeteredNetwork(app)::isMetered,
        gate = gate,
        now = now,
        scope = scope,
        clearCache = { UpdateCache.clear(app.cacheDir) },
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
