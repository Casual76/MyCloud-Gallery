package dev.pampa.mycloudgallery.core.family.testing

import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.FolderSurvey
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinFlow
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.RecoveryCodeDisplay
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.SavedNas
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.StopReason
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.family.contract.TrustGuard
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.contract.WritePermit
import dev.pampa.mycloudgallery.core.family.invite.InviteExpiry
import dev.pampa.mycloudgallery.core.family.invite.InviteRules
import dev.pampa.mycloudgallery.core.family.invite.isManual
import dev.pampa.mycloudgallery.core.family.onboarding.FamilyKeyStage
import dev.pampa.mycloudgallery.core.family.onboarding.FoundationRecord
import dev.pampa.mycloudgallery.core.family.onboarding.JoinRecord
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingPlans
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingRules
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.completing
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalWindow
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow

// I fake di core-family (D6): in src/main per i test di core-family (P3, P4), delle schermate (P6, P7, P8) e
// di :app (RootViewModel, screenshot dello scaffold). Mai nella release (tools/check.ps1).

/** Lo stato di configurazione pilotato dal test (o, in debug, dal pannello "Sviluppo"). */
class FakeFamilySetupRepository(initial: SetupState = SetupState.NotConfigured) : FamilySetupRepository {
  override val state: MutableStateFlow<SetupState> = MutableStateFlow(initial)
}

/**
 * Un coordinatore finto con le **regole vere** dei passi (`OnboardingRules`, le stesse di `DefaultOnboardingCoordinator`;
 * verifica funzionale, ON-3 e ON-7: prima il finto aveva regole sue e nascondeva i difetti):
 *  - un input si accetta solo al suo passo (il cambio di percorso da qualunque passo, finche' niente e' fissato);
 *  - "salta" rimanda tutti i passi del NAS dopo un "NAS non trovato" (E-O1, anche se l'esito era in [scripted]);
 *  - "riprendi" guarda i passi da completare e, a onboarding finito, quelli locali gia' fatti (ON-7);
 *  - "indietro" non fa niente a onboarding finito; il cambio di percorso riparte da capo;
 *  - "Vai alla Libreria" vuole l'essenziale fatto: la fondazione (passo 12), la richiesta d'ingresso ([joinRegistered],
 *    la segna `FakeJoinFlow` collegato) o l'admin recuperato ([adminRecovered], lo segna `FakeAdminRecovery` collegato).
 *
 * Finto e' solo cio' che tocca il NAS e il telefono: ogni input accettato completa il suo passo, salvo che il test abbia
 * messo in coda un esito in [scripted] (che viene prima di tutto e non cambia l'avanzamento). Le password ricevute si
 * azzerano subito (come deve fare quello vero) e in [inputs] resta solo il tipo di input. [progress] si puo' anche
 * scrivere direttamente dal test (per partire da un passo qualunque).
 */
class FakeOnboardingCoordinator(initial: OnboardingProgress = OnboardingProgress.START) : OnboardingCoordinator {

  override val progress: MutableStateFlow<OnboardingProgress> = MutableStateFlow(initial)

  /** Esiti da restituire, in ordine, prima del comportamento automatico. */
  val scripted: ArrayDeque<StepResult> = ArrayDeque()

  /** I tipi di input ricevuti (mai il contenuto: dentro possono esserci password). */
  val inputs: MutableList<String> = mutableListOf()

  var candidate: HomeCandidate? = null
  var discovered: List<DiscoveredNas> = emptyList()
  var areas: List<AreaCandidate> = emptyList()
  var folders: FolderProposal = FolderProposal.EMPTY
  var recoveryGroups: List<String> = List(RECOVERY_GROUPS) { "ABCD" }

  /** "Vai alla Libreria" fatto. */
  var finished: Boolean = false

  /** Si sta riprendendo un passo: finito quello si va al prossimo da completare. */
  var resuming: Boolean = false

