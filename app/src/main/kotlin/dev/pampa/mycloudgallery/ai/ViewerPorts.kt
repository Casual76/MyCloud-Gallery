package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextBox
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLayout
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLine
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextReader
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerWordSpan
import dev.pampa.mycloudgallery.feature.viewer.menu.SimilarAvailability
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerSimilarSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Il testo dal vivo dall'indice (D43): anche con la lettura spenta e sui media solo-NAS, senza ML Kit (D42). */
class IndexViewerTextSource(private val text: MediaText) : ViewerTextSource {
  override suspend fun layout(id: MediaId): ViewerTextLayout? = text.layout(id)?.toViewer()
}

/**
 * La lettura "al volo" per il viewer (D42, §2.10): **solo** con la lettura attiva qui ([available]: altrimenti il motore non si chiama mai e
 * ML Kit non parte) e solo dall'anteprima di analisi gia' nel buffer (preparata dall'app, mai byte di altre app al motore, D5). Al piu' 2 s;
 * un errore = nessun testo. Il risultato non si salva qui: lo salva e lo pubblica il calcolo, con la sua ricetta.
 */
class GatedViewerTextReader(
  private val switch: TextReadingSwitch,
  private val reader: TextReader,
  private val catalog: suspend () -> MediaCatalog?,
  private val buffer: AiBuffer,
) : ViewerTextReader {
  override val available: Boolean get() = switch.state.value.activeHere

  override suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout? {
    if (!available) return null
    val cell = catalog()?.cellContentKeysOf(listOf(id))?.firstOrNull { it.mediaId == id } ?: return null
    val bytes = cell.own.firstNotNullOfOrNull { buffer.get(it, SegmentClass.ANALYSIS) } ?: return null
    val image = ModelInputs.forOcr(bytes, OcrSource.ANALYSIS) ?: return null
    if (!available) return null
    val result = try {
      withTimeoutOrNull(READ_TIMEOUT_MS) { reader.read(image) }
    } catch (e: AiEngineException) {
      null
    } ?: return null
    return ViewerTextLayout(result.lines.map { it.toViewer() })
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

  private companion object {
    const val READ_TIMEOUT_MS = 2_000L
  }
}

/**
 * "Trova simili" (D30): la voce c'e' per le celle della Libreria (mai i Nascosti, che non sono celle: D29) e porta a Cerca con il chip
 * "Simili a" ([requests], che la navigazione segue). Se il media non ha ancora un vettore, la ricerca lo dice con i suoi stati.
 */
class SearchViewerSimilar(private val keys: CellKeys, private val requests: SimilarRequests) : ViewerSimilarSearch {
  override fun availability(id: MediaId): SimilarAvailability = if (keys[id] == null) SimilarAvailability.HIDDEN else SimilarAvailability.AVAILABLE

  override fun findSimilar(id: MediaId) = requests.request(id)
}

/** Le richieste di "Trova simili" dal viewer verso la scheda Cerca (una alla volta: la navigazione la consuma). */
class SimilarRequests {
  private val flow = MutableStateFlow<MediaId?>(null)
  val pending: StateFlow<MediaId?> = flow.asStateFlow()

  fun request(id: MediaId) {
    flow.value = id
  }

  fun consume(): MediaId? = flow.value.also { flow.value = null }
}
