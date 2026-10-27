package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P2, riga P2 del §4.4: "allineamento confrontato con i punti di riferimento"). La similitudine ai minimi quadrati dai cinque punti al
 * modello a 112 px di ArcFace contro le matrici di P1 (`common.similitudine_umeyama`), i punti trasformati contro il modello, e il ritaglio
 * 112x112 contro un'immagine analitica (un gradiente: l'interpolazione bilineare di una funzione lineare e' esatta).
 */
class FaceAlignerTest {

  private val template = FaceAligner.TEMPLATE

  private fun apply(m: DoubleArray, x: Double, y: Double) = doubleArrayOf(m[0] * x - m[1] * y + m[2], m[1] * x + m[0] * y + m[3])

  // --- Contro le matrici di P1 ----------------------------------------------------------------------------------------------

  @Test
  fun laSimilitudineDaGliStessiNumeriDiP1() {
    val cases = FaceFixtures.alignmentCases()
    assertThat(cases.size).isAtLeast(20)
    for ((i, case) in cases.withIndex()) {
      val m = checkNotNull(FaceAligner.similarity(case.landmarks)) { "caso $i" }
      val scale = hypot(case.a, case.b)
      assertWithMessage("caso $i: a").that(m[0]).isWithin(1e-6 * scale).of(case.a)
      assertWithMessage("caso $i: b").that(m[1]).isWithin(1e-6 * scale).of(case.b)
      assertWithMessage("caso $i: tx").that(m[2]).isWithin(1e-4).of(case.tx)
      assertWithMessage("caso $i: ty").that(m[3]).isWithin(1e-4).of(case.ty)
    }
  }

  @Test
  fun iPuntiTrasformatiStannoSulModelloQuantoDiceP1() {
    for ((i, case) in FaceFixtures.alignmentCases().withIndex()) {
      val ours = checkNotNull(FaceAligner.similarity(case.landmarks))
      val theirs = doubleArrayOf(case.a, case.b, case.tx, case.ty)
      var rmsOurs = 0.0
      var rmsTheirs = 0.0
      for (p in 0 until 5) {
        val x = case.landmarks[2 * p].toDouble()
        val y = case.landmarks[2 * p + 1].toDouble()
        val a = apply(ours, x, y)
        val b = apply(theirs, x, y)
        assertWithMessage("caso $i punto $p").that(hypot(a[0] - b[0], a[1] - b[1])).isLessThan(1e-3)
        rmsOurs += (a[0] - template[2 * p]).let { it * it } + (a[1] - template[2 * p + 1]).let { it * it }
        rmsTheirs += (b[0] - template[2 * p]).let { it * it } + (b[1] - template[2 * p + 1]).let { it * it }
      }
      // Lo stesso residuo ai minimi quadrati di P1 (e il residuo e' quello di un volto vero: pochi pixel del modello, non decine).
      assertWithMessage("caso $i: residuo").that(Math.sqrt(rmsOurs / 5)).isWithin(1e-3).of(Math.sqrt(rmsTheirs / 5))
      assertWithMessage("caso $i: residuo piccolo").that(Math.sqrt(rmsOurs / 5)).isLessThan(12.0)
    }
  }

  @Test
  fun puntiGiaSulModelloDannoLIdentita() {
    val m = checkNotNull(FaceAligner.similarity(template.copyOf()))
    assertThat(m[0]).isWithin(1e-9).of(1.0)
    assertThat(m[1]).isWithin(1e-9).of(0.0)
    assertThat(m[2]).isWithin(1e-4).of(0.0)
    assertThat(m[3]).isWithin(1e-4).of(0.0)
  }

  @Test
  fun unaSimilitudineEsattaSiRitrovaEPortaIPuntiSulModelloSenzaResiduo() {
    val random = Random(7)
    repeat(200) {
      val angle = (random.nextDouble() - 0.5) * 2.4
      val scale = 0.2 + random.nextDouble() * 6.0
      val tx = random.nextDouble() * 2000
      val ty = random.nextDouble() * 2000
      // I punti del volto: il modello portato "indietro" da una similitudine a caso.
      val points = FloatArray(10)
      for (p in 0 until 5) {
        val x = template[2 * p] - 56.0
        val y = template[2 * p + 1] - 56.0
        points[2 * p] = (scale * (cos(angle) * x - sin(angle) * y) + tx).toFloat()
        points[2 * p + 1] = (scale * (sin(angle) * x + cos(angle) * y) + ty).toFloat()
      }
      val m = checkNotNull(FaceAligner.similarity(points))
      assertThat(hypot(m[0], m[1])).isWithin(1e-3 / scale).of(1.0 / scale)
      for (p in 0 until 5) {
        val out = apply(m, points[2 * p].toDouble(), points[2 * p + 1].toDouble())
        // float32 con coordinate fino a ~13.000: 1e-3 px di risoluzione.
        assertThat(hypot(out[0] - template[2 * p], out[1] - template[2 * p + 1])).isLessThan(5e-3)
      }
    }
  }

  @Test
  fun puntiCoincidentiONonFinitiNonHannoSimilitudine_EAlignLanciaUnCodice() {
    assertThat(FaceAligner.similarity(FloatArray(10) { 100f })).isNull()
    assertThat(FaceAligner.similarity(FloatArray(10) { if (it == 3) Float.NaN else it.toFloat() * 10 })).isNull()
    assertThat(FaceAligner.similarity(FloatArray(10) { if (it == 3) Float.POSITIVE_INFINITY else it.toFloat() * 10 })).isNull()
    assertThat(FaceAligner.similarity(FloatArray(9))).isNull()
    val image = FaceImage(IntArray(64 * 64), 64, 64)
    val error = assertThrows(AiEngineException::class.java) { FaceAligner.align(image, FloatArray(10) { 5f }) }
    assertThat(error.error).isEqualTo(AiEngineError.BAD_INPUT)
    assertThrows(IllegalArgumentException::class.java) { FaceAligner.align(image, FloatArray(8)) }
  }

