package dev.pampa.mycloudgallery.core.ai.cutout

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import kotlin.math.hypot
import org.junit.Test

/** Fase 6 (P13, E-V10, D44): la decisione "c'e' un soggetto chiaro?" e la maschera alla risoluzione dell'immagine, su mappe scritte a mano. */
class MaskPostprocessTest {

  private fun disc(w: Int, h: Int, cx: Float, cy: Float, r: Float, value: Float = 1f, soft: Float = 0f): FloatArray =
    FloatArray(w * h) {
      val d = hypot((it % w + 0.5f) - cx, (it / w + 0.5f) - cy)
      if (soft == 0f) (if (d <= r) value else 0f) else (((r - d) / soft + 0.5f).coerceIn(0f, 1f) * value)
    }

  private fun uniform(w: Int, h: Int, r: Int, g: Int, b: Int) = FloatArray(w * h * 3) { if (it % 3 == 0) r.toFloat() else if (it % 3 == 1) g.toFloat() else b.toFloat() }

  /** Un disco chiaro su fondo scuro: il bordo c'e'. */
  private fun discOnDark(w: Int, h: Int, cx: Float, cy: Float, r: Float): FloatArray = FloatArray(w * h * 3) {
    val p = it / 3
    if (hypot((p % w + 0.5f) - cx, (p / w + 0.5f) - cy) <= r) 230f else 30f
  }

