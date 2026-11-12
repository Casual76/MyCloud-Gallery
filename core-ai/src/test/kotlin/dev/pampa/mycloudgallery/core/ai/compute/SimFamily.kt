package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiBuffer
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiResults
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiRoles
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody

/** Il motore delle immagini che conta le immagini calcolate per contenuto (byte) e fa avanzare l'orologio finto. */
class CountingEmbedder(private val inner: FakeImageEmbedder = FakeImageEmbedder(), private val tick: () -> Unit = {}) : ImageEmbedder {
  val perImage = HashMap<String, Int>()

  /** Pacchetto X: l'ordine con cui le immagini sono state calcolate (i loro byte come testo). */
  val order = ArrayList<String>()
  override val backend get() = inner.backend

  /** Revisione A (IX-7): gli errori del motore delle prossime chiamate, in ordine (vuoto = nessuno). */
  val failNext = ArrayDeque<dev.pampa.mycloudgallery.core.ai.contract.AiEngineError>()

  override suspend fun embed(images: List<ModelImage>): List<FloatArray> {
    failNext.removeFirstOrNull()?.let { throw dev.pampa.mycloudgallery.core.ai.contract.AiEngineException(it) }
    images.forEach {
      val k = String(it.jpeg, Charsets.UTF_8)
      perImage[k] = (perImage[k] ?: 0) + 1
      order += k
      tick()
    }
    return inner.embed(images)
  }

  override suspend fun release() = inner.release()

  val total: Int get() = perImage.values.sum()

  val fake: FakeImageEmbedder get() = inner
}

/** Il preparatore finto per i media del telefono: dai byte del telefono un'anteprima di analisi (o i fotogrammi). */
class PhonePreparer : Preparer {
  var calls = 0
    private set

  override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
    calls++
    val bytes = (source as PrepareSource.Bytes).bytes.read(0L until source.bytes.size)
    val out = if (SegmentClass.FRAMES in spec.classes) {
      PrepOutput.Frames(FramesBlob.split(SimBytes.preview(MediaKind.VIDEO, String(bytes)))!!)
    } else {
      PrepOutput.Image(SegmentClass.ANALYSIS, "FAKE:${String(bytes)}".toByteArray(), 1024, 768)
    }
    return PrepResult.Done(listOf(out), null, null, null)
  }
}

/**
 * Un dispositivo della famiglia sul NAS finto: cio' che sopravvive alla morte del processo (risultati, outbox e voci in attesa,
 * buffer, segni "non supportato", telefono) e i pezzi di un processo ([engine], unita' prese) ricreati da [restart].
 *
 * @param areas le aree di questo dispositivo (D14): la privata del suo account e la comune.
 */