  // --- Il ritaglio ------------------------------------------------------------------------------------------------------------

  /** Un gradiente: R cresce con x, G con y, B con x + y. Interpolato in modo bilineare e' lineare: il valore atteso si calcola a mano. */
  private fun gradient(width: Int, height: Int): FaceImage =
    FaceImage(
      IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        (0xFF shl 24) or ((x * 255 / (width - 1)) shl 16) or ((y * 255 / (height - 1)) shl 8) or ((x + y) * 255 / (width + height - 2))
      },
      width,
      height,
    )

  @Test
  fun ilRitaglioEIlGradienteVistoAttraversoLaSimilitudineInversa() {
    val width = 1000
    val height = 800
    val image = gradient(width, height)
    // Un volto inclinato di 12 gradi e largo 300 px (scala 112/300 circa) nel mezzo dell'immagine.
    val angle = Math.toRadians(12.0)
    val s = 300.0 / 112.0
    val points = FloatArray(10)
    for (p in 0 until 5) {
      val x = template[2 * p] - 56.0
      val y = template[2 * p + 1] - 56.0
      points[2 * p] = (s * (cos(angle) * x - sin(angle) * y) + 520).toFloat()
      points[2 * p + 1] = (s * (sin(angle) * x + cos(angle) * y) + 400).toFloat()
    }
    val aligned = FaceAligner.align(image, points)
    val m = checkNotNull(FaceAligner.similarity(points))
    val det = m[0] * m[0] + m[1] * m[1]
    var worst = 0
    for (y in 0 until 112) for (x in 0 until 112) {
      val px = x - m[2]
      val py = y - m[3]
      val sx = (m[0] * px + m[1] * py) / det
      val sy = (-m[1] * px + m[0] * py) / det
      val expectedR = sx * 255 / (width - 1)
      val expectedG = sy * 255 / (height - 1)
      val expectedB = (sx + sy) * 255 / (width + height - 2)
      val p = aligned.pixelAt(x, y)
      worst = maxOf(worst, kotlin.math.abs(((p shr 16) and 0xff) - expectedR.toInt()), kotlin.math.abs(((p shr 8) and 0xff) - expectedG.toInt()), kotlin.math.abs((p and 0xff) - expectedB.toInt()))
    }
    // Il gradiente e' memorizzato in interi (errore di mezzo livello) e il ritaglio e' arrotondato: al massimo 2 livelli su 255.
    assertThat(worst).isAtMost(2)
    // Il centro del ritaglio e' il centro del volto: il punto (520, 400) cade a meta' circa (nel modello, il centro e' fra gli occhi e il naso).
    assertThat((aligned.pixelAt(56, 56) shr 16) and 0xff).isAtLeast(100)
  }

  @Test
  fun fuoriDallImmagineIlRitaglioENero_EDentroNo() {
    val image = gradient(200, 200)
    // Un volto largo 56 px con il centro vicino a un angolo (10, 10): il ritaglio copre 56 px di sorgente, 18 dei quali fuori dall'immagine.
    val points = FloatArray(10) { i -> (10.0 + (template[i] - 56.0) * 0.5).toFloat() }
    val aligned = FaceAligner.align(image, points)
    var black = 0
    for (y in 0 until 112) for (x in 0 until 112) if ((aligned.pixelAt(x, y) and 0xffffff) == 0) black++
    assertThat(black).isGreaterThan(112 * 112 / 3)
    // I pixel sono opachi (l'alfa non conta ma non deve essere sporco).
    assertThat(aligned.pixelAt(0, 0) ushr 24).isEqualTo(0xFF)
    // Dentro l'immagine il colore non e' nero: il centro del ritaglio e' il punto (10, 10), dove il gradiente vale ~13 in R e G.
    val p = aligned.pixelAt(56, 56)
    assertThat((p shr 16) and 0xff).isGreaterThan(0)
    assertThat((p shr 8) and 0xff).isGreaterThan(0)
  }

  @Test
  fun unaSimilitudineEUnaRotazioneConScalaUniforme_MaiUnaRiflessione() {
    // Occhi e bocca scambiati da sinistra a destra (un volto "specchiato" nei punti): il minimo ai quadrati resta una rotazione con scala, la parte
    // lineare [[a, -b], [b, a]] ha sempre determinante positivo, e non puo' portare i punti scambiati sul modello meglio di quanto faccia una rotazione.
    val mirrored = floatArrayOf(
      template[2], template[3], template[0], template[1], template[4], template[5], template[8], template[9], template[6], template[7],
    )
    val m = checkNotNull(FaceAligner.similarity(mirrored))
    assertThat(m[0] * m[0] + m[1] * m[1]).isGreaterThan(0.0)
    var residual = 0.0
    for (p in 0 until 5) {
      val out = apply(m, mirrored[2 * p].toDouble(), mirrored[2 * p + 1].toDouble())
      residual += hypot(out[0] - template[2 * p], out[1] - template[2 * p + 1])
    }
    assertThat(residual).isGreaterThan(5.0) // uno specchio li avrebbe portati a zero
  }

  @Test
  fun ilRitaglioHaLaTagliaDelModello() {
    val aligned = FaceAligner.align(gradient(300, 300), TensorSynth.faceLandmarks(floatArrayOf(60f, 60f, 140f, 160f)))
    assertThat(aligned.pixels).hasLength(FaceEngineLimits.ALIGNED_SIDE * FaceEngineLimits.ALIGNED_SIDE)
    assertThat(aligned.toString()).isEqualTo("AlignedFace")
  }
}
