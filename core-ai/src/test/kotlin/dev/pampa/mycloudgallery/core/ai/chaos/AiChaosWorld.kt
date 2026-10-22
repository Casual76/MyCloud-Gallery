package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.compute.AiComputeConfig
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeEngine
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeParts
import dev.pampa.mycloudgallery.core.ai.compute.AiFreeSpace
import dev.pampa.mycloudgallery.core.ai.compute.AiLocalBodies
import dev.pampa.mycloudgallery.core.ai.compute.AiMediaSources
import dev.pampa.mycloudgallery.core.ai.compute.AiPace
import dev.pampa.mycloudgallery.core.ai.compute.AiPhoneMedia
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.ai.compute.AiUnsupported
import dev.pampa.mycloudgallery.core.ai.compute.CountingEmbedder
import dev.pampa.mycloudgallery.core.ai.compute.DiskAiBuffer
import dev.pampa.mycloudgallery.core.ai.compute.NoPriority
import dev.pampa.mycloudgallery.core.ai.compute.PhonePreparer
import dev.pampa.mycloudgallery.core.ai.compute.SimBytes
import dev.pampa.mycloudgallery.core.ai.compute.SimDevice
import dev.pampa.mycloudgallery.core.ai.compute.SimDone
import dev.pampa.mycloudgallery.core.ai.compute.SimFetcher
import dev.pampa.mycloudgallery.core.ai.compute.SimInputs
import dev.pampa.mycloudgallery.core.ai.compute.SimNas
import dev.pampa.mycloudgallery.core.ai.compute.SimPublisher
import dev.pampa.mycloudgallery.core.ai.compute.SimWorkUnits
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiRoles
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.runBlocking

// Il MONDO di un giro del calcolo: una famiglia sul NAS finto di P5 ([SimNas]: aree private di Anna e Bruno, area comune), il telefono di
// Anna con il vero `AiComputeEngine`, un indice locale con il file dei vettori e il CRC, il vero `DiskAiBuffer`, il vero `LocalModels`, e il
// telefono di Bruno (un `SimDevice` di P5) che lavora sulle stesse unita'. Cio' che sopravvive alla morte del processo sta nei campi del
// mondo; il processo e' `ChaosDevice.restart()`.

/** Un difetto iniettato nei pezzi del calcolo di Anna (`AiChaosMutants`): per default nessuno. */
fun interface AiStackFactory {
  fun wrap(world: AiChaosWorld, parts: AiComputeParts): AiComputeParts

  companion object {
    val REAL = AiStackFactory { _, parts -> parts }
  }
}

/** Il motore delle immagini che non gira se il modello di questa vita non e' stato caricato verificato (invariante 1). */
class GuardedImages(private val inner: ImageEmbedder, private val model: AiChaosModel, private val violations: MutableList<String>) : ImageEmbedder by inner {
  override suspend fun embed(images: List<ModelImage>): List<FloatArray> {
    if (!model.loadedThisLife) violations += "il motore delle immagini ha lavorato senza un modello caricato e verificato"
    return inner.embed(images)
  }
}

class AiChaosWorld(val plan: AiRoundPlan, factory: AiStackFactory = AiStackFactory.REAL) : Closeable {
  val stats = AiRoundStats()
  val nas = SimNas(plan.unitSize)
  val annaPrivate = "test-privata-anna"
  val brunoPrivate = "test-privata-bruno"
  val common = "test-comune"
  val hidden = LinkedHashSet<ContentKey>()
  val unsupportedKeys = LinkedHashSet<ContentKey>()
  val leases = LeaseLog()
  val violations: MutableList<Pair<AiInvariant, String>> = mutableListOf()

  /** Chi vede quali chiavi: Anna le sue due aree, Bruno la sua e la comune. */
  val annaKeys = LinkedHashSet<ContentKey>()
  val phoneKeys = LinkedHashSet<ContentKey>()
  private val bufferDir: File = Files.createTempDirectory("mcg-chaos-ai-buffer").toFile().apply { deleteOnExit() }

  @Volatile var healthy = false
    private set

  val model = AiChaosModel(plan, stats) { !healthy }
  val a = ChaosDevice(this, factory)
  val b = SimDevice(nas, "telefono-bruno", "bruno", listOf(brunoPrivate, common), indexer = true, textReading = false)

  init {
    populate()
    a.restart()
  }

