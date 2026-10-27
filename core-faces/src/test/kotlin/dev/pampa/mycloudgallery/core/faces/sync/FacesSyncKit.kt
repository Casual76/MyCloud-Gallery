package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
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

// Il NAS finto e la famiglia vera dei test di sync/ (Fase 8, P3). Qui sta `NasFileSystem` (Phase8ArchitectureTest: in core-faces solo sotto
// faces/sync/, test compresi). Nessun dato di persone vere: identita' sintetiche e nomi inventati.

/**
 * I guasti del banco (come `FaultPlan` di core-nas, che core-faces non vede): a ogni chiamata, con probabilita' [probability] e un seme
 * ripetibile, un errore del NAS **prima** dell'operazione (niente fatto) o **dopo** (fatto, ma chi chiama non lo sa). Si spegne con [enabled].
 */
class FacesFaultPlan(seed: Long, private val probability: Double) {
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

/**
 * Una share in memoria con la semantica di [NasFileSystem] (Samba: i nomi non distinguono le maiuscole; pubblicazione esclusiva; nessuna
 * cancellazione), il registro di ogni operazione ([writes], [reads]) e le porte di servizio di chi ha la password SMB senza chiavi
 * ([plant], [delete]): un PC, un familiare, un attaccante. [plan] fa sbagliare il NAS prima o dopo l'operazione vera.
 */
class MemoryShare(override val shareName: String, var plan: FacesFaultPlan? = null) : NasFileSystem {
  private val lock = Any()
  private val files = LinkedHashMap<String, Pair<SharePath, ByteArray>>()

  /** Ogni chiamata a [publishExclusive], in ordine (anche quelle che trovano il nome occupato o che falliscono dopo). */
  val writes: MutableList<SharePath> = java.util.Collections.synchronizedList(mutableListOf())

  /** Ogni lettura con un tetto maggiore di zero (le prove "le teste di un altro account non si leggono mai"). */
  val reads: MutableList<SharePath> = java.util.Collections.synchronizedList(mutableListOf())

  var failNext: Int = 0

  private fun key(path: SharePath) = path.value.lowercase(Locale.ROOT)

  private inline fun <T> faulty(block: () -> T): T {
    synchronized(lock) {
      if (failNext > 0) {
        failNext--
        throw IOException("guasto")
      }
    }
    if (plan?.before() == true) throw IOException("guasto prima")
    val result = block()
    if (plan?.after() == true) throw IOException("guasto dopo")
    return result
  }

  override fun list(dir: SharePath, limit: Int): NasListing = faulty {
    synchronized(lock) {
      val prefix = key(dir) + "/"
      val names = files.values.map { it.first }.filter { key(it).startsWith(prefix) }
        .map { it.components[dir.components.size] }.distinctBy { it.lowercase(Locale.ROOT) }.sorted()
      NasListing(names.take(limit), names.size > limit)
    }
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead = faulty {
    synchronized(lock) {
      if (maxBytes > 0) reads += path
      val file = files[key(path)] ?: return@synchronized NasRead.NotFound
      if (file.second.size > maxBytes) NasRead.TooLarge else NasRead.Data(file.second.copyOf())
    }
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = faulty {
    synchronized(lock) {
      writes += path
      if (key(path) in files) {
        PublishResult.ALREADY_EXISTS
      } else {
        files[key(path)] = path to bytes.copyOf()
        PublishResult.CREATED
      }
    }
  }

  /** Scrive (o sovrascrive) un file come chi ha accesso SMB: nessuna firma, nessun controllo. */
  fun plant(path: SharePath, bytes: ByteArray) = synchronized(lock) { files[key(path)] = path to bytes.copyOf() }

  fun delete(path: SharePath) = synchronized(lock) { files.remove(key(path)) }

  fun bytes(path: SharePath): ByteArray? = synchronized(lock) { files[key(path)]?.second?.copyOf() }

  fun paths(): List<SharePath> = synchronized(lock) { files.values.map { it.first }.sortedBy { it.value } }
}

/**
 * [delegate] visto da un dispositivo: ogni scrittura riuscita va in [log] con l'id di chi scrive (la prova "solo sotto la mia cartella");
 * ogni tentativo, anche fallito o finito con un guasto, in [attempts].
 */
class RecordingShare(
  private val delegate: NasFileSystem,
  private val device: String,
  private val log: MutableList<Pair<String, SharePath>>,
  private val attempts: MutableList<Pair<String, SharePath>>? = null,
) : NasFileSystem by delegate {
  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    attempts?.add(device to path)
    return delegate.publishExclusive(path, bytes).also { if (it == PublishResult.CREATED) log += device to path }
  }
}

/** Una famiglia vera (chiave di famiglia, registro firmato nell'area comune `test-comune`), con l'ora del NAS dei test. */
class FacesTestFamily(private val clock: FakeFacesClock, val common: MemoryShare = MemoryShare("test-comune")) {
  private val familyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  private val authority = FamilyAuthority(familyKey) { clock.nasNow!! }

  @Volatile lateinit var state: RegistryState
    private set

  init {
    val founder = verified(request(FamilyKey.generate(), "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = common.shareName), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    state = (RegistryVerifier.accept(signed.bytes, null, TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!) as Verdict.Accepted).value
  }

  /** Approva un dispositivo nuovo dell'account [nasUser] e restituisce la sua chiave. */
  fun approve(name: String, nasUser: String): Signer {
    val device = FamilyKey.generate()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clock.nasNow!!, admin = false))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus, frozenHeads: List<String> = emptyList()) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status, frozenHeads))
  }

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nasNow!!)

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91)))) as Verdict.Accepted).value
}
