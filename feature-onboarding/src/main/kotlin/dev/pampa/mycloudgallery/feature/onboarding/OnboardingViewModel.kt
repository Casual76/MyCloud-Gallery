package dev.pampa.mycloudgallery.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.JoinFlow
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.RecoveryCodeDisplay
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.invite.ManualInvite
import dev.pampa.mycloudgallery.core.family.onboarding.asOnboardingProblem
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AddressUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AreasUi
import dev.pampa.mycloudgallery.feature.onboarding.model.FoldersUi
import dev.pampa.mycloudgallery.feature.onboarding.model.FoundNasUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeCandidateUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinUi
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyUi
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingDestination
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingUiState
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryUi
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalReason
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalUi
import dev.pampa.mycloudgallery.feature.onboarding.model.SummaryUi
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnTestUi
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnUi
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Quello che il ViewModel dice alla pagina una volta sola. */
internal sealed interface OnboardingEvent {
  /**
   * Si esce dalla pagina: l'onboarding e' finito (`onDone` di :app, poi la destinazione la decide `SetupState`), oppure
   * il passo riaperto dalle Impostazioni non si riapre (ON-7), oppure la richiesta rifatta dalla pagina d'attesa e' partita
   * o e' stata lasciata (ON-2).
   */
  data object Finished : OnboardingEvent
}

