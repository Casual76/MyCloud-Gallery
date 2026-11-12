package dev.pampa.mycloudgallery.core.personal

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.remote.NasPersonalRemote
import dev.pampa.mycloudgallery.core.personal.remote.PersonalHeadPublished
import dev.pampa.mycloudgallery.core.personal.remote.PersonalRemote
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.NoProjection
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.sync.NasPersonalSync
import dev.pampa.mycloudgallery.core.personal.sync.SyncLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
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
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers

// Il "mondo" dei test di P13: una famiglia vera (chiavi, registro firmato), le share del NAS in memoria con la semantica di
// NasFileSystem, un orologio del NAS finto con uno scarto per dispositivo (orologi sfasati) e i dispositivi che ci lavorano con
// lo store, il lettore e il motore di sincronizzazione VERI. Niente rete, niente dispositivo, niente NAS.

/** 14/10/2026 12:00 UTC. */
const val NAS_NOW: Long = 1_792_065_600_000L

/** Un media: l'id e' un UUID canonico come quelli del catalogo. */
fun mediaId(n: Int): String = java.util.UUID(0x1234_5678_9abc_def0L, n.toLong()).toString()

/** L'orologio del NAS, uno per tutti: ogni dispositivo ci somma il proprio scarto. */
class TestClock(var now: Long = NAS_NOW) {
  fun advance(millis: Long) {
    now += millis
  }
}

/** Le share del NAS finto: nomi senza maiuscole, come Samba. */
class TestNas {
  private val shares = HashMap<String, InMemoryNasFileSystem>()

  fun share(name: String): InMemoryNasFileSystem =
    synchronized(shares) { shares.getOrPut(name.lowercase(Locale.ROOT)) { InMemoryNasFileSystem(name) } }
}

/** Una famiglia di prova con il registro firmato nell'area comune. */
class TestTrust(private val clock: TestClock, private val nas: TestNas, val commonShare: String = COMMON) {
  private val familyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  private val authority = FamilyAuthority(familyKey) { clock.now }

  @Volatile lateinit var state: RegistryState
    private set

  init {
    val admin = FamilyKey.generate()
    val founder = verified(request(admin, "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = commonShare), founder)
    check(FamilyAuthority.publishFounding(nas.share(commonShare), signed) == PublishResult.CREATED)
    val anchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!
    state = (RegistryVerifier.accept(signed.bytes, null, anchor) as Verdict.Accepted).value
  }

  fun approveNewDevice(name: String = "Telefono", nasUser: String = "test"): Signer {
    val device = FamilyKey.generate()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clock.now))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status))
  }

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(nas.share(commonShare), draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.now)

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(publicKeyOf(bytes))) as Verdict.Accepted).value

  private fun publicKeyOf(bytes: ByteArray) = EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91))

  companion object {
    const val COMMON = "test-comune"
  }
}

/** Una share che conta le richieste (per provare che un giro "solo locale" non tocca il NAS) e puo' farle fallire. */
class CountingFileSystem(private val delegate: NasFileSystem) : NasFileSystem {
  override val shareName: String get() = delegate.shareName

  var lists = 0
    private set
  var reads = 0
    private set
  var publishes = 0
    private set

  val requests: Int get() = lists + reads + publishes

  /** Fa fallire le richieste che soddisfano la condizione (operazione, percorso). */
  var failWhen: ((String, SharePath) -> NasError?)? = null

  /**
   * Il piano dei guasti (come `FaultPlan` di P3): alla richiesta numero [faultAt] (contata da [armFault]) il NAS cade PRIMA di
   * applicarla, o DOPO ([faultAfter]: l'ha applicata ma chi chiama non lo sa); con [faultDeath] e' la morte del processo (un
   * `Error`, che nessun `catch` del codice di produzione intercetta), altrimenti un errore di rete.
   */
  var faultAt = 0
  var faultAfter = false
  var faultDeath = false
  private var counted = 0

  /** Richieste fatte da quando e' armato (e prima che scatti il guasto). */
  val faultCalls: Int get() = counted

  fun armFault(at: Int, after: Boolean, death: Boolean) {
    faultAt = at
    faultAfter = after
    faultDeath = death
    counted = 0
  }

  fun disarmFault() {
    faultAt = 0
  }

  private fun fault(): Throwable = if (faultDeath) ProcessDeath() else NasIoException(NasError.Timeout)

  private fun <T> around(op: String, path: SharePath, block: () -> T): T {
    failWhen?.invoke(op, path)?.let { throw NasIoException(it) }
    counted++
    val here = counted
    if (faultAt != 0 && here == faultAt && !faultAfter) throw fault()
    val result = block()
    if (faultAt != 0 && here == faultAt && faultAfter) throw fault()
    return result
  }

  override fun list(dir: SharePath, limit: Int): NasListing {
    lists++
    return around("list", dir) { delegate.list(dir, limit) }
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead {
    reads++
    return around("read", path) { delegate.read(path, maxBytes) }
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    publishes++
    return around("publish", path) { delegate.publishExclusive(path, bytes) }
  }
}

/** La morte del processo: un `Error`, cosi' nessun `catch` del codice di produzione la intercetta. */
class ProcessDeath : Error("morte simulata del processo")

/** Il mondo: orologio, NAS e famiglia. Le aree private sono `test` e `test2` (un account ciascuna). */
class PersonalWorld {
  val clock = TestClock()
  val nas = TestNas()
  val trust = TestTrust(clock, nas)

  private val counting = HashMap<String, CountingFileSystem>()

  /** La share vista da un dispositivo: sempre la stessa istanza che conta, sopra quella in memoria. */
  fun fs(name: String): CountingFileSystem =
    synchronized(counting) { counting.getOrPut(name.lowercase(Locale.ROOT)) { CountingFileSystem(nas.share(name)) } }

