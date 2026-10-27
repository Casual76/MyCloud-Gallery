package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginBuffer
import dev.pampa.mycloudgallery.core.ai.compute.OcrBodies
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextBox
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLayout
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLine
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextReader
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerWordSpan
import dev.pampa.mycloudgallery.feature.library.library.SimilarSelectionOutcome
import dev.pampa.mycloudgallery.feature.viewer.menu.SimilarAvailability
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerSimilarSearch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Le PORTE del viewer della Fase 6 (P12 testo dal vivo, P8 "Trova simili") sopra la ricerca e l'AI (P11). Il viewer non vede core-search
// ne' core-ai (Phase6ArchitectureTest): qui si convertono i tipi.

/** Le righe del contratto di core-search (0..1) come righe del viewer. */
internal fun TextLayout.toViewer(): ViewerTextLayout = ViewerTextLayout(
  lines.map { line ->
    ViewerTextLine(
      box = ViewerTextBox(line.box.x0, line.box.y0, line.box.x1, line.box.y1),
      angleDegrees = line.angleDegrees,
      words = line.words.map { ViewerWordSpan(it.start, it.end) },
      text = line.text,
    )
  },
)

/**
 * Il testo dal vivo dall'indice (D43): anche con la lettura spenta e sui media solo-NAS, senza ML Kit (D42). Revisione A (VT-2): [changes]
 * dice al viewer quando l'indice e' cambiato (un testo arrivato, ritirato o di un revocato), cosi' le righe e il pulsante "Testo" seguono.
 */
class IndexViewerTextSource(private val text: MediaText, override val changes: Flow<Unit>? = null) : ViewerTextSource {
  override suspend fun layout(id: MediaId): ViewerTextLayout? = text.layout(id)?.toViewer()
}

/**
 * Un'immagine da leggere al volo: i byte (anteprima di analisi o HQ), la chiave di contenuto cui appartiene il testo, da dove vengono e
 * (revisione A, R5) di chi sono: i propri byte, o l'anteprima di un altro dispositivo in una share (il testo letto vale solo li').
 */
class OnTheFlyImage(val key: ContentKey, val bytes: ByteArray, val source: OcrSource, val from: AiInputOrigin = AiInputOrigin.Own) {
  override fun toString(): String = "OnTheFlyImage($source, $from)"
}

/**
 * Da dove si prende l'immagine mostrata dal viewer per leggerla al volo (§2.10): sempre cio' che c'e' gia' sul dispositivo, **mai** uno
 * scaricamento solo per leggere. Null = niente da leggere (non e' una cella della Libreria, il file non si legge piu').
 */
fun interface OnTheFlyImages {
  suspend fun image(id: MediaId, ref: MediaRef): OnTheFlyImage?
}

/**
 * Le immagini della lettura al volo, nell'ordine: (1) l'anteprima di analisi gia' nel buffer degli indicizzatori; (2) per un media del
 * telefono, l'anteprima preparata adesso dai suoi byte ([AppAiPhone.analysisOf], la stessa strada del calcolo, D10); (3) per un media del NAS,
 * l'HQ della replica ([hq], 2.048 px, solo con la qualita' "Alta") e, se non c'e', l'originale che il viewer ha gia' portato sul dispositivo per
 * mostrarlo ([original]), preparato come ogni anteprima di analisi. Mai uno scaricamento solo per leggere.
 *
 * Revisione A: anche i nascosti e i membri di un gruppo, nel loro viewer (VT-1, D29); dal buffer solo un'anteprima propria, e l'HQ della
 * replica (preparato da un altro dispositivo) da' un testo che vale solo nella sua share e mai "proprio" (R5).
 */
