package dev.pampa.mycloudgallery.core.index.p3

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.FencedFlags
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.OwnIndexSink
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.MapShareCatalog
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.TestStores
import dev.pampa.mycloudgallery.core.index.store.IndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryIndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryOutboxBlobStore
import dev.pampa.mycloudgallery.core.index.store.OutboxBlobStore
import dev.pampa.mycloudgallery.core.index.store.OutboxSnapshot
import dev.pampa.mycloudgallery.core.index.store.PublishCommit
import dev.pampa.mycloudgallery.core.index.store.StagedBatch
import dev.pampa.mycloudgallery.core.index.store.StagedBlob
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
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
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers

// Il "mondo" dei test di P3: famiglia vera (chiavi, registro firmato), share in memoria con la semantica di
// NasFileSystem, orologio del NAS finto, guasti e morti del processo programmabili. Niente rete, niente dispositivo.

/** 14/10/2026 12:00 UTC. */
const val NAS_NOW: Long = 1_792_065_600_000L

class TestClock(var now: Long = NAS_NOW) : NasClock {
  override fun nowMillis(): Long = now

  fun advance(millis: Long) {
    now += millis
  }
}

/** Le share del NAS finto: nomi senza maiuscole, come Samba. */
class TestNas {
  private val shares = HashMap<String, InMemoryNasFileSystem>()

  fun share(name: String): InMemoryNasFileSystem = synchronized(shares) { shares.getOrPut(name.lowercase(Locale.ROOT)) { InMemoryNasFileSystem(name) } }

  /** Tutti i byte dei file sotto `.mycloudgallery/` di una share (indice, prenotazioni, registro: per cercarci dentro). */
  fun appFiles(share: String): Map<String, ByteArray> {
    val fs = share(share)
    val out = LinkedHashMap<String, ByteArray>()
    fun walk(dir: SharePath, depth: Int) {
      for (name in fs.list(dir, 1_000_000).names) {
        val child = dir.child(name)
        when (val read = fs.read(child, 64 * 1024 * 1024)) {
          is NasRead.Data -> out[child.value] = read.bytes
          NasRead.NotFound -> if (depth < 12) walk(child, depth + 1)
          NasRead.TooLarge -> error("file enorme nella share di prova")
        }
      }
    }
    walk(SharePath.of(".mycloudgallery"), 0)
    return out
  }
}

/** Una famiglia di prova con il registro firmato nell'area comune `test-comune`. */
class TestTrust(val clock: TestClock, val nas: TestNas, val commonShare: String = "test-comune") {
  private val familyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  private val authority = FamilyAuthority(familyKey, clock)

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
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clock.nowMillis()))
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
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nowMillis())

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(publicKeyOf(bytes))) as Verdict.Accepted).value

  private fun publicKeyOf(bytes: ByteArray) = dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91))
}

/** La morte del processo: un `Error`, cosi' nessun `catch` del codice di produzione la intercetta. */
class ProcessDeath : Error("morte simulata del processo")

/**
 * Il piano dei guasti condiviso da NAS, database, pacco e replica: ogni chiamata e' un passo; al passo [dieAt] il
 * processo muore prima o dopo che la chiamata abbia avuto effetto ([afterEffect]). [nasError] fa invece fallire le
 * chiamate al NAS con un errore (una volta, al passo indicato).
 */
class FaultPlan {
  var step = 0
  var dieAt: Int? = null
  var afterEffect = false
  var died = false
  var errorAt: Int? = null
  var error: NasError = NasError.Timeout

  /** Le chiamate al NAS che falliscono con [error] quando il percorso soddisfa la condizione (per DISK_FULL mirati). */
  var failWhen: ((String, SharePath) -> NasError?)? = null

  fun <T> around(block: () -> T): T {
    step++
    val here = step
    if (here == dieAt && !afterEffect) {
      died = true
      dieAt = null
      throw ProcessDeath()
    }
    if (here == errorAt) {
      errorAt = null
      throw NasIoException(error)
    }
    val result = block()
    if (here == dieAt && afterEffect) {
      died = true
      dieAt = null
      throw ProcessDeath()
    }
    return result
  }

  suspend fun <T> aroundSuspend(block: suspend () -> T): T {
    step++
    val here = step
    if (here == dieAt && !afterEffect) {
      died = true
      dieAt = null
      throw ProcessDeath()
    }
    val result = block()
    if (here == dieAt && afterEffect) {
      died = true
      dieAt = null
      throw ProcessDeath()
    }
    return result
  }
}

class FaultyFileSystem(private val delegate: NasFileSystem, private val plan: FaultPlan) : NasFileSystem {
  override val shareName: String get() = delegate.shareName