  private fun populate() {
    nas.share(annaPrivate, "anna")
    nas.share(brunoPrivate, "bruno")
    nas.share(common)
    val r = Random(plan.seed + 1)
    val concepts = listOf("cane", "mare", "festa", "bosco", "lago", "pizza")
    val tag = { r.nextInt(concepts.size) }
    var videos = plan.videos
    var bad = plan.badPreviews
    var hide = plan.hidden
    val tags = ArrayList<String>()
    fun add(share: String, forAnna: Boolean): ContentKey {
      val kind = if (videos > 0 && r.nextInt(3) == 0) MediaKind.VIDEO.also { videos-- } else MediaKind.PHOTO
      val isHidden = hide > 0 && r.nextInt(4) == 0
      if (isHidden) hide--
      val concept = concepts[tag()]
      val key = nas.add(share, month = "2024-0${1 + r.nextInt(3)}", kind = kind, concepts = concept, hidden = isHidden)
      // Le etichette delle immagini finte di questo media (la foto, o i quattro fotogrammi del video): per il lettore del testo.
      val base = "$concept ${key.hex.takeLast(4)}"
      tags += base
      if (kind == MediaKind.VIDEO) repeat(4) { tags += "$base f$it" }
      if (isHidden) hidden += key
      if (bad > 0 && kind == MediaKind.PHOTO && r.nextInt(3) == 0) {
        bad--
        nas.of(share).previews[key to SegmentClass.ANALYSIS] = SimBytes.BAD
        unsupportedKeys += key
      }
      if (forAnna) annaKeys += key
      return key
    }
    repeat(plan.commonMedia) { add(common, forAnna = true) }
    repeat(plan.privateMedia) { add(annaPrivate, forAnna = true) }
    repeat(plan.brunoMedia) { add(brunoPrivate, forAnna = false) }
    // Lo stesso contenuto in area privata e comune: una chiave sola (§2.2), una volta sola.
    repeat(plan.sharedBetweenAreas) {
      val key = nas.of(common).media.keys.elementAtOrNull(it) ?: return@repeat
      val media = nas.of(common).media.getValue(key)
      nas.add(annaPrivate, month = media.month, kind = media.kind, source = media.source, concepts = "cane", hidden = media.hidden, key = key, preview = false)
      nas.of(annaPrivate).previews.putAll(nas.of(common).previews.filterKeys { k -> k.first == key })
    }
    repeat(plan.phoneMedia) {
      val key = nas.newKey()
      a.phoneMedia[key] = MediaKind.PHOTO to "${concepts[tag()]} telefono $it"
      tags += a.phoneMedia.getValue(key).second
      phoneKeys += key
    }
    // Il testo di ogni media di Anna: righe vere per il lettore finto (solo le usa se la lettura e' attiva).
    val line = OcrLine(ShortBox(0, 0, 60_000, 5_000), 0, listOf(WordSpan(0, 30_000), WordSpan(30_001, 60_000)), "Totale euro")
    tags.forEach { a.reader.fixture(it, OcrResult(listOf(line), dense = false)) }
  }

  /** Le aree di Anna che hanno questo contenuto (una o due: lo stesso contenuto in area privata e comune). */
  fun sharesOf(key: ContentKey): List<String> = listOf(annaPrivate, common).filter { key in nas.of(it).media }

  /** L'altro dispositivo lavora fino in fondo (senza guasti): prende le unita' scadute e pubblica. */
  fun runOther() {
    // Un millisecondo del NAS passa fra le concessioni di Anna e quelle di Bruno: l'ordine fra le due si legge dall'ora.
    nas.now += 1
    runBlocking { b.engine.runAll(Duration.INFINITE) }
  }

  /** Il banco "rimette a posto" rete e disco: da qui nessun guasto del piano scatta piu'. */
  fun heal() {
    healthy = true
    a.injector.healthy = true
    a.gate.block(common, null)
  }

  val bufferPath: File get() = bufferDir

  override fun close() {
    model.close()
    bufferDir.deleteRecursively()
  }
}

/** Il telefono di Anna: cio' che sopravvive alla morte sta qui, il processo e' ricreato da [restart]. */
class ChaosDevice(private val world: AiChaosWorld, private val factory: AiStackFactory) {
  private val plan = world.plan
  private val nas = world.nas
  val id = "telefono-anna"
  val roles = FakeAiRoles(true, plan.reading, listOf(world.annaPrivate, world.common))
  val gate = MapWriteGate()
  val index = ChaosLocalIndex(plan, world.stats) { !world.healthy }
  val injector = NasFaultInjector(plan, world.stats, nas)
  val simPublisher = SimPublisher(nas, id, gate)
  val publisher = ChaosPublisher(simPublisher, injector, index, world.stats)
  val unsupported = HashSet<Pair<ContentKey, AiClass>>()
  val phoneMedia = LinkedHashMap<ContentKey, Pair<MediaKind, String>>()
  val reader = FakeTextReader(readingHere = { roles.textReadingHere.value })
  private val counting = CountingEmbedder(FakeImageEmbedder()) { clock += 1_000_000_000L }
  private val recipe = AiRecipe.CURRENT
  var clock = 0L
  var checks = 0
    private set

