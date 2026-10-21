package dev.pampa.mycloudgallery.core.ai.contract

// Il RITAGLIO DEL SOGGETTO (docs/fase6-progetto.md D44, D47, §2.10, E-V10). Contratto di P0; il ritagliatore vero (MagicTouch su
// LiteRT, CPU, maschera con filtro guidato, PNG) e' di P13 in core-ai/.../cutout/. Il viewer non vede core-ai: :app lega questo
// contratto alla porta del viewer (`ViewerSubjectCutter`).

/** Un punto normalizzato sull'immagine raddrizzata (0..1), gia' passato per la trasformazione dello zoom. */
data class NormalizedPoint(val x: Float, val y: Float) {
  init {
    require(x in 0f..1f && y in 0f..1f) { "Punto fuori dall'immagine." }
  }
}

/**
 * L'immagine su cui ritagliare: pixel ARGB (la migliore gia' sul dispositivo, al piu' 4096 px sul lato lungo, E-M1-M4). Costruttore
 * `internal`: la prepara solo core-ai (D5).
 */
class CutoutImage internal constructor(val width: Int, val height: Int, argb: IntArray) {
  private val pixels: IntArray = argb.copyOf()

  init {
    require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && pixels.size == width * height) { "Immagine non valida." }
  }

  fun argb(): IntArray = pixels.copyOf()

  fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

  override fun toString(): String = "CutoutImage(${width}x$height)"

  companion object {
    const val MAX_SIDE: Int = 4096
  }
}

/** Una maschera alfa (0 = fuori, 255 = soggetto) grande come l'immagine. */
class AlphaMask(val width: Int, val height: Int, alpha: ByteArray) {
  private val data: ByteArray = alpha.copyOf()

  init {
    require(width > 0 && height > 0 && data.size == width * height) { "Maschera non valida." }
  }

  fun alpha(): ByteArray = data.copyOf()

  fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xff

  /** La parte dell'immagine coperta (alfa >= 128), da 0 a 1. */
  val coverage: Float get() = data.count { (it.toInt() and 0xff) >= 128 }.toFloat() / data.size

  override fun toString(): String = "AlphaMask(${width}x$height)"
}

/** Un rettangolo in pixel dell'immagine (estremi inclusi a sinistra e in alto, esclusi a destra e in basso). */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  init {
    require(left in 0..right && top in 0..bottom) { "Rettangolo rovesciato." }
  }
}

/** L'esito di un ritaglio (§1.5, `CutoutState`). */
sealed interface CutoutResult {
  data class Subject(val mask: AlphaMask, val bounds: PixelBox) : CutoutResult

  /** Nessun soggetto chiaro (maschera sotto l'1% o sopra il 90%, o confidenza bassa, E-V10): niente, vibrazione leggera. */
  data object NoClearSubject : CutoutResult

  data class Failed(val error: AiEngineError) : CutoutResult

  companion object {
    /** I limiti di copertura di un soggetto chiaro (D44). */
    const val MIN_COVERAGE: Float = 0.01f
    const val MAX_COVERAGE: Float = 0.90f
  }
}

/** Il ritagliatore (D44): MagicTouch su LiteRT, CPU, dal punto toccato. Mai rete (F67). */
interface SubjectCutter {
  suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult

  suspend fun release()
}