  override fun list(dir: SharePath, limit: Int): NasListing = plan.around {
    plan.failWhen?.invoke("list", dir)?.let { throw NasIoException(it) }
    delegate.list(dir, limit)
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead = plan.around {
    plan.failWhen?.invoke("read", path)?.let { throw NasIoException(it) }
    delegate.read(path, maxBytes)
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = plan.around {
    plan.failWhen?.invoke("publish", path)?.let { throw NasIoException(it) }
    delegate.publishExclusive(path, bytes)
  }
}

class DyingStore(private val delegate: IndexPublishStore, private val plan: FaultPlan) : IndexPublishStore by delegate {
  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<StagedBlob>): StagedBatch =
    plan.aroundSuspend { delegate.stage(share, changes, blobs) }

  override suspend fun markSunk(share: String, recordIds: Set<Long>, blobIds: Set<Long>) =
    plan.aroundSuspend { delegate.markSunk(share, recordIds, blobIds) }

  override suspend fun discard(share: String, recordIds: Set<Long>, blobIds: Set<Long>) =
    plan.aroundSuspend { delegate.discard(share, recordIds, blobIds) }

  override suspend fun commit(share: String, commit: PublishCommit) = plan.aroundSuspend { delegate.commit(share, commit) }

  override suspend fun outbox(share: String): OutboxSnapshot = plan.aroundSuspend { delegate.outbox(share) }

  override suspend fun pending(): PendingCounts = plan.aroundSuspend { delegate.pending() }
}

class DyingBlobs(private val delegate: OutboxBlobStore, private val plan: FaultPlan) : OutboxBlobStore by delegate {
  override suspend fun write(share: String, cls: SegmentClass, bytes: ByteArray): String = plan.aroundSuspend { delegate.write(share, cls, bytes) }

  override suspend fun delete(handles: Collection<String>) = plan.aroundSuspend { delegate.delete(handles) }

  override suspend fun retainOnly(referenced: Set<String>) = plan.aroundSuspend { delegate.retainOnly(referenced) }
}

class DyingSink(private val delegate: OwnIndexSink, private val plan: FaultPlan) : OwnIndexSink {
  override suspend fun applyOwn(share: String, records: List<MetaRecord>, blobs: List<BlobPut>) =
    plan.aroundSuspend { delegate.applyOwn(share, records, blobs) }
}

/** Immagini minime con intestazioni vere (il codec legge solo le dimensioni). [marker] finisce nei byte. */
object Images {
  fun jpeg(width: Int = 200, height: Int = 150, marker: ByteArray = ByteArray(8)): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8))
    out.write(byteArrayOf((height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), 1, 1, 0x11, 0))
    out.write(marker)
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  fun webp(width: Int = 400, height: Int = 300, marker: ByteArray = ByteArray(8)): ByteArray {
    val payload = byteArrayOf(0x10, 0x02, 0x00, 0x9D.toByte(), 0x01, 0x2A) + le16(width) + le16(height) + marker.let { if (it.size % 2 == 1) it + 0 else it }
    val chunk = "VP8 ".toByteArray() + le32(payload.size) + payload
    return "RIFF".toByteArray() + le32(chunk.size + 4) + "WEBP".toByteArray() + chunk
  }

  private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

  private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
}

/** Un file di una share del NAS finto: percorso, contenuto (unico), timbro, e il record che un preparatore pubblicherebbe. */
class TestFile(val share: String, val path: SharePath, val content: ByteArray, val mtime: Long = NAS_NOW - 86_400_000L) {
  val stamp: NasStamp get() = NasStamp(content.size.toLong(), mtime, fileId = null)

  val mediaId: MediaId = MediaId.random()
  val versionId: VersionId = VersionId.random()

  private val quickHash = QuickHashAlgorithm.ofContent(content)

  fun meta(prepared: Set<SegmentClass> = emptySet()): MetaFile = MetaFile(
    stamp = stamp,
    quickHash = quickHash,
    mediaId = mediaId,
    versionId = versionId,
    kind = MediaKind.PHOTO,
    source = MediaSource.CAMERA,
    mime = "image/jpeg",
    prepared = SegmentClass.maskOf(prepared),
  )

  val contentKey: ContentKey = ContentKey.of(quickHash, content.size.toLong())

  fun put(prepared: Set<SegmentClass> = emptySet()) = MetaChange.Put(path, meta(prepared))

  /** Una miniatura che porta nei byte un pezzo unico del file (per cercarla nell'indice). */
  fun thumb(cls: SegmentClass = SegmentClass.THUMB_S): BlobPut.Image =
    BlobPut.Image(cls, contentKey, if (cls == SegmentClass.ANALYSIS) Images.jpeg(marker = marker()) else Images.webp(marker = marker()))

  fun marker(): ByteArray = content.copyOf(16)

  companion object {
    fun random(share: String, path: String, random: Random, size: Int = 600): TestFile =
      TestFile(share, SharePath.parseMediaPath(path), ByteArray(size).also { random.nextBytes(it) })
  }
}

