package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceAlignment
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import kotlin.math.floor

// L'ALLINEAMENTO DEL VOLTO (docs/fase8-progetto.md §2.2 "Nel telefono"; docs/decisioni.md "Fase 8 P1"): una similitudine (rotazione,
// scala uniforme e traslazione, **senza deformazioni ne' riflessioni**) ai minimi quadrati (Umeyama) dai cinque punti del volto ai cinque
// punti del modello a 112 px di ArcFace, e un ritaglio 112x112 con interpolazione bilineare e bordi a 0 (nero). E' `volto_allineato` di
// `tools/models/faces/common.py`, che P1 ha provato contro `FaceRecognizerSF.alignCrop` di OpenCV (differenza media 0,0015 livelli su 255,
// coseno dei vettori >= 0,999987): stesse formule, stessa convenzione (il pixel (i, j) ha il centro nelle coordinate intere (i, j), come
// `warpAffine`). Puro, sulla JVM; il volto allineato **non si salva mai** (D25, C36).

object FaceAligner : FaceAlignment {

  /** I cinque punti del modello a 112 px di ArcFace: occhio destro e sinistro, punta del naso, angolo destro e sinistro della bocca. */
  val TEMPLATE: FloatArray = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

  /** Sotto questa spread (somma dei quadrati degli scarti dal centro, px^2) i cinque punti sono uno solo: nessuna similitudine. */
  private const val MIN_SPREAD = 1e-6

  /**
   * La similitudine che porta [landmarks] (cinque punti in pixel dell'immagine) sul [TEMPLATE]: `[a, b, tx, ty]` con
   * `x' = a*x - b*y + tx` e `y' = b*x + a*y + ty` (la scala e' `hypot(a, b)`). Null se i punti sono coincidenti o non finiti.
   * Il minimo ai quadrati fra le sole rotazioni con scala uniforme (la forma chiusa in 2D di Umeyama, senza il caso della riflessione).
   */
  fun similarity(landmarks: FloatArray): DoubleArray? {
    if (landmarks.size != FaceEngineLimits.LANDMARK_VALUES || landmarks.any { !it.isFinite() }) return null
    var mx = 0.0
    var my = 0.0
    var tx = 0.0
    var ty = 0.0
    for (i in 0 until 5) {
      mx += landmarks[2 * i]
      my += landmarks[2 * i + 1]
      tx += TEMPLATE[2 * i]
      ty += TEMPLATE[2 * i + 1]
    }
    mx /= 5
    my /= 5
    tx /= 5
    ty /= 5
    var spread = 0.0
    var dot = 0.0
    var cross = 0.0
    for (i in 0 until 5) {
      val sx = landmarks[2 * i] - mx
      val sy = landmarks[2 * i + 1] - my
      val dx = TEMPLATE[2 * i] - tx
      val dy = TEMPLATE[2 * i + 1] - ty
      spread += sx * sx + sy * sy
      dot += sx * dx + sy * dy
      cross += sx * dy - sy * dx
    }
    if (spread < MIN_SPREAD) return null
    val a = dot / spread
    val b = cross / spread
    if (a * a + b * b < 1e-18) return null
    return doubleArrayOf(a, b, tx - (a * mx - b * my), ty - (b * mx + a * my))
  }

  override fun align(image: FaceImage, landmarks: FloatArray): AlignedFace {
    require(landmarks.size == FaceEngineLimits.LANDMARK_VALUES) { "Cinque punti attesi." }
    val m = similarity(landmarks) ?: throw AiEngineException(AiEngineError.BAD_INPUT)
    return AlignedFace(warp(image.raw(), image.width, image.height, m))
  }

  /** Il ritaglio 112x112: ogni pixel d'arrivo guarda dove la similitudine inversa lo porta nell'immagine (interpolazione bilineare, fuori = nero). */
  internal fun warp(pixels: IntArray, width: Int, height: Int, m: DoubleArray): IntArray {
    val side = FaceEngineLimits.ALIGNED_SIDE
    val a = m[0]
    val b = m[1]
    val det = a * a + b * b
    val out = IntArray(side * side)
    for (y in 0 until side) {
      for (x in 0 until side) {
        val px = x - m[2]
        val py = y - m[3]
        val sx = (a * px + b * py) / det
        val sy = (-b * px + a * py) / det
        out[y * side + x] = bilinear(pixels, width, height, sx, sy)
      }
    }
    return out
  }

  /** Il colore in ([sx], [sy]) (centri dei pixel sulle coordinate intere); i vicini fuori dall'immagine valgono 0 (`BORDER_CONSTANT`). */
  private fun bilinear(pixels: IntArray, width: Int, height: Int, sx: Double, sy: Double): Int {
    if (!(sx > -1.0 && sx < width && sy > -1.0 && sy < height)) return OPAQUE_BLACK
    val x0 = floor(sx).toInt()
    val y0 = floor(sy).toInt()
    val fx = (sx - x0).toFloat()
    val fy = (sy - y0).toFloat()
    val x1 = x0 + 1
    val y1 = y0 + 1
    val p00 = if (x0 >= 0 && y0 >= 0 && x0 < width && y0 < height) pixels[y0 * width + x0] else OUTSIDE
    val p10 = if (x1 >= 0 && y0 >= 0 && x1 < width && y0 < height) pixels[y0 * width + x1] else OUTSIDE
    val p01 = if (x0 >= 0 && y1 >= 0 && x0 < width && y1 < height) pixels[y1 * width + x0] else OUTSIDE
    val p11 = if (x1 >= 0 && y1 >= 0 && x1 < width && y1 < height) pixels[y1 * width + x1] else OUTSIDE
    val w00 = (1f - fx) * (1f - fy)
    val w10 = fx * (1f - fy)
    val w01 = (1f - fx) * fy
    val w11 = fx * fy
    val r = channel(p00, 16) * w00 + channel(p10, 16) * w10 + channel(p01, 16) * w01 + channel(p11, 16) * w11
    val g = channel(p00, 8) * w00 + channel(p10, 8) * w10 + channel(p01, 8) * w01 + channel(p11, 8) * w11
    val bl = channel(p00, 0) * w00 + channel(p10, 0) * w10 + channel(p01, 0) * w01 + channel(p11, 0) * w11
    return OPAQUE_BLACK or (round8(r) shl 16) or (round8(g) shl 8) or round8(bl)
  }

  private fun channel(p: Int, shift: Int): Float = ((p shr shift) and 0xff).toFloat()

  private fun round8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

  private const val OPAQUE_BLACK: Int = 0xFF shl 24

  /** Un vicino fuori dall'immagine: nero (l'alfa non conta, si ignora). */
  private const val OUTSIDE: Int = 0
}