  /** Una share privata per account, come sul NAS vero: `alessio`, `pampa`... */
  fun privateShare(account: String): String = account

  fun device(
    account: String = "test",
    skewMillis: Long = 0,
    projection: PersonalProjection = NoProjection,
    ledger: PersonalLedger = InMemoryPersonalLedger(),
    share: String = privateShare(account),
    signer: Signer = trust.approveNewDevice(name = "Telefono $account", nasUser = account),
    gate: MapWriteGate = MapWriteGate(),
    flags: FeatureFlags = FencedFlags.of(),
    limits: SyncLimits = SyncLimits(),
  ): PersonalDevice = PersonalDevice(this, signer, account, skewMillis, projection, ledger, share, gate, flags, limits)
}

/** Un dispositivo: identita', orologio (con il suo scarto), store con il suo registro, lettore e motore. */
class PersonalDevice(
  val world: PersonalWorld,
  val signer: Signer,
  val account: String,
  /** Quanto il NAS "sembra" avanti a questo dispositivo (ancora presa a una NEGOTIATE diversa). */
  var skewMillis: Long,
  val projection: PersonalProjection,
  val ledger: PersonalLedger,
  val share: String,
  val gate: MapWriteGate,
  val flags: FeatureFlags,
  val limits: SyncLimits = SyncLimits(),
) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)
  val identity = IndexIdentity(signer, account) { world.trust.state }

  /** L'ora del NAS come la vede questo dispositivo; null = nessuna ancora (avvio senza NAS). */
  var anchored: Boolean = true

  fun nasNow(): Long = world.clock.now + skewMillis

  val store = LedgerPersonalStore(id, { if (anchored) nasNow() else null }, ledger, projection)
  val remote: PersonalRemote = NasPersonalRemote(share, { world.fs(it) }, identity, Dispatchers.Unconfined)
  /** D31 e il cancello di :app, come li compone P11 per l'indice. */
  private val writeRules = IndexWriteRules(flags, gate)

  val sync = NasPersonalSync(
    share = share,
    account = account,
    remote = remote,
    store = store,
    identity = identity,
    clock = NasClock { if (anchored) nasNow() else throw NasIoException(NasError.Timeout) },
    gate = IndexWriteGate { share -> writeRules.blockFor(share) },
    monotonicMillis = ::monotonic,
    limits = limits,
  )

  /** Il tempo monotono di prova: avanza solo quando il test lo dice, cosi' i budget sono esatti. */
  var monotonicNow: Long = 0
  var monotonicStep: Long = 0

  private fun monotonic(): Long {
    monotonicNow += monotonicStep
    return monotonicNow
  }

  suspend fun favorite(media: String, on: Boolean = true) =
    store.set(PersonalKey(PersonalKind.MEDIA_FLAG, media, PersonalFields.FAVORITE), PersonalValue.Flag(on))

  suspend fun hide(media: String, on: Boolean = true) =
    store.set(PersonalKey(PersonalKind.MEDIA_FLAG, media, PersonalFields.HIDDEN), PersonalValue.Flag(on))

  fun block(block: WriteBlock?) = gate.block(share, block)

  /** Le teste `personal` di questo dispositivo sulla sua share. */
  fun headFiles(): List<String> = world.nas.share(share).list(NasLayout.headDir(id, "personal"), 10_000).names.sorted()

  suspend fun syncOnce(budget: kotlin.time.Duration = 5.minutes) = sync.sync(budget)
}

fun flagKey(media: String, field: String): PersonalKey =
  PersonalKey(PersonalKind.MEDIA_FLAG, media, field)

fun entry(
  key: PersonalKey,
  at: Long,
  author: String,
  on: Boolean = true,
  fingerprint: PersonalFingerprint? = null,
): PersonalEntry = PersonalEntry(key, at, author, PersonalValue.Flag(on), fingerprint)

/** Un percorso della share per i test delle esclusioni. */
fun path(raw: String): SharePath = SharePath.parse(raw)

/** Chi scrive quello che vuole nella share che vuole: un dispositivo approvato compromesso (L29), o il PC con la password dell'account. */
class Forger(private val world: PersonalWorld, val signer: Signer, val account: String) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)
  private val identity = IndexIdentity(signer, account) { world.trust.state }

  /** Una testa vera (firmata bene, nel posto giusto) con questi segmenti. */
  suspend fun publishState(share: String, entries: List<PersonalEntry>, after: Long = 0): RemoteResult<PersonalHeadPublished> =
    NasPersonalRemote(share, { world.nas.share(it) }, identity, Dispatchers.Unconfined)
      .publish(PersonalSegments.plan(entries), emptySet(), after, world.clock.now)

  /** Una testa firmata bene che elenca segmenti qualunque (anche inesistenti o spazzatura). */
  fun publishHead(share: String, version: Long, segments: List<SegmentRef>) {
    val registry = world.trust.state.registry
    val head = DeviceHead(
      registry.familyId, id, share, HeadKind.PERSONAL, version, registry.version, world.clock.now,
      segments.map { ClassedSegment(SegmentClass.PERSONAL, it) }.sortedBy { it.ref.hash },
    )
    world.nas.share(share).publishExclusive(NasLayout.headFile(id, "personal", version), head.sign(signer))
  }

  /** Un file di segmento a mano, con il nome che dice di essere. */
  fun putSegment(share: String, hash: String, bytes: ByteArray) {
    world.nas.share(share).put(NasLayout.segmentFile(id, "personal", hash), bytes)
  }

  fun entry(key: PersonalKey, on: Boolean = true, at: Long = world.clock.now - 1_000, author: String = id): PersonalEntry =
    PersonalEntry(key, at, author, PersonalValue.Flag(on))
}