  /** L'ultimo tentativo non ha trovato il NAS: "Continua piu' tardi" e' ammesso (E-O1). */
  var deferrable: Boolean = false

  /** La famiglia e' fondata (passo 12 fatto). */
  var founded: Boolean = false

  /** La richiesta d'ingresso e' sul NAS. */
  var joinRegistered: Boolean = false

  /** Il registro dice "approvato, admin" (recupero dell'admin o secondo admin riuscito). */
  var adminRecovered: Boolean = false

  /** Il NAS e le aree salvati: li scrivono gli input dei passi 2-4, o il test. */
  var saved: SavedNas? = null

  /** "E' la rete di casa?" confermato al passo 2 (o, senza QR, prima dell'ingresso). */
  var homeConfirmed: Boolean = false

  /** Quante volte e' stato chiesto "Configura di nuovo". */
  var startOvers: Int = 0
    private set

  /** Il Keystore non risponde o le chiavi non si tolgono (R7, R19): "Configura di nuovo" si ferma come quello vero, senza toccare niente. */
  var keysStuck: Boolean = false

  /**
   * Punto 10 delle prove sul campo: le aree comuni che hanno una famiglia (null = tutte, come prima). Come il vero, il recupero dell'admin e il
   * secondo admin su un'area senza famiglia si fermano al passo 4 con [OnboardingProblem.NO_FAMILY_IN_AREA].
   */
  var familyShares: Set<String>? = null

  override suspend fun submit(input: StepInput): StepResult {
    inputs += input::class.simpleName.orEmpty()
    when (input) {
      is StepInput.Credentials -> input.clear()
      is StepInput.RecoveryCodeTyped -> input.clear()
      else -> Unit
    }
    scripted.removeFirstOrNull()?.let { result ->
      // Come il vero: dopo "NAS non trovato" si puo' continuare piu' tardi.
      if (result is StepResult.Problem) deferrable = result.problem in DEFERRABLE
      return result
    }
    val snapshot = snapshot()
    if (!OnboardingRules.accepts(snapshot, input, adminRecovered)) return notAvailable()
    val path = snapshot.path
    val joining = path == OnboardingPath.JOIN_QR || path == OnboardingPath.JOIN_MANUAL
    return when (input) {
      is StepInput.ChoosePath -> {
        val next = OnboardingRules.choosePath(snapshot, input.path, now = 0L)
        founded = false
        joinRegistered = false
        homeConfirmed = false
        saved = null
        apply(next)
        val warnings = if (input.path == OnboardingPath.JOIN_MANUAL) setOf(OnboardingWarning.NO_MIN_REGISTRY_VERSION) else emptySet()
        StepResult.Advanced(next.progress.current, warnings)
      }
      // "E' casa?" non completa il passo 2 (lo fa l'indirizzo); con il QR la casa la dice l'invito.
      is StepInput.ConfirmHome -> when {
        path == OnboardingPath.JOIN_QR -> notAvailable()
        !input.isHome -> StepResult.Problem(OnboardingProblem.NOT_ON_WIFI)
        // Come il vero: senza Wi-Fi non c'e' una casa da confermare, e senza ULA nemmeno (E-S10).
        candidate == null -> StepResult.Problem(OnboardingProblem.NOT_ON_WIFI)
        candidate?.canBeConfirmed == false -> StepResult.Problem(OnboardingProblem.NO_ULA)
        else -> {
          homeConfirmed = true
          StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
        }
      }
      // Indirizzo e account dell'ingresso con invito li fa JoinFlow.
      is StepInput.NasAddressChosen -> if (joining) notAvailable() else {
        saved = SavedNas(input.address, user = null, commonShare = null, privateShare = null)
        complete(OnboardingStep.NAS_ADDRESS)
      }
      is StepInput.Credentials -> if (joining) notAvailable() else {
        saved = saved?.copy(user = input.user.trim())
        complete(OnboardingStep.ACCOUNT)
      }
      is StepInput.Areas -> {
        val recovering = path == OnboardingPath.RECOVER_ADMIN || path == OnboardingPath.SECOND_ADMIN
        val families = familyShares
        if (recovering && families != null && families.none { it.equals(input.commonShare.trim(), ignoreCase = true) }) {
          StepResult.Problem(OnboardingProblem.NO_FAMILY_IN_AREA)
        } else {
          saved = saved?.copy(commonShare = input.commonShare, privateShare = input.privateShare)
          complete(OnboardingStep.AREAS)
        }
      }
      is StepInput.RecoveryCodeTyped -> {
        founded = true
        complete(OnboardingStep.FAMILY_KEY)
      }
      is StepInput.Finish ->
        if (!OnboardingRules.essentialsDone(snapshot, adminRecovered)) notAvailable() else complete(OnboardingStep.SUMMARY, finish = true)
      else -> complete(OnboardingRules.stepOf(input))
    }
  }

