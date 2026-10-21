package dev.pampa.mycloudgallery.core.ai.contract

import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import kotlinx.coroutines.flow.StateFlow

// I MOTORI ON-DEVICE (docs/fase6-progetto.md §1.2 "core-ai", D2-D5, D11, D12, D41). Contratti di P0; i motori veri sono di P4
// (`runtime/`: EmbeddingGemma 2 con LiteRT-LM; `ocr/`: ML Kit, SOLO dietro il cancello della lettura del testo). I fake fedeli
// (§5.2) stanno in `testing/` e passano la stessa suite di contratto dei motori veri (`EmbedderContract`).
//
// D5: gli ingressi dei motori li prepara SEMPRE l'app (`ImageBounds`, `ImageDecoder`, riduzione a 768 px, JPEG q95): mai byte
// letti dal NAS o da altre app al decodificatore nativo di LiteRT-LM o di ML Kit. Per questo [ModelImage], [OcrImage] e
// `CutoutImage` hanno il costruttore `internal`: li costruisce solo `ModelInputs` di core-ai (P4) e i fake di questo modulo.

/**
 * La ricetta degli ingressi (D4): come si preparano le immagini e il testo. Una ricetta nuova resta nello **stesso spazio** (vettori
 * confrontabili) e si ricalcola piano dal piu' recente (D21); uno spazio nuovo non si mescola mai (E-I4).
 */
data class AiRecipe(
  /** Il numero della ricetta (1..255, un byte del corpo `vec`). */
  val version: Int = 1,
  val visionTokens: Int = 70,
  /** Lato lungo dell'ingresso del modello, in pixel. */
  val longSide: Int = 768,
  val jpegQuality: Int = 95,
  /** Fotogrammi di un video da leggere con l'OCR (copertina e centrale, D11). */
  val ocrFramesPerVideo: Int = 2,
  /** La ricetta dell'OCR (un byte del corpo `ocr`). */
  val ocrVersion: Int = 1,
  /** Testo delle query: lunghezza massima in token (D5) e in caratteri (E-Q9). */
  val maxInputTokens: Int = 128,
  val maxQueryChars: Int = 200,
) {
  init {
    require(version in 1..255 && ocrVersion in 1..255) { "Ricetta fuori intervallo." }
    require(longSide in 64..4096 && jpegQuality in 1..100 && visionTokens > 0) { "Ingressi non validi." }
  }

  companion object {
    /** La ricetta corrente. */
    val CURRENT = AiRecipe()
  }
}

/** Dove gira un motore (D3): GPU per gli indicizzatori, ripiego su CPU per la sessione (E-A12); le query sempre su CPU. */
enum class AiBackend { GPU, CPU }

/** I guasti dei motori come codici stabili (mappati dalle eccezioni delle librerie: mai un messaggio della libreria nei log). */
enum class AiEngineError {
  /** L'inizializzazione e' fallita (modello assente o rifiutato, libreria nativa, GPU): `AI_ENGINE_INIT_FAILED`. */
  INIT_FAILED,

  /** Memoria esaurita durante l'inizializzazione o il calcolo. */
  OUT_OF_MEMORY,

  /** Un vettore con valori non finiti (E-A12): non si salva ne' si pubblica. */
  NON_FINITE,

  /** Un ingresso rifiutato da `ModelInputs` (troppo grande, non decodificabile, formato non ammesso). */
  BAD_INPUT,

  /** La lettura del testo e' spenta su questo dispositivo: il motore OCR non si chiama (D41). */
  TEXT_READING_OFF,

  /** Il modello non c'e' o la verifica e' fallita (E-A11). */
  MODEL_UNAVAILABLE,
}

/** Un guasto di un motore. Il messaggio e' solo il codice (D40). */
class AiEngineException(val error: AiEngineError, cause: Throwable? = null) : Exception(error.name, cause)

/** Il vettore di una query, in float32, normalizzato, nello [space] del motore. */
class TextVector(val space: EmbeddingSpace, values: FloatArray) {
  private val data: FloatArray = values.copyOf()

  init {
    require(data.size == space.dimension) { "Dimensione diversa da quella dello spazio." }
  }

  val values: FloatArray get() = data.copyOf()