  @Test
  fun ilDiscoToccatoESoggetto() {
    val map = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 20f))
    val verdict = SubjectRules.decide(map, 0.5f, 0.5f, discOnDark(100, 100, 50f, 50f, 20f))
    assertThat(verdict).isInstanceOf(SubjectRules.Verdict.Subject::class.java)
    verdict as SubjectRules.Verdict.Subject
    assertThat(verdict.coverage).isWithin(0.01f).of(0.1257f)
    assertThat(verdict.confidence).isWithin(1e-3f).of(1f)
    assertThat(verdict.stats.contrast).isGreaterThan(0.5f)
  }

  @Test
  fun ilPuntoFuoriDalSoggettoNonDaNiente() {
    val map = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 20f))
    val verdict = SubjectRules.decide(map, 0.1f, 0.1f)
    assertThat((verdict as SubjectRules.Verdict.None).reason).isEqualTo(SubjectRules.Reason.POINT)
  }

  @Test
  fun unDitoVicinoAlBordoPrendeIlSoggetto() {
    val map = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 20f))
    // A un pixel dal bordo (il centro del pixel 69 e' dentro, il dito e' sul 70): dentro la tolleranza del dito.
    val verdict = SubjectRules.decide(map, 0.705f, 0.5f)
    assertThat(verdict).isInstanceOf(SubjectRules.Verdict.Subject::class.java)
  }

  @Test
  fun sottoLUnPerCentoOSopraIlNovantaNonCEUnSoggetto() {
    val tiny = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 5f))
    val tinyVerdict = SubjectRules.decide(tiny, 0.5f, 0.5f)
    assertThat((tinyVerdict as SubjectRules.Verdict.None).reason).isEqualTo(SubjectRules.Reason.COVERAGE)
    val huge = ProbabilityMap(100, 100, FloatArray(100 * 100) { 1f })
    val hugeVerdict = SubjectRules.decide(huge, 0.5f, 0.5f)
    assertThat((hugeVerdict as SubjectRules.Verdict.None).reason).isEqualTo(SubjectRules.Reason.COVERAGE)
  }

  @Test
  fun unaConfidenzaBassaNonEUnSoggetto() {
    val map = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 20f, value = 0.6f))
    val verdict = SubjectRules.decide(map, 0.5f, 0.5f)
    assertThat((verdict as SubjectRules.Verdict.None).reason).isEqualTo(SubjectRules.Reason.CONFIDENCE)
  }

  @Test
  fun unaMacchiaSenzaBordoNelleFotoNonEUnSoggetto() {
    // Il modello disegna un disco sul cielo (colore uniforme): il contorno non si distingue dal resto (E-V10).
    val map = ProbabilityMap(100, 100, disc(100, 100, 50f, 50f, 20f))
    val verdict = SubjectRules.decide(map, 0.5f, 0.5f, uniform(100, 100, 120, 160, 220))
    verdict as SubjectRules.Verdict.None
    assertThat(verdict.reason).isEqualTo(SubjectRules.Reason.CONTRAST)
    assertThat(verdict.stats!!.contrast).isLessThan(SubjectRules.MIN_CONTRAST)
  }

  @Test
  fun leMacchieStaccateSpariscono() {
    val values = disc(100, 100, 40f, 50f, 20f)
    // Una macchia di 3x3 pixel lontana: meno del 2% della componente toccata.
    for (y in 10..12) for (x in 85..87) values[y * 100 + x] = 1f
    val verdict = SubjectRules.decide(ProbabilityMap(100, 100, values), 0.4f, 0.5f)
    verdict as SubjectRules.Verdict.Subject
    assertThat(verdict.map.values[11 * 100 + 86]).isEqualTo(0f)
    assertThat(verdict.map.values[50 * 100 + 40]).isEqualTo(1f)
  }

  @Test
  fun unaPartiStaccataDelloStessoSoggettoResta() {
    val values = disc(100, 100, 30f, 50f, 20f)
    val part = disc(100, 100, 75f, 50f, 8f)
    for (i in values.indices) if (part[i] > 0f) values[i] = 1f
    val verdict = SubjectRules.decide(ProbabilityMap(100, 100, values), 0.3f, 0.5f)
    verdict as SubjectRules.Verdict.Subject
    assertThat(verdict.map.values[50 * 100 + 75]).isEqualTo(1f)
  }

  // --- La maschera alla risoluzione dell'immagine ----------------------------------------------------------------------------------

  /** La mappa del modello (taglia [mw]x[mh], stirata sull'immagine) di un disco dell'immagine, con un bordo sfumato di [soft] pixel della mappa. */
  private fun coarseOf(scene: Scene, mw: Int, mh: Int, soft: Float): ProbabilityMap {
    val sx = scene.w.toFloat() / mw
    val sy = scene.h.toFloat() / mh
    return ProbabilityMap(mw, mh, FloatArray(mw * mh) {
      val d = hypot(((it % mw) + 0.5f) * sx - scene.cx, ((it / mw) + 0.5f) * sy - scene.cy)
      ((scene.r - d) / (soft * sx) + 0.5f).coerceIn(0f, 1f)
    })
  }

  /** Un'immagine con un disco chiaro su fondo scuro e la sua verita'. */
  private class Scene(val w: Int, val h: Int, val cx: Float, val cy: Float, val r: Float) {
    val pixels = IntArray(w * h) { if (inside(it)) 0xFFE6E6E6.toInt() else 0xFF1E1E1E.toInt() }
    val truth = BooleanArray(w * h) { inside(it) }
    private fun inside(i: Int) = hypot((i % w + 0.5f) - cx, (i / w + 0.5f) - cy) <= r
  }

  @Test
  fun ilFiltroGuidatoPortaIlBordoDellaMappaGrossolanaSulBordoVero() {
    val scene = Scene(1200, 900, 600f, 450f, 260f)
    // La mappa del modello, 192x144 (grossolana), disegnata con il disco spostato di 1 px del modello (~6 px dell'immagine) e sfocato.
    val coarse = ProbabilityMap(192, 144, disc(192, 144, 600f / 1200 * 192 + 1f, 450f / 900 * 144, 260f / 1200 * 192, soft = 3f))
    val refined = MaskRefiner.refine(scene.pixels, scene.w, scene.h, coarse)
    val mask = AlphaMask(scene.w, scene.h, refined.alpha)
    val iou = CutoutQuality.iou(mask, scene.truth)
    assertThat(iou).isGreaterThan(0.985f)
    assertThat(refined.coverage).isWithin(0.01f).of(Math.PI.toFloat() * 260f * 260f / (1200f * 900f))
    val bounds = refined.bounds!!
    assertThat(bounds.left).isIn(338..342)
    assertThat(bounds.right).isIn(858..862)
  }

  @Test
  fun lontanoDalSoggettoLAlfaEZeroEDentroEPieno() {
    val scene = Scene(400, 300, 200f, 150f, 80f)
    val coarse = ProbabilityMap(64, 48, disc(64, 48, 32f, 24f, 12.8f, soft = 1.5f))
    val refined = MaskRefiner.refine(scene.pixels, scene.w, scene.h, coarse)
    assertThat(refined.alpha[10 * 400 + 10].toInt() and 0xff).isEqualTo(0)
    assertThat(refined.alpha[150 * 400 + 200].toInt() and 0xff).isEqualTo(255)
    // Il bordo e' sfumato di un pixel o due, non a gradino ne' una fascia larga.
    val edge = (0 until 400).count { x -> (refined.alpha[150 * 400 + x].toInt() and 0xff) in 20..235 }
    assertThat(edge).isAtMost(8)
  }

  @Test
  fun unaFotoDaDodiciMegapixelSiRaffinaSenzaMemoriaInPiu() {
    val scene = Scene(4096, 3072, 2000f, 1500f, 900f)
    // La mappa e' 512x512 su un'immagine 4:3: il modello deforma, e il disco dell'immagine e' un'ellisse nella mappa.
    val coarse = coarseOf(scene, 512, 512, soft = 2f)
    val refined = MaskRefiner.refine(scene.pixels, scene.w, scene.h, coarse)
    assertThat(refined.alpha.size).isEqualTo(4096 * 3072)
    assertThat(refined.bounds!!.left).isIn(1090..1110)
    assertThat(refined.coverage).isWithin(0.01f).of(Math.PI.toFloat() * 900f * 900f / (4096f * 3072f))
  }

  @Test
  fun unaMappaVuotaNonDaUnRettangoloDelSoggetto() {
    val scene = Scene(200, 150, 100f, 75f, 40f)
    val refined = MaskRefiner.refine(scene.pixels, 200, 150, ProbabilityMap(32, 24, FloatArray(32 * 24)))
    assertThat(refined.bounds).isNull()
    assertThat(refined.coverage).isEqualTo(0f)
  }
}
