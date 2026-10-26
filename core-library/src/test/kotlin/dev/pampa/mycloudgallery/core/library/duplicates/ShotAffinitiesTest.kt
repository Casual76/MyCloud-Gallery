package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.similar.Raster
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * Il giudizio sulla somiglianza di due scatti (Fase 7, P8, D25): i vettori (coseno ≥ `Sdup`), la firma visiva con le soglie degli scatti simili e
 * il ripiego dagli uni all'altra. Vettori finti scritti a mano: il coseno lo sceglie il test.
 */
class ShotAffinitiesTest {

  private fun id(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  private fun shot(n: Int) = Shot(id(n), 1_000L * n, 20_251_009, 1333)

  /** Due vettori unitari a 768 dimensioni il cui coseno e' [cosine]. */
  private fun pair(cosine: Double): Pair<FloatArray, FloatArray> {
    val angle = kotlin.math.acos(cosine)
    val a = FloatArray(768).also { it[0] = 1f }
    val b = FloatArray(768).also {
      it[0] = cos(angle).toFloat()
      it[1] = sin(angle).toFloat()
    }
    return a to b
  }

  private fun vectors(cosine: Double): ShotVectors {
    val (a, b) = pair(cosine)
    return ShotVectors(mapOf(id(1) to a, id(2) to b))
  }

  @Test
  fun sdupDecideIlConfrontoDeiVettori() {
    val sdup = SimilarShotsRules.SDUP.toDouble()
    assertThat(VectorAffinity(vectors(sdup + 0.004)).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    assertThat(VectorAffinity(vectors(sdup - 0.004)).between(shot(1), shot(2))).isEqualTo(Affinity.DIFFERENT)
    assertThat(VectorAffinity(vectors(1.0)).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    // La soglia si puo' dare (la riprova sul campo la sposta senza toccare il codice).
    assertThat(VectorAffinity(vectors(0.90), sdup = 0.85f).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
  }

  @Test
  fun iVettoriSiNormalizzano_UnaScalaNonCambiaIlGiudizio() {
    val (a, b) = pair(0.99)
    val scaled = ShotVectors(mapOf(id(1) to FloatArray(768) { a[it] * 37f }, id(2) to FloatArray(768) { b[it] * 0.01f }))
    assertThat(VectorAffinity(scaled).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    assertThat(scaled[id(1)]!!.sumOf { it.toDouble() * it }).isWithin(1e-5).of(1.0)
  }

  @Test
  fun unVettoreCheManca_OHaUnaLunghezzaDiversa_OEGuasto_NonDiceNiente() {
    val (a, b) = pair(0.99)
    assertThat(VectorAffinity(ShotVectors(mapOf(id(1) to a))).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
    assertThat(VectorAffinity(ShotVectors(mapOf(id(1) to a, id(2) to FloatArray(256) { 1f }))).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
    val broken = ShotVectors(mapOf(id(1) to a, id(2) to FloatArray(768) { Float.NaN }, id(3) to FloatArray(768), id(4) to floatArrayOf(), id(5) to b))
    assertThat(broken.size).isEqualTo(2)
    assertThat(id(2) in broken).isFalse()
    assertThat(VectorAffinity(broken).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
    assertThat(VectorAffinity(broken).between(shot(1), shot(5))).isEqualTo(Affinity.SIMILAR)
    assertThat(VectorAffinity(ShotVectors.NONE).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
  }

  // --- La firma visiva ------------------------------------------------------------------------------------------------------

  /** Una scena con struttura (barre e un riquadro), il riquadro spostato di [shift] pixel. */
  private fun raster(shift: Int = 0, brightness: Int = 0): Raster {
    val w = 256
    val h = 192
    val pixels = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      var r = 40 + (x * 150 / w)
      var g = 60 + (y * 120 / h)
      var b = 90 + ((x + y) % 64)
      if (x in (100 + shift)..(150 + shift) && y in 60..120) {
        r = 220
        g = 40
        b = 40
      }
      val v = ((r + brightness).coerceIn(0, 255) shl 16) or ((g + brightness).coerceIn(0, 255) shl 8) or (b + brightness).coerceIn(0, 255)
      v
    }
    return Raster.of(w, h, pixels)
  }

  /** Un'altra scena: gradienti opposti e un riquadro in un altro posto. */
  private fun otherScene(): Raster {
    val w = 256
    val h = 192
    val pixels = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      val inBox = x in 20..90 && y in 110..180
      val r = if (inBox) 30 else 200 - (x * 120 / w)
      val g = if (inBox) 160 else 180 - (y * 100 / h)
      val b = if (inBox) 220 else 60 + ((x * 3 + y) % 50)
      (r shl 16) or (g shl 8) or b
    }
    return Raster.of(w, h, pixels)
  }

  private fun signatures(a: Raster, b: Raster) = SignatureAffinity(mapOf(id(1) to VisualSignatures.of(a), id(2) to VisualSignatures.of(b)))

  @Test
  fun laFirmaDiUnoScattoUgualeEDelSoggettoCheSiMuoveSonoSimili_UnaScenaDiversaOUnaLuceDiversaNo() {
    assertThat(signatures(raster(), raster()).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    // Il riquadro (il soggetto) si sposta poco o molto: la disposizione della scena e' la stessa.
    assertThat(signatures(raster(), raster(shift = 2)).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    assertThat(signatures(raster(), raster(shift = 90)).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    // Un'altra scena, o la stessa con un'esposizione molto diversa: no.
    assertThat(signatures(raster(), otherScene()).between(shot(1), shot(2))).isEqualTo(Affinity.DIFFERENT)
    assertThat(signatures(raster(), raster(brightness = 90)).between(shot(1), shot(2))).isEqualTo(Affinity.DIFFERENT)
  }

  @Test
  fun unaFirmaSenzaStrutturaONonConfrontabileNonDiceNiente() {
    val flat = Raster.of(64, 64, IntArray(64 * 64) { 0x6495ED })
    assertThat(signatures(flat, flat).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
    // Un altro algoritmo non si confronta: si ricalcola.
    val foreign = VisualSignature.of(2, 1, ByteArray(VisualSignatures.FRAME_BYTES))
    val mixed = SignatureAffinity(mapOf(id(1) to VisualSignatures.of(raster()), id(2) to foreign))
    assertThat(mixed.between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
    assertThat(SignatureAffinity(emptyMap()).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
  }

  @Test
  fun iVettoriVincono_LaFirmaEIlRipiego() {
    val sig = signatures(raster(), raster())
    val different = VectorAffinity(vectors(0.5))
    // I vettori dicono "diversi" anche se la firma direbbe "simili": vincono i vettori.
    assertThat(CombinedAffinity(different, sig).between(shot(1), shot(2))).isEqualTo(Affinity.DIFFERENT)
    // Senza vettori si ripiega sulla firma.
    assertThat(CombinedAffinity(VectorAffinity(ShotVectors.NONE), sig).between(shot(1), shot(2))).isEqualTo(Affinity.SIMILAR)
    // Senza ne' l'uno ne' l'altra: non si dice niente.
    assertThat(CombinedAffinity(VectorAffinity(ShotVectors.NONE), SignatureAffinity(emptyMap())).between(shot(1), shot(2))).isEqualTo(Affinity.UNKNOWN)
  }
}
