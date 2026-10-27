package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FloatBox
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Test

/**
 * Fase 8 (P2, riga P2 del §4.4: "qualita' e livelli"; D19). I tre livelli ai loro confini (24 e 48 px, 0,80, 35 e 60 gradi), i motivi, l'imbardata
 * dai cinque punti (anche con il capo inclinato), la nitidezza su un volto disegnato e sfocato a gradi, e il fatto che il giudizio non porta
 * altro che livello, motivi, imbardata e nitidezza (nessun attributo della persona).
 */
class FaceQualityTest {

  // --- L'imbardata ------------------------------------------------------------------------------------------------------------

  /** Cinque punti di un volto largo 100 px con il capo girato di [yawDeg] gradi (il naso sporge 0,5 distanze fra gli occhi) e inclinato di [rollDeg] nel piano. */
  private fun landmarks(yawDeg: Double, rollDeg: Double = 0.0): FloatArray {
    val eye = 40.0 // distanza fra gli occhi, px
    val yaw = Math.toRadians(yawDeg)
    val noseOffset = FaceQuality.NOSE_DEPTH_RATIO * eye * tan(yaw)
    val local = doubleArrayOf(-eye / 2, 0.0, eye / 2, 0.0, noseOffset, 25.0, -eye * 0.3, 55.0, eye * 0.3, 55.0)
    val roll = Math.toRadians(rollDeg)
    return FloatArray(10) { i ->
      val x = local[i - i % 2]
      val y = local[i - i % 2 + 1]
      val rx = x * cos(roll) - y * sin(roll)
      val ry = x * sin(roll) + y * cos(roll)
      (if (i % 2 == 0) 500.0 + rx else 400.0 + ry).toFloat()
    }
  }

  @Test
  fun lImbardataESegnataEInvarianteAlRollEAlScalare() {
    for (deg in listOf(-75, -60, -45, -35, -20, -10, 0, 10, 20, 35, 45, 60, 75)) {
      assertWithMessage("imbardata $deg").that(FaceQuality.yawOf(landmarks(deg.toDouble()))).isWithin(1).of(deg)
      for (roll in listOf(-40.0, 25.0, 90.0, 170.0)) {
        assertWithMessage("imbardata $deg con roll $roll").that(FaceQuality.yawOf(landmarks(deg.toDouble(), roll))).isWithin(1).of(deg)
      }
    }
    // Una foto ingrandita o rimpicciolita non cambia l'angolo.
    val l = landmarks(30.0)
    val doubled = FloatArray(10) { l[it] * 2f }
    assertThat(FaceQuality.yawOf(doubled)).isWithin(1).of(FaceQuality.yawOf(l))
    // Verso: il naso a destra (nell'immagine) e' positivo.
    assertThat(FaceQuality.yawOf(landmarks(30.0))).isGreaterThan(0)
    assertThat(FaceQuality.yawOf(landmarks(-30.0))).isLessThan(0)
  }

  @Test
  fun occhiCoincidentiONonFinitiNonSiFidano() {
    assertThat(FaceQuality.yawOf(FloatArray(10) { 7f })).isEqualTo(90)
    assertThat(FaceQuality.yawOf(FloatArray(10) { Float.NaN }.also { it[0] = 1f })).isEqualTo(90)
  }

  // --- I livelli ----------------------------------------------------------------------------------------------------------------

  private fun face(score: Float = 0.95f, yaw: Double = 0.0): DetectedFace = DetectedFace(FloatBox(100f, 100f, 300f, 300f), landmarks(yaw), score)

  /** Un volto allineato nitido (il disegno di sotto) o sfocato. */
  private fun crop(sigma: Double = 0.0): AlignedFace = drawnFace(sigma)

  @Test
  fun coreDa48PxCon080E34GradiENitido() {
    val v = FaceQuality.assess(face(0.80f, 34.0), 48, crop())
    assertThat(v.tier).isEqualTo(FaceTier.CORE)
    assertThat(v.reasons).isEqualTo(0)
    assertThat(v.yaw).isWithin(1).of(34)
    assertThat(v.sharpness).isAtLeast(FaceQuality.BLURRY_BELOW)
  }

