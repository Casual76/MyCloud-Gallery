package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.p3.FaultPlan
import dev.pampa.mycloudgallery.core.index.p3.FaultyFileSystem
import dev.pampa.mycloudgallery.core.index.p3.TestDevice
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.p3.TestWorld
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryIndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryOutboxBlobStore
import dev.pampa.mycloudgallery.core.index.sync.DeviceStanding
import dev.pampa.mycloudgallery.core.index.sync.DeviceStandings
import dev.pampa.mycloudgallery.core.index.sync.IndexSyncEngine
import dev.pampa.mycloudgallery.core.index.testing.FakeAiReplicaSink
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers

// Il "mondo" dei test dei dati AI (Fase 6, P2): quello della Fase 4 (p3/P3Support: famiglia vera, share in memoria, orologio del NAS,
// guasti e morti del processo) piu' il catalogo M3 per chiavi di contenuto, i corpi `vec` e `ocr` e, per ogni dispositivo, il
// pubblicatore `ai`, la replica dell'indice con il suo motore di sincronizzazione e la replica `ai` agganciata.

/** Le porte di persistenza dei dati AI sotto prova: di default in memoria; P1/P11 possono rifare le stesse prove su Room. */
object AiTestStores {
  @Volatile var publish: () -> AiPublishStore = { InMemoryAiPublishStore() }

  @Volatile var replica: () -> AiReplicaStore = { InMemoryAiReplicaStore() }
}

/** I corpi di prova: vettori unitari deterministici e righe di testo. */
object AiBodies {
  fun unit(seed: Int, dimension: Int = 768): ShortArray {
    val random = Random(seed)
    val values = FloatArray(dimension) { random.nextFloat() - 0.5f }
    val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    return Float16.encode(FloatArray(dimension) { values[it] / norm })
  }

  fun vec(seed: Int, recipe: Int = 1): VecBody = VecBody(SpaceCode.CURRENT, recipe, VecSource.ANALYSIS, listOf(VecItem(null, unit(seed))))

  fun ocr(text: String, recipe: Int = 1): OcrBody {
    val words = text.split(' ')
    val step = 65_535 / words.size
    val line = OcrLine(ShortBox(0, 0, 60_000, 5_000), 0, words.indices.map { WordSpan(it * step, it * step + step / 2) }, text)
    return OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe, OcrSource.ANALYSIS, listOf(line))
  }

  /** Un vettore quasi uguale a quello di [seed] (stesso contenuto calcolato su un altro dispositivo: coseno ~0,999). */
  fun near(seed: Int, recipe: Int = 1): VecBody {
    val base = Float16.decode(unit(seed))
    val noise = Random(seed + 7_919)
    val values = FloatArray(base.size) { base[it] + (noise.nextFloat() - 0.5f) * 0.001f }
    val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    return VecBody(SpaceCode.CURRENT, recipe, VecSource.ANALYSIS, listOf(VecItem(null, Float16.encode(FloatArray(values.size) { values[it] / norm }))))
  }
}

/** Un file del NAS che registra le letture (per provare che un lettore senza la Fase 6 non apre `ai/`). */
class RecordingFileSystem(private val delegate: NasFileSystem) : NasFileSystem {
  val reads = ArrayList<String>()
  val lists = ArrayList<String>()

  override val shareName: String get() = delegate.shareName

  override fun list(dir: SharePath, limit: Int): NasListing = delegate.list(dir, limit).also { synchronized(lists) { lists += dir.value } }

  override fun read(path: SharePath, maxBytes: Int): NasRead = delegate.read(path, maxBytes).also { synchronized(reads) { reads += path.value } }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = delegate.publishExclusive(path, bytes)
}

/** Il mondo: quello della Fase 4 e il catalogo M3 dei dati AI (le chiavi dei file di ogni share, uguali per tutti i dispositivi). */
class AiWorld(val tmp: File) {
  val world = TestWorld()
  val content = MapAiShareContent()

  val clock get() = world.clock

  /** Un file nuovo nella share, nel catalogo locale (anche per M3 con le chiavi di contenuto). */
  fun file(share: String, path: String, size: Int = 600): TestFile =
    world.file(share, path, size = size).also { content.put(share, it.contentKey) }

  fun device(name: String = "Telefono", account: String = "test"): AiDevice = AiDevice(this, world.device(name, account))