class SimDevice(
  val nas: SimNas,
  val id: String,
  val account: String,
  areas: List<String>,
  indexer: Boolean = true,
  textReading: Boolean = false,
  var buffer: AiBuffer = FakeAiBuffer(),
) {
  val roles = FakeAiRoles(indexer, textReading, areas)
  val gate = MapWriteGate()
  val results = FakeAiResults()
  val publisher = SimPublisher(nas, id, gate)
  val unsupported = HashSet<Pair<ContentKey, AiClass>>()
  val phoneMedia = LinkedHashMap<ContentKey, Pair<MediaKind, String>>()
  val preparer = PhonePreparer()
  var recipe: AiRecipe = AiRecipe.CURRENT

  /** Fase 8 (D17): chi tiene ancora le anteprime del buffer (di serie nessuno, come nella Fase 6). */
  var keepers: dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers = dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers.NONE

  /** Pacchetto X: le urgenze ("Analizza adesso") di questo dispositivo: l'insieme e le chiavi uscite con `done`. */
  val urgentKeys = LinkedHashSet<ContentKey>()
  val urgentDone = ArrayList<ContentKey>()
  private val urgentPort = object : AiUrgentKeys {
    override fun urgent(): Set<ContentKey> = urgentKeys.toSet()

    override fun done(key: ContentKey) {
      if (urgentKeys.remove(key)) urgentDone += key
    }
  }
  var freeBytes: Long = Long.MAX_VALUE
  var clock = 0L
  val images = CountingEmbedder(FakeImageEmbedder()) { clock += 1_000_000_000L }
  val reader = FakeTextReader(readingHere = { roles.textReadingHere.value })
  val fetcher = SimFetcher(nas, id)

  /** Quante volte il calcolo ha chiesto il lettore del testo (il provider): con la lettura spenta deve restare 0. */
  var readerAsked: Int = 0
    private set

  lateinit var units: SimWorkUnits
    private set
  lateinit var engine: AiComputeEngine
    private set

  private fun wanted(): Set<AiClass> =
    if (roles.textReadingHere.value) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)

  /** Il catalogo locale per il calcolo solo-buffer e il telefono (P1): cio' che manca qui, dal piu' recente. */
  val candidates = object : AiCandidates {
    override suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate> {
      nas.touch(id, share)
      val s = nas.of(share)
      return s.media.values.sortedByDescending { it.month }.mapNotNull { m ->
        val miss = wanted().filterTo(LinkedHashSet()) { !nas.doneIn(share, m.key, recipe, it) && (m.key to it) !in unsupported }
        if (miss.isEmpty()) null else AiCandidate(m.key, m.kind, m.source, miss)
      }.take(limit)
    }

    override suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate> = phoneMedia.mapNotNull { (k, v) ->
      val miss = wanted() - results.saved(k, recipe)
      if (miss.isEmpty()) null else AiCandidate(k, v.first, MediaSource.CAMERA, miss)
    }.take(limit)
  }

  /** Revisione A (R5): risultati e corpi che ricordano la provenienza, al posto di quelli finti (null = quelli finti). */
  var resultsOverride: dev.pampa.mycloudgallery.core.ai.contract.AiResults? = null
  var bodiesOverride: AiLocalBodies? = null

  /** La morte del processo **dopo** il salvataggio numero [dieAfterSave] (contati dall'inizio del mondo); -1 = mai. */
  var dieAfterSave: Int = -1
  var saves: Int = 0
    private set

  private val crashing = object : dev.pampa.mycloudgallery.core.ai.contract.AiResults {
    override suspend fun saveVectors(key: ContentKey, body: VecBody) {
      results.saveVectors(key, body)
      afterSave()
    }

    override suspend fun saveText(key: ContentKey, body: OcrBody) {
      results.saveText(key, body)
      afterSave()
    }

    override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = results.saved(key, recipe)

    private fun afterSave() {
      saves++
      if (saves == dieAfterSave) throw dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath()
    }
  }

  private val localBodies = object : AiLocalBodies {
    override suspend fun vectors(key: ContentKey): VecBody? = results.vectors[key]

    override suspend fun text(key: ContentKey): OcrBody? = results.texts[key]
  }

  /** Un processo nuovo (dopo una morte): stessi dati su disco, unita' e motore ricreati. */
  fun restart(config: AiComputeConfig = AiComputeConfig.DEFAULT, pauses: MutableList<kotlin.time.Duration>? = null) {
    units = SimWorkUnits(nas, id, gate, ::wanted, { recipe }, unsupported, publisher)
    val parts = AiComputeParts(
      roles = roles,
      units = units,
      priority = NoPriority,
      candidates = candidates,
      buffer = buffer,
      fetcher = fetcher,
      doneByOthers = SimDone(nas, id),
      publisher = publisher,
      results = resultsOverride ?: crashing,
      images = images,
      reader = {
        readerAsked++
        reader
      },
      inputs = SimInputs(),
      preparer = preparer,
      phone = AiPhoneMedia { key, _ ->
        val p = phoneMedia[key] ?: return@AiPhoneMedia PrepOpen.Unavailable(dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode.SOURCE_CHANGED)
        PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(p.second.toByteArray()), p.first, null)) {}
      },
      sources = AiMediaSources { keys -> keys.mapNotNull { k -> nas.shares.values.firstNotNullOfOrNull { it.media[k] }?.let { k to it.source } }.toMap() },
      localBodies = bodiesOverride ?: localBodies,
      unsupported = AiUnsupported { key, cls -> unsupported += key to cls },
      freeSpace = AiFreeSpace { freeBytes },
      keepers = keepers,
      urgent = urgentPort,
    )
    engine = AiComputeEngine(parts, recipe, config, nanos = { clock }, pause = { pauses?.add(it) })
  }

  // Dopo tutte le proprieta' (l'ordine di inizializzazione conta).
  init {
    restart()
  }

  /** Vettori calcolati da questo dispositivo (tutte le immagini, anche i fotogrammi). */
  val embedded: Int get() = images.total

  val backend: AiBackend get() = images.backend.value
}