  override suspend fun skip(step: OnboardingStep): StepResult {
    val (next, following) = OnboardingRules.skip(snapshot(), step) ?: return notAvailable()
    apply(next)
    return StepResult.Advanced(following)
  }

  override suspend fun back() {
    apply(OnboardingRules.back(snapshot(), adminRecovered))
  }

  override suspend fun resume(step: OnboardingStep): StepResult {
    val next = OnboardingRules.resume(snapshot(), step) ?: return notAvailable()
    apply(next)
    return StepResult.Advanced(step)
  }

  override suspend fun homeCandidate(): HomeCandidate? = candidate

  override fun discoverNas(): Flow<DiscoveredNas> = discovered.asFlow()

  override suspend fun proposeAreas(extra: List<String>): List<AreaCandidate> = areas

  override suspend fun proposeFolders(): FolderProposal = folders

  /** Come il vero: il codice c'e' solo al passo 12 del fondatore, prima della fondazione (dopo: zero gruppi). */
  override suspend fun recoveryCode(): RecoveryCodeDisplay {
    val p = progress.value
    val shown = (p.path == OnboardingPath.FOUND_FAMILY || p.path == null) && p.current == OnboardingStep.FAMILY_KEY && !founded
    return RecoveryCodeDisplay(if (shown) recoveryGroups.map { it.toCharArray() } else emptyList())
  }

  override suspend fun savedNas(): SavedNas? = saved

  override suspend fun recoveryPending(): Boolean = OnboardingRules.recoveryPending(snapshot(), adminRecovered)

  /** Come il vero: il fondatore, l'admin recuperato e il secondo admin tengono la chiave di famiglia (R7). */
  override suspend fun holdsFamilyKey(): Boolean =
    founded || adminRecovered || OnboardingRules.isRecoveryPath(progress.value.path)

  override suspend fun startOver(): StepResult {
    startOvers++
    if (keysStuck) return StepResult.Problem(OnboardingProblem.KEYS_NOT_REMOVED)
    finished = false
    resuming = false
    deferrable = false
    founded = false
    joinRegistered = false
    adminRecovered = false
    homeConfirmed = false
    saved = null
    progress.value = OnboardingProgress.START
    return StepResult.Advanced(OnboardingStep.WELCOME)
  }

  /**
   * La richiesta d'ingresso e' sul NAS (lo fa `FakeJoinFlow` collegato, come `DefaultJoinFlow.finish`): indirizzo e
   * account diventano fatti insieme e si passa ai passi locali.
   */
  fun markJoinRegistered() {
    joinRegistered = true
    val afterAddress = snapshot().completing(OnboardingStep.NAS_ADDRESS).first
    apply(afterAddress.completing(OnboardingStep.ACCOUNT).first)
  }

  private fun complete(step: OnboardingStep, finish: Boolean = false): StepResult {
    val (next, following) = snapshot().completing(step)
    apply(if (finish) next.copy(finished = true) else next)
    return StepResult.Advanced(following)
  }