  @Test
  fun iConfiniDelleDimensioni_23NonHaIlVettore_24EDebole_47EDebole_48Core() {
    assertThat(FaceQuality.assess(face(), 23, crop()).tier).isEqualTo(FaceTier.NONE)
    for (side in listOf(24, 30, 47)) {
      val v = FaceQuality.assess(face(), side, crop())
      assertWithMessage("lato $side").that(v.tier).isEqualTo(FaceTier.WEAK)
      assertThat(v.reasons and FaceReasons.SMALL).isEqualTo(FaceReasons.SMALL)
    }
    assertThat(FaceQuality.assess(face(), 48, crop()).tier).isEqualTo(FaceTier.CORE)
    assertThat(FaceQuality.assess(face(), 800, crop()).tier).isEqualTo(FaceTier.CORE)
    // Il giudizio di un volto di 23 px porta lo stesso il motivo.
    assertThat(FaceQuality.assess(face(), 23, crop()).reasons and FaceReasons.SMALL).isEqualTo(FaceReasons.SMALL)
  }

  @Test
  fun iConfiniDelPunteggio_079EDebole_080Core_EUnPunteggioBassoNonHaUnMotivo() {
    val low = FaceQuality.assess(face(score = 0.79f), 100, crop())
    assertThat(low.tier).isEqualTo(FaceTier.WEAK)
    assertThat(low.reasons).isEqualTo(0)
    assertThat(FaceQuality.assess(face(score = 0.80f), 100, crop()).tier).isEqualTo(FaceTier.CORE)
    assertThat(FaceQuality.assess(face(score = 0.60f), 100, crop()).tier).isEqualTo(FaceTier.WEAK)
  }

  @Test
  fun iConfiniDellImbardata_35Core_36Debole_60Debole_61NonHaIlVettore() {
    assertThat(FaceQuality.assess(face(yaw = 34.0), 100, crop()).tier).isEqualTo(FaceTier.CORE)
    for (deg in listOf(38.0, 45.0, 55.0, 58.0)) {
      val v = FaceQuality.assess(face(yaw = deg), 100, crop())
      assertWithMessage("imbardata $deg").that(v.tier).isEqualTo(FaceTier.WEAK)
      assertThat(v.reasons and FaceReasons.PROFILE).isEqualTo(FaceReasons.PROFILE)
    }
    for (deg in listOf(63.0, 75.0, -80.0)) {
      val v = FaceQuality.assess(face(yaw = deg), 100, crop())
      assertWithMessage("imbardata $deg").that(v.tier).isEqualTo(FaceTier.NONE)
      assertThat(v.reasons and FaceReasons.PROFILE).isEqualTo(FaceReasons.PROFILE)
    }
    // Il segno non conta: -40 e +40 sono lo stesso livello.
    assertThat(FaceQuality.assess(face(yaw = -40.0), 100, crop()).tier).isEqualTo(FaceQuality.assess(face(yaw = 40.0), 100, crop()).tier)
  }

  @Test
  fun sfocatoEDebole_SenzaIlRitaglioLaNitidezzaNonSiGiudica() {
    val blurry = FaceQuality.assess(face(), 100, crop(sigma = 4.0))
    assertThat(blurry.tier).isEqualTo(FaceTier.WEAK)
    assertThat(blurry.reasons and FaceReasons.BLURRY).isEqualTo(FaceReasons.BLURRY)
    assertThat(blurry.sharpness).isLessThan(FaceQuality.BLURRY_BELOW)
    // Senza il ritaglio: livello provvisorio, nitidezza 0 e nessun motivo "sfocato".
    val unknown = FaceQuality.assess(face(), 100, null)
    assertThat(unknown.tier).isEqualTo(FaceTier.CORE)
    assertThat(unknown.sharpness).isEqualTo(0)
    assertThat(unknown.reasons).isEqualTo(0)
  }

  @Test
  fun piuMotiviInsieme_ENessunBitSconosciuto() {
    val v = FaceQuality.assess(face(score = 0.9f, yaw = 50.0), 30, crop(sigma = 4.0))
    assertThat(v.tier).isEqualTo(FaceTier.WEAK)
    assertThat(v.reasons).isEqualTo(FaceReasons.SMALL or FaceReasons.PROFILE or FaceReasons.BLURRY)
    assertThat(FaceReasons.isValid(v.reasons)).isTrue()
  }