  lateinit var engine: AiComputeEngine
    private set
  lateinit var buffer: DiskAiBuffer
    private set
  lateinit var units: SimWorkUnits
    private set

  val embedded: Int get() = counting.total

  private fun wanted(): Set<AiClass> = if (roles.textReadingHere.value) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)

  private val candidates = object : AiCandidates {
    override suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate> =
      nas.of(share).media.values.sortedByDescending { it.month }.mapNotNull { m ->
        val miss = wanted().filterTo(LinkedHashSet()) { !nas.doneIn(share, m.key, recipe, it) && (m.key to it) !in unsupported }
        if (miss.isEmpty()) null else AiCandidate(m.key, m.kind, m.source, miss)
      }.take(limit)

    override suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate> = phoneMedia.mapNotNull { (k, v) ->
      val miss = wanted() - index.saved(k, recipe) - unsupported.filter { it.first == k }.mapTo(HashSet()) { it.second }
      if (miss.isEmpty()) null else AiCandidate(k, v.first, MediaSource.CAMERA, miss)
    }.take(limit)
  }

  /** Le regole che il calcolo rilegge a ogni lotto: qui il piano fa succedere cose fra un lotto e l'altro. */
  val control = object : AiRunControl {
    override suspend fun check(): AiPace {
      checks++
      val event = if (world.healthy) null else plan.events[checks]
      when (event) {
        CheckEvent.OTHER_TAKES_UNITS -> {
          nas.now += TrustLimits.MAX_LEASE_MILLIS + 1
          world.stats.takeovers++
          world.runOther()
        }
        CheckEvent.BLOCK_COMMON -> {
          gate.block(world.common, WriteBlock.LOCAL_ONLY)
          world.stats.blocks++
        }
        CheckEvent.UNBLOCK_COMMON -> gate.block(world.common, null)
        null -> Unit
      }
      return AiPace.GO
    }
  }

  /** Somma i contatori del processo che finisce (il motore e' ricreato a ogni vita). */
  fun harvest() {
    if (!::engine.isInitialized) return
    world.stats.unitsLost += engine.counters.unitsLost.get()
    world.stats.restaged += engine.counters.restaged.get()
    harvestedEngine = engine
  }

  private var harvestedEngine: AiComputeEngine? = null

  /** Un processo nuovo: il file dei vettori si ripulisce, il buffer si ricontrolla dai file, le unita' prese in memoria sono perse. */
  fun restart() {
    if (::engine.isInitialized && harvestedEngine !== engine) harvest()
    index.recover()
    world.model.restart()
    buffer = DiskAiBuffer(world.bufferPath, AiFreeSpace { Long.MAX_VALUE }, maxBytes = BUFFER_MAX)
    units = SimWorkUnits(nas, id, gate, ::wanted, { recipe }, unsupported, simPublisher)
    val base = AiComputeParts(
      roles = roles,
      units = FaultyUnits(units, injector, world.leases, id, nas),
      priority = NoPriority,
      candidates = candidates,
      buffer = buffer,
      fetcher = FaultyFetcher(SimFetcher(nas, id), injector),
      doneByOthers = FaultyDone(SimDone(nas, id), injector),
      publisher = publisher,
      results = index,
      images = GuardedImages(counting, world.model, world.model.violations),
      reader = { reader },
      inputs = SimInputs(),
      preparer = PhonePreparer(),
      phone = AiPhoneMedia { key, _ ->
        val p = phoneMedia[key] ?: return@AiPhoneMedia PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
        PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(p.second.toByteArray()), p.first, null)) {}
      },
      sources = AiMediaSources { keys -> keys.mapNotNull { k -> nas.shares.values.firstNotNullOfOrNull { it.media[k] }?.let { k to it.source } }.toMap() },
      localBodies = index as AiLocalBodies,
      unsupported = AiUnsupported { key, cls -> unsupported += key to cls },
      freeSpace = AiFreeSpace { Long.MAX_VALUE },
      modelReady = { world.model.ready() },
    )
    val config = AiComputeConfig(batch = plan.batch, reducedBatch = 1, keepAliveEvery = plan.keepAliveEveryNanos.nanoseconds)
    engine = AiComputeEngine(factory.wrap(world, base), recipe, config, nanos = { clock }, pause = { })
  }

  companion object {
    const val BUFFER_MAX = 64L * 1024
  }
}