  private fun snapshot(): OnboardingSnapshot {
    val path = progress.value.path
    return OnboardingSnapshot(
      progress = progress.value,
      finished = finished,
      resuming = resuming,
      deferrable = deferrable,
      foundation = if (founded) FoundationRecord(FamilyKeyStage.FOUNDED, FAKE_ID, FAKE_ID) else null,
      join = if (joinRegistered) JoinRecord(FAKE_ID, manual = path == OnboardingPath.JOIN_MANUAL, 0L, registered = true, requestedAt = null) else null,
    )
  }

  private fun apply(snapshot: OnboardingSnapshot) {
    finished = snapshot.finished
    resuming = snapshot.resuming
    deferrable = snapshot.deferrable
    progress.value = snapshot.progress
  }

  private fun notAvailable(): StepResult = StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)

  companion object {
    private const val RECOVERY_GROUPS = 7
    private const val FAKE_ID = "finto"
    private val DEFERRABLE = setOf(OnboardingProblem.NAS_NOT_FOUND, OnboardingProblem.NAS_SERVICE_DOWN)

    /** Gli stessi piani del coordinatore vero (recupero e secondo admin hanno le aree; il finto le saltava, 19/10). */
    fun planFor(path: OnboardingPath): List<OnboardingStep> = OnboardingPlans.planFor(path)
  }
}

/**
 * Ingresso finto: ogni chiamata restituisce il prossimo passo da [steps] (o l'ultimo, ripetuto).
 *
 * Allineato al vero (verifica funzionale, ON-1: il finto accettava qualunque invito, percorso e casa). [start] fa prima
 * i controlli di `DefaultJoinFlow.start` che non toccano la rete, con le stesse regole:
 *  - la forma dell'invito (`InviteRules`, con o senza QR: id e impronta canonici, aree, account);
 *  - con [coordinator] collegato, il percorso scelto (QR o senza QR), "e' casa?" confermato per l'invito senza QR e il
 *    rinnovo della richiesta solo per un dispositivo in attesa ([status]);
 *  - con [nasNow], la scadenza dell'invito con QR (`InviteExpiry`, ora del NAS).
 * Con [coordinator] collegato, una richiesta riuscita ([JoinStep.AwaitingApproval] o [JoinStep.Approved]) segna fatti
 * indirizzo e account come `DefaultJoinFlow.finish`.
 */
class FakeJoinFlow(vararg steps: JoinStep) : JoinFlow {
  val steps: ArrayDeque<JoinStep> = ArrayDeque(steps.toList())
  val calls: MutableList<String> = mutableListOf()
  private var last: JoinStep = JoinStep.NeedsPassword

  var coordinator: FakeOnboardingCoordinator? = null
  var nasNow: Long? = null

  /** Lo stato di questo dispositivo nel registro: si rifa' la richiesta solo "in attesa". */
  var status: () -> TrustStatus = { TrustStatus.Pending("") }

  /** L'ultimo invito accettato da [start] (per guardare come la schermata lo ha costruito). */
  var lastInvite: FamilyInvite? = null
    private set

  private fun next(call: String): JoinStep {
    calls += call
    last = steps.removeFirstOrNull() ?: last
    return last
  }

  override suspend fun start(invite: FamilyInvite): JoinStep {
    precheck(invite)?.let { problem ->
      calls += "start"
      return JoinStep.Failed(problem)
    }
    lastInvite = invite
    return next("start")
  }

