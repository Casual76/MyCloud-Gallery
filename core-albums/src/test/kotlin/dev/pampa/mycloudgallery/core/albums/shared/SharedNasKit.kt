package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
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
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

// Il NAS finto e la famiglia vera dei test di shared/ (P2). Qui sta `NasFileSystem` (Phase7ArchitectureTest: solo in albums/shared/).

/**
 * Una share in memoria con la semantica di [NasFileSystem] (Samba: i nomi non distinguono le maiuscole; pubblicazione esclusiva; nessuna
 * cancellazione) e le porte di servizio di chi ha accesso SMB senza chiavi ([plant], [delete]): un PC con un virus, un familiare dal PC.
 */
class MemoryNasFileSystem(override val shareName: String = "test-comune") : NasFileSystem {
  private val lock = Any()
  private val files = LinkedHashMap<String, Pair<String, ByteArray>>()

  /** Ogni chiamata a [publishExclusive], in ordine (anche quelle che trovano il nome occupato). */
  val published: MutableList<SharePath> = mutableListOf()

  var reads: Int = 0
    private set

  override fun list(dir: SharePath, limit: Int): NasListing = synchronized(lock) {
    val prefix = dir.value.lowercase(Locale.ROOT) + "/"
    val names = files.values.map { it.first }.filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
      .map { it.substring(prefix.length).substringBefore('/') }.distinct()
    NasListing(names.take(limit), names.size > limit)
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead = synchronized(lock) {
    reads++
    val file = files[path.value.lowercase(Locale.ROOT)] ?: return NasRead.NotFound
    if (file.second.size > maxBytes) NasRead.TooLarge else NasRead.Data(file.second.copyOf())
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = synchronized(lock) {
    published += path
    val key = path.value.lowercase(Locale.ROOT)
    if (key in files) return PublishResult.ALREADY_EXISTS
    files[key] = path.value to bytes.copyOf()
    PublishResult.CREATED
  }

  /** Scrive (o sovrascrive) un file come farebbe chi ha accesso SMB: nessuna firma, nessun controllo. */
  fun plant(path: String, bytes: ByteArray) = synchronized(lock) { files[path.lowercase(Locale.ROOT)] = path to bytes.copyOf() }

  fun delete(path: String) = synchronized(lock) { files.remove(path.lowercase(Locale.ROOT)) }

  fun bytes(path: String): ByteArray? = synchronized(lock) { files[path.lowercase(Locale.ROOT)]?.second?.copyOf() }

  fun paths(): List<String> = synchronized(lock) { files.values.map { it.first } }
}

/**
 * I guasti del banco (come `FaultPlan` di core-nas, che core-albums non vede): a ogni chiamata, con probabilita' [probability] e un seme
 * ripetibile, un errore del NAS **prima** (niente fatto) o **dopo** (fatto, ma chi chiama non lo sa). Si spegne con [enabled].
 */
class AlbumFaultPlan(seed: Long, private val probability: Double) {
  private val random = Random(seed)
  var enabled: Boolean = true
  var fired: Int = 0
    private set

  fun before(): Boolean = enabled && random.nextDouble() < probability / 2 && fire()

  fun after(): Boolean = enabled && random.nextDouble() < probability / 2 && fire()

  private fun fire(): Boolean {
    fired++
    return true
  }
}

/** Una share che sbaglia secondo [plan]: `IOException` prima o dopo l'operazione vera. */
class FaultyFileSystem(private val delegate: NasFileSystem, private val plan: AlbumFaultPlan) : NasFileSystem {
  override val shareName: String get() = delegate.shareName

  override fun list(dir: SharePath, limit: Int): NasListing = faulty { delegate.list(dir, limit) }

  override fun read(path: SharePath, maxBytes: Int): NasRead = faulty { delegate.read(path, maxBytes) }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = faulty { delegate.publishExclusive(path, bytes) }

  private inline fun <T> faulty(block: () -> T): T {
    if (plan.before()) throw IOException("guasto prima")
    val result = block()
    if (plan.after()) throw IOException("guasto dopo")
    return result
  }
}

/** Una famiglia vera (chiave di famiglia, registro firmato pubblicato nell'area comune), con l'ora del NAS dei test. */
class AlbumTestFamily(private val clocks: FakeAlbumClocks, private val common: MemoryNasFileSystem) {
  private val familyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  private val authority = FamilyAuthority(familyKey) { clocks.nas!! }

  @Volatile lateinit var state: RegistryState
    private set

  init {
    val founder = verified(request(FamilyKey.generate(), "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = common.shareName), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    state = (RegistryVerifier.accept(signed.bytes, null, TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!) as Verdict.Accepted).value
  }

  /** Approva un dispositivo nuovo dell'account [nasUser] (admin se [admin]) e restituisce la sua chiave. */
  fun approve(name: String, nasUser: String, admin: Boolean = false): Signer {
    val device = FamilyKey.generate()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clocks.nas!!, admin = admin))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus, frozenHeads: List<String> = emptyList()) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status, frozenHeads))
  }

  /** Tutti i dispositivi dell'account [nasUser] revocati: l'account esce dalla famiglia (D11). */
  fun removeAccount(nasUser: String) {
    for (d in state.registry.devices.filter { it.nasUser.equals(nasUser, ignoreCase = true) && it.status == DeviceStatus.APPROVED }) {
      commit(authority.changeStatus(state, d.id, DeviceStatus.REVOKED, emptyList()))
    }
  }

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clocks.nas!!)

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91)))) as Verdict.Accepted).value
}
