package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceQualityRule
import dev.pampa.mycloudgallery.core.ai.contract.QualityVerdict
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

// LA QUALITA' DI UN VOLTO (docs/fase8-progetto.md D19, §2.2; `FaceEngineLimits`): tre livelli che decidono se un volto ha un vettore e se
// puo' far nascere una persona. **Nessun attributo della persona** (eta', genere, etnia, emozioni, accessori: C20): solo la dimensione del
// riquadro, il punteggio del rilevatore, l'imbardata stimata dai cinque punti e la nitidezza del ritaglio allineato.
//
//   NONE  lato corto sotto 24 px, oppure imbardata oltre 60 gradi: nessun vettore, solo "1 volto non riconoscibile" nelle info;
//   CORE  lato corto >= 48 px, punteggio >= 0,80, imbardata <= 35 gradi, nitido: il solo livello che fa nascere una persona;
//   WEAK  tutto il resto (24-48 px, profilo fino a 60 gradi, sfocato, punteggio fra 0,60 e 0,80): si unisce solo con una somiglianza piu' alta.
// I motivi (`FaceReasons`) dicono perche' non e' CORE; l'etichetta di un fotogramma di video (`FRAME`) la aggiunge chi calcola (P4).
// Le soglie di nitidezza e il fattore dell'imbardata sono PROVVISORI: li ha misurati P2 su immagini sintetiche, li ricalibra P11 / F105 sui
// dati veri (docs/decisioni.md, "Fase 8 P2"). Puro, sulla JVM.

object FaceQuality : FaceQualityRule {

  /**
   * Quanto il naso sporge dal piano degli occhi, in distanze fra gli occhi (la punta del naso e' circa 3 cm davanti al piano degli occhi,
   * la distanza fra le pupille circa 6,3 cm): con l'imbardata `t` lo scarto orizzontale del naso dal punto medio degli occhi, in distanze
   * fra gli occhi, vale `0,5 * tan(t)`; l'imbardata e' l'arcotangente dello scarto diviso questo numero.
   */
  const val NOSE_DEPTH_RATIO: Double = 0.5

  /** Gli estremi della finestra del volto allineato in cui si misura la nitidezza: occhi, naso e bocca, senza capelli e sfondo. */
  private const val WINDOW_X0 = 16
  private const val WINDOW_X1 = 96
  private const val WINDOW_Y0 = 20
  private const val WINDOW_Y1 = 100

  /** La varianza del laplaciano (livelli di grigio 0-255) che vale nitidezza 255: la scala e' logaritmica. */
  const val FULL_SCALE_VARIANCE: Double = 1000.0

  /** Sotto questa nitidezza (0-255) il volto e' "sfocato" (`FaceReasons.BLURRY`). */
  const val BLURRY_BELOW: Int = 90

  /**
   * L'imbardata in gradi (-90..90) dai cinque punti: positiva quando il naso e' spostato verso destra nell'immagine (il soggetto guarda
   * verso la sinistra del soggetto), negativa verso sinistra; 0 per un volto frontale. Lo scarto del naso e' misurato lungo la retta degli
   * occhi (l'inclinazione del capo nel piano non conta). Occhi coincidenti: 90.
   */
  fun yawOf(landmarks: FloatArray): Int {
    require(landmarks.size == FaceEngineLimits.LANDMARK_VALUES) { "Cinque punti attesi." }
    val ex = (landmarks[2] - landmarks[0]).toDouble()
    val ey = (landmarks[3] - landmarks[1]).toDouble()
    val eyes = hypot(ex, ey)
    if (!(eyes > 1e-6)) return 90
    val mx = (landmarks[0] + landmarks[2]) / 2.0
    val my = (landmarks[1] + landmarks[3]) / 2.0
    val offset = ((landmarks[4] - mx) * ex + (landmarks[5] - my) * ey) / (eyes * eyes)
    return Math.toDegrees(atan(offset / NOSE_DEPTH_RATIO)).roundToInt().coerceIn(-90, 90)
  }

  /**
   * La nitidezza (0-255) di un volto allineato: la varianza del laplaciano della luminanza nella finestra di occhi, naso e bocca, su
   * scala logaritmica (una varianza di [FULL_SCALE_VARIANCE] vale 255). Un ritaglio uniforme vale 0.
   */
  fun sharpnessOf(aligned: AlignedFace): Int {
    val side = FaceEngineLimits.ALIGNED_SIDE
    val luma = FloatArray(side * side)
    val pixels = aligned.raw()
    for (i in luma.indices) {
      val p = pixels[i]
      luma[i] = 0.299f * ((p shr 16) and 0xff) + 0.587f * ((p shr 8) and 0xff) + 0.114f * (p and 0xff)
    }
    var sum = 0.0
    var sumSquares = 0.0
    var count = 0
    for (y in WINDOW_Y0 + 1 until WINDOW_Y1 - 1) {
      for (x in WINDOW_X0 + 1 until WINDOW_X1 - 1) {
        val i = y * side + x
        val laplacian = 4.0 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - side] - luma[i + side]
        sum += laplacian
        sumSquares += laplacian * laplacian
        count++
      }
    }
    val mean = sum / count
    val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
    return (255.0 * ln(1.0 + variance) / ln(1.0 + FULL_SCALE_VARIANCE)).roundToInt().coerceIn(0, 255)
  }

  /**
   * Il giudizio di [face]: [sidePx] e' il lato corto del riquadro **nella sorgente dell'allineamento** (non nell'anteprima, se il volto si
   * allinea dall'HQ o dall'originale). Senza [aligned] la nitidezza non si valuta (vale 0 e non e' un motivo): il livello e' provvisorio e
   * chi lo usa lo rifa con il volto allineato prima di salvare.
   */
  override fun assess(face: DetectedFace, sidePx: Int, aligned: AlignedFace?): QualityVerdict {
    val yaw = yawOf(face.landmarks)
    val sharpness = aligned?.let(::sharpnessOf) ?: 0
    var reasons = 0
    if (sidePx < FaceEngineLimits.CORE_MIN_SIDE_PX) reasons = reasons or FaceReasons.SMALL
    if (abs(yaw) > FaceEngineLimits.CORE_MAX_YAW) reasons = reasons or FaceReasons.PROFILE
    if (aligned != null && sharpness < BLURRY_BELOW) reasons = reasons or FaceReasons.BLURRY
    val tier = when {
      sidePx < FaceEngineLimits.WEAK_MIN_SIDE_PX || abs(yaw) > FaceEngineLimits.WEAK_MAX_YAW -> FaceTier.NONE
      reasons == 0 && face.score >= FaceEngineLimits.CORE_MIN_SCORE -> FaceTier.CORE
      else -> FaceTier.WEAK
    }
    return QualityVerdict(tier, reasons, yaw, sharpness)
  }
}