  /** Lo stato dei dispositivi nel registro accettato (E-I15). */
  val standings: DeviceStandings = DeviceStandings { id ->
    when (world.trust.state.registry.devices.firstOrNull { it.id == id }?.status) {
      DeviceStatus.APPROVED -> DeviceStanding.APPROVED
      DeviceStatus.RETIRED -> DeviceStanding.RETIRED
      DeviceStatus.REVOKED -> DeviceStanding.REVOKED
      DeviceStatus.LOST -> DeviceStanding.LOST
      null -> DeviceStanding.UNKNOWN
    }
  }

  /** I byte di tutti i file sotto `devices/<id>/ai/` di una share. */
  fun aiFiles(share: String): Map<String, ByteArray> = world.nas.appFiles(share).filterKeys { "/ai/" in it }
}

/**
 * Un dispositivo dei test dei dati AI: lo stesso firmatario e lo stesso account del [TestDevice] della Fase 4, piu' cio' che
 * sopravvive alla morte del processo (database del pubblicatore `ai`, replica dell'indice, replica `ai`, indice locale finto) e i
 * pezzi che un processo nuovo ricrea ([publisher], [engine]).
 */
class AiDevice(val ai: AiWorld, val base: TestDevice) {
  val id: String get() = base.id
  val identity get() = base.identity
  val share: String = "test-comune"

  var gate: MapWriteGate = MapWriteGate()
  var flags: FeatureFlags = FeatureFlags.RELEASE
  val counters = AiIndexCounters()

  // Pubblicazione `ai` (database) e dell'indice (database e pacco dei blob).
  val publishStore: AiPublishStore = AiTestStores.publish()
  private val indexStore = InMemoryIndexPublishStore()
  private val indexBlobs = InMemoryOutboxBlobStore()

  // Lettura: replica dell'indice (D6, attestazione) e replica `ai` con l'indice locale finto.
  val replicaStore = MemoryReplicaStore()
  val catalog = MemoryReplicaCatalog()
  val replica = LocalReplica(replicaStore, PackDirectory(File(ai.tmp, "packs-$id")), catalog, id)
  val aiStore: AiReplicaStore = AiTestStores.replica()
  val sink = FakeAiReplicaSink()

  /** Quanti corpi questo dispositivo ha calcolato (per "pubblicati dopo, senza ricalcolo", D8). */
  var computed: Int = 0

  fun rules(gateOverride: IndexWriteGate? = null) = IndexWriteRules(flags, gateOverride ?: gate)

  fun publisher(
    plan: FaultPlan? = null,
    store: AiPublishStore = publishStore,
    maxRowsPerFlush: Int = OutboxAiIndexPublisher.DEFAULT_MAX_ROWS_PER_FLUSH,
  ): OutboxAiIndexPublisher = OutboxAiIndexPublisher(
    identity = identity,
    clock = ai.clock,
    fileSystem = { s -> ai.world.nas.share(s).let { if (plan == null) it else FaultyFileSystem(it, plan) } },
    store = store,
    content = ai.content,
    rules = rules(),
    counters = counters,
    locks = ShareLocks(),
    io = Dispatchers.Unconfined,
    maxRowsPerFlush = maxRowsPerFlush,
  )

  /** Il pubblicatore dell'indice, con la replica locale come destinazione dei propri record (D31). */
  fun indexPublisher(): OutboxIndexPublisher = OutboxIndexPublisher(
    identity = identity,
    clock = ai.clock,
    fileSystem = { ai.world.nas.share(it) },
    store = indexStore,
    blobStore = indexBlobs,
    catalog = ai.world.catalog,
    rules = rules(),
    sink = replica,
    locks = ShareLocks(),
    io = Dispatchers.Unconfined,
  )

  fun remote(fs: ((String) -> NasFileSystem)? = null): NasRemoteIndex =
    NasRemoteIndex(fs ?: { ai.world.nas.share(it) }, { ai.world.trust.state }, base.account, Dispatchers.Unconfined)

  fun aiRemote(fs: ((String) -> NasFileSystem)? = null): NasAiRemoteIndex =
    NasAiRemoteIndex(fs ?: { ai.world.nas.share(it) }, { ai.world.trust.state }, base.account, Dispatchers.Unconfined)

  fun shareSync(remote: NasRemoteIndex = remote(), aiRemote: NasAiRemoteIndex = aiRemote(), maxBytesPerShare: Long = AiShareSync.DEFAULT_MAX_BYTES_PER_SHARE): AiShareSync =
    AiShareSync(
      remote = aiRemote,
      store = aiStore,
      attestation = ReplicaAttestation(replicaStore, catalog),
      sink = sink,
      standings = ai.standings,
      counters = counters,
      listDevices = { remote.devices(it) },
      startRound = { aiRemote.startRound() },
      maxBytesPerShare = maxBytesPerShare,
    )