/**
 * Lo stato delle schermate dell'onboarding (docs/fase2-progetto.md §2.1 e §2.2, lato nuovo dispositivo).
 *
 * **Non decide niente.** La logica dei 14 passi e' del coordinatore di `core-family` (P4), che salva l'avanzamento a
 * ogni passo; quella dell'ingresso con invito e del recupero e' di [JoinFlow] e [AdminRecovery]. Qui si
 * inoltrano i gesti ([StepInput] per i passi), si legge l'esito ([StepResult], [JoinStep], [RecoveryOutcome]) e lo
 * si traduce in cio' che le pagine mostrano ([OnboardingUiState]): caricamento, problema (uno dei 29 di
 * [OnboardingProblem], ognuno con la sua scheda), avviso, conferma.
 *
 * Regole che il ViewModel rispetta:
 *  - **le password non restano qui**: arrivano come `CharArray`, passano al coordinatore (che le azzera) e si
 *    azzerano di nuovo qui; non finiscono in nessuno stato ne' in un log;
 *  - **un solo tentativo** di password: dopo un errore d'autenticazione lo stato chiede una password nuova
 *    (`needsNewPassword`); il ViewModel non riprova mai da solo (E-R8);
 *  - il codice di recupero ([RecoveryCodeDisplay]) vive solo fra la generazione e la fine del passo 12, e si chiude
 *    (azzerando i caratteri) quando il passo finisce o il ViewModel viene distrutto;
 *  - un'eccezione inattesa di un contratto diventa un problema da mostrare, mai un crash.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
  private val coordinator: OnboardingCoordinator,
  private val joinFlow: JoinFlow,
  private val adminRecovery: AdminRecovery,
  private val inviteCodec: QrPayloadCodec<FamilyInvite>,
  private val familyStateCodec: QrPayloadCodec<FamilyStateQr>,
  private val vpnController: VpnController,
  private val healthReader: BackgroundHealthReader,
  private val localNetwork: LocalNetworkPermission,
  private val vpnConfig: VpnControlConfigStore,
  private val registry: RegistryService,
) : ViewModel() {

  /** L'avanzamento, dal coordinatore (salvato a ogni passo, E-O9). */
  val progress: StateFlow<OnboardingProgress> = coordinator.progress

  /** Cio' che l'utente sta scrivendo, un modulo per pagina: sopravvive a un cambio di pagina e a una rotazione. */
  internal val forms = OnboardingForms()

  private val state = MutableStateFlow(OnboardingUiState())
  internal val ui: StateFlow<OnboardingUiState> = state.asStateFlow()

  private val eventFlow = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  internal val events: SharedFlow<OnboardingEvent> = eventFlow.asSharedFlow()

  private var discoveryJob: Job? = null
  private var foldersJob: Job? = null
  private var recoveryDisplay: RecoveryCodeDisplay? = null
  private var extraShares: List<String> = emptyList()
  private var homeCandidate: HomeCandidateUi? = null
  private var oldAdminFate: OldAdminFate? = null

  @Volatile
  private var lastRejectedText: String? = null
  private var joinClosed = false

  /** Si rifa' la richiesta dalla pagina d'attesa (ON-2): finito (o lasciato) l'ingresso, si esce. */
  private var renewing = false

  /** La pagina e' quella della radice (non un passo riaperto dalle Impostazioni): un onboarding finito qui vuol dire "fuori dalla famiglia" (ON-4). */
  private var atRoot = false

  init {
    viewModelScope.launch {
      val health = guard { healthReader.read() }
      if (health != null) state.update { it.copy(isSystemUser = health.isSystemUser) }
    }
    viewModelScope.launch {
      // Un percorso "entra" scelto in una sessione precedente (il processo e' morto): si riparte dalla scansione.
      coordinator.progress.collect { p ->
        val path = p.path
        // Solo se la richiesta non e' ancora partita (passi 2-3): dopo, i passi locali si riprendono dove erano (P9).
        if ((path == OnboardingPath.JOIN_QR || path == OnboardingPath.JOIN_MANUAL) &&
          (p.current == OnboardingStep.NAS_ADDRESS || p.current == OnboardingStep.ACCOUNT) && state.value.join == null && !joinClosed
        ) {
          state.update { it.copy(join = JoinUi(page = if (path == OnboardingPath.JOIN_MANUAL) JoinPage.Manual else JoinPage.Scan)) }
          if (path == OnboardingPath.JOIN_MANUAL) loadManualHome()
        }
      }
    }
    viewModelScope.launch {
      // Un recupero dell'admin a meta' (aree fatte, admin non ancora recuperato) riprende dai kit anche dopo la morte del
      // processo: prima il recupero viveva solo qui, e al riavvio si finiva su "Ruoli" senza uscita (ON-3).
      if (guard { coordinator.recoveryPending() } == true && state.value.recovery == null) reopenRecovery()
    }
  }

  // --- Generale -------------------------------------------------------------------------------------

  /**
   * Riapre un passo (dal riepilogo, o arrivando da "Da completare" e dagli altri tasti delle Impostazioni). Se il passo non
   * si riapre non si resta su una pagina che non c'entra (il riepilogo, con "indietro" che non fa niente): si esce (ON-7).
   */
  fun resume(step: OnboardingStep) {
    viewModelScope.launch {
      if (guardResult { coordinator.resume(step) } is StepResult.Problem) eventFlow.tryEmit(OnboardingEvent.Finished)
    }
  }

  /**
   * La pagina e' quella della radice (stato del dispositivo "non configurato"): se l'onboarding risulta finito, il
   * dispositivo e' stato revocato, dismesso o ha perso lo stato della famiglia, e invece del vecchio riepilogo si dice
   * cosa e' successo, con "Configura di nuovo" (ON-4).
   */
  fun enterAtRoot() {
    if (atRoot) return
    atRoot = true
    viewModelScope.launch {
      combine(coordinator.progress, registry.status) { progress, status -> progress to status }.collect { (progress, status) ->
        // Finito e "fuori": revocato, dismesso o senza stato. Con "approvato" o "in attesa" la radice sta gia' andando
        // altrove (Libreria, attesa): niente pagina in mezzo.
        val reason = removalReasonOf(status).takeIf { OnboardingStep.SUMMARY in progress.completed }
        state.update { s ->
          when {
            reason == null -> s.copy(removal = null)
            s.removal == null -> s.copy(removal = RemovalUi(reason = reason))
            else -> s.copy(removal = s.removal.copy(reason = reason))
          }
        }
      }
    }
  }

  private fun removalReasonOf(status: TrustStatus): RemovalReason? = when (status) {
    TrustStatus.Revoked -> RemovalReason.REVOKED
    TrustStatus.Retired -> RemovalReason.RETIRED
    TrustStatus.NotConfigured -> RemovalReason.OTHER
    is TrustStatus.Approved, is TrustStatus.Pending, is TrustStatus.Stale, TrustStatus.Lost -> null
  }

  /**
   * "Configura di nuovo" (ON-4, e dalle Impostazioni ON-5): il coordinatore dimentica account, password, chiavi e stato
   * della famiglia; si riparte dal benvenuto.
   */
  fun startOver() {
    state.update { s -> s.copy(removal = s.removal?.copy(busy = true, problem = null)) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.startOver() }) {
        is StepResult.Advanced -> {
          joinClosed = false
          state.update { OnboardingUiState(isSystemUser = it.isSystemUser) }
        }
        is StepResult.Problem -> state.update { s -> s.copy(removal = s.removal?.copy(busy = false, problem = result.problem)) }
      }
    }
  }

  /** Il permesso per la rete locale (ramo per il target 37): compare al passo 9 solo se e' negato. */
  internal fun localNetworkAccess(): LocalNetworkAccess = localNetwork.status()

  /** L'intent che apre WireGuard, o null se non e' installato. */
  internal fun wireGuardIntent(): android.content.Intent? = vpnController.openWireGuardIntent()

  /** Cosa mostrare adesso: una pagina di "entra", una di "recupera", oppure il passo corrente. */
  internal fun destination(progress: OnboardingProgress, ui: OnboardingUiState): OnboardingDestination = when {
    ui.removal != null -> OnboardingDestination.Removed
    ui.join != null -> OnboardingDestination.Join(ui.join.page)
    ui.recovery != null -> OnboardingDestination.Recovery(ui.recovery.page)
    progress.current == OnboardingStep.FAMILY_KEY -> OnboardingDestination.Step(progress.current, ui.key.page.ordinal)
    else -> OnboardingDestination.Step(progress.current)
  }

  /** Torna indietro di una pagina o di un passo. Falso se in questa pagina non si puo' (la pagina decide se mostrare la freccia). */
  fun back() {
    val current = state.value
    when {
      current.join != null -> backFromJoin(current.join)
      current.recovery != null -> backFromRecovery(current.recovery)
      progress.value.current == OnboardingStep.FAMILY_KEY && current.key.page == KeyPage.Retype ->
        state.update { it.copy(key = it.key.copy(page = KeyPage.ShowCode, problem = null)) }
      progress.value.current == OnboardingStep.FAMILY_KEY && current.key.page != KeyPage.Intro -> Unit
      else -> viewModelScope.launch {
        discoveryJob?.cancel()
        guard { coordinator.back() }
        state.update { it.copy(banner = null) }
      }
    }
  }

  /** Dove si puo' tornare dalla pagina di adesso (per mostrare o nascondere la freccia di ritorno). */
  internal fun canGoBack(progress: OnboardingProgress, ui: OnboardingUiState): Boolean = when {
    ui.removal != null -> false
    ui.join != null -> ui.join.page in setOf(JoinPage.Scan, JoinPage.Manual, JoinPage.Summary, JoinPage.Password) && !ui.join.busy
    ui.recovery != null -> ui.recovery.page != RecoveryPage.Done && !ui.recovery.busy
    progress.current == OnboardingStep.FAMILY_KEY -> ui.key.page == KeyPage.Intro && !ui.key.busy || ui.key.page == KeyPage.Retype
    else -> progress.current != OnboardingStep.WELCOME && !isStepBusy(progress.current, ui)
  }

  private fun isStepBusy(step: OnboardingStep, ui: OnboardingUiState): Boolean = when (step) {
    OnboardingStep.NAS_ADDRESS -> ui.address.checking
    OnboardingStep.ACCOUNT -> ui.account.submitting
    OnboardingStep.AREAS -> ui.areas.submitting
    else -> false
  }

  fun dismissWarning(warning: OnboardingWarning) = state.update { it.copy(warnings = it.warnings - warning) }

  fun dismissBanner() = state.update { it.copy(banner = null) }

  /** Salta un passo saltabile: finisce in "Da completare" nelle Impostazioni (§2.1). */
  fun skip(step: OnboardingStep) {
    if (step == OnboardingStep.FOLDERS) foldersJob?.cancel()
    viewModelScope.launch {
      val result = guardResult { coordinator.skip(step) }
      if (result is StepResult.Problem) setStepProblem(step, result.problem) else setStepProblem(step, null)
    }
  }

  /**
   * "Continua piu' tardi" (E-O1, NAS spento o non trovato): il passo corrente e tutti quelli che hanno bisogno del NAS
   * aspettano, e si va avanti con i passi locali (6-11, 13); dal riepilogo si riprendono. Se non c'e' niente da rimandare
   * (ingresso con invito, recupero, o il coordinatore non lo ammette) la configurazione resta salvata cosi' com'e' e
   * [onNothingToDefer] (di :app) manda l'app in secondo piano.
   */
  fun continueLater(onNothingToDefer: () -> Unit) {
    val step = progress.value.current
    val current = state.value
    if (current.join != null || current.recovery != null || step.local) {
      onNothingToDefer()
      return
    }
    viewModelScope.launch {
      when (guardResult { coordinator.skip(step) }) {
        is StepResult.Advanced -> setStepProblem(step, null)
        is StepResult.Problem -> onNothingToDefer()
      }
    }
  }

  /** Il passo e' stato finito e si puo' passare oltre (usato da "Da completare": un solo passo, poi si esce). */
  fun clearProblem(step: OnboardingStep) = setStepProblem(step, null)

  // --- 1. Benvenuto ---------------------------------------------------------------------------------

  /** Passo 1: il percorso scelto. Dopo, per "entra" parte la scansione, per "recupera" l'indirizzo del NAS. */
  fun choosePath(path: OnboardingPath) {
    state.update { it.copy(welcomeBusy = true, welcomeProblem = null) }
    viewModelScope.launch {
      val result = guardResult { coordinator.submit(StepInput.ChoosePath(path)) }
      when (result) {
        is StepResult.Advanced -> state.update {
          it.copy(
            welcomeBusy = false,
            join = when (path) {
              OnboardingPath.JOIN_QR -> JoinUi(page = JoinPage.Scan)
              OnboardingPath.JOIN_MANUAL -> JoinUi(page = JoinPage.Manual)
              else -> null
            },
            recovery = null,
            address = AddressUi(),
            account = AccountUi(),
          )
        }
        is StepResult.Problem -> state.update { it.copy(welcomeBusy = false, welcomeProblem = result.problem) }
      }
      if (path == OnboardingPath.JOIN_QR || path == OnboardingPath.JOIN_MANUAL) joinClosed = false
      // L'avviso sulla versione minima lo aggiunge l'invito senza QR, se manca lo stato della famiglia.
      if (result is StepResult.Advanced && path == OnboardingPath.JOIN_MANUAL) loadManualHome()
    }
  }

  // --- 2. Indirizzo del NAS -------------------------------------------------------------------------

  /** Entrando nel passo 2: si legge la rete attuale ("e' la rete di casa?"). Nessuna rete e' contattata. */
  fun enterAddressStep() {
    discoveryJob?.cancel()
    state.update { it.copy(address = AddressUi()) }
    viewModelScope.launch {
      val candidate = guard { coordinator.homeCandidate() }
      homeCandidate = candidate?.let {
        HomeCandidateUi(it.ipv4Subnet, it.gatewayV4, it.dnsDomain, canConfirm = it.canBeConfirmed)
      }
      val home = homeCandidate?.let { HomeUi.Ask(it) } ?: HomeUi.NoWifi
      state.update { it.copy(address = it.address.copy(home = home)) }
    }
  }

  /** "Si', e' casa" (con il consenso si raccolgono ULA, gateway e dominio) o "No". */
  fun confirmHome(isHome: Boolean) {
    val candidate = homeCandidate
    viewModelScope.launch {
      val result = guardResult { coordinator.submit(StepInput.ConfirmHome(isHome)) }
      when {
        result is StepResult.Problem -> state.update { it.copy(address = it.address.copy(problem = result.problem)) }
        !isHome -> state.update { it.copy(address = it.address.copy(home = HomeUi.NotHome, problem = null)) }
        candidate != null -> {
          state.update { it.copy(address = it.address.copy(home = HomeUi.Confirmed(candidate), problem = null)) }
          startDiscovery()
        }
        else -> state.update { it.copy(address = it.address.copy(problem = OnboardingProblem.NOT_ON_WIFI)) }
      }
    }
  }

  private fun startDiscovery() {
    discoveryJob?.cancel()
    state.update { it.copy(address = it.address.copy(discovering = true, found = emptyList())) }
    discoveryJob = viewModelScope.launch {
      withTimeoutOrNull(DISCOVERY_MILLIS) {
        guard {
          coordinator.discoverNas().collect { nas ->
            val entry = FoundNasUi(name = nas.serviceName, ip = nas.address.ip)
            state.update { s ->
              if (s.address.found.any { it.ip == entry.ip }) s else s.copy(address = s.address.copy(found = s.address.found + entry))
            }
          }
        }
      }
      state.update { it.copy(address = it.address.copy(discovering = false)) }
    }
  }

  /** Controlla il NAS: TCP, NEGOTIATE, identita'. Nessuna password parte (§2.1 passo 2). */
  fun checkNas(address: NasAddress) {
    discoveryJob?.cancel()
    state.update { it.copy(address = it.address.copy(checking = true, discovering = false, problem = null)) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(StepInput.NasAddressChosen(address)) }) {
        is StepResult.Advanced -> state.update {
          it.copy(address = it.address.copy(checking = false), banner = OnboardingBanner.NAS_VERIFIED)
        }
        is StepResult.Problem -> state.update { it.copy(address = it.address.copy(checking = false, problem = result.problem)) }
      }
    }
  }

  // --- 3. Account -----------------------------------------------------------------------------------

  /**
   * L'**unico** tentativo con utente e password. [password] si azzera qui dopo l'uso (il coordinatore l'ha gia'
   * azzerata, ma una copia in piu' non deve restare).
   */
  fun submitAccount(user: String, password: CharArray) {
    state.update { it.copy(account = AccountUi(submitting = true)) }
    viewModelScope.launch {
      val input = StepInput.Credentials(user, password)
      val result = try {
        guardResult { coordinator.submit(input) }
      } finally {
        input.clear()
        password.fill(CLEARED)
      }
      when (result) {
        is StepResult.Advanced -> {
          addWarnings(result.warnings)
          state.update {
            it.copy(account = AccountUi(), banner = OnboardingBanner.CREDENTIALS_SAVED)
          }
        }
        is StepResult.Problem -> state.update {
          it.copy(account = AccountUi(problem = result.problem, needsNewPassword = result.problem.isAuthFailure()))
        }
      }
    }
  }

  // --- 4. Aree --------------------------------------------------------------------------------------

  /** Entrando nel passo 4: si provano `<utente>`, `Public`, `NAS` e le share digitate. */
  fun enterAreasStep() = loadAreas()

  fun refreshAreas() = loadAreas()

  /** Aggiunge una share digitata all'elenco e la prova. */
  fun addShare(name: String) {
    if (name.isBlank() || name in extraShares) return
    extraShares = extraShares + name.trim()
    loadAreas()
  }

  private fun loadAreas() {
    state.update { it.copy(areas = AreasUi(loading = true)) }
    viewModelScope.launch {
      val candidates = guard { coordinator.proposeAreas(extraShares) }
      state.update { it.copy(areas = AreasUi(loading = false, candidates = candidates.orEmpty())) }
    }
  }

  fun submitAreas(commonShare: String, privateShare: String) {
    state.update { it.copy(areas = it.areas.copy(submitting = true, problem = null)) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(StepInput.Areas(commonShare, privateShare)) }) {
        is StepResult.Advanced -> {
          addWarnings(result.warnings)
          state.update { it.copy(areas = it.areas.copy(submitting = false), banner = null) }
          // Il recupero parte dopo le aree, non dopo l'account: per verificare il kit serve il registro della famiglia, che sta
          // nell'area comune (senza, il profilo del dispositivo non c'e' e il recupero rispondeva "passo non disponibile";
          // trovato sul campo il 19/10). Il kit si cerca nell'area privata scelta qui.
          if (isRecoveryPath()) startRecovery(privateShare)
        }
        is StepResult.Problem -> state.update { it.copy(areas = it.areas.copy(submitting = false, problem = result.problem)) }
      }
    }
  }

  // --- 5. Cartelle ----------------------------------------------------------------------------------

  /** Entrando nel passo 5: la proposta di esclusioni, limitata e annullabile (saltando il passo si annulla). */
  fun enterFoldersStep() {
    foldersJob?.cancel()
    state.update { it.copy(folders = FoldersUi(loading = true)) }
    foldersJob = viewModelScope.launch {
      val proposal = guard { coordinator.proposeFolders() }
      state.update { it.copy(folders = FoldersUi(loading = false, proposal = proposal)) }
    }
  }

  fun submitFolders(excluded: Set<String>) = runLocal(OnboardingStep.FOLDERS, StepInput.Folders(excluded))

  // --- 6-8. Scelte locali ---------------------------------------------------------------------------

  fun submitBackupSources(camera: Boolean, screenshots: Boolean) =
    runLocal(OnboardingStep.BACKUP_SOURCES, StepInput.BackupSources(camera, screenshots))

  fun submitRoles(indexer: Boolean, preparer: Boolean) = runLocal(OnboardingStep.ROLES, StepInput.Roles(indexer, preparer))

  fun submitPreset(preset: Preset, quality: PreviewQuality) =
    runLocal(OnboardingStep.PRESET, StepInput.PresetChoice(preset, quality))

  // --- 9. Permessi ----------------------------------------------------------------------------------

  /** I dialoghi dei permessi sono stati mostrati: l'esito si rilegge da Android (E-O4), qui non si decide niente. */
  fun submitPermissionsReviewed() = runLocal(OnboardingStep.PERMISSIONS, StepInput.PermissionsReviewed)

  // --- 10. Batteria ---------------------------------------------------------------------------------

  /** Entrando nel passo 10, e a ogni ritorno dalle impostazioni (E-O9): la salute del background riletta. */
  fun refreshBattery() {
    viewModelScope.launch {
      val health = guard { healthReader.read() }
      state.update { it.copy(battery = it.battery.copy(health = health)) }
    }
  }

  fun submitBatteryReviewed() = runLocal(OnboardingStep.BATTERY, StepInput.BatteryReviewed)

  // --- 11. VPN --------------------------------------------------------------------------------------

  /** Entrando nel passo 11: cosa si puo' fare con WireGuard su questo profilo (ogni profilo ha il suo, E-P20). */
  fun enterVpnStep() {
    viewModelScope.launch {
      state.update { it.copy(vpn = VpnUi(capability = guard { vpnController.capability.value })) }
    }
  }

  /**
   * La prova osservata: si accende la VPN, si guarda cosa fa la rete, si spegne, si guarda ancora. Mai "accesa"
   * solo perche' il comando e' partito. Se spegnendola la rete sparisce (probabile "VPN sempre attiva con blocco"),
   * la si riaccende subito: l'utente non deve restare senza rete per una prova.
   */
  fun runVpnTest(tunnelName: String) {
    viewModelScope.launch {
      state.update { it.copy(vpn = it.vpn.copy(test = VpnTestUi.Running(up = true))) }
      // WireGuard accende e spegne un tunnel per nome, letto dalla configurazione del controllo (P2): per la prova si usa
      // il nome appena scritto, poi si rimette la configurazione di prima. La scelta vera la salva "Continua".
      val previous = vpnConfig.config.value
      guard { vpnConfig.save(VpnControlConfig(enabled = true, tunnelName = tunnelName.trim())) }
      try {
        observedVpnTest()
      } finally {
        if (vpnConfig.config.value != previous) guard { vpnConfig.save(previous) }
      }
    }
  }

  private suspend fun observedVpnTest() {
    val up = guard { vpnController.setTunnel(true) } ?: VpnCommandResult.NotObserved
    if (up != VpnCommandResult.Confirmed) {
      state.update { it.copy(vpn = it.vpn.copy(test = VpnTestUi.Done(up = up, down = null))) }
      return
    }
    state.update { it.copy(vpn = it.vpn.copy(test = VpnTestUi.Running(up = false))) }
    val down = guard { vpnController.setTunnel(false) } ?: VpnCommandResult.NotObserved
    if (down == VpnCommandResult.LostConnectivity) guard { vpnController.setTunnel(true) }
    state.update { it.copy(vpn = it.vpn.copy(test = VpnTestUi.Done(up = up, down = down))) }
  }

  fun submitVpn(remoteControl: Boolean, tunnelName: String?) {
    state.update { it.copy(vpn = it.vpn.copy(submitting = true, problem = null)) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(StepInput.VpnSetup(remoteControl, tunnelName)) }) {
        is StepResult.Advanced -> state.update { it.copy(vpn = it.vpn.copy(submitting = false)) }
        is StepResult.Problem -> state.update { it.copy(vpn = it.vpn.copy(submitting = false, problem = result.problem)) }
      }
    }
  }

  // --- 12. Chiave di famiglia e kit -----------------------------------------------------------------

  /** (a) e (b): si genera la chiave e il codice di recupero, e si mostra il codice. */
  fun generateKey() {
    state.update { it.copy(key = KeyUi(busy = true)) }
    viewModelScope.launch {
      val display = guard { coordinator.recoveryCode() }
      if (display == null || display.groups.isEmpty()) {
        display?.close()
        state.update { it.copy(key = KeyUi(problem = OnboardingProblem.STEP_NOT_AVAILABLE)) }
      } else {
        recoveryDisplay?.close()
        recoveryDisplay = display
        val groups = display.groups.map { String(it) }
        state.update { it.copy(key = KeyUi(page = KeyPage.ShowCode, groups = groups, fingerprint = display.familyFingerprint)) }
      }
    }
  }

  /** (c): il codice e' stato scritto; si passa alla pagina in cui lo si riscrive per intero. */
  fun codeWritten() = state.update { it.copy(key = it.key.copy(page = KeyPage.Retype, problem = null)) }

  fun showCodeAgain() = state.update { it.copy(key = it.key.copy(page = KeyPage.ShowCode, problem = null)) }

  /**
   * (c), (d) e (e): il codice riscritto per intero; poi il coordinatore salva il kit, lo rilegge e lo apre con quel
   * codice, e solo allora fonda la famiglia. Un errore riporta alla pagina del codice (o all'inizio se la famiglia
   * esiste gia'), con la sua scheda.
   */
  fun submitRecoveryCode(code: CharArray) {
    state.update { it.copy(key = it.key.copy(page = KeyPage.Verifying, problem = null)) }
    viewModelScope.launch {
      val input = StepInput.RecoveryCodeTyped(code)
      val result = try {
        guardResult { coordinator.submit(input) }
      } finally {
        input.clear()
        code.fill(CLEARED)
      }
      when (result) {
        is StepResult.Advanced -> {
          closeRecoveryDisplay()
          state.update { it.copy(key = KeyUi(), banner = OnboardingBanner.KIT_VERIFIED) }
        }
        is StepResult.Problem -> {
          if (result.problem == OnboardingProblem.FAMILY_ALREADY_EXISTS) closeRecoveryDisplay()
          state.update {
            val page = if (result.problem == OnboardingProblem.FAMILY_ALREADY_EXISTS) KeyPage.Intro else KeyPage.Retype
            it.copy(key = it.key.copy(page = page, problem = result.problem, groups = if (page == KeyPage.Intro) emptyList() else it.key.groups))
          }
        }
      }
    }
  }

  private fun closeRecoveryDisplay() {
    recoveryDisplay?.close()
    recoveryDisplay = null
  }

  // --- 13-14. Avviso e riepilogo --------------------------------------------------------------------

  fun submitSecurityNotice(externalBackupDone: Boolean, routerChecklistDone: Boolean) =
    runLocal(OnboardingStep.SECURITY_NOTICE, StepInput.SecurityNotice(externalBackupDone, routerChecklistDone))

  /** "Vai alla Libreria": l'onboarding e' finito. Dopo l'evento [OnboardingEvent.Finished] decide `SetupState`. */
  fun finish() {
    state.update { it.copy(summary = SummaryUi(submitting = true)) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(StepInput.Finish) }) {
        is StepResult.Advanced -> {
          state.update { it.copy(summary = SummaryUi()) }
          eventFlow.tryEmit(OnboardingEvent.Finished)
        }
        is StepResult.Problem -> {
          state.update { it.copy(summary = SummaryUi(problem = result.problem)) }
          // Il recupero dell'admin non e' finito: non "passo non disponibile" senza uscita, ma le pagine del recupero (ON-3).
          if (guard { coordinator.recoveryPending() } == true) reopenRecovery()
        }
      }
    }
  }

  // --- Ingresso con invito (§2.2, lato nuovo dispositivo) -------------------------------------------

  private fun updateJoin(transform: (JoinUi) -> JoinUi) =
    state.update { s -> s.join?.let { s.copy(join = transform(it)) } ?: s }

  /**
   * Un testo letto dalla fotocamera: e' ostile per definizione. Il verdetto e' immediato (lo scanner lo aspetta):
   * un QR di WireGuard (E-S15), uno che non e' nostro, uno rovinato o di un'altra famiglia (E-O5) si rifiuta con il
   * suo motivo; lo stesso testo appena rifiutato si ignora in silenzio. Un invito valido ferma lo scanner.
   */
  fun onScanned(text: String): ScanVerdict {
    if (text == lastRejectedText) return ScanVerdict.Ignore
    val verdict = if (state.value.recovery?.scanningFamilyState == true || state.value.join?.scanningFamilyState == true) {
      acceptFamilyState(text)
    } else {
      acceptInvite(text)
    }
    if (verdict is ScanVerdict.Reject) {
      lastRejectedText = text
      updateJoin { it.copy(rejection = verdict.reason) }
      updateRecovery { it.copy(rejection = verdict.reason) }
    } else if (verdict == ScanVerdict.Accept) {
      lastRejectedText = null
    }
    return verdict
  }

  private fun acceptInvite(text: String): ScanVerdict {
    if (QrPayloads.looksLikeWireGuardConfig(text)) return ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET)
    if (!text.startsWith(QrPayloads.INVITE_PREFIX)) return ScanVerdict.Reject(ScanRejection.NOT_OURS)
    return when (val verdict = inviteCodec.decode(text)) {
      is Verdict.Accepted -> {
        updateJoin { it.copy(page = JoinPage.Summary, invite = verdict.value, rejection = null, problem = null) }
        ScanVerdict.Accept
      }
      is Verdict.Rejected -> ScanVerdict.Reject(verdict.reason.toScanRejection())
    }
  }

  private fun acceptFamilyState(text: String): ScanVerdict {
    if (QrPayloads.looksLikeWireGuardConfig(text)) return ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET)
    if (!text.startsWith(QrPayloads.FAMILY_STATE_PREFIX)) return ScanVerdict.Reject(ScanRejection.NOT_OURS)
    return when (val verdict = familyStateCodec.decode(text)) {
      is Verdict.Accepted -> {
        state.update { s ->
          s.copy(
            join = s.join?.copy(
              familyState = verdict.value,
              scanningFamilyState = false,
              rejection = null,
              page = if (s.join.invite == null) JoinPage.Manual else s.join.page,
            ),
            recovery = s.recovery?.copy(familyState = verdict.value, scanningFamilyState = false),
          )
        }
        // Senza QR: id e impronta della famiglia vengono gia' dal QR di un dispositivo approvato (non dal NAS), non si riscrivono.
        if (state.value.join != null) {
          if (forms.manualJoin.familyId.isBlank()) forms.manualJoin.familyId = verdict.value.familyId
          if (forms.manualJoin.fingerprint.isBlank()) forms.manualJoin.fingerprint = verdict.value.familyKeyFingerprint
        }
        ScanVerdict.Accept
      }
      is Verdict.Rejected -> ScanVerdict.Reject(verdict.reason.toScanRejection())
    }
  }

  private fun RejectReason.toScanRejection(): ScanRejection = when (this) {
    RejectReason.WRONG_FAMILY -> ScanRejection.OTHER_FAMILY
    else -> ScanRejection.MALFORMED
  }

  /**
   * Dalla scansione ai dati a mano. Il percorso diventa quello senza QR (ON-1): `JoinFlow` vuole il percorso giusto per
   * l'invito, e "e' la rete di casa?" si chiede solo li'. Rifacendo la richiesta (ON-2) il percorso non si cambia piu'.
   */
  fun joinEnterManually() {
    lastRejectedText = null
    viewModelScope.launch {
      if (!switchJoinPath(OnboardingPath.JOIN_MANUAL)) return@launch
      updateJoin { it.copy(page = JoinPage.Manual, rejection = null, scanningFamilyState = false, problem = null) }
      loadManualHome()
    }
  }

  /** Dai dati a mano alla scansione dell'invito (il percorso torna quello con il QR). */
  fun joinScanInvite() {
    lastRejectedText = null
    viewModelScope.launch {
      if (!switchJoinPath(OnboardingPath.JOIN_QR)) return@launch
      updateJoin { it.copy(page = JoinPage.Scan, rejection = null, scanningFamilyState = false, invite = null, problem = null) }
    }
  }

  /** Il percorso dell'ingresso diventa [path] (se non lo e' gia'). Falso se il coordinatore non lo permette: il problema si vede. */
  private suspend fun switchJoinPath(path: OnboardingPath): Boolean {
    if (progress.value.path == path) return true
    return when (val result = guardResult { coordinator.submit(StepInput.ChoosePath(path)) }) {
      is StepResult.Advanced -> true
      is StepResult.Problem -> {
        updateJoin { it.copy(problem = result.problem, rejection = null) }
        false
      }
    }
  }

  /** Senza QR: la rete attuale, per la domanda "e' la rete di casa?" (nessuna rete e' contattata). */
  private fun loadManualHome() {
    // Rifacendo la richiesta la casa e' gia' confermata (e il passo 2 non si rifa').
    if (renewing) {
      updateJoin { it.copy(home = null) }
      return
    }
    updateJoin { it.copy(home = HomeUi.Loading) }
    viewModelScope.launch {
      val candidate = guard { coordinator.homeCandidate() }
      val home = candidate?.let { HomeCandidateUi(it.ipv4Subnet, it.gatewayV4, it.dnsDomain, canConfirm = it.canBeConfirmed) }
      homeCandidate = home
      updateJoin { it.copy(home = home?.let { c -> HomeUi.Ask(c) } ?: HomeUi.NoWifi) }
    }
  }

  /** Senza QR non c'e' un ULA da confrontare: "e' casa" lo conferma l'utente, prima di contattare il NAS (ON-1). */
  fun confirmManualHome(isHome: Boolean) {
    val candidate = homeCandidate
    if (!isHome) {
      updateJoin { it.copy(home = HomeUi.NotHome, problem = null) }
      return
    }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(StepInput.ConfirmHome(true)) }) {
        is StepResult.Problem -> updateJoin { it.copy(problem = result.problem) }
        is StepResult.Advanced -> updateJoin {
          if (candidate != null) it.copy(home = HomeUi.Confirmed(candidate), problem = null) else it.copy(problem = OnboardingProblem.NOT_ON_WIFI)
        }
      }
    }
  }

  /** Si torna alla domanda "e' casa?" (Wi-Fi cambiato, impostazioni del Wi-Fi). */
  fun refreshManualHome() = loadManualHome()

  /** Lo "stato della famiglia" (M7) si scansiona dalla pagina dei dati a mano o da quella del recupero. */
  fun scanFamilyState() {
    lastRejectedText = null
    state.update { s ->
      s.copy(
        join = s.join?.copy(page = JoinPage.Scan, scanningFamilyState = true, rejection = null),
        recovery = s.recovery?.copy(scanningFamilyState = true),
      )
    }
  }

  /** Si lascia la scansione dello "stato della famiglia" senza leggerlo. */
  fun cancelFamilyStateScan() {
    lastRejectedText = null
    state.update { s ->
      s.copy(
        join = s.join?.copy(page = if (s.join.invite == null) JoinPage.Manual else JoinPage.Summary, scanningFamilyState = false, rejection = null),
        recovery = s.recovery?.copy(scanningFamilyState = false),
      )
    }
  }

  /**
   * "Entra senza QR": i dati scritti a mano diventano un invito (`ManualInvite`: id e impronta letti come li scrive una
   * persona, le due aree, la versione minima del registro 0 o quella dello "stato della famiglia" se c'e', M7). Il GUID
   * del NAS e i prefissi di casa non si conoscono: li fornisce la configurazione **firmata** letta dal NAS dopo
   * l'autenticazione (mai dal NAS in chiaro, B9). Prima l'invito non aveva le aree e non si normalizzava: non poteva
   * riuscire (ON-1). [privateShare] vuota = quella con il nome dell'utente, come nell'invito dell'admin.
   */
  fun submitManualJoin(
    ip: String,
    user: String,
    familyId: String,
    fingerprint: String,
    deviceName: String,
    commonShare: String,
    privateShare: String = "",
  ) {
    val current = state.value.join ?: return
    val familyState = current.familyState
    // Lo "stato della famiglia" e i dati scritti devono dire la stessa famiglia: altrimenti uno dei due e' di un'altra (E-O5).
    if (familyState != null &&
      (Base32.normalizeHumanInput(familyId) != familyState.familyId || Base32.normalizeHumanInput(fingerprint) != familyState.familyKeyFingerprint)
    ) {
      updateJoin { it.copy(problem = OnboardingProblem.INVITE_OTHER_FAMILY) }
      return
    }
    val verdict = ManualInvite.create(
      familyId = familyId,
      familyKeyFingerprint = fingerprint,
      nasIp = ip,
      nasUser = user,
      commonShare = commonShare.trim(),
      privateShare = privateShare.trim().ifBlank { user.trim() },
      deviceName = deviceName.trim(),
      minRegistryVersion = familyState?.registryVersion ?: 0L,
    )
    val invite = (verdict as? Verdict.Accepted)?.value
    if (invite == null) {
      updateJoin { it.copy(problem = OnboardingProblem.INVITE_INVALID) }
      return
    }
    if (familyState == null) addWarnings(setOf(OnboardingWarning.NO_MIN_REGISTRY_VERSION))
    updateJoin { it.copy(invite = invite) }
    continueJoin()
  }

  /** "Continua" sul riepilogo: solo adesso si controlla la casa e il NAS (TCP, NEGOTIATE, GUID uguale all'invito). */
  fun continueJoin() {
    val invite = state.value.join?.invite ?: return
    updateJoin { it.copy(busy = true, problem = null) }
    viewModelScope.launch {
      val step = guard { joinFlow.start(invite) } ?: JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
      applyJoinStep(step)
    }
  }

  /** La password dell'account: l'unico campo da scrivere. Un solo tentativo (E-R8). */
  fun submitJoinPassword(password: CharArray) {
    updateJoin { it.copy(busy = true, problem = null, needsNewPassword = false) }
    viewModelScope.launch {
      val step = try {
        guard { joinFlow.authenticate(password) } ?: JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
      } finally {
        password.fill(CLEARED)
      }
      applyJoinStep(step)
    }
  }

  fun submitJoinName(name: String) {
    updateJoin { it.copy(busy = true, problem = null) }
    viewModelScope.launch {
      val step = guard { joinFlow.register(name) } ?: JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
      applyJoinStep(step)
    }
  }

  private fun applyJoinStep(step: JoinStep) {
    when (step) {
      is JoinStep.InviteAccepted -> updateJoin { it.copy(busy = false, invite = step.invite, page = JoinPage.Summary) }
      JoinStep.NeedsPassword -> updateJoin { it.copy(busy = false, page = JoinPage.Password, problem = null) }
      is JoinStep.NeedsName -> updateJoin { it.copy(busy = false, page = JoinPage.Name, suggestedName = step.suggestedName, problem = null) }
      is JoinStep.AwaitingApproval -> updateJoin {
        it.copy(busy = false, page = JoinPage.Awaiting, code = step.code, deviceKeyQr = step.deviceKeyQr, problem = null)
      }
      JoinStep.Approved -> updateJoin { it.copy(busy = false, page = JoinPage.Approved, problem = null) }
      is JoinStep.Failed -> updateJoin {
        it.copy(busy = false, problem = step.problem, needsNewPassword = step.problem.isAuthFailure())
      }
    }
  }

  /**
   * La richiesta e' sul NAS e il codice e' stato mostrato: mentre l'admin approva si finiscono i passi sul telefono
   * (ruoli, preset, permessi, batteria, VPN). Alla fine dell'onboarding la radice mostra l'attesa (con il codice), che
   * si aggiorna da sola all'approvazione (P9).
   */
  fun continueAfterRequest() {
    joinClosed = true
    state.update { it.copy(join = null) }
    // Rifacendo la richiesta dalla pagina d'attesa (ON-2) i passi sul telefono sono gia' fatti: si torna all'attesa.
    if (renewing) eventFlow.tryEmit(OnboardingEvent.Finished)
  }

  /**
   * Il dispositivo era gia' approvato (la chiave c'era, ON-12a): niente attesa, ma i passi sul telefono restano da fare
   * (ruoli, preset, permessi...). Prima qui si usciva senza finire l'onboarding e si restava su "Ruoli" dopo un tasto che
   * diceva "Vai alla Libreria".
   */
  fun finishJoin() {
    joinClosed = true
    state.update { it.copy(join = null) }
    if (renewing) eventFlow.tryEmit(OnboardingEvent.Finished)
  }

  /**
   * "Rifai la richiesta" dalla pagina d'attesa (ON-2): la finestra di 15 minuti dell'admin e' scaduta e la richiesta va
   * rifatta, con una chiave nuova (`JoinFlow.start` la rinnova finche' il dispositivo e' in attesa). Si riapre la scansione
   * (o i dati a mano, per chi e' entrato senza QR); finita la richiesta, o lasciata, si torna all'attesa.
   */
  fun startRenewal() {
    if (renewing) return
    renewing = true
    joinClosed = false
    val manual = progress.value.path == OnboardingPath.JOIN_MANUAL
    state.update { it.copy(join = JoinUi(page = if (manual) JoinPage.Manual else JoinPage.Scan), removal = null) }
    if (manual) loadManualHome()
  }

  private fun backFromJoin(join: JoinUi) {
    lastRejectedText = null
    when (join.page) {
      JoinPage.Scan -> if (join.scanningFamilyState) cancelFamilyStateScan() else leaveJoin()
      JoinPage.Manual -> leaveJoin()
      JoinPage.Summary -> joinScanInvite()
      JoinPage.Password -> updateJoin { it.copy(page = JoinPage.Summary, problem = null) }
      JoinPage.Name, JoinPage.Awaiting, JoinPage.Approved -> Unit
    }
  }

  private fun leaveJoin() {
    joinClosed = true
    state.update { it.copy(join = null) }
    if (renewing) {
      eventFlow.tryEmit(OnboardingEvent.Finished)
      return
    }
    viewModelScope.launch { guard { coordinator.back() } }
  }

  // --- Recupero dell'admin e secondo admin (§2.3, E-S13) --------------------------------------------

  private fun isRecoveryPath(): Boolean =
    progress.value.path == OnboardingPath.RECOVER_ADMIN || progress.value.path == OnboardingPath.SECOND_ADMIN

  /** Dopo indirizzo, account e aree dell'admin: si cercano i kit nell'area privata. */
  private fun startRecovery(privateShare: String) {
    state.update {
      it.copy(
        recovery = RecoveryUi(
          secondAdmin = progress.value.path == OnboardingPath.SECOND_ADMIN,
          privateShare = privateShare,
        ),
        summary = SummaryUi(),
      )
    }
    searchKits(privateShare)
  }

  /** Il recupero a meta' riprende dai kit, con l'area privata salvata (ON-3). */
  private suspend fun reopenRecovery() {
    val nas = guard { coordinator.savedNas() }
    startRecovery(nas?.privateShare ?: nas?.user.orEmpty())
  }

  /**
   * Cerca i kit di recupero in `recovery/` dell'area privata (solo nomi validi). Indirizzo e utente vengono
   * dall'avanzamento salvato, non da questa pagina: ci sono anche dopo la morte del processo (ON-3). Un errore del NAS
   * non e' "nessun kit" (ON-9): NAS lento, permesso negato o VPN caduta si dicono con il loro problema e "Riprova".
   */
  fun searchKits(privateShare: String) {
    updateRecovery { it.copy(kitsLoaded = false, noKits = false, problem = null, privateShare = privateShare) }
    viewModelScope.launch {
      val nas = guard { coordinator.savedNas() }
      val user = nas?.user
      if (nas == null || user == null) {
        updateRecovery { it.copy(kitsLoaded = true, problem = OnboardingProblem.STEP_NOT_AVAILABLE) }
        return@launch
      }
      val account = NasAccount(address = nas.address, user = user, credentialSlot = RecoveryAccounts.slotFor(user), pinnedGuid = null)
      when (val result = guard { adminRecovery.findKits(account, privateShare.trim().ifBlank { user }) }) {
        is NasResult.Ok -> updateRecovery {
          it.copy(kitsLoaded = true, kits = result.value.sortedByDescending { kit -> kit.createdAt ?: 0L }, selectedKit = 0, noKits = result.value.isEmpty())
        }
        is NasResult.Err -> updateRecovery { it.copy(kitsLoaded = true, kits = emptyList(), noKits = false, problem = result.error.asOnboardingProblem()) }
        null -> updateRecovery { it.copy(kitsLoaded = true, problem = OnboardingProblem.STEP_NOT_AVAILABLE) }
      }
    }
  }

  /**
   * Senza kit ne' codice (ON-6, scelta dell'utente "via dal PC"): la guida per ripartire da zero, con il percorso esatto
   * della cartella della famiglia sul NAS. L'app non cancella niente: lo fa l'utente dal PC.
   */
  fun showStartOverGuide() {
    viewModelScope.launch {
      val path = guard { coordinator.savedNas() }?.familyFolderForWindows()
      updateRecovery { it.copy(page = RecoveryPage.StartOver, startOverReturn = it.page, startOverPath = path) }
    }
  }

  /** Cancellata la cartella dal PC: si rifa' la famiglia da capo (passo 1, "Crea la famiglia"). */
  fun startFamilyFromScratch() {
    state.update { it.copy(recovery = null) }
    choosePath(OnboardingPath.FOUND_FAMILY)
  }

  private fun updateRecovery(transform: (RecoveryUi) -> RecoveryUi) =
    state.update { s -> s.recovery?.let { s.copy(recovery = transform(it)) } ?: s }

  fun selectKit(index: Int) = updateRecovery { it.copy(selectedKit = index) }

  /** I kit sono scelti: per il recupero si decide cosa fare del vecchio admin, per il secondo admin si va al codice. */
  fun kitsContinue() = updateRecovery {
    if (it.secondAdmin) {
      oldAdminFate = OldAdminFate.KEEP_APPROVED
      it.copy(page = RecoveryPage.Code)
    } else {
      it.copy(page = RecoveryPage.Fate)
    }
  }

  /** La scelta sul vecchio admin e' dell'utente (mai di un dato letto dal NAS) e si fa prima del codice. */
  fun chooseFate(fate: OldAdminFate) {
    oldAdminFate = fate
    updateRecovery { it.copy(page = RecoveryPage.Code) }
  }

  /** Il codice digitato apre il kit? Non si tocca il NAS per sbagliare: si puo' riprovare quante volte si vuole. */
  fun submitRecoveryCodeForKit(code: CharArray, fingerprint: String = "") {
    val recovery = state.value.recovery ?: return
    val kit: RecoveryKitRef = recovery.kits.getOrNull(recovery.selectedKit) ?: return
    updateRecovery { it.copy(busy = true, wrongCode = false, problem = null) }
    viewModelScope.launch {
      val outcome = try {
        // Revisione 2, F3: senza QR di stato l'ancora e' l'impronta scritta sul foglio.
        val typed = fingerprint.takeIf { recovery.familyState == null && it.isNotBlank() }
        guard { adminRecovery.recover(kit, code, recovery.familyState, oldAdminFate ?: OldAdminFate.KEEP_APPROVED, typed) }
          ?: RecoveryOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
      } finally {
        code.fill(CLEARED)
      }
      when (outcome) {
        RecoveryOutcome.Recovered -> updateRecovery { it.copy(busy = false, page = RecoveryPage.Done) }
        RecoveryOutcome.WrongCode -> updateRecovery { it.copy(busy = false, wrongCode = true) }
        is RecoveryOutcome.Failed -> updateRecovery { it.copy(busy = false, problem = outcome.problem) }
      }
    }
  }

  fun finishRecovery() {
    state.update { it.copy(recovery = null) }
  }

  private fun backFromRecovery(recovery: RecoveryUi) {
    lastRejectedText = null
    when (recovery.page) {
      RecoveryPage.Kits -> if (recovery.scanningFamilyState) cancelFamilyStateScan() else {
        state.update { it.copy(recovery = null, account = AccountUi()) }
        viewModelScope.launch { guard { coordinator.back() } }
      }
      RecoveryPage.Fate -> updateRecovery { it.copy(page = RecoveryPage.Kits) }
      RecoveryPage.Code ->
        if (recovery.scanningFamilyState) cancelFamilyStateScan() else updateRecovery {
          it.copy(page = if (it.secondAdmin) RecoveryPage.Kits else RecoveryPage.Fate, wrongCode = false, problem = null)
        }
      RecoveryPage.Done -> Unit
      RecoveryPage.StartOver -> updateRecovery { it.copy(page = it.startOverReturn) }
    }
  }

  // --- Comune ---------------------------------------------------------------------------------------

  /** Un passo che si finisce con un [StepInput] e che non ha altro che "in corso" e "problema". */
  private fun runLocal(step: OnboardingStep, input: StepInput) {
    state.update { it.withStatus(step, submitting = true, problem = null) }
    viewModelScope.launch {
      when (val result = guardResult { coordinator.submit(input) }) {
        is StepResult.Advanced -> {
          addWarnings(result.warnings)
          state.update { it.withStatus(step, submitting = false, problem = null) }
        }
        is StepResult.Problem -> state.update { it.withStatus(step, submitting = false, problem = result.problem) }
      }
    }
  }

  private fun addWarnings(warnings: Set<OnboardingWarning>) {
    if (warnings.isNotEmpty()) state.update { it.copy(warnings = it.warnings + warnings) }
  }

  private fun setStepProblem(step: OnboardingStep, problem: OnboardingProblem?) =
    state.update { it.withStatus(step, submitting = false, problem = problem) }

  private fun OnboardingProblem.isAuthFailure(): Boolean = when (this) {
    OnboardingProblem.AUTH_FAILED,
    OnboardingProblem.ACCOUNT_LOCKED,
    OnboardingProblem.ACCOUNT_DISABLED,
    OnboardingProblem.PASSWORD_EXPIRED,
    OnboardingProblem.ACCOUNT_RESTRICTED,
    -> true
    else -> false
  }

  /** Un'eccezione inattesa di un contratto non fa cadere la pagina: diventa "non disponibile" (e il tentativo si ripete). */
  private suspend fun <T> guard(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    null
  }

  private suspend fun guardResult(block: suspend () -> StepResult): StepResult =
    guard(block) ?: StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)

  override fun onCleared() {
    closeRecoveryDisplay()
    discoveryJob?.cancel()
    foldersJob?.cancel()
    super.onCleared()
  }

  private companion object {
    const val DISCOVERY_MILLIS = 8_000L
    const val CLEARED = '\u0000'
  }
}