class ViewerOcrImages(
  private val catalog: suspend () -> MediaCatalog?,
  private val buffer: AiBuffer,
  private val phone: AppAiPhone?,
  private val preparer: () -> Preparer?,
  private val hq: suspend (MediaRef.Nas) -> ByteArray?,
  private val original: (MediaRef.Nas) -> File? = { null },
  /**
   * R5: la share dell'HQ della replica di [MediaRef.Nas], se e' una sola (l'anteprima e' di chi l'ha preparata, non nota: il testo letto
   * vale solo li'); null = non si sa, e il testo resta solo su questo dispositivo.
   */
  private val hqShare: suspend (MediaRef.Nas) -> String? = { null },
) : OnTheFlyImages {
  override suspend fun image(id: MediaId, ref: MediaRef): OnTheFlyImage? {
    val cat = catalog() ?: return null
    // VT-1 (D29): anche un nascosto o un membro di un gruppo (il viewer dei Nascosti).
    val cell = cat.mediaContentKeysOf(listOf(id)).firstOrNull { it.mediaId == id } ?: return null
    for (key in cell.own) {
      // R5: dal buffer solo un'anteprima propria (una di un altro dispositivo farebbe "proprio" il testo letto su di lei).
      val blob = (buffer as? AiOriginBuffer)?.getFor(key, SegmentClass.ANALYSIS, null)?.bytes
        ?: buffer.takeIf { it !is AiOriginBuffer }?.get(key, SegmentClass.ANALYSIS)
      blob?.let { return OnTheFlyImage(key, it, OcrSource.ANALYSIS) }
    }
    return when (ref) {
      is MediaRef.Phone -> {
        val prepared = phone?.let { p -> preparer()?.let { p.analysisOf(id, it) } }
        prepared?.let { (key, bytes) -> OnTheFlyImage(key, bytes, OcrSource.PHONE_ORIGINAL) }
      }
      is MediaRef.Nas -> {
        // La chiave del testo e' quella della versione che il NAS mostra (la forma ridotta della chiave e' quella del riferimento).
        val key = cell.own.firstOrNull { it.short == ref.contentKey } ?: return null
        hq(ref)?.let { return OnTheFlyImage(key, it, OcrSource.HQ, AiInputOrigin.Preview(hqShare(ref).orEmpty(), null)) }
        originalAnalysis(cat, id, ref, key)
      }
      else -> null
    }
  }

  /** L'anteprima di analisi dell'originale del NAS gia' sul dispositivo; le sue impronte devono essere quelle della chiave (D8). */
  private suspend fun originalAnalysis(cat: MediaCatalog, id: MediaId, ref: MediaRef.Nas, key: ContentKey): OnTheFlyImage? {
    val file = original(ref) ?: return null
    val engine = preparer() ?: return null
    val media = cat.media(id) ?: return null
    if (media.kind == MediaKind.VIDEO) return null
    val result = try {
      engine.prepare(PrepareSource.Local(file, media.kind, ref.mime), PrepSpec(classes = setOf(SegmentClass.ANALYSIS)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return null
    } as? PrepResult.Done ?: return null
    val quick = result.quickHash ?: return null
    if (ContentKey.of(quick, file.length()) != key) return null
    val bytes = result.outputs.firstNotNullOfOrNull { out -> (out as? PrepOutput.Image)?.takeIf { it.cls == SegmentClass.ANALYSIS }?.bytes } ?: return null
    return OnTheFlyImage(key, bytes, OcrSource.ANALYSIS)
  }
}

/**
 * La lettura "al volo" per il viewer (D42, §2.10): **solo** con la lettura attiva qui ([available]: altrimenti il motore non si chiama mai e
 * ML Kit non parte), dall'immagine mostrata ([images]), al piu' [timeoutMs] (2 s). Il risultato segue il percorso normale: il corpo `ocr` si
 * costruisce come quello del calcolo ([OcrBodies]), si salva e si pubblica ([keep], `AiGraph.keepReadText`); il viewer mostra il testo
 * **salvato**, cosi' quello che si vede e' quello che c'e' nell'indice. Un errore o il tempo scaduto = nessun testo (niente si salva).
 */
class GatedViewerTextReader(
  private val switch: TextReadingSwitch,
  private val reader: TextReader,
  private val images: OnTheFlyImages,
  private val ocr: (ByteArray, OcrSource) -> OcrImage?,
  private val keep: suspend (ContentKey, OcrBody, AiInputOrigin) -> Unit,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  private val timeoutMs: Long = READ_TIMEOUT_MS,
  /** Dove si prepara l'immagine e si costruisce il corpo (CPU: mai sul thread del viewer). */
  private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewerTextReader {
  override val available: Boolean get() = switch.currentState().activeHere

  override suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout? {
    if (!available) return null
    val read = withTimeoutOrNull(timeoutMs) {
      withContext(work) {
        try {
          read(id, ref)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Un errore qualunque (catalogo, preparazione, motore) e' "nessun testo": il viewer non deve mai cadere per una lettura al volo.
          null
        }
      }
    } ?: return null
    // Salvato e in coda per la pubblicazione: non si interrompe a meta' (il tempo del viewer non lo riguarda piu').
    withContext(NonCancellable) {
      try {
        keep(read.key, read.body, read.from)
      } catch (e: IOException) {
        // Il testo e' gia' sullo schermo; il calcolo lo rileggera' se serve.
      }
    }
    return ViewerTextLayout(read.body.lines.map { it.toViewer() })
  }

  private class Read(val key: ContentKey, val body: OcrBody, val from: AiInputOrigin)

  private suspend fun read(id: MediaId, ref: MediaRef): Read? {
    val input = images.image(id, ref) ?: return null
    val image = ocr(input.bytes, input.source) ?: return null
    // La lettura puo' essere stata spenta nel frattempo (E-A18): il motore non si chiama.
    if (!available) return null
    val result = try {
      reader.read(image)
    } catch (e: AiEngineException) {
      return null
    }
    return OcrBodies.of(input.source, result.lines, recipe)?.let { Read(input.key, it, input.from) }
  }

  private fun OcrLine.toViewer(): ViewerTextLine {
    val unit = AiLimits.UNIT.toFloat()
    return ViewerTextLine(
      box = ViewerTextBox(box.x0 / unit, box.y0 / unit, box.x1 / unit, box.y1 / unit),
      angleDegrees = angle,
      words = words.map { ViewerWordSpan(it.start / unit, it.end / unit) },
      text = text,
    )
  }

  companion object {
    const val READ_TIMEOUT_MS = 2_000L
  }
}

/**
 * "Trova simili" (D30, E-Q14): la voce c'e' per le celle della Libreria (mai i Nascosti, che non sono celle: D29). Con un vettore ([hasVectors]
 * per una delle chiavi della cella) porta a Cerca con il chip "Simili a" ([requests], che la navigazione segue); senza (il media non e' ancora
 * stato analizzato) la voce c'e' lo stesso e al tocco dice perche' non si puo' (`SimilarAvailability.EXPLAINED`), invece di aprire una ricerca vuota.
 */
class SearchViewerSimilar(
  private val keys: CellKeys,
  private val requests: SimilarRequests,
  private val hasVectors: (ContentKey) -> Boolean = { true },
) : ViewerSimilarSearch {
  override fun availability(id: MediaId): SimilarAvailability {
    val cell = keys[id] ?: return SimilarAvailability.HIDDEN
    return if (cell.keys.any(hasVectors)) SimilarAvailability.AVAILABLE else SimilarAvailability.EXPLAINED
  }

  override fun findSimilar(id: MediaId) = requests.request(id)
}

/**
 * "Trova simili" dalla selezione della Libreria (correzioni F, TS-4, D30). Ogni media della selezione che e' una cella della Libreria entra nella richiesta (mai un
 * Nascosto: non e' una cella); la ricerca usa il centroide di quelli con un vettore e lascia fuori gli altri dicendolo (`SearchNotice.SimilarWithoutVectors`).
 * Se **nessuno** ha un vettore la ricerca non parte: la Libreria spiega che le foto non sono ancora state analizzate, invece di aprire Cerca vuota.
 */
class SearchSelectionSimilar(
  private val keys: CellKeys,
  private val requests: SimilarRequests,
  private val hasVectors: (ContentKey) -> Boolean = { true },
) {
  fun find(ids: Collection<MediaId>): SimilarSelectionOutcome {
    val cells = ids.filter { keys[it] != null }
    if (cells.none { id -> keys[id]?.keys?.any(hasVectors) == true }) return SimilarSelectionOutcome.NOT_ANALYZED
    requests.request(cells)
    return SimilarSelectionOutcome.STARTED
  }
}

/** Le richieste di "Trova simili" dal viewer e dalla selezione verso la scheda Cerca (una alla volta: la navigazione la consuma). */
class SimilarRequests {
  private val flow = MutableStateFlow<List<MediaId>?>(null)
  val pending: StateFlow<List<MediaId>?> = flow.asStateFlow()

  /** Un media (il viewer). */
  fun request(id: MediaId) = request(listOf(id))

  /** Uno o piu' media (la selezione della Libreria): Cerca parte dal centroide. Una richiesta vuota non fa niente. */
  fun request(ids: List<MediaId>) {
    if (ids.isNotEmpty()) flow.value = ids
  }

  fun consume(): List<MediaId>? = flow.value.also { flow.value = null }
}
