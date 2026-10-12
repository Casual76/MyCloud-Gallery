package dev.pampa.mycloudgallery.core.family.registry

import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.DeviceRoleView
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.trust.SyncOutcome
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalWindow
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.LookAlike
import dev.pampa.mycloudgallery.core.trust.registry.PendingRequests
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il registro della famiglia visto dall'app (P3, §2.2 lato admin, §2.3): stato proprio, elenco dei
 * dispositivi (registro + stato pubblicato, L11), richieste d'ingresso in attesa, azioni dell'admin.
 *
 * **Approvazione** ([approve], revisione M2): la richiesta si rilegge per id dal NAS (anche se la cartella e'
 * inondata); la finestra di 15 minuti parte da quando l'app dell'admin l'ha **vista** la prima volta
 * ([ApprovalSightings], ora del NAS, in locale: non `requestedAt` che scrive il richiedente); la prova e' il
 * codice **digitato** dall'admin o la chiave scansionata (`ApprovalProof`, mai costruita dalla richiesta, N6).
 * Poi `FamilyAuthority.commit` tramite [RegistryWriter].
 */
class NasRegistryService(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val writer: RegistryWriter,
  private val sightings: ApprovalSightings,
  private val statuses: DeviceStatusBook,
  private val scheduler: TrustSyncScheduler,
) : RegistryService {

  private val mutex = Mutex()
  private val _devices = MutableStateFlow<List<DeviceView>>(emptyList())
  private val _pending = MutableStateFlow<List<PendingRequestView>>(emptyList())

  override val status: StateFlow<TrustStatus> = engine.status
  override val devices: StateFlow<List<DeviceView>> = _devices.asStateFlow()
  override val pendingRequests: StateFlow<List<PendingRequestView>> = _pending.asStateFlow()

  override suspend fun refresh(purpose: Purpose) = mutex.withLock {
    val outcome = engine.sync(purpose)
    if (outcome is SyncOutcome.Synced) {
      statuses.refresh(purpose)
      statuses.publishIfDue(purpose)
      if (outcome.result.trustedVersionReplaced) writer.reissue() // N3: si fa da sola se la cassaforte si apre
      refreshPending(purpose)
    }
    rebuildDevices()
  }

  override suspend fun approve(requestId: String, proof: ApprovalProof): AdminActionResult = mutex.withLock {
    val admin = writer.adminContext() ?: return@withLock AdminActionResult.NotAdmin
    val read = try {
      sessions.onShare(admin.commonShare, Purpose.INTERACTIVE) { fs -> PendingRequests.readOne(fs, admin.state, requestId) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NasResult.Err(UNEXPECTED)
    }
    val request = when (read) {
      is NasResult.Err -> return@withLock AdminActionResult.Failed(read.error)
      is NasResult.Ok -> read.value?.acceptedOrNull() ?: return@withLock AdminActionResult.Failed(NasError.NotFound)
    }
    if (admin.state.registry.device(request.deviceId) != null) return@withLock AdminActionResult.Done
    val now = engine.nasNow()
    val firstSeen = sightings.observe(request.deviceId, now)
    if (!ApprovalWindow.isOpen(firstSeen, now)) return@withLock AdminActionResult.WindowExpired
    if (!proof.proves(request)) return@withLock AdminActionResult.ProofMismatch
    val draft = try {
      admin.authority.approve(admin.state, request, proof, firstSeen)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return@withLock AdminActionResult.Failed(UNEXPECTED)
    }
    val result = writer.commit(admin, draft)
    if (result == AdminActionResult.Done) {
      _pending.value = _pending.value.filterNot { it.id == request.deviceId }
      rebuildDevices()
    }
    result
  }

  override suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult = mutex.withLock {
    val result = writer.changeStatus(deviceId, status)
    if (result == AdminActionResult.Done) rebuildDevices()
    result
  }

  /** La scelta dell'admin sui rami in conflitto (`writer.pendingForks`): unire ([recognize]) o rifiutare. */
  suspend fun resolveForks(recognize: Boolean): AdminActionResult = mutex.withLock {
    val result = writer.resolveForks(recognize)
    if (result == AdminActionResult.Done) rebuildDevices()
    result
  }

  /**
   * L'invito per un familiare (§2.2): ancora del registro **attuale** (id, impronta, versione minima), indirizzo,
   * GUID e ULA dalla configurazione firmata, mai una password (C8). Apre la finestra d'invito di 24 h (ora del NAS).
   * Lancia se questo dispositivo non e' admin, se una rotazione e' a meta' (nessun QR con la chiave vecchia, N4)
   * o se la configurazione non ha il GUID del NAS.
   */
  override suspend fun createInvite(params: InviteParams): FamilyInvite {
    val admin = writer.adminContext() ?: throw IllegalStateException("solo l'admin crea un invito")
    val anchor = admin.state.toAnchor() ?: throw IllegalStateException("rotazione della chiave a meta': completarla prima")
    val config = admin.state.registry.config
    val guid = config.nasGuid ?: throw IllegalStateException("configurazione senza GUID del NAS")
    try {
      TextRules.nasAccount(params.nasUser)
      TextRules.displayText(params.deviceName, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
      ShareNames.check(params.privateShare)
    } catch (e: Exception) {
      throw IllegalArgumentException("invito: utente, nome o area privata non validi")
    }
    val expires = engine.nasNow() + INVITE_VALIDITY_MILLIS
    scheduler.setInviteWindow(expires)
    return FamilyInvite(
      familyId = anchor.familyId,
      familyKeyFingerprint = anchor.familyKeyFingerprint,
      minRegistryVersion = anchor.minRegistryVersion,
      nasIp = config.nasHost,
      nasName = config.nasName,
      nasGuid = guid,
      homeUla = config.homeUla,
      commonShare = config.commonShare,
      privateShare = params.privateShare,
      nasUser = params.nasUser,
      deviceName = params.deviceName,
      presetName = params.presetName,
      expiresAtNas = expires,
    )
  }

  // ------------------------------------------------------------------------------------------------------

  private suspend fun refreshPending(purpose: Purpose) {
    val state = engine.state ?: return
    val me = engine.myDeviceId() ?: return
    val entry = state.registry.device(me)
    if (entry?.status != DeviceStatus.APPROVED || !entry.admin) {
      _pending.value = emptyList()
      return
    }
    val share = engine.profile()?.commonShare ?: state.registry.config.commonShare
    val read = try {
      sessions.onShare(share, purpose) { fs -> PendingRequests.read(fs, state) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NasResult.Err(UNEXPECTED)
    }
    val pending = (read as? NasResult.Ok)?.value ?: return
    val now = engine.nasNow()
    sightings.retain(pending.requests.map { it.deviceId }.toSet())
    _pending.value = pending.requests.map { request ->
      val firstSeen = sightings.observe(request.deviceId, now)
      PendingRequestView(
        id = request.deviceId,
        name = request.name,
        model = request.model,
        declaredNasUser = request.nasUser,
        requestedAt = request.requestedAt,
        firstSeenAt = firstSeen,
        windowOpen = ApprovalWindow.isOpen(firstSeen, now),
        lookAlikeOf = if (request.deviceId in pending.lookAlikes) lookAlikeOf(request.name, request.deviceId, state, pending.requests) else null,
      )
    }
  }

  private fun rebuildDevices() {
    val state = engine.state ?: run {
      _devices.value = emptyList()
      return
    }
    val me = try {
      engine.myDeviceId()
    } catch (e: Exception) {
      null
    }
    val reports = statuses.latest.value
    _devices.value = state.registry.devices.map { entry ->
      val report = reports[entry.id]?.takeIf { entry.status == DeviceStatus.APPROVED }
      DeviceView(
        id = entry.id,
        name = entry.name,
        model = entry.model,
        nasUser = entry.nasUser,
        admin = entry.admin,
        status = entry.status,
        roles = report?.roles?.map { it.toView() }?.toSet().orEmpty(),
        lastSeenAt = report?.lastSeenAt,
        appVersion = report?.appVersion,
        isThisDevice = entry.id == me,
        lookAlikeOf = lookAlikeOf(entry.name, entry.id, state, emptyList()),
      )
    }
  }

  companion object {
    /** L'invito scade dopo 24 h secondo l'ora del NAS (§2.2, E-S8). */
    const val INVITE_VALIDITY_MILLIS: Long = 24L * 60 * 60 * 1000

    private fun DeviceRole.toView(): DeviceRoleView = when (this) {
      DeviceRole.INDEXER -> DeviceRoleView.INDEXER
      DeviceRole.PREPARER -> DeviceRoleView.PREPARER
    }

    /** Un altro dispositivo (o richiesta) con un nome che "sembra uguale" (`LookAlike`, revisione B2, E-O7). */
    internal fun lookAlikeOf(name: String, selfId: String, state: RegistryState, requests: List<RegistrationRequest>): String? {
      val key = LookAlike.key(name)
      return state.registry.devices.firstOrNull { it.id != selfId && LookAlike.key(it.name) == key }?.id
        ?: requests.firstOrNull { it.deviceId != selfId && LookAlike.key(it.name) == key }?.deviceId
    }
  }
}
