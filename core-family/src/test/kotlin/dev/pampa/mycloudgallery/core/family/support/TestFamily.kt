package dev.pampa.mycloudgallery.core.family.support

import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.flow.StateFlow

/**
 * Una famiglia gia' fondata sull'area comune [common]: chiave di famiglia, telefono dell'admin e registro
 * versione 1 pubblicato. E' cio' che trova un dispositivo che entra con un invito.
 */
class TestFamily(
  val common: InMemoryNasFileSystem,
  nasHost: String = TEST_IP,
  commonShare: String = "NAS",
  val clock: NasClock = NasClock { NAS_NOON },
  /** Il GUID che l'admin ha firmato nella configurazione (quello del NAS vero), o null per una configurazione senza. */
  nasGuid: String? = TEST_GUID,
  /** I prefissi ULA che l'admin ha firmato nella configurazione. */
  homeUla: List<String> = listOf(TEST_ULA),
) {
  val familyKey: FamilyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  val adminDevice: Signer = FamilyKey.generate()
  val authority = FamilyAuthority(familyKey, clock)
  var state: RegistryState
    private set

  init {
    val founder = RegistrationRequest(
      familyId, TrustIds.deviceIdOf(adminDevice.publicKey), adminDevice.publicKey, "Telefono di Alessio", "SM-S931B", "alessio", NAS_NOON,
    )
    val signed = authority.found(familyId, FamilyConfig(nasHost = nasHost, commonShare = commonShare, nasGuid = nasGuid, homeUla = homeUla), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    state = (RegistryVerifier.accept(signed.bytes, null, manualAnchor()) as Verdict.Accepted).value
  }

  val fingerprint: String get() = familyKey.publicKey.fingerprint

  fun manualAnchor(): TrustAnchor = TrustAnchor.create(familyId, fingerprint, 0)!!

  fun anchor(): TrustAnchor = state.toAnchor()!!

  /** L'invito che l'admin mostrerebbe adesso, valido per un'altra ora. */
  fun invite(
    user: String = "mamma",
    nasIp: String = TEST_IP,
    guid: String = TEST_GUID,
    expiresAt: Long = NAS_NOON + 60 * 60 * 1000L,
    fingerprint: String = this.fingerprint,
    familyId: String = this.familyId,
    minVersion: Long = state.registry.version,
  ) = FamilyInvite(
    familyId = familyId,
    familyKeyFingerprint = fingerprint,
    minRegistryVersion = minVersion,
    nasIp = nasIp,
    nasName = "MyCloudEX2Ultra.fritz.box",
    nasGuid = guid,
    homeUla = listOf(TEST_ULA),
    commonShare = "NAS",
    privateShare = user,
    nasUser = user,
    deviceName = "Tablet profilo Mamma",
    presetName = "BALANCED",
    expiresAtNas = expiresAt,
  )

  private fun commit(draft: dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft) {
    val result = authority.commit(common, draft)
    state = (result as CommitResult.Published).state
  }

  /** Approva un dispositivo come farebbe l'admin con il codice digitato. */
  fun approve(device: Signer, name: String = "Tablet", nasUser: String = "mamma") {
    val request = RegistrationRequest(
      familyId, TrustIds.deviceIdOf(device.publicKey), device.publicKey, name, "SM-X916B", nasUser, NAS_NOON,
    )
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = NAS_NOON))
  }

  fun setStatus(device: Signer, status: DeviceStatus) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status))
  }

  /** Un secondo registro, di un'altra famiglia, sullo stesso percorso: cio' che trova chi crea una famiglia dove ce n'e' gia' una. */
  companion object {
    fun other(common: InMemoryNasFileSystem): TestFamily = TestFamily(common)
  }
}

/** Il file system di una share con un occhio di riguardo: [onRead] puo' cambiare cio' che si legge, [afterPublish] far fallire dopo la scrittura. */
class TamperedFileSystem(
  private val inner: NasFileSystem,
  var onRead: (SharePath, NasRead) -> NasRead = { _, real -> real },
  var failReadsAfterPublish: Boolean = false,
) : NasFileSystem {
  override val shareName: String get() = inner.shareName
  private var published = false

  override fun list(dir: SharePath, limit: Int): NasListing = inner.list(dir, limit)

  override fun read(path: SharePath, maxBytes: Int): NasRead {
    if (failReadsAfterPublish && published) {
      failReadsAfterPublish = false
      throw dev.pampa.mycloudgallery.core.nas.contract.NasIoException(dev.pampa.mycloudgallery.core.nas.contract.NasError.Timeout)
    }
    return onRead(path, inner.read(path, maxBytes))
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    val result = inner.publishExclusive(path, bytes)
    published = true
    return result
  }
}

/** Una sessione che passa le share attraverso [tamper]: per far fallire o mentire il NAS solo dove serve. */
class TamperingSessions(
  private val delegate: NasSessionManager,
  private val tamper: (String, NasFileSystem) -> NasFileSystem,
) : NasSessionManager by delegate {

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> =
    delegate.withShare(share, purpose) { scope ->
      val tampered = tamper(share, scope.fileSystem)
      block(
        object : NasShareScope {
          override val shareName: String = scope.shareName
          override val fileSystem: NasFileSystem = tampered
          override val browser: NasBrowser = scope.browser
        },
      )
    }

  override val state: StateFlow<NasConnState> get() = delegate.state

  override suspend fun invalidate(reason: InvalidateReason) = delegate.invalidate(reason)
}
