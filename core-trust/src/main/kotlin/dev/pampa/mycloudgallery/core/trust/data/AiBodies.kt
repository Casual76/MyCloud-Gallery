package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.TrustLimits

// Fase 6 (docs/fase6-progetto.md §1.2 "core-trust", §2.3, D6, D13, D43): i CORPI delle due classi nuove dell'indice, `vec` (7) e
// `ocr` (8), come tipi. Firme di P0, tenute da P2 con il codec ostile (`AiSegments`, data/AiSegments.kt): lettura e scrittura dei
// byte, versione 2 del formato, segmento rifiutato intero per ogni violazione (E-I20).
//
// I costruttori controllano la FORMA (conteggi, intervalli, tetti): un corpo costruito in memoria che li viola e' un errore di
// programmazione. I controlli sui VALORI che arrivano dal NAS (componenti finite, norma, UTF-8, NFC, caratteri di controllo e di
// direzione) li fa il codec prima di costruire questi tipi; chi scrive passa dagli stessi controlli (`AiSegments.admits`), cosi' non
// pubblica mai un corpo che gli altri rifiuterebbero.

/**
 * Le due classi dei dati AI (D6): stanno **solo** nelle teste di tipo `ai`, mai in una testa `index` o `personal`. Codici e nomi del
 * formato, gli stessi di [SegmentClass.VEC] e [SegmentClass.OCR] ([segmentClass]): questo tipo e' la vista dei soli dati AI, comoda
 * dove una classe dell'indice non deve poter entrare.
 */
enum class AiClass(val wire: String, val code: Int) {
  VEC("vec", 7),
  OCR("ocr", 8),
  ;

  /** La stessa classe come [SegmentClass] (nelle teste e nei segmenti). */
  val segmentClass: SegmentClass get() = if (this == VEC) SegmentClass.VEC else SegmentClass.OCR

  companion object {
    fun fromWire(wire: String): AiClass? = entries.firstOrNull { it.wire == wire }

    fun fromCode(code: Int): AiClass? = entries.firstOrNull { it.code == code }

    /** La classe AI di [cls], o null se [cls] non e' `vec` ne' `ocr`. */
    fun of(cls: SegmentClass): AiClass? = fromCode(cls.code)?.takeIf { cls.isAi }
  }
}

/** I tetti dei corpi `vec` e `ocr` (§2.3, A6 di formato-nas.md): quelli interi stanno in [TrustLimits] (§7 del formato). */
object AiLimits {
  /** Al massimo 9 vettori per corpo (copertina + 8 fotogrammi, D11); esattamente 1 per le sorgenti d'immagine. */
  const val MAX_VECTORS: Int = TrustLimits.MAX_VEC_VECTORS

  /** 768 componenti float16 x 9 + intestazione e istanti: 13.868 byte; il tetto del corpo e' 14 KiB. */
  const val MAX_VEC_BODY_BYTES: Int = TrustLimits.MAX_VEC_BODY_BYTES

  /** Le ricette sono 1..255 (un byte). */
  const val MAX_RECIPE: Int = 255

  /** Righe di testo per corpo (0 = "analizzata, nessun testo"). */
  const val MAX_OCR_LINES: Int = TrustLimits.MAX_OCR_LINES

  /** Byte UTF-8 di una riga. */
  const val MAX_LINE_BYTES: Int = TrustLimits.MAX_OCR_LINE_BYTES

  /** Parole per riga (gli estremi servono al testo dal vivo, D43). */
  const val MAX_WORDS_PER_LINE: Int = TrustLimits.MAX_OCR_WORDS_PER_LINE

  /** Il tetto del corpo `ocr`. */
  const val MAX_OCR_BODY_BYTES: Int = TrustLimits.MAX_OCR_BODY_BYTES

  /** Le coordinate e gli estremi sono in 1/65535 (2 byte senza segno). */
  const val UNIT: Int = 65535

  /** La norma ricalcolata in float32 deve stare in [MIN_NORM, MAX_NORM]. */
  const val MIN_NORM: Float = 0.99f
  const val MAX_NORM: Float = 1.01f
}

/** Il corpo di una voce dei dati AI: un [VecBody] o un [OcrBody], con la sua classe e la sua ricetta (D7: vince la piu' alta). */
sealed interface AiBody {
  val cls: AiClass
  val recipe: Int
}

/**
 * Un vettore di un corpo `vec`: le componenti in **float16** (bit grezzi, come nel formato) e, per un video, l'istante del
 * fotogramma in millisecondi.
 */
class VecItem(val frameMs: Int?, f16: ShortArray) {
  private val bits: ShortArray = f16.copyOf()

  init {
    require(frameMs == null || frameMs >= 0) { "Istante negativo." }
    require(bits.isNotEmpty()) { "Vettore vuoto." }
  }

  /** Le componenti float16 (copia). */
  val f16: ShortArray get() = bits.copyOf()

  val dimension: Int get() = bits.size

  /** La componente [index] in float16, senza copiare tutto il vettore. */
  fun component(index: Int): Short = bits[index]

  override fun equals(other: Any?): Boolean = other is VecItem && other.frameMs == frameMs && other.bits.contentEquals(bits)

  override fun hashCode(): Int = (frameMs ?: -1) * 31 + bits.contentHashCode()

  override fun toString(): String = "VecItem(${bits.size} comp., frame=${frameMs != null})"
}

