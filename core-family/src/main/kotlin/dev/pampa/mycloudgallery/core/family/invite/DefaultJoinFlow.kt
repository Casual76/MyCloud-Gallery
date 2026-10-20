package dev.pampa.mycloudgallery.core.family.invite

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.JoinFlow
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.onboarding.FamilyConfigFactory
import dev.pampa.mycloudgallery.core.family.onboarding.JoinRecord
import dev.pampa.mycloudgallery.core.family.onboarding.NasFacts
import dev.pampa.mycloudgallery.core.family.onboarding.NasProblems
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingPorts
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.RejectedCredentials
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.onboarding.completing
import dev.pampa.mycloudgallery.core.family.onboarding.onShare
import dev.pampa.mycloudgallery.core.family.setup.NasCredentialSlots
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalCodes
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'ingresso di un dispositivo nuovo in una famiglia (§2.2, lato nuovo dispositivo), con l'invito letto da un QR
 * (`MCG1:`) o digitato a mano ([ManualInvite]). Va in tre tempi, nell'ordine che tiene la password al sicuro:
 *
 * 1. [start]: forma dell'invito → **controllo casa** (il Wi-Fi ha un ULA dell'invito, oppure c'e' la VPN di casa;
 *    senza QR, l'utente ha confermato "e' casa" al passo 2) → TCP → NEGOTIATE (3.1.1 con cifratura) → **GUID
 *    uguale a quello del QR** → scadenza dell'invito con l'ora del NAS. Fin qui **nessuna password** e, su una
 *    rete estranea, nemmeno una connessione TCP (la decide il cancello di rete, E-S10);
 * 2. [authenticate]: la password (unico campo digitato, **un solo tentativo**) → controllo di sicurezza →
 *    `RegistrySync.refresh` con l'ancora del QR (id della famiglia, impronta, versione minima) → si usa la
 *    configurazione **firmata** letta dal NAS, non quella del QR (E-O6);
 * 3. [register]: il nome del dispositivo → chiave nel Keystore → `RegistrationRequest` → codice da far confrontare
 *    all'admin e QR della chiave (`MCGK1:`) → in attesa di approvazione (sola lettura, E-S14).
 *
 * Un QR di un'altra famiglia, o con un'impronta diversa da quella che firma il registro sul NAS, non porta a
 * nulla (E-O5). Una reinstallazione ha una chiave nuova e quindi un id nuovo (E-O7); un dispositivo gia' revocato
 * che rientra ne riceve una nuova (E-S2).
 *
 * L'avanzamento ([OnboardingStep.NAS_ADDRESS] e [OnboardingStep.ACCOUNT]) si segna fatto solo a registrazione
 * avvenuta: se il processo muore a meta' si riparte dalla scansione, non da una password che nessuno ricorda.
 * Se un passo fallisce prima della fine, le credenziali e l'account appena salvati si tolgono (nessun residuo).
 */