/** Un dispositivo di prova: identita', outbox persistente (sopravvive al pubblicatore), replica locale, lettore. */
class TestDevice(
  val world: TestWorld,
  val signer: Signer,
  val account: String = "test",
  val store: IndexPublishStore = TestStores.publish(),
  val blobs: InMemoryOutboxBlobStore = InMemoryOutboxBlobStore(),
  val replica: InMemoryReplica = InMemoryReplica(TrustIds.deviceIdOf(signer.publicKey)),
  var gate: MapWriteGate = MapWriteGate(),
  var flags: FeatureFlags = FencedFlags.of(),
) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)
  val identity = IndexIdentity(signer, account) { world.trust.state }
  val locks = ShareLocks()

  fun publisher(
    plan: FaultPlan? = null,
    gateOverride: IndexWriteGate? = null,
    maxBlobBytesPerFlush: Long = OutboxIndexPublisher.DEFAULT_MAX_BLOB_BYTES_PER_FLUSH,
  ): OutboxIndexPublisher = OutboxIndexPublisher(
    identity = identity,
    clock = world.clock,
    fileSystem = { share -> world.nas.share(share).let { if (plan == null) it else FaultyFileSystem(it, plan) } },
    store = if (plan == null) store else DyingStore(store, plan),
    blobStore = if (plan == null) blobs else DyingBlobs(blobs, plan),
    catalog = world.catalog,
    rules = IndexWriteRules(flags, gateOverride ?: gate),
    sink = if (plan == null) replica else DyingSink(replica, plan),
    locks = ShareLocks(),
    io = Dispatchers.Unconfined,
    maxBlobBytesPerFlush = maxBlobBytesPerFlush,
  )

  fun remote(): NasRemoteIndex = world.reader(account)
}

/** Il mondo: NAS, orologio, famiglia, catalogo locale (condiviso: stessi file per tutti i "dispositivi"). */
class TestWorld(val clock: TestClock = TestClock(), val nas: TestNas = TestNas()) {
  val trust = TestTrust(clock, nas)
  val catalog = MapShareCatalog()

  fun device(name: String = "Telefono", account: String = "test"): TestDevice = TestDevice(this, trust.approveNewDevice(name, account), account)

  fun reader(account: String = "test"): NasRemoteIndex =
    NasRemoteIndex({ nas.share(it) }, { trust.state }, account, Dispatchers.Unconfined)

  /** Un file nuovo nella share, gia' nel catalogo locale (come dopo una scansione). */
  fun file(share: String, path: String, random: Random = Random(path.hashCode()), size: Int = 600): TestFile =
    TestFile.random(share, path, random, size).also { catalog.put(share, it.path, it.stamp, it.meta().quickHash) }
}

/**
 * Lo stato di un dispositivo in una share **visto da chi legge** (D5): la testa piu' recente accettata, poi per ogni
 * percorso il record con il `seq` piu' alto (le lapidi tolgono) e per ogni (classe, chiave) i byte dei blob.
 */
class ReaderState(
  val headVersion: Long?,
  val records: Map<String, MetaRecord.Present>,
  val blobs: Map<Pair<SegmentClass, ContentKey>, Set<String>>,
  val segments: Int,
) {
  companion object {
    suspend fun read(world: TestWorld, share: String, deviceId: String): ReaderState {
      val remote = world.reader()
      val head: IndexHeadView = when (val r = remote.latestHead(share, deviceId, null)) {
        is RemoteResult.Ok -> (r.value as? HeadFetch.Accepted)?.head ?: return ReaderState(null, emptyMap(), emptyMap(), 0)
        is RemoteResult.Failed -> error("lettura fallita: ${r.failure}")
      }
      val byPath = HashMap<String, MetaRecord>()
      val blobs = HashMap<Pair<SegmentClass, ContentKey>, MutableSet<String>>()
      for (segment in head.segments) {
        val content = when (val r = remote.segment(head, segment)) {
          is RemoteResult.Ok -> (r.value as? SegmentFetch.Ok)?.content ?: error("segmento non valido: ${r.value}")
          is RemoteResult.Failed -> error("lettura fallita: ${r.failure}")
        }
        when (content) {
          is SegmentContent.Meta -> content.records.forEach { record ->
            val key = IndexKeys.metaHex(record.path)
            val old = byPath[key]
            check(old == null || old.seq != record.seq) { "due record con lo stesso seq" }
            if (old == null || old.seq < record.seq) byPath[key] = record
          }
          is SegmentContent.Blobs -> content.entries.forEach { e ->
            blobs.getOrPut(content.cls to e.contentKey) { HashSet() } +=
              content.bytes.copyOfRange(e.offset, e.offset + e.length).joinToString("") { "%02x".format(it) }
          }
        }
      }
      return ReaderState(head.version, byPath.filterValues { it is MetaRecord.Present }.mapValues { it.value as MetaRecord.Present }, blobs, head.segments.size)
    }
  }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** I numeri di versione delle teste proprie presenti sul NAS. */
fun headVersions(world: TestWorld, share: String, deviceId: String): List<Long> =
  world.nas.share(share).list(dev.pampa.mycloudgallery.core.trust.nas.NasLayout.headDir(deviceId, "index"), 10_000).names
    .mapNotNull(dev.pampa.mycloudgallery.core.trust.nas.NasLayout::parseVersionName).sorted()