/** Il corpo `vec` (§2.3): spazio, dimensione, precisione (float16), ricetta, sorgente, da 1 a 9 vettori. */
class VecBody(
  val space: SpaceCode,
  override val recipe: Int,
  val source: VecSource,
  val vectors: List<VecItem>,
) : AiBody {
  init {
    require(recipe in 1..AiLimits.MAX_RECIPE) { "Ricetta fuori intervallo." }
    require(vectors.size in 1..AiLimits.MAX_VECTORS) { "Da 1 a ${AiLimits.MAX_VECTORS} vettori." }
    require(!source.singleVector || vectors.size == 1) { "Una sorgente d'immagine ha un vettore solo." }
    require(vectors.all { it.dimension == space.space.dimension }) { "Dimensione diversa da quella dello spazio." }
    require(vectors.all { (it.frameMs != null) == (source == VecSource.FRAMES) }) { "Istanti solo per i fotogrammi." }
  }

  override val cls: AiClass get() = AiClass.VEC

  val dimension: Int get() = space.space.dimension

  override fun equals(other: Any?): Boolean =
    other is VecBody && other.space == space && other.recipe == recipe && other.source == source && other.vectors == vectors

  override fun hashCode(): Int = ((space.hashCode() * 31 + recipe) * 31 + source.hashCode()) * 31 + vectors.hashCode()

  override fun toString(): String = "VecBody(${space.code}, r$recipe, ${source.code}, ${vectors.size})"
}

/**
 * Un riquadro in 1/65535 dell'immagine **raddrizzata** (x0 <= x1, y0 <= y1). Interi da 0 a 65535 (2 byte senza segno nel
 * formato).
 */
data class ShortBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
  init {
    require(x0 in 0..AiLimits.UNIT && x1 in 0..AiLimits.UNIT && y0 in 0..AiLimits.UNIT && y1 in 0..AiLimits.UNIT) { "Fuori da 0..65535." }
    require(x0 <= x1 && y0 <= y1) { "Riquadro rovesciato." }
  }
}

/** Gli estremi di una parola lungo la riga, in 1/65535 della lunghezza della riga (inizio <= fine). */
data class WordSpan(val start: Int, val end: Int) {
  init {
    require(start in 0..AiLimits.UNIT && end in 0..AiLimits.UNIT && start <= end) { "Estremi non validi." }
  }
}

/**
 * Una riga di testo letto (D13, D43): riquadro, inclinazione in gradi (-90..90, dall'angolo della riga di ML Kit), estremi delle
 * parole (non decrescenti) e testo UTF-8 in NFC. Le parole sono i pezzi del testo separati da uno spazio: devono essere tante quante
 * [words], e nessuna e' vuota (niente spazi ai bordi o doppi: P2, una parola vuota non ha estremi che il testo dal vivo possa usare).
 */
class OcrLine(
  val box: ShortBox,
  val angle: Int,
  val words: List<WordSpan>,
  val text: String,
) {
  init {
    require(angle in -90..90) { "Inclinazione fuori da -90..90." }
    require(words.size in 1..AiLimits.MAX_WORDS_PER_LINE) { "Da 1 a ${AiLimits.MAX_WORDS_PER_LINE} parole." }
    require(words.zipWithNext().all { (a, b) -> a.start <= b.start && a.end <= b.end }) { "Estremi decrescenti." }
    val bytes = text.toByteArray(Charsets.UTF_8).size
    require(bytes in 1..AiLimits.MAX_LINE_BYTES) { "Riga vuota o oltre ${AiLimits.MAX_LINE_BYTES} byte." }
    val pieces = text.split(' ')
    require(pieces.size == words.size) { "Le parole non tornano con il testo." }
    require(pieces.none { it.isEmpty() }) { "Una parola vuota (spazi ai bordi o doppi)." }
  }

  override fun equals(other: Any?): Boolean =
    other is OcrLine && other.box == box && other.angle == angle && other.words == words && other.text == text

  override fun hashCode(): Int = ((box.hashCode() * 31 + angle) * 31 + words.hashCode()) * 31 + text.hashCode()

  /** Mai il testo (D40): solo numeri. */
  override fun toString(): String = "OcrLine(${words.size} parole, ${text.length} caratteri)"
}

/** Il corpo `ocr` (§2.3): motore, ricetta OCR, sorgente, da 0 a 400 righe (0 = "analizzata, nessun testo"). */
class OcrBody(
  val engine: OcrEngineCode,
  override val recipe: Int,
  val source: OcrSource,
  val lines: List<OcrLine>,
) : AiBody {
  init {
    require(recipe in 1..AiLimits.MAX_RECIPE) { "Ricetta fuori intervallo." }
    require(lines.size <= AiLimits.MAX_OCR_LINES) { "Oltre ${AiLimits.MAX_OCR_LINES} righe." }
  }

  override val cls: AiClass get() = AiClass.OCR

  /** "Nessun testo" e' un risultato (D13). */
  val noText: Boolean get() = lines.isEmpty()

  override fun equals(other: Any?): Boolean =
    other is OcrBody && other.engine == engine && other.recipe == recipe && other.source == source && other.lines == lines

  override fun hashCode(): Int = ((engine.hashCode() * 31 + recipe) * 31 + source.hashCode()) * 31 + lines.hashCode()

  override fun toString(): String = "OcrBody(${engine.code}, r$recipe, ${source.code}, ${lines.size} righe)"
}