class DefaultJoinFlow(
  private val store: OnboardingStateStore,
  private val ports: OnboardingPorts,
) : JoinFlow {

  private val lock = Mutex()
  private val rejected = RejectedCredentials()
  private var session: JoinSession? = null

  /** Cio' che si sa dell'ingresso in corso (solo in memoria: la password non esiste qui, la richiesta si rifa'). */
  private class JoinSession(
    val invite: FamilyInvite,
    var address: NasAddress,
    var commonShare: String,
    val guid: String,
    val candidate: HomeCandidate?,
    val provisionalFingerprint: Boolean,
    /** Si rifa' la richiesta di un dispositivo gia' in attesa (finestra dell'admin scaduta): serve una chiave nuova. */
    var renewKey: Boolean,
    /**
     * La versione minima del registro che l'ancora pretende. Quella dell'invito; nel rinnovo (R18) mai meno di quella gia'
     * fissata alla prima richiesta e di quella del registro accettato allora: un invito senza QR vale 0, ma il rinnovo non
     * deve accettare un registro piu' vecchio di quello che questo dispositivo ha gia' visto.
     */
    val minRegistryVersion: Long,
  ) {
    var state: RegistryState? = null
    var requestBytes: ByteArray? = null
  }

  // --- 1. Invito, casa, NAS --------------------------------------------------------------------------

  override suspend fun start(invite: FamilyInvite): JoinStep = lock.withLock {
    rollback()
    session = null
    val manual = invite.isManual
    try {
      InviteRules.check(invite, manual)
    } catch (e: TrustViolation) {
      return@withLock JoinStep.Failed(OnboardingProblem.INVITE_INVALID)
    }
    val snapshot = (store.state.first { it !is StoreState.Loading } as? StoreState.Ready)?.snapshot
      ?: return@withLock JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val expectedPath = if (manual) OnboardingPath.JOIN_MANUAL else OnboardingPath.JOIN_QR
    // Un dispositivo gia' registrato puo' rifare l'ingresso solo se e' ancora in attesa: la richiesta e' scaduta
    // per l'admin (15 minuti da quando l'ha vista) e ne serve una nuova, con una chiave nuova.
    val renewing = snapshot.join?.registered == true
    if (snapshot.path != expectedPath || (renewing && ports.registry.status.value !is TrustStatus.Pending)) {
      return@withLock JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    }
    val address = NasAddress.parseOrNull(invite.nasIp, invite.nasName) ?: return@withLock JoinStep.Failed(OnboardingProblem.INVITE_INVALID)
    // R18: rifacendo la richiesta il NAS e la famiglia sono gia' quelli fissati la prima volta. Il GUID noto vale come quello
    // dell'invito (che senza QR non c'e'): la password non parte verso un NAS che il dispositivo sapeva gia' non essere quello
    // giusto, e un invito che ne porta un altro non va nemmeno provato. La versione minima non scende mai.
    val knownGuid = snapshot.nas?.guid?.takeIf { renewing && it.isNotBlank() }
    val inviteGuid = invite.nasGuid.takeIf { it.isNotEmpty() }
    if (knownGuid != null && inviteGuid != null && !knownGuid.equals(inviteGuid, ignoreCase = true)) {
      return@withLock JoinStep.Failed(OnboardingProblem.IDENTITY_MISMATCH)
    }
    val minVersion = if (renewing) renewalMinVersion(invite, snapshot) else invite.minRegistryVersion

    // Casa: prima di toccare la rete.
    if (ports.localNetwork.status() == LocalNetworkAccess.DENIED) return@withLock JoinStep.Failed(OnboardingProblem.LOCAL_NETWORK_DENIED)
    var candidate: HomeCandidate? = null
    var provisional = false
    if (manual) {
      if (ports.network.snapshot.value.vpn != VpnObservation.NONE) return@withLock JoinStep.Failed(OnboardingProblem.VPN_ACTIVE)
      // Senza QR non c'e' un ULA da confrontare: vale il Wi-Fi che l'utente ha confermato come casa (passo 2).
      candidate = snapshot.homeConfirmed ?: return@withLock JoinStep.Failed(OnboardingProblem.NOT_AT_HOME)
    } else {
      val wifi = ports.homeCapture.candidateFromCurrentWifi()
      if (wifi != null && HomeUla.overlap(wifi.ulaPrefixes, invite.homeUla)) {
        candidate = wifi
      } else if (ports.homeFingerprints.fingerprint.value == null) {
        // "Oppure VPN di casa": il cancello riconosce la VPN dall'impronta, e un dispositivo nuovo non ne ha ancora
        // una. Si usa provvisoriamente quella del QR, che si toglie se l'ingresso non arriva in fondo.
        ports.homeFingerprints.save(HomeFingerprint(invite.homeUla.toSet(), gatewayV4 = null, dnsDomain = null, capturedAt = ports.deviceNow()))
        provisional = true
      }
    }

    // TCP, NEGOTIATE, GUID uguale a quello dell'invito. Sulla rete sbagliata il cancello nega e non parte nulla.
    val stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
    val expectedGuid = inviteGuid ?: knownGuid
    val report = ports.tester.run(TestTarget(address, user = null, share = null, expectedGuid = expectedGuid, candidate = candidate), null, stages)
    val failure = failureOf(report, stages)
    if (failure != null) {
      if (provisional) ports.homeFingerprints.clear()
      markDeferrable(failure)
      return@withLock JoinStep.Failed(failure)
    }
    val facts = report.negotiation
    if (facts == null) return@withLock failStart(provisional, OnboardingProblem.NAS_NOT_FOUND)
    if (facts.dialect != SmbDialect.SMB_3_1_1 || !facts.encryptionOffered) {
      return@withLock failStart(provisional, OnboardingProblem.NAS_INSECURE)
    }
    // Il GUID del QR deve essere quello della NEGOTIATE, qualunque cosa dica il test: e' l'unica conferma prima della password.
    if (facts.serverGuid.isBlank() || (expectedGuid != null && facts.serverGuid != expectedGuid)) {
      return@withLock failStart(provisional, OnboardingProblem.IDENTITY_MISMATCH)
    }
    if (!manual) {
      // La scadenza si giudica con l'ora del NAS, non con quella del telefono (E-P9).
      val nasNow = ports.deviceNow() + facts.timeOffsetMs
      when (InviteExpiry.status(invite, nasNow)) {
        InviteExpiry.Status.VALID -> Unit
        InviteExpiry.Status.EXPIRED -> return@withLock failStart(provisional, OnboardingProblem.INVITE_EXPIRED)
        InviteExpiry.Status.TOO_FAR -> return@withLock failStart(provisional, OnboardingProblem.INVITE_INVALID)
      }
    }
    // Il Wi-Fi diventa "casa" ora che il NAS ha risposto con il GUID giusto (con l'invito manuale, il GUID e' quello
    // che il NAS ha dato su un Wi-Fi che l'utente ha confermato: lo si impara qui e lo si ricontrolla a ogni sessione).
    if (candidate != null) {
      ports.homeFingerprints.save(HomeFingerprint(candidate.ulaPrefixes, candidate.gatewayV4, candidate.dnsDomain, ports.deviceNow()))
    }
    val nas = NasFacts.of(address, facts.serverGuid)
    val preset = Preset.entries.firstOrNull { it.name == invite.presetName }
    store.update { s ->
      s.copy(
        nas = nas,
        nasUser = invite.nasUser,
        commonShare = invite.commonShare,
        privateShare = invite.privateShare,
        homeConfirmed = candidate ?: s.homeConfirmed,
        choices = if (preset != null) s.choices.copy(preset = preset) else s.choices,
        join = JoinRecord(invite.familyId, manual, minVersion, registered = s.join?.registered == true, requestedAt = s.join?.requestedAt),
        deferrable = false,
      )
    } ?: return@withLock failStart(provisional, OnboardingProblem.STEP_NOT_AVAILABLE)
    session = JoinSession(invite, address, invite.commonShare, facts.serverGuid, candidate, provisional, renewKey = renewing, minRegistryVersion = minVersion)
    JoinStep.NeedsPassword
  }

  /** Il Wi-Fi dell'onboarding serve finche' "casa" non e' registrata (all'inizio dell'ingresso); dopo, il percorso lo decide il cancello di rete. */
  private fun routeCandidate(s: JoinSession): HomeCandidate? = s.candidate.takeIf { ports.homeFingerprints.fingerprint.value == null }

  /** I prefissi ULA di casa firmati dall'admin si aggiungono a quelli gia' registrati (E-O6: la configurazione firmata vince sul QR). */
  private suspend fun mergeHome(signedUla: List<String>) {
    if (signedUla.isEmpty()) return
    val current = ports.homeFingerprints.fingerprint.value
    val known = current?.ulaPrefixes.orEmpty().mapNotNull(HomeUla::canonicalOf).toSet()
    val merged = known + signedUla.mapNotNull(HomeUla::canonicalOf)
    if (current == null || merged != known) {
      ports.homeFingerprints.save(HomeFingerprint(merged, current?.gatewayV4, current?.dnsDomain, ports.deviceNow()))
    }
  }

  private suspend fun failStart(provisional: Boolean, problem: OnboardingProblem): JoinStep {
    if (provisional) ports.homeFingerprints.clear()
    return JoinStep.Failed(problem)
  }

  // --- 2. Password e registro --------------------------------------------------------------------------

  override suspend fun authenticate(password: CharArray): JoinStep =
    try {
      lock.withLock { authenticateLocked(password) }
    } finally {
      password.fill('\u0000')
    }

  private suspend fun authenticateLocked(password: CharArray): JoinStep {
    val s = session ?: return JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val user = s.invite.nasUser
    if (password.isEmpty() || rejected.isRepeat(user, password)) return JoinStep.Failed(OnboardingProblem.AUTH_FAILED)
    // Revisione 2, F1: anche la password del familiare deve reggere un attacco offline; se e' debole non parte.
    if (!NasPasswordPolicy.isAcceptable(user, password)) return JoinStep.Failed(OnboardingProblem.PASSWORD_TOO_WEAK)
    val credentials = try {
      NasCredentials(user, password)
    } catch (e: IllegalArgumentException) {
      return JoinStep.Failed(OnboardingProblem.AUTH_FAILED)
    }
    credentials.use { c ->
      // Una password nuova scritta dall'utente richiude l'interruttore di questo account aperto da un tentativo precedente.
      ports.reopenBreakerFor(NasAccount(s.address, user, NasCredentialSlots.PRIMARY, s.guid))
      val stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID, Stage.AUTHENTICATE, Stage.SECURITY_CHECK)
      val target = TestTarget(s.address, user = user, share = null, expectedGuid = s.guid, candidate = routeCandidate(s))
      val report = ports.tester.run(target, c, stages)
      val failure = failureOf(report, stages, mustPass = setOf(Stage.AUTHENTICATE, Stage.SECURITY_CHECK))
      if (failure != null) {
        val error = report.firstFailure?.error
        if (error is NasError.Auth || error is NasError.AuthBlocked) rejected.remember(user, password)
        return JoinStep.Failed(failure)
      }
      // La sessione che legge il registro ha bisogno delle credenziali salvate: si salvano ora, e si tolgono se poi non va.
      when (ports.credentials.save(NasCredentialSlots.PRIMARY, c)) {
        is SaveResult.Saved -> Unit
        SaveResult.HardwareKeyUnavailable, SaveResult.Unavailable -> return JoinStep.Failed(OnboardingProblem.CREDENTIALS_NOT_SAVED)
      }
    }
    rejected.clear()
    ports.accounts.save(NasAccount(s.address, user, NasCredentialSlots.PRIMARY, s.guid))
    ports.breaker.onNewPasswordSaved()

    // Il registro firmato, con l'ancora del QR: da qui in poi vale la configurazione firmata (E-O6).
    val anchor = TrustAnchor.create(s.invite.familyId, s.invite.familyKeyFingerprint, s.minRegistryVersion)
      ?: return abandon(OnboardingProblem.INVITE_INVALID)
    val synced = ports.sessions.onShare(s.commonShare) { fs -> RegistrySync(fs).refresh(null, anchor) }
    val result = when (synced) {
      is NasResult.Err -> return abandon(NasProblems.reach(synced.error, joining = true))
      is NasResult.Ok -> synced.value
    }
    if (result.needsAppUpdate) return abandon(OnboardingProblem.NEEDS_APP_UPDATE)
    // Nessun registro che l'ancora del QR accetti: un'altra famiglia, un'impronta diversa, o niente (E-O5).
    val state = result.state ?: return abandon(
      // Un registro piu' vecchio di quello del QR (o nessuno dove il QR dice che c'e' una versione) e' un rollback.
      if (result.rejected.count(RejectReason.ROLLBACK) > 0 || (result.rejected.total == 0 && s.minRegistryVersion > 1)) {
        OnboardingProblem.REGISTRY_UNTRUSTED
      } else if (result.rejected.total == 0) {
        // Punto 10 delle prove sul campo: nessun registro da rifiutare. Se nell'area non c'e' proprio una famiglia lo si dice ("Crea la
        // famiglia" o un'altra area), invece di "e' di un'altra famiglia"; un errore del NAS nel controllo resta il suo, con "Riprova".
        when (val first = ports.sessions.onShare(s.commonShare) { fs -> fs.read(NasLayout.registryFile(1), 1) }) {
          is NasResult.Err -> NasProblems.reach(first.error, joining = true)
          is NasResult.Ok -> if (first.value is NasRead.NotFound) OnboardingProblem.NO_FAMILY_IN_AREA else OnboardingProblem.INVITE_OTHER_FAMILY
        }
      } else {
        OnboardingProblem.INVITE_OTHER_FAMILY
      },
    )
    if (result.forks.isNotEmpty() || result.trustedVersionReplaced) return abandon(OnboardingProblem.REGISTRY_UNTRUSTED)
    // E-O6: la configurazione e' quella firmata, anche se il QR dice altro. Se l'indirizzo e' cambiato si verifica
    // che dietro il nuovo ci sia lo stesso NAS (stesso GUID) prima di fidarsene.
    val signed = state.registry.config
    // Il GUID firmato dall'admin e' la conferma indipendente dal QR (e l'unica per chi entra senza QR): un NAS con un
    // altro GUID non e' quello della famiglia, qualunque cosa dica l'indirizzo.
    val signedGuid = signed.nasGuid
    // Revisione 2, F6: qui la password e' gia' partita verso il NAS sbagliato: lo si dice, perche' va cambiata.
    if (signedGuid != null && !signedGuid.equals(s.guid, ignoreCase = true)) return abandon(OnboardingProblem.PASSWORD_SENT_TO_OTHER_NAS)
    val signedAddress = NasAddress.parseOrNull(FamilyConfigFactory.ipOf(signed.nasHost), s.address.name)
    if (signedAddress != null && signedAddress != s.address) {
      val stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
      val check = ports.tester.run(TestTarget(signedAddress, null, null, s.guid, routeCandidate(s)), null, stages)
      failureOf(check, stages)?.let { return abandon(it) }
      s.address = signedAddress
      ports.accounts.save(NasAccount(signedAddress, user, NasCredentialSlots.PRIMARY, s.guid))
    }
    s.commonShare = signed.commonShare
    s.state = state
    mergeHome(signed.homeUla)
    val address = s.address
    val commonShare = s.commonShare
    store.update { snap ->
      snap.copy(nas = NasFacts.of(address, s.guid), commonShare = commonShare)
    }
    return JoinStep.NeedsName(s.invite.deviceName)
  }

  // --- 3. Nome, chiave, richiesta -------------------------------------------------------------------------

  override suspend fun register(name: String): JoinStep = lock.withLock {
    val s = session
    val state = s?.state ?: return@withLock JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val deviceName = try {
      TextRules.displayText(name.trim(), TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    } catch (e: TrustViolation) {
      return@withLock JoinStep.NeedsName(s.invite.deviceName)
    }
    val user = s.invite.nasUser
    if (s.renewKey && ports.deviceKeys.hasDeviceKey()) {
      // La richiesta vecchia e' scaduta per l'admin: non si ripresenta la stessa chiave, se ne fa una nuova (id nuovo).
      ports.deviceKeys.destroyDeviceKey()
      s.requestBytes = null
      s.renewKey = false // una volta sola: un secondo tentativo di scrittura riusa la chiave nuova
    }
    var signer = ports.deviceKeys.deviceSigner()
    when (state.registry.device(TrustIds.deviceIdOf(signer.publicKey))?.status) {
      null -> Unit
      DeviceStatus.APPROVED -> {
        // Questo dispositivo e' gia' nella famiglia (la chiave c'era): niente da chiedere all'admin.
        finish(s, state, requestedAt = ports.nasClock.nowMillis())
        session = null
        return@withLock JoinStep.Approved
      }
      // Un dispositivo dismesso, revocato o perso che rientra ha una chiave nuova, quindi un id nuovo (E-S2, E-O7).
      DeviceStatus.RETIRED, DeviceStatus.REVOKED, DeviceStatus.LOST -> {
        ports.deviceKeys.destroyDeviceKey()
        s.requestBytes = null
        signer = ports.deviceKeys.deviceSigner()
      }
    }
    val deviceId = TrustIds.deviceIdOf(signer.publicKey)
    val requestedAt = ports.nasClock.nowMillis()
    var bytes = s.requestBytes
    if (bytes == null) {
      // La firma non e' deterministica: la richiesta si firma una volta sola e si ripubblicano sempre gli stessi byte.
      bytes = try {
        RegistrationRequest.sign(signer, s.invite.familyId, deviceName, safeModel(ports.device.describe(user).model), user, requestedAt)
      } catch (e: TrustViolation) {
        return@withLock JoinStep.Failed(OnboardingProblem.INVITE_INVALID)
      }
      s.requestBytes = bytes
    }
    val request: ByteArray = bytes

    // Idempotente: lo stesso nome gia' occupato da una richiesta valida di questa chiave vale come "gia' fatto".
    val path = NasLayout.requestFile(deviceId)
    val written = ports.sessions.onShare(s.commonShare) { fs ->
      when (fs.publishExclusive(path, request)) {
        PublishResult.CREATED -> true
        PublishResult.ALREADY_EXISTS -> ownRequest(fs.read(path, TrustLimits.MAX_REQUEST_PAYLOAD + REQUEST_OVERHEAD_BYTES), s.invite.familyId, deviceId, signer)
      }
    }
    when (written) {
      is NasResult.Err -> return@withLock JoinStep.Failed(NasProblems.write(written.error))
      is NasResult.Ok -> if (!written.value) return@withLock JoinStep.Failed(OnboardingProblem.NAS_WRITE_FAILED)
    }
    finish(s, state, requestedAt)
    session = null
    val qr = DeviceKeyCodec.encode(DeviceKeyQr(s.invite.familyId, deviceId, signer.publicKey.encoded))
    JoinStep.AwaitingApproval(ApprovalCodes.of(signer.publicKey), qr)
  }

  private fun ownRequest(read: NasRead, familyId: String, deviceId: String, signer: Signer): Boolean {
    val existing = (read as? NasRead.Data)?.bytes ?: return false
    val verdict = RegistrationRequest.verify(existing, familyId, deviceId)
    return verdict is Verdict.Accepted && verdict.value.key == signer.publicKey
  }

  private fun safeModel(model: String): String =
    try {
      TextRules.displayText(model, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    } catch (e: TrustViolation) {
      FALLBACK_MODEL
    }

  /** La richiesta e' sul NAS (o il dispositivo c'e' gia'): lo stato fidato si salva e l'avanzamento si segna. */
  private suspend fun finish(s: JoinSession, state: RegistryState, requestedAt: Long) {
    ports.trustState.save(
      TrustSnapshot(
        registryBytes = state.signedBytes,
        signerFingerprint = state.signerKey.fingerprint,
        familyId = s.invite.familyId,
        familyKeyFingerprint = state.signerKey.fingerprint,
        minRegistryVersion = state.registry.version,
        ownHeads = emptyMap(),
      ),
    )
    store.update { snap ->
      val join = JoinRecord(s.invite.familyId, s.invite.isManual, s.minRegistryVersion, registered = true, requestedAt = requestedAt)
      val withJoin = snap.copy(join = join)
      // Indirizzo e account sono fatti insieme: se il processo muore prima di qui si riparte dalla scansione.
      // (Rifacendo la richiesta di un dispositivo gia' in attesa, i passi sono gia' fatti e non cambia niente.)
      val afterAddress = withJoin.completing(OnboardingStep.NAS_ADDRESS).first
      afterAddress.completing(OnboardingStep.ACCOUNT).first
    }
    try {
      ports.registry.refresh(Purpose.ONBOARDING)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "registro non riletto subito (${e.javaClass.simpleName})" }
    }
  }

  /**
   * La versione minima del registro per il rinnovo della richiesta (R18): la piu' alta fra quella dell'invito, quella fissata alla
   * prima richiesta e quella del registro accettato allora (lo stato fidato della stessa famiglia). Un'ancora si alza e basta.
   */
  private suspend fun renewalMinVersion(invite: FamilyInvite, snapshot: OnboardingSnapshot): Long {
    val first = snapshot.join?.takeIf { it.familyId == invite.familyId }?.minRegistryVersion ?: 0L
    val trusted = try {
      ports.trustState.load()?.takeIf { it.familyId == invite.familyId }?.minRegistryVersion ?: 0L
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      0L
    }
    return maxOf(invite.minRegistryVersion, first, trusted)
  }

  // --- Comuni ---------------------------------------------------------------------------------------------

  /** Ritira cio' che l'ingresso ha salvato prima di fallire: nessun account ne' password a meta'. */
  private suspend fun abandon(problem: OnboardingProblem): JoinStep {
    rollback()
    session = null
    return JoinStep.Failed(problem)
  }

  private suspend fun rollback() {
    val s = session ?: return
    ports.credentials.remove(NasCredentialSlots.PRIMARY)
    ports.accounts.clear()
    if (s.provisionalFingerprint) ports.homeFingerprints.clear()
  }

  /**
   * Il problema di un rapporto di prova, o null se le fasi chieste sono tutte andate bene (come per l'onboarding
   * del fondatore, ma un percorso negato qui vuol dire "non sei a casa").
   */
  private fun failureOf(report: ConnectionReport, stages: Set<Stage>, mustPass: Set<Stage> = emptySet()): OnboardingProblem? {
    report.firstFailure?.let { return NasProblems.reach(it.error ?: NasError.Timeout, joining = true) }
    for (stage in stages) {
      val result = report.stages.firstOrNull { it.stage == stage }
      val ok = result != null && (result.outcome == StageOutcome.PASSED || (stage !in mustPass && result.outcome == StageOutcome.SKIPPED))
      if (!ok) return if (stage in mustPass) OnboardingProblem.AUTH_FAILED else OnboardingProblem.NAS_NOT_FOUND
    }
    return null
  }

  /** E-O1: il NAS non si trova. "Continua piu' tardi" diventa possibile (i passi locali si fanno lo stesso). */
  private suspend fun markDeferrable(problem: OnboardingProblem) {
    val deferrable = NasProblems.isDeferrable(problem)
    store.update { it.copy(deferrable = deferrable) }
  }

  private companion object {
    const val TAG = "JoinFlow"
    const val FALLBACK_MODEL = "Android"
    const val REQUEST_OVERHEAD_BYTES = 512
  }
}