  /** Il motore della Fase 4, con (o senza) l'aggancio delle teste `ai`. */
  fun engine(remote: NasRemoteIndex = remote(), hook: AiSyncHook? = shareSync(remote)): IndexSyncEngine = IndexSyncEngine(
    remote = remote,
    replica = replica,
    store = replicaStore,
    gate = { null },
    standings = ai.standings,
    nasClock = ai.clock,
    ai = hook,
  )

  /** Un giro di sincronizzazione completo (teste `index`, poi `ai`), con un motore nuovo (come un processo nuovo). */
  suspend fun sync(vararg shares: String = arrayOf(share), budget: Duration = Duration.INFINITE): SyncReport {
    val remote = remote()
    return engine(remote).sync(SyncScope(shares.toSet(), setOf(SegmentClass.THUMB_S)), budget)
  }

  /**
   * Questo dispositivo "prepara" [files]: pubblica i loro record `meta` nella testa `index` (sono l'attestazione dei dati AI, D7).
   */
  suspend fun prepare(files: List<TestFile>, share: String = this.share) {
    val publisher = indexPublisher()
    publisher.stage(share, files.map { MetaChange.Put(it.path, it.meta()) }, emptyList())
    check(publisher.flush(share, FlushReason.UNIT_FINISHED) is dev.pampa.mycloudgallery.core.index.contract.PublishOutcome.Published)
  }

  /** Il vettore "calcolato" qui per [file] (conta un calcolo). */
  fun vector(file: TestFile, recipe: Int = 1, seed: Int = file.path.hashCode()): AiEntry.Vector {
    computed++
    return AiEntry.Vector(file.contentKey, AiBodies.vec(seed, recipe))
  }

  /** Il testo "letto" qui in [file] (conta un calcolo). */
  fun text(file: TestFile, text: String = "Scontrino ${file.path.name}", recipe: Int = 1): AiEntry.Text {
    computed++
    return AiEntry.Text(file.contentKey, AiBodies.ocr(text.replace('.', ' '), recipe))
  }

  /** Lo stato dell'indice locale finto: (share, chiave, classe) → (autore, ricetta, impronta del corpo). */
  fun winners(): Map<Triple<String, ContentKey, AiClass>, Triple<String, Int, String>> = sink.applied.associate {
    val bytes = when (val e = it.entry) {
      is AiEntry.Vector -> AiSegments.encodeBody(e.body)
      is AiEntry.Text -> AiSegments.encodeBody(e.body)
    }
    Triple(it.share.lowercase(), it.entry.contentKey, it.entry.cls) to Triple(it.device, it.recipe, AiSegments.digest(bytes))
  }

  /** Le teste `ai` di questo dispositivo sul NAS. */
  fun aiHeads(share: String = this.share): List<Long> =
    ai.world.nas.share(share).list(NasLayout.headDir(id, HeadKind.AI.wire), 10_000).names.mapNotNull(NasLayout::parseVersionName).sorted()
}

/**
 * Cio' che un lettore qualunque vede nelle teste `ai` di [deviceId] (letto da zero con un lettore nuovo): per (classe, chiave) i
 * corpi (impronte) di tutti i segmenti elencati dalla testa piu' recente.
 */
suspend fun aiReaderState(ai: AiWorld, share: String, deviceId: String): Map<AiKey, Set<String>> {
  val remote = NasAiRemoteIndex({ ai.world.nas.share(it) }, { ai.world.trust.state }, "test", Dispatchers.Unconfined)
  val head = when (val r = remote.latestHead(share, deviceId, null)) {
    is dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Ok ->
      (r.value as? dev.pampa.mycloudgallery.core.index.contract.HeadFetch.Accepted)?.head ?: return emptyMap()
    is dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Failed -> error("lettura fallita: ${r.failure}")
  }
  val out = HashMap<AiKey, MutableSet<String>>()
  for (segment in head.segments) {
    val content = when (val r = remote.segment(head, segment)) {
      is dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Ok -> (r.value as? AiSegmentFetch.Ok)?.content ?: error("segmento non valido: ${r.value}")
      is dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Failed -> error("lettura fallita: ${r.failure}")
    }
    content.entries.forEach { out.getOrPut(AiKey(it.contentKey, content.cls)) { HashSet() } += it.digest }
  }
  return out
}

/** L'impronta del corpo di una voce (come la pubblica il formato). */
fun AiEntry.digest(): String = when (this) {
  is AiEntry.Vector -> AiSegments.digest(AiSegments.encodeBody(body))
  is AiEntry.Text -> AiSegments.digest(AiSegments.encodeBody(body))
}

val AiEntry.key: AiKey get() = AiKey(contentKey, cls)
