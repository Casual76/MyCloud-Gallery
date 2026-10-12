package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.FolderSurvey
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.RecoveryCodeDisplay
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.setup.NasCredentialSlots
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NasSchedule
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La logica dei 14 passi dell'onboarding (P4, `docs/fase2-progetto.md` §2.1), senza schermate: le schermate di
 * `feature-onboarding` consegnano uno [StepInput] a [submit] e mostrano [progress].
 *
 * **Persistente e riprendibile (E-O9)**: ad ogni passo l'avanzamento e le scelte si salvano in
 * [OnboardingStateStore], e un coordinatore nuovo (processo morto, cambio di app ai passi 10 e 11) riparte da li'.
 * Niente segreti nello stato: la password si azzera appena usata, il codice di recupero sta solo in memoria e sul
 * foglio dell'utente.
 *
 * **Un passo alla volta**: un input si accetta solo se e' del passo corrente ([OnboardingProgress.current]); altrimenti
 * [OnboardingProblem.STEP_NOT_AVAILABLE] (e le password si azzerano comunque).
 *
 * **Idempotente sul NAS**: ogni scrittura (kit, fondazione) puo' essere ripetuta (vedi [FamilyFoundation]).
 *
 * **Un solo tentativo di password (E-R8)**: l'errore d'autenticazione non si ripete da solo, e la stessa coppia
 * utente/password gia' rifiutata non arriva due volte al NAS (un account che si blocca per colpa di un doppio tocco
 * e' peggio di un messaggio d'errore).
 *
 * I percorsi dell'ingresso con invito (QR e manuale) usano questo coordinatore per i passi locali e
 * [dev.pampa.mycloudgallery.core.family.invite.DefaultJoinFlow] per indirizzo e account (passi 2 e 3). Il
 * recupero dell'admin e il secondo admin usano passi 2-4 come la creazione, poi `AdminRecovery` (P3).
 *
 * @param scope dove gira l'osservazione dello store che alimenta [progress] (la vita del processo).
 */