  private fun precheck(invite: FamilyInvite): OnboardingProblem? {
    val manual = invite.isManual
    try {
      InviteRules.check(invite, manual)
    } catch (e: TrustViolation) {
      return OnboardingProblem.INVITE_INVALID
    }
    coordinator?.let { c ->
      val expected = if (manual) OnboardingPath.JOIN_MANUAL else OnboardingPath.JOIN_QR
      if (c.progress.value.path != expected || (c.joinRegistered && status() !is TrustStatus.Pending)) {
        return OnboardingProblem.STEP_NOT_AVAILABLE
      }
      if (manual && !c.homeConfirmed) return OnboardingProblem.NOT_AT_HOME
    }
    val now = nasNow
    if (!manual && now != null) {
      when (InviteExpiry.status(invite, now)) {
        InviteExpiry.Status.VALID -> Unit
        InviteExpiry.Status.EXPIRED -> return OnboardingProblem.INVITE_EXPIRED
        InviteExpiry.Status.TOO_FAR -> return OnboardingProblem.INVITE_INVALID
      }
    }
    return null
  }

  override suspend fun authenticate(password: CharArray): JoinStep {
    password.fill('\u0000')
    return next("authenticate")
  }

  override suspend fun register(name: String): JoinStep {
    val step = next("register")
    if (step is JoinStep.AwaitingApproval || step == JoinStep.Approved) coordinator?.markJoinRegistered()
    return step
  }
}

/**
 * Registro finto: stato, dispositivi e richieste si impostano dal test; le azioni restituiscono [nextResult].
 *
 * Allineato al vero (verifica funzionale, ON-2 e ON-4: il finto non cambiava mai stato e approvava a qualunque ora):
 *  - lo stato cambia **rileggendo** il registro: ogni [refresh] prende il prossimo di [statusOnRefresh] (revocato, admin,
 *    "vecchio"...), come `NasRegistryService` che lo ricalcola dopo la lettura;
 *  - con [nasNow] impostato, [approve] applica la stessa finestra di 15 minuti del vero (`ApprovalWindow`, dalla prima
 *    vista annotata in [pendingRequests]) e risponde [AdminActionResult.WindowExpired].
 */
class FakeRegistryService(
  status: TrustStatus = TrustStatus.NotConfigured,
  devices: List<DeviceView> = emptyList(),
  pending: List<PendingRequestView> = emptyList(),
) : RegistryService {
  override val status: MutableStateFlow<TrustStatus> = MutableStateFlow(status)
  override val devices: MutableStateFlow<List<DeviceView>> = MutableStateFlow(devices)
  override val pendingRequests: MutableStateFlow<List<PendingRequestView>> = MutableStateFlow(pending)

  var nextResult: AdminActionResult = AdminActionResult.Done
  var invite: FamilyInvite? = null
  val refreshes: MutableList<Purpose> = mutableListOf()
  val approvals: MutableList<String> = mutableListOf()
  val statusChanges: MutableList<Pair<String, DeviceStatus>> = mutableListOf()

  /** Gli stati che il registro riletto dara', uno per [refresh]; vuoto = lo stato non cambia. */
  val statusOnRefresh: ArrayDeque<TrustStatus> = ArrayDeque()

  /** L'ora del NAS per la finestra d'approvazione; null = nessun controllo della finestra (i test che non la guardano). */
  var nasNow: Long? = null

  /** Le esclusioni dell'area comune firmate (ON-8), in ordine. */
  val exclusionUpdates: MutableList<Set<String>> = mutableListOf()

  override suspend fun refresh(purpose: Purpose) {
    refreshes += purpose
    statusOnRefresh.removeFirstOrNull()?.let { status.value = it }
  }

  override suspend fun approve(requestId: String, proof: ApprovalProof): AdminActionResult {
    val now = nasNow
    val firstSeen = pendingRequests.value.firstOrNull { it.id == requestId }?.firstSeenAt
    if (now != null && firstSeen != null && !ApprovalWindow.isOpen(firstSeen, now)) return AdminActionResult.WindowExpired
    approvals += requestId
    return nextResult
  }

  override suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult {
    statusChanges += deviceId to status
    return nextResult
  }

  override suspend fun createInvite(params: InviteParams): FamilyInvite =
    invite ?: throw IllegalStateException("FakeRegistryService: imposta `invite` nel test")

  override suspend fun updateCommonExclusions(excluded: Set<String>): AdminActionResult {
    exclusionUpdates += excluded
    return nextResult
  }

  /** Gli indirizzi del NAS firmati (RE-5), in ordine. */
  val addressUpdates: MutableList<String> = mutableListOf()

  /** I prefissi ULA di casa firmati (RE-5), in ordine. */
  val homeUlaUpdates: MutableList<Set<String>> = mutableListOf()

  override suspend fun updateNasAddress(ip: String): AdminActionResult {
    addressUpdates += ip
    return nextResult
  }

  override suspend fun updateHomeUla(prefixes: Set<String>): AdminActionResult {
    homeUlaUpdates += prefixes
    return nextResult
  }
}