  @Test
  fun ilGiudizioHaSoloLivelloMotiviImbardataENitidezza_NessunAttributoDellaPersona() {
    val fields = dev.pampa.mycloudgallery.core.ai.contract.QualityVerdict::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }
    assertThat(fields).containsExactly("tier", "reasons", "yaw", "sharpness")
    // E' un tipo puro: nessun metodo che parli di eta', genere, emozioni.
    val methods = dev.pampa.mycloudgallery.core.ai.contract.QualityVerdict::class.java.declaredMethods.map { it.name.lowercase() }
    for (word in listOf("age", "eta", "gender", "genere", "ethnic", "emotion", "smile", "glasses")) assertWithMessage(word).that(methods.none { word in it }).isTrue()
  }

  // --- La nitidezza -------------------------------------------------------------------------------------------------------------

  @Test
  fun unRitaglioUniformeVale0_UnoNitidoMoltoDiPiu() {
    val flat = AlignedFace(IntArray(112 * 112) { 0xFF808080.toInt() })
    assertThat(FaceQuality.sharpnessOf(flat)).isEqualTo(0)
    assertThat(FaceQuality.sharpnessOf(crop())).isAtLeast(200)
  }

  @Test
  fun laNitidezzaScendeConLaSfocaturaESuperaLaSogliaSoloOltreUnCertoPunto() {
    val sharpness = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0).map { FaceQuality.sharpnessOf(crop(it)) }
    assertThat(sharpness).isInOrder(compareByDescending<Int> { it })
    // Sfocature lievi restano nitide per la soglia, quelle forti (sigma >= 3 px nel ritaglio da 112) no.
    assertThat(sharpness[3]).isAtLeast(FaceQuality.BLURRY_BELOW) // sigma 1,5
    assertThat(sharpness[5]).isLessThan(FaceQuality.BLURRY_BELOW) // sigma 3
    // Una grana di pelle forte, sfocata insieme al resto, non fa credere nitido un volto sfocato (la misura segue i bordi).
    assertThat(FaceQuality.sharpnessOf(drawnFace(sigma = 3.0, noise = 10.0))).isLessThan(FaceQuality.BLURRY_BELOW)
  }

  // --- Un volto disegnato (nessun volto vero): ellisse, occhi, sopracciglia, naso, bocca e capelli, con grana di pelle, sfocato con una gaussiana ----------------

  private fun drawnFace(sigma: Double, noise: Double = 5.0): AlignedFace {
    val s = FaceEngineLimits.ALIGNED_SIDE
    val rgb = Array(3) { DoubleArray(s * s) { 90.0 } }
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, r: Int, g: Int, b: Int, onlyAbove: Double = 1e9) {
      for (y in 0 until s) for (x in 0 until s) {
        if (((x - cx) / rx) * ((x - cx) / rx) + ((y - cy) / ry) * ((y - cy) / ry) <= 1.0 && y < onlyAbove) {
          rgb[0][y * s + x] = r.toDouble()
          rgb[1][y * s + x] = g.toDouble()
          rgb[2][y * s + x] = b.toDouble()
        }
      }
    }
    ellipse(56.0, 60.0, 40.0, 48.0, 205, 160, 140)
    ellipse(56.0, 60.0, 40.0, 48.0, 50, 40, 35, onlyAbove = 25.0)
    ellipse(38.0, 52.0, 7.0, 4.0, 40, 30, 30)
    ellipse(73.0, 52.0, 7.0, 4.0, 40, 30, 30)
    ellipse(38.0, 43.0, 9.0, 2.0, 70, 50, 40)
    ellipse(73.0, 43.0, 9.0, 2.0, 70, 50, 40)
    ellipse(56.0, 72.0, 3.0, 8.0, 170, 120, 105)
    ellipse(56.0, 92.0, 13.0, 3.0, 150, 60, 70)
    val random = java.util.Random(11)
    for (c in 0 until 3) for (i in 0 until s * s) rgb[c][i] += random.nextGaussian() * noise
    val blurred = if (sigma > 0) Array(3) { gaussian(rgb[it], s, sigma) } else rgb
    return AlignedFace(
      IntArray(s * s) { i ->
        (0xFF shl 24) or (blurred[0][i].toInt().coerceIn(0, 255) shl 16) or (blurred[1][i].toInt().coerceIn(0, 255) shl 8) or blurred[2][i].toInt().coerceIn(0, 255)
      },
    )
  }

  private fun gaussian(plane: DoubleArray, s: Int, sigma: Double): DoubleArray {
    val k = Math.ceil(sigma * 3).toInt()
    val w = DoubleArray(2 * k + 1) { exp(-((it - k) * (it - k)) / (2 * sigma * sigma)) }
    val total = w.sum()
    for (i in w.indices) w[i] /= total
    val tmp = DoubleArray(s * s)
    val out = DoubleArray(s * s)
    for (y in 0 until s) for (x in 0 until s) {
      var acc = 0.0
      for (j in -k..k) acc += w[j + k] * plane[y * s + (x + j).coerceIn(0, s - 1)]
      tmp[y * s + x] = acc
    }
    for (y in 0 until s) for (x in 0 until s) {
      var acc = 0.0
      for (j in -k..k) acc += w[j + k] * tmp[(y + j).coerceIn(0, s - 1) * s + x]
      out[y * s + x] = acc
    }
    return out
  }

}