class DefaultOnboardingCoordinator(
  private val store: OnboardingStateStore,
  private val ports: OnboardingPorts,
  private val survey: FolderSurvey,
  scope: CoroutineScope,
) : OnboardingCoordinator {

  private val lock = Mutex()
  private val foundation = FamilyFoundation(ports)
  private val mutableProgress = MutableStateFlow(OnboardingProgress.START)
  override val progress: StateFlow<OnboardingProgress> = mutableProgress

  // Stato transitorio (mai salvato): sparisce con il processo, e va bene cosi'.
  private val rejected = RejectedCredentials()
  private var pending: PendingFoundation? = null
  private var foundationBlock: OnboardingProblem? = null

  @Volatile
  private var areaProposal: List<AreaCandidate> = emptyList()

  init {
    scope.launch {
      store.state.collect { if (it is StoreState.Ready) mutableProgress.value = it.snapshot.progress }
    }
  }

  // --- La porta d'ingresso ---------------------------------------------------------------------------

  override suspend fun submit(input: StepInput): StepResult =
    try {
      lock.withLock { dispatch(input) }
    } finally {
      when (input) {
        is StepInput.Credentials -> input.clear()
        is StepInput.RecoveryCodeTyped -> input.clear()
        else -> Unit
      }
    }

  private suspend fun dispatch(input: StepInput): StepResult {
    val snapshot = ready() ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val step = stepOf(input)
    val p = snapshot.progress
    if (p.current != step || step !in p.plan) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    // Fondata la famiglia (o inviata la richiesta), indirizzo, account e aree stanno nella configurazione firmata: non si rifanno.
    val settled = snapshot.foundation?.stage == FamilyKeyStage.FOUNDED || snapshot.join?.registered == true
    if (settled && step in SETTLED_STEPS) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    return when (input) {
      is StepInput.ChoosePath -> choosePath(snapshot, input.path)
      is StepInput.ConfirmHome -> confirmHome(snapshot, input.isHome)
      is StepInput.NasAddressChosen -> chooseAddress(snapshot, input.address)
      is StepInput.Credentials -> credentials(snapshot, input)
      is StepInput.Areas -> areas(snapshot, input)
      is StepInput.Folders -> folders(input)
      is StepInput.BackupSources -> backupSources(input)
      is StepInput.Roles -> roles(input)
      is StepInput.PresetChoice -> preset(input)
      is StepInput.PermissionsReviewed -> permissions()
      is StepInput.BatteryReviewed -> battery()
      is StepInput.VpnSetup -> vpn(input)
      is StepInput.RecoveryCodeTyped -> familyKey(snapshot, input)
      is StepInput.SecurityNotice -> securityNotice(input)
      is StepInput.Finish -> finish(snapshot)
    }
  }

  private fun stepOf(input: StepInput): OnboardingStep = when (input) {
    is StepInput.ChoosePath -> OnboardingStep.WELCOME
    is StepInput.ConfirmHome, is StepInput.NasAddressChosen -> OnboardingStep.NAS_ADDRESS
    is StepInput.Credentials -> OnboardingStep.ACCOUNT
    is StepInput.Areas -> OnboardingStep.AREAS
    is StepInput.Folders -> OnboardingStep.FOLDERS
    is StepInput.BackupSources -> OnboardingStep.BACKUP_SOURCES
    is StepInput.Roles -> OnboardingStep.ROLES
    is StepInput.PresetChoice -> OnboardingStep.PRESET
    is StepInput.PermissionsReviewed -> OnboardingStep.PERMISSIONS
    is StepInput.BatteryReviewed -> OnboardingStep.BATTERY
    is StepInput.VpnSetup -> OnboardingStep.VPN
    is StepInput.RecoveryCodeTyped -> OnboardingStep.FAMILY_KEY
    is StepInput.SecurityNotice -> OnboardingStep.SECURITY_NOTICE
    is StepInput.Finish -> OnboardingStep.SUMMARY
  }

  // --- Navigazione -----------------------------------------------------------------------------------

  override suspend fun skip(step: OnboardingStep): StepResult = lock.withLock {
    val snapshot = ready() ?: return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val p = snapshot.progress
    if (step != p.current || step !in p.plan) return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    // E-O1, "Continua piu' tardi": se il NAS non si trova, anche i passi che ne hanno bisogno aspettano.
    val later = step == OnboardingStep.NAS_ADDRESS && snapshot.deferrable
    if (!step.skippable && !later) return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val skipping = if (later) OnboardingPlans.NAS_DEPENDENT.filter { it in p.plan && it !in p.completed }.toSet() else setOf(step)
    var next: OnboardingStep? = null
    store.update { s ->
      val skipped = s.progress.skipped + skipping
      next = OnboardingPlans.nextAfter(s.progress.plan, s.progress.completed, skipped, step, s.resuming)
      s.copy(
        progress = s.progress.copy(skipped = skipped, current = next ?: s.progress.plan.last()),
        resuming = s.resuming && next != null,
        deferrable = false,
      )
    } ?: return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    StepResult.Advanced(next)
  }

  override suspend fun back() {
    lock.withLock {
      store.update { s ->
        val p = s.progress
        val index = p.plan.indexOf(p.current)
        if (s.finished || index <= 0) s else s.copy(progress = p.copy(current = p.plan[index - 1]), resuming = false)
      }
    }
  }

  override suspend fun resume(step: OnboardingStep): StepResult = lock.withLock {
    val snapshot = ready() ?: return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (step !in snapshot.progress.pending) return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    store.update { it.copy(progress = it.progress.copy(current = step), resuming = true) }
      ?: return@withLock problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    StepResult.Advanced(step)
  }

  // --- Passo 1 -----------------------------------------------------------------------------------------

  private suspend fun choosePath(snapshot: OnboardingSnapshot, path: OnboardingPath): StepResult {
    // Una volta fondata la famiglia (o inviata la richiesta) il percorso non si cambia piu'. Prima si': per esempio
    // dopo "Qui c'e' gia' una famiglia" (12(e)) si puo' scegliere di entrare come familiare.
    if (snapshot.foundation?.stage == FamilyKeyStage.FOUNDED || snapshot.join?.registered == true) {
      return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    }
    if (snapshot.foundation != null || pending != null) {
      // Un kit gia' scritto ma una famiglia mai fondata: la chiave non serve piu', non deve restare nella cassaforte.
      pending = null
      runCatching { ports.familyKeys.destroy() }
    }
    val plan = OnboardingPlans.planFor(path)
    val now = ports.deviceNow()
    store.update { s ->
      OnboardingSnapshot.EMPTY.copy(
        progress = OnboardingProgress(
          path = path,
          plan = plan,
          current = plan[1],
          completed = setOf(OnboardingStep.WELCOME),
          skipped = emptySet(),
          startedAt = s.progress.startedAt ?: now,
        ),
      )
    } ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val warnings = if (path == OnboardingPath.JOIN_MANUAL) setOf(OnboardingWarning.NO_MIN_REGISTRY_VERSION) else emptySet()
    return StepResult.Advanced(plan[1], warnings)
  }

  // --- Passo 2: indirizzo del NAS --------------------------------------------------------------------

  override suspend fun homeCandidate(): HomeCandidate? = ports.homeCapture.candidateFromCurrentWifi()

  override fun discoverNas(): Flow<DiscoveredNas> = ports.discovery.discover(DISCOVERY_TIMEOUT)

  /**
   * Un nome digitato ("MyCloudEX2Ultra.fritz.box") risolto con il DNS del Wi-Fi di casa, mai della rete
   * predefinita (che potrebbe essere la VPN o i dati mobili). Non e' nel contratto di [OnboardingCoordinator]:
   * lo usa chi conosce questa classe. L'indirizzo che esce e' un IP letterale, come tutti gli indirizzi (E-R7).
   */
  suspend fun resolveName(name: String): NasAddress? {
    val resolved = ports.discovery.resolveOnWifi(name) ?: return null
    return NasAddress.parseOrNull(resolved.hostAddress.orEmpty().substringBefore('%'), name.takeIf { it.length <= MAX_NAME_CHARS })
  }

  private suspend fun confirmHome(snapshot: OnboardingSnapshot, isHome: Boolean): StepResult {
    if (snapshot.path == OnboardingPath.JOIN_QR) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (!isHome) return problem(OnboardingProblem.NOT_ON_WIFI)
    environmentProblem()?.let { return problem(it) }
    val candidate = ports.homeCapture.candidateFromCurrentWifi() ?: return problem(OnboardingProblem.NOT_ON_WIFI)
    // Senza ULA "casa" non si puo' confermare: un FRITZ!Box vale l'altro per gateway e dominio (E-S10).
    if (!candidate.canBeConfirmed) return problem(OnboardingProblem.NO_ULA)
    store.update { it.copy(homeConfirmed = candidate) } ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    return StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
  }

  private suspend fun chooseAddress(snapshot: OnboardingSnapshot, address: NasAddress): StepResult {
    if (snapshot.path == OnboardingPath.JOIN_QR || snapshot.path == OnboardingPath.JOIN_MANUAL) {
      return problem(OnboardingProblem.STEP_NOT_AVAILABLE) // lo fa JoinFlow
    }
    val candidate = snapshot.homeConfirmed ?: return problem(OnboardingProblem.NOT_ON_WIFI)
    environmentProblem()?.let { return problem(it) }
    val stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
    val report = ports.tester.run(TestTarget(address, user = null, share = null, expectedGuid = null, candidate = candidate), null, stages)
    reportProblem(report, stages)?.let { return failAddress(it) }
    val facts = report.negotiation ?: return failAddress(OnboardingProblem.NAS_NOT_FOUND)
    // Solo SMB 3.1.1 con la cifratura offerta (C3), e un GUID da memorizzare prima di mandare una password (D3).
    if (facts.dialect != SmbDialect.SMB_3_1_1 || !facts.encryptionOffered) return failAddress(OnboardingProblem.NAS_INSECURE)
    if (facts.serverGuid.isBlank()) return failAddress(OnboardingProblem.IDENTITY_MISMATCH)

    // Il Wi-Fi diventa "casa" solo ora: l'utente l'ha confermato e il NAS ha risposto con il suo GUID.
    ports.homeFingerprints.save(HomeFingerprint(candidate.ulaPrefixes, candidate.gatewayV4, candidate.dnsDomain, ports.deviceNow()))
    val nas = NasFacts.of(address, facts.serverGuid)
    return complete(OnboardingStep.NAS_ADDRESS) { s ->
      val sameNas = s.nas?.let { it.ip == nas.ip && it.port == nas.port } == true
      s.copy(nas = nas, nasUser = s.nasUser.takeIf { sameNas }, commonShare = s.commonShare.takeIf { sameNas }, privateShare = s.privateShare.takeIf { sameNas })
    }
  }

  /** Il Wi-Fi giusto, senza VPN, e il permesso di rete locale: si controllano PRIMA di dire "NAS spento". */
  private fun environmentProblem(): OnboardingProblem? = when {
    ports.network.snapshot.value.vpn != VpnObservation.NONE -> OnboardingProblem.VPN_ACTIVE
    ports.localNetwork.status() == LocalNetworkAccess.DENIED -> OnboardingProblem.LOCAL_NETWORK_DENIED
    else -> null
  }

  private suspend fun failAddress(problem: OnboardingProblem): StepResult {
    // E-O1: il NAS non si trova. Il messaggio dice di controllare l'indirizzo e offre "Continua piu' tardi".
    val deferrable = NasProblems.isDeferrable(problem)
    store.update { it.copy(deferrable = deferrable) }
    return problem(problem)
  }

  // --- Passo 3: account e password ---------------------------------------------------------------------

  private suspend fun credentials(snapshot: OnboardingSnapshot, input: StepInput.Credentials): StepResult {
    val nas = snapshot.nas ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (snapshot.path == OnboardingPath.JOIN_QR || snapshot.path == OnboardingPath.JOIN_MANUAL) {
      return problem(OnboardingProblem.STEP_NOT_AVAILABLE) // lo fa JoinFlow
    }
    val user = input.user.trim()
    // L'account admin del NAS non si usa mai nell'app (docs/sicurezza.md §8): nemmeno se l'utente insiste.
    if (user.equals("admin", ignoreCase = true)) return problem(OnboardingProblem.ADMIN_ACCOUNT_REFUSED)
    if (input.password.isEmpty() || !validAccount(user)) return problem(OnboardingProblem.AUTH_FAILED)

    // La stessa coppia gia' rifiutata non torna al NAS: si aspetta una password diversa (E-R8, E-S12).
    if (rejected.isRepeat(user, input.password)) return problem(OnboardingProblem.AUTH_FAILED)

    val credentials = try {
      NasCredentials(user, input.password)
    } catch (e: IllegalArgumentException) {
      return problem(OnboardingProblem.AUTH_FAILED)
    }
    credentials.use { c ->
      // L'utente ha scritto una password nuova: l'interruttore aperto da un tentativo precedente si richiude.
      if (ports.breaker.state.value is BreakerState.Open) ports.breaker.onNewPasswordSaved()
      val stages = ACCOUNT_STAGES
      // Il Wi-Fi del passo 2 serve finche' "casa" non e' stata registrata; dopo, il percorso lo decide il cancello di rete.
      val candidate = snapshot.homeConfirmed.takeIf { ports.homeFingerprints.fingerprint.value == null }
      val target = TestTarget(nas.address(), user = user, share = null, expectedGuid = nas.guid, candidate = candidate)
      val report = ports.tester.run(target, c, stages)
      // Autenticazione e controllo di sicurezza devono essere PASSATI (un "saltato" per l'interruttore non basta).
      val failure = reportProblem(report, stages, mustPass = setOf(Stage.AUTHENTICATE, Stage.SECURITY_CHECK))
      if (failure != null) {
        val error = report.firstFailure?.error
        if (error is NasError.Auth || error is NasError.AuthBlocked) rejected.remember(user, input.password)
        return problem(failure)
      }
      // Le credenziali si salvano solo ora, dopo il successo.
      when (ports.credentials.save(NasCredentialSlots.PRIMARY, c)) {
        is SaveResult.Saved -> Unit
        SaveResult.HardwareKeyUnavailable, SaveResult.Unavailable -> return problem(OnboardingProblem.CREDENTIALS_NOT_SAVED)
      }
    }
    rejected.clear()
    ports.accounts.save(NasAccount(nas.address(), user, NasCredentialSlots.PRIMARY, nas.guid))
    ports.breaker.onNewPasswordSaved()
    val warnings = if (input.password.size < MIN_PASSWORD_CHARS) setOf(OnboardingWarning.SHORT_PASSWORD) else emptySet()
    return complete(OnboardingStep.ACCOUNT, warnings) { it.copy(nasUser = user) }
  }

  private fun validAccount(user: String): Boolean =
    try {
      TextRules.nasAccount(user)
      true
    } catch (e: TrustViolation) {
      false
    }

  // --- Passo 4: aree ------------------------------------------------------------------------------------

  override suspend fun proposeAreas(extra: List<String>): List<AreaCandidate> {
    val snapshot = ready() ?: return emptyList()
    val user = snapshot.nasUser ?: return emptyList()
    val seen = HashSet<String>()
    val names = (listOf(user, PUBLIC_SHARE, COMMON_DEFAULT_SHARE) + extra)
      .filter { validShare(it) != null && seen.add(it.lowercase()) }
    val proposal = names.map { share ->
      val access = ports.shareProbe.probe(share)
      val usable = access.okOrNull().let { it == ShareAccess.READ_ONLY || it == ShareAccess.READ_WRITE }
      AreaCandidate(
        share = share,
        access = access,
        isPublic = if (share.equals(PUBLIC_SHARE, ignoreCase = true)) true else null,
        hasFamily = usable && hasFamily(share),
      )
    }
    areaProposal = proposal
    return proposal
  }

  /** Nella share c'e' gia' `family/registry/…1`? Basta sapere se il file esiste: si legge un byte, mai di piu'. */
  private suspend fun hasFamily(share: String): Boolean =
    when (val read = ports.sessions.onShare(share) { fs -> fs.read(NasLayout.registryFile(1), 1) }) {
      is NasResult.Ok -> read.value !is NasRead.NotFound
      is NasResult.Err -> false
    }

  private suspend fun areas(snapshot: OnboardingSnapshot, input: StepInput.Areas): StepResult {
    val user = snapshot.nasUser ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val common = validShare(input.commonShare) ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val private = validShare(input.privateShare) ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    // Il kit di recupero sta nell'area privata e il registro in quella comune: la stessa share per tutti e due li mescola.
    if (common.equals(private, ignoreCase = true)) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)

    val commonAccess = accessOf(ports.shareProbe.probe(common)) ?: return problem(OnboardingProblem.NAS_NOT_FOUND)
    val privateAccess = accessOf(ports.shareProbe.probe(private)) ?: return problem(OnboardingProblem.NAS_NOT_FOUND)
    val missing = setOf(ShareAccess.MISSING, ShareAccess.DENIED)
    // E-O2: nessuna delle due si apre. E-O3: l'area comune manca o non si scrive (l'app non crea share).
    if (commonAccess in missing && privateAccess in missing) return problem(OnboardingProblem.NO_ACCESSIBLE_SHARE)
    if (commonAccess != ShareAccess.READ_WRITE) return problem(OnboardingProblem.COMMON_AREA_NOT_WRITABLE)
    if (privateAccess in missing) return problem(OnboardingProblem.NO_ACCESSIBLE_SHARE)
    if (privateAccess == ShareAccess.READ_ONLY) return problem(OnboardingProblem.NAS_WRITE_FAILED)

    // "Qui c'e' gia' una famiglia": chi la crea non la sovrascrive (si recupera l'admin o si entra come familiare).
    if (snapshot.path == OnboardingPath.FOUND_FAMILY && hasFamily(common)) return problem(OnboardingProblem.FAMILY_ALREADY_EXISTS)

    val warnings = HashSet<OnboardingWarning>()
    if (common.equals(PUBLIC_SHARE, ignoreCase = true) || private.equals(PUBLIC_SHARE, ignoreCase = true)) {
      warnings += OnboardingWarning.SHARE_IS_PUBLIC
    }
    val chosen = setOf(user, common, private).map { it.lowercase() }
    val others = areaProposal.filter { candidate ->
      candidate.share.lowercase() !in chosen && candidate.isPublic != true &&
        candidate.access.okOrNull().let { it == ShareAccess.READ_ONLY || it == ShareAccess.READ_WRITE }
    }
    if (others.isNotEmpty()) warnings += OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES
    return complete(OnboardingStep.AREAS, warnings) { it.copy(commonShare = common, privateShare = private) }
  }

  private fun accessOf(result: NasResult<ShareAccess>): ShareAccess? = result.okOrNull()

  private fun validShare(name: String): String? =
    try {
      ShareNames.check(name)
    } catch (e: TrustViolation) {
      null
    }

  // --- Passo 5: cartelle -------------------------------------------------------------------------------

  /** Il sondaggio non tiene il blocco dei passi: si puo' annullare (chiudendo la schermata) o saltare mentre gira. */
  override suspend fun proposeFolders(): FolderProposal {
    val common = ready()?.commonShare ?: return FolderProposal.EMPTY
    return survey.propose(common)
  }

  private suspend fun folders(input: StepInput.Folders): StepResult {
    if (input.excluded.size > TrustLimits.MAX_COMMON_EXCLUSIONS) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val excluded = try {
      // Come nella configurazione firmata: percorsi di media validi, non piu' lunghi del tetto.
      input.excluded.map { SharePath.parseMediaPath(it).value }.toSet().onEach {
        if (it.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_EXCLUSION_UTF8_BYTES) {
          throw TrustViolation(RejectReason.TOO_LARGE, "esclusione troppo lunga")
        }
      }
    } catch (e: TrustViolation) {
      return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    }
    return complete(OnboardingStep.FOLDERS) { it.copy(excluded = excluded) }
  }

  // --- Passi 6-11: locali --------------------------------------------------------------------------------

  private suspend fun backupSources(input: StepInput.BackupSources): StepResult =
    complete(OnboardingStep.BACKUP_SOURCES, applyChoices = true) {
      it.copy(choices = it.choices.copy(camera = input.camera, screenshots = input.screenshots))
    }

  private suspend fun roles(input: StepInput.Roles): StepResult =
    complete(OnboardingStep.ROLES, applyChoices = true) {
      it.copy(choices = it.choices.copy(indexer = input.indexer, preparer = input.preparer))
    }

  /**
   * Preset e qualita': vanno in `RuleSettingsStore`. La fascia notte deriva dall'orario del NAS, che oggi e'
   * "acceso sempre" (la pianificazione e' disattivata): 23-07. Le anteprime ad alta qualita' sono un'attivita'
   * a parte (`HQ_PREVIEWS`): si spengono se la qualita' scelta non e' "Alta".
   */
  private suspend fun preset(input: StepInput.PresetChoice): StepResult {
    val night = NightWindows.derive(NasSchedule(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).window
    ports.rules.update { settings ->
      val disabled = if (input.quality == PreviewQuality.HIGH) {
        settings.disabledActivities - BackgroundActivity.HQ_PREVIEWS
      } else {
        settings.disabledActivities + BackgroundActivity.HQ_PREVIEWS
      }
      settings.copy(preset = input.preset, nightWindow = night, disabledActivities = disabled)
    }
    return complete(OnboardingStep.PRESET) { it.copy(choices = it.choices.copy(preset = input.preset, quality = input.quality)) }
  }

  /**
   * I permessi non bloccano mai (E-O4): l'esito si rilegge da Android dopo i dialoghi. Se uno di quelli che
   * contano (notifiche, foto, video) risulta negato il passo prosegue ma resta in "Da completare", con la
   * Diagnostica che spiega cosa non funzionera'. Si avvisa anche del blocco schermo che manca (E-S3).
   */
  private suspend fun permissions(): StepResult {
    val denied = ports.health.read().permissions.any { (permission, grant) ->
      permission in REQUIRED_PERMISSIONS && grant == PermissionGrant.DENIED
    }
    val warnings = if (ports.screenLock.status().needsWarning) setOf(OnboardingWarning.NO_SCREEN_LOCK) else emptySet()
    return complete(OnboardingStep.PERMISSIONS, warnings, keepPending = denied)
  }

  /**
   * La batteria si verifica (E-P2, E-P15): se Android dice ancora che l'app e' ottimizzata, il passo non conta
   * come fatto e resta in "Da completare". Se Android non lo dice (`null`) non si indovina: vale come fatto.
   */
  private suspend fun battery(): StepResult {
    val verified = ports.health.read().ignoringBatteryOpt != false
    return complete(OnboardingStep.BATTERY, keepPending = !verified)
  }

  private suspend fun vpn(input: StepInput.VpnSetup): StepResult {
    val tunnel = input.tunnelName?.trim()?.takeIf { it.isNotEmpty() }
    if (input.remoteControl && (tunnel == null || !TUNNEL_NAME.matches(tunnel))) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    return complete(OnboardingStep.VPN, applyChoices = true) {
      it.copy(choices = it.choices.copy(remoteControl = input.remoteControl, tunnelName = if (input.remoteControl) tunnel else null))
    }
  }

  private suspend fun securityNotice(input: StepInput.SecurityNotice): StepResult =
    complete(OnboardingStep.SECURITY_NOTICE, applyChoices = true) {
      it.copy(choices = it.choices.copy(externalBackupDone = input.externalBackupDone, routerChecklistDone = input.routerChecklistDone))
    }

  // --- Passo 12: chiave di famiglia e kit ------------------------------------------------------------------

  /**
   * (a) e (b). La lista dei sette gruppi del codice, da mostrare **una volta** con `FLAG_SECURE`; i gruppi si
   * azzerano con `close()`. Richiamarla finche' il codice non e' stato riscritto da' lo stesso codice (si puo'
   * rileggere); dopo un riavvio del processo ne nasce uno nuovo, con una chiave nuova. Se il kit e' gia'
   * verificato (o la famiglia gia' fondata) restituisce **zero gruppi**: non c'e' piu' niente da mostrare e si
   * riprende da (e) con un [StepInput.RecoveryCodeTyped] qualunque.
   */
  override suspend fun recoveryCode(): RecoveryCodeDisplay = lock.withLock {
    val empty = RecoveryCodeDisplay(emptyList())
    val snapshot = ready() ?: return@withLock empty
    if (!foundationReady(snapshot) || snapshot.foundation != null) return@withLock empty
    val current = pending ?: when (val start = foundation.begin()) {
      is FoundationStart.Started -> start.pending.also { pending = it }
      is FoundationStart.Blocked -> {
        foundationBlock = start.problem
        return@withLock empty
      }
    }
    foundationBlock = null
    val code = current.code ?: return@withLock empty
    RecoveryCodeDisplay(code.toDisplayString().split('-').map { it.toCharArray() })
  }

  private fun foundationReady(snapshot: OnboardingSnapshot): Boolean =
    snapshot.path == OnboardingPath.FOUND_FAMILY && snapshot.progress.current == OnboardingStep.FAMILY_KEY &&
      snapshot.nas != null && snapshot.nasUser != null && snapshot.commonShare != null && snapshot.privateShare != null

  private suspend fun familyKey(snapshot: OnboardingSnapshot, input: StepInput.RecoveryCodeTyped): StepResult {
    if (!foundationReady(snapshot)) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    // Gia' fondata (riletto dopo un "indietro", o un tocco doppio): niente da rifare.
    if (snapshot.foundation?.stage == FamilyKeyStage.FOUNDED) return complete(OnboardingStep.FAMILY_KEY)
    foundationBlock?.let { return problem(it) }
    val current = pending ?: resumeFoundation(snapshot) ?: return problem(OnboardingProblem.RECOVERY_CODE_MISMATCH)
    val result = foundation.run(snapshot, current, input.code) {
      store.update {
        it.copy(foundation = FoundationRecord(FamilyKeyStage.KIT_VERIFIED, current.familyId, current.familyKey.publicKey.toText()))
      }
    }
    return when (result) {
      is FoundationResult.Failed -> problem(result.problem)
      is FoundationResult.Founded -> {
        pending = null
        complete(OnboardingStep.FAMILY_KEY) {
          it.copy(foundation = FoundationRecord(FamilyKeyStage.FOUNDED, current.familyId, current.familyKey.publicKey.toText()))
        }
      }
    }
  }

  /** Dopo un riavvio fra (d) ed (e): la chiave si riprende dalla cassaforte, il codice non serve piu'. */
  private fun resumeFoundation(snapshot: OnboardingSnapshot): PendingFoundation? {
    val record = snapshot.foundation?.takeIf { it.stage == FamilyKeyStage.KIT_VERIFIED } ?: return null
    val key = try {
      ports.familyKeys.load(EcPublicKey.fromText(record.keyPublic))
    } catch (e: TrustViolation) {
      null
    } catch (e: RuntimeException) {
      null
    } ?: return null
    return PendingFoundation(record.familyId, key, code = null, kitVerified = true).also { pending = it }
  }

  // --- Passo 14 -----------------------------------------------------------------------------------------

  private suspend fun finish(snapshot: OnboardingSnapshot): StepResult {
    if (!essentialsDone(snapshot)) return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val now = ports.deviceNow()
    val result = complete(OnboardingStep.SUMMARY, applyChoices = true) { it.copy(finished = true, finishedAt = now) }
    val join = snapshot.join
    val manual = join != null && join.manual && join.minRegistryVersion == 0L
    return if (result is StepResult.Advanced && manual) result.copy(warnings = result.warnings + OnboardingWarning.NO_MIN_REGISTRY_VERSION) else result
  }

  /** Cio' che deve esserci per poter dire "fatto": la famiglia fondata, la richiesta inviata o l'admin recuperato. */
  private fun essentialsDone(snapshot: OnboardingSnapshot): Boolean = when (snapshot.path) {
    OnboardingPath.FOUND_FAMILY -> snapshot.foundation?.stage == FamilyKeyStage.FOUNDED
    OnboardingPath.JOIN_QR, OnboardingPath.JOIN_MANUAL -> snapshot.join?.registered == true
    OnboardingPath.RECOVER_ADMIN, OnboardingPath.SECOND_ADMIN -> ports.registry.status.value == TrustStatus.Approved(admin = true)
    null -> false
  }

  // --- Comuni -----------------------------------------------------------------------------------------------

  /**
   * Segna fatto [step] (o, con [keepPending], visto ma non verificato: resta in "Da completare") e passa al
   * successivo. [mutate] aggiunge cio' che il passo ha deciso; con [applyChoices] le scelte locali vanno a chi le applica.
   */
  private suspend fun complete(
    step: OnboardingStep,
    warnings: Set<OnboardingWarning> = emptySet(),
    keepPending: Boolean = false,
    applyChoices: Boolean = false,
    mutate: (OnboardingSnapshot) -> OnboardingSnapshot = { it },
  ): StepResult {
    var next: OnboardingStep? = null
    val updated = store.update { s ->
      val (advanced, following) = mutate(s).completing(step, keepPending)
      next = following
      advanced
    } ?: return problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (applyChoices) ports.choices.apply(updated.choices)
    return StepResult.Advanced(next, warnings)
  }

  private suspend fun ready(): OnboardingSnapshot? =
    (store.state.first { it !is StoreState.Loading } as? StoreState.Ready)?.snapshot

  private fun problem(problem: OnboardingProblem): StepResult = StepResult.Problem(problem)

  /**
   * Il problema da mostrare per un rapporto di prova, o null se tutte le fasi chieste sono andate bene. Le fasi in
   * [mustPass] devono essere proprio `PASSED` (una `SKIPPED` non basta); le altre possono anche essere saltate.
   */
  private fun reportProblem(report: ConnectionReport, stages: Set<Stage>, mustPass: Set<Stage> = emptySet()): OnboardingProblem? {
    report.firstFailure?.let { return NasProblems.reach(it.error ?: NasError.Timeout) }
    for (stage in stages) {
      val result = report.stages.firstOrNull { it.stage == stage }
      val ok = result != null && (result.outcome == StageOutcome.PASSED || (stage !in mustPass && result.outcome == StageOutcome.SKIPPED))
      if (!ok) return if (stage in mustPass) OnboardingProblem.AUTH_FAILED else OnboardingProblem.NAS_NOT_FOUND
    }
    return null
  }

  private companion object {
    val DISCOVERY_TIMEOUT = 10.seconds
    val SETTLED_STEPS = setOf(OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS)
    val ACCOUNT_STAGES = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID, Stage.AUTHENTICATE, Stage.SECURITY_CHECK)
    val REQUIRED_PERMISSIONS = setOf(HealthPermission.NOTIFICATIONS, HealthPermission.MEDIA_IMAGES, HealthPermission.MEDIA_VIDEO)
    val TUNNEL_NAME = Regex("^[A-Za-z0-9_=+.-]{1,15}$")
    const val MIN_PASSWORD_CHARS = 12
    const val MAX_NAME_CHARS = 253
    const val PUBLIC_SHARE = "Public"
    const val COMMON_DEFAULT_SHARE = "NAS"
  }
}