class FakeTrustGuard(
  var result: PreflightResult = PreflightResult.Stop(StopReason.NOT_CONFIGURED),
  var permit: WritePermit? = null,
) : TrustGuard {
  val preflights: MutableList<Purpose> = mutableListOf()

  override suspend fun preflight(purpose: Purpose): PreflightResult {
    preflights += purpose
    return result
  }

  override fun writePermit(): WritePermit? = permit
}

class FakeTrustStateStore(var snapshot: TrustSnapshot? = null) : TrustStateStore {
  override suspend fun load(): TrustSnapshot? = snapshot

  override suspend fun save(snapshot: TrustSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun clear() {
    snapshot = null
  }
}

/**
 * Un registro condiviso dell'ordine delle operazioni: i fake dell'azzeramento ci scrivono, il test verifica
 * che la chiave sparisca **prima** dei dati (§2.3, test obbligatorio di P3).
 */
class OperationLog {
  private val entries = mutableListOf<String>()

  @Synchronized
  fun record(entry: String) {
    entries += entry
  }

  @Synchronized
  fun all(): List<String> = entries.toList()
}

/** Azzeramento finto: percorre le fasi in ordine registrandole in [log]; si puo' fermare a [stopAfter]. */
class RecordingSelfWipe(private val log: OperationLog = OperationLog(), var stopAfter: WipePhase? = null) : SelfWipe {
  override val progress: MutableStateFlow<WipePhase?> = MutableStateFlow(null)

  override suspend fun run(reason: WipeReason): WipePhase {
    val start = progress.value?.let { WipePhase.entries.indexOf(it) + 1 } ?: 0
    for (phase in WipePhase.entries.drop(start)) {
      log.record("wipe:${phase.name}")
      progress.value = phase
      if (phase == stopAfter) return phase
    }
    return WipePhase.DONE
  }