  override fun toString(): String = "TextVector(${space.id}/${space.dimension})"
}

/** L'esito di [TextEmbedder.warmUp]. */
sealed interface WarmUpResult {
  /** Pronto; [coldStartMs] e' il tempo dell'inizializzazione (0 se era gia' caldo). */
  data class Ready(val coldStartMs: Long) : WarmUpResult

  data class Failed(val error: AiEngineError) : WarmUpResult
}

/**
 * Il motore del testo delle query (D3, D35): EmbeddingGemma 2 **270M su CPU** su ogni dispositivo, anche sugli indicizzatori (stessi
 * vettori di query ovunque, E-Q8). Un motore solo, **una richiesta alla volta** (le chiamate si mettono in fila). [warmUp] e
 * [release] sono idempotenti; dopo [release] un [embed] lo riscalda da solo. [embed] lancia [AiEngineException] per ogni guasto.
 * Il testo e' troncato a `AiRecipe.maxQueryChars` caratteri (E-Q9) e non va mai nei log (D40).
 */
interface TextEmbedder {
  suspend fun warmUp(): WarmUpResult

  suspend fun embed(text: String): TextVector

  suspend fun release()
}

/**
 * Un'immagine pronta per il modello (D5): JPEG ricodificato da noi, lato lungo `AiRecipe.longSide`, gia' raddrizzato. Per un video
 * [frameMs] e' l'istante del fotogramma. Costruttore `internal`: la fa solo `ModelInputs` (P4) o un fake di core-ai.
 */
class ModelImage internal constructor(
  jpeg: ByteArray,
  val width: Int,
  val height: Int,
  val frameMs: Int? = null,
) {
  private val bytes: ByteArray = jpeg.copyOf()

  init {
    require(bytes.isNotEmpty() && width > 0 && height > 0) { "Immagine vuota." }
  }

  val jpeg: ByteArray get() = bytes.copyOf()

  override fun toString(): String = "ModelImage(${width}x$height, ${bytes.size} B)"
}

/**
 * Il motore delle immagini (D3): EmbeddingGemma 2 **440M su GPU** sugli indicizzatori, ripiego su CPU per la sessione se la GPU non
 * parte o da' valori non finiti (E-A12, `AI_GPU_FALLBACK`). Un vettore normalizzato per immagine, nello stesso ordine.
 */
interface ImageEmbedder {
  val backend: StateFlow<AiBackend>

  suspend fun embed(images: List<ModelImage>): List<FloatArray>

  suspend fun release()
}

/**
 * Un'immagine per l'OCR (D12): JPEG preparato da noi (anteprima di analisi 1024 px o HQ 2048 px, gia' raddrizzata, trasparenza su
 * bianco). Costruttore `internal`, come [ModelImage].
 */
class OcrImage internal constructor(
  jpeg: ByteArray,
  val width: Int,
  val height: Int,
  val source: OcrSource,
) {
  private val bytes: ByteArray = jpeg.copyOf()

  init {
    require(bytes.isNotEmpty() && width > 0 && height > 0) { "Immagine vuota." }
  }

  val jpeg: ByteArray get() = bytes.copyOf()

  override fun toString(): String = "OcrImage(${width}x$height, ${source.code})"
}

/**
 * Il testo letto in un'immagine (D13, D43): righe con riquadro, inclinazione ed estremi delle parole, coordinate normalizzate
 * sull'immagine raddrizzata. [dense] chiede la seconda passata sull'HQ (D12: >= 6 righe con altezza mediana sotto 16 px). Zero righe
 * = "analizzata, nessun testo" (un risultato, non un errore).
 */
class OcrResult(val lines: List<OcrLine>, val dense: Boolean) {
  override fun toString(): String = "OcrResult(${lines.size} righe, dense=$dense)"
}

/**
 * Il motore OCR (D12): ML Kit Text Recognition v2, script latino, integrato. **Solo dietro il cancello della lettura del testo**
 * (D41): con la lettura spenta su questo dispositivo [read] lancia [AiEngineException] con [AiEngineError.TEXT_READING_OFF] e ML Kit
 * non si inizializza. Il fake dei test fallisce se chiamato da spento (§4.3).
 */
interface TextReader {
  suspend fun read(image: OcrImage): OcrResult
}