/** Dove il recupero trova la voce del `CredentialVault` dell'account dell'admin (convenzione da confermare con P3/P4). */
internal object RecoveryAccounts {
  fun slotFor(user: String): String = user
}

/** Imposta "in corso" e "problema" di un passo, senza toccare il resto del suo stato. */
private fun OnboardingUiState.withStatus(step: OnboardingStep, submitting: Boolean, problem: OnboardingProblem?): OnboardingUiState =
  when (step) {
    OnboardingStep.WELCOME -> copy(welcomeBusy = submitting, welcomeProblem = problem)
    OnboardingStep.NAS_ADDRESS -> copy(address = address.copy(checking = submitting, problem = problem))
    OnboardingStep.ACCOUNT -> copy(account = account.copy(submitting = submitting, problem = problem))
    OnboardingStep.AREAS -> copy(areas = areas.copy(submitting = submitting, problem = problem))
    OnboardingStep.FOLDERS -> copy(folders = folders.copy(submitting = submitting, problem = problem))
    OnboardingStep.BACKUP_SOURCES -> copy(backupSources = LocalStepUi(submitting, problem))
    OnboardingStep.ROLES -> copy(roles = LocalStepUi(submitting, problem))
    OnboardingStep.PRESET -> copy(preset = LocalStepUi(submitting, problem))
    OnboardingStep.PERMISSIONS -> copy(permissions = LocalStepUi(submitting, problem))
    OnboardingStep.BATTERY -> copy(battery = battery.copy(submitting = submitting, problem = problem))
    OnboardingStep.VPN -> copy(vpn = vpn.copy(submitting = submitting, problem = problem))
    OnboardingStep.FAMILY_KEY -> copy(key = key.copy(problem = problem))
    OnboardingStep.SECURITY_NOTICE -> copy(securityNotice = LocalStepUi(submitting, problem))
    OnboardingStep.SUMMARY -> copy(summary = SummaryUi(submitting, problem))
  }