  override suspend fun resumeIfInterrupted() {
    val current = progress.value ?: return
    if (current != WipePhase.DONE) run(WipeReason.MARKED_LOST)
  }
}

class RecordingLocalDataEraser(private val log: OperationLog = OperationLog(), var result: Boolean = true) : LocalDataEraser {
  override suspend fun eraseAll(): Boolean {
    log.record("eraser:eraseAll")
    return result
  }
}

/**
 * Recupero finto: i kit e l'esito li sceglie il test ([kits] puo' anche essere un errore del NAS).
 *
 * Allineato al vero (verifica funzionale, ON-9 e il difetto del 19/10: il finto ignorava account, area, errori e il
 * profilo del dispositivo):
 *  - [lastSearch] ricorda con quale account (indirizzo e utente) e in quale area privata si e' cercato;
 *  - [recover] vuole il profilo del dispositivo come `KitAdminRecovery` (account e area comune, cioe' le aree fatte):
 *    senza ([profileReady] falso) risponde "passo non disponibile";
 *  - un recupero riuscito chiama [onRecovered] (il coordinatore finto collegato diventa "approvato, admin").
 */
class FakeAdminRecovery(
  var kits: NasResult<List<RecoveryKitRef>> = NasResult.Ok(emptyList()),
  var outcome: RecoveryOutcome = RecoveryOutcome.WrongCode,
) : AdminRecovery {
  /** Se l'ultima ricerca ha ricevuto una password (mai la password stessa). */
  var lastSearchHadPassword: Boolean = false

  /** L'account e l'area privata dell'ultima ricerca. */
  var lastSearch: Pair<NasAccount, String>? = null
    private set

  /** L'impronta digitata dal foglio all'ultimo recupero (revisione 2, F3). */
  var lastTypedFingerprint: String? = null

  /** Il profilo del dispositivo c'e' (account e aree configurati). */
  var profileReady: () -> Boolean = { true }

  var onRecovered: () -> Unit = {}

  override suspend fun findKits(account: NasAccount, privateShare: String, password: CharArray?): NasResult<List<RecoveryKitRef>> {
    lastSearchHadPassword = password != null
    lastSearch = account to privateShare
    password?.fill('\u0000')
    return kits
  }

  override suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
    typedFingerprint: String?,
  ): RecoveryOutcome {
    lastTypedFingerprint = typedFingerprint
    code.fill('\u0000')
    if (!profileReady()) return RecoveryOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (outcome == RecoveryOutcome.Recovered) onRecovered()
    return outcome
  }
}

class FakeFamilyReset(
  var outcome: ResetOutcome = ResetOutcome.Done("famiglia-nuova"),
  /** Il codice del kit nuovo dopo un reset riuscito, a gruppi separati da `-`. */
  var newCode: String = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345",
) : FamilyReset {
  var attempts: Int = 0
    private set

  private var pending: String? = null

  override suspend fun reset(confirmedTwice: Boolean): ResetOutcome {
    attempts++
    val result = if (confirmedTwice) outcome else ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (result is ResetOutcome.Done) pending = newCode
    return result
  }

  override fun recoveryCodeForDisplay(): RecoveryCodeDisplay? =
    pending?.let { code -> RecoveryCodeDisplay(code.split('-').map { it.toCharArray() }) }

  override fun confirmTypedCode(typed: CharArray): Boolean {
    val ok = pending != null && String(typed).replace("-", "").replace(" ", "").equals(pending!!.replace("-", ""), ignoreCase = true)
    typed.fill('\u0000')
    if (ok) pending = null
    return ok
  }
}

class FakeLostReporting(var result: AdminActionResult = AdminActionResult.Done) : LostReporting {
  val reported: MutableList<String> = mutableListOf()

  override suspend fun reportOwnDeviceLost(targetDeviceId: String): AdminActionResult {
    reported += targetDeviceId
    return result
  }

  override suspend fun convertReports(): AdminActionResult = result
}

class FakeTrustSyncScheduler : TrustSyncScheduler {
  val requests: MutableList<SyncReason> = mutableListOf()
  var inviteWindowUntil: Long? = null

  override fun requestSoon(reason: SyncReason) {
    requests += reason
  }

  override fun setInviteWindow(untilNasMs: Long) {
    inviteWindowUntil = untilNasMs
  }
}

class FakeFolderSurvey(var proposal: FolderProposal = FolderProposal.EMPTY) : FolderSurvey {
  override suspend fun propose(share: String): FolderProposal = proposal
}

/** Codec finto: riconosce solo i testi registrati in [known]; [encode] restituisce la chiave registrata. */
class FakeQrPayloadCodec<T>(val known: MutableMap<String, T> = mutableMapOf()) : QrPayloadCodec<T> {
  override fun encode(value: T): String =
    known.entries.firstOrNull { it.value == value }?.key ?: throw IllegalArgumentException("valore non registrato nel fake")

  override fun decode(text: String): Verdict<T> =
    known[text]?.let { Verdict.Accepted(it) } ?: Verdict.Rejected(RejectReason.MALFORMED)
}
