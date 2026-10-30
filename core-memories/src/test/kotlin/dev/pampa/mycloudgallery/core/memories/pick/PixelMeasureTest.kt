package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import java.util.Random
import org.junit.Test

/** La misura dei pixel (D6) come funzione pura su un array di luminanze: luce media e nitidezza (varianza del laplaciano). */
class PixelMeasureTest {

  private fun gray(width: Int, height: Int, value: (x: Int, y: Int) -> Int) =
    ByteArray(width * height) { value(it % width, it / width).toByte() }

  private fun noise(width: Int, height: Int, seed: Long): ByteArray {
    val random = Random(seed)
    return ByteArray(width * height) { random.nextInt(256).toByte() }
  }

  /** Un filtro a scatola 3 x 3 (i bordi si ripetono): toglie i dettagli come una foto mossa. */
  private fun blur(source: ByteArray, width: Int, height: Int, rounds: Int): ByteArray {
    var current = source
    repeat(rounds) {
      val next = ByteArray(current.size)
      for (y in 0 until height) for (x in 0 until width) {
        var sum = 0
        for (dy in -1..1) for (dx in -1..1) {
          sum += current[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)].toInt() and 0xFF
        }
        next[y * width + x] = (sum / 9).toByte()
      }
      current = next
    }
    return current
  }

  @Test
  fun unaFotoUniformeHaNitidezzaZeroELuceDelSuoGrigio() {
    val probe = PixelMeasure.probe(gray(64, 48) { _, _ -> 128 }, 64, 48)!!
    assertThat(probe.sharpness).isEqualTo(0f)
    assertThat(probe.luma).isWithin(1e-6f).of(128f / 255f)
  }

  @Test
  fun neroEBianco() {
    assertThat(PixelMeasure.probe(gray(16, 16) { _, _ -> 0 }, 16, 16)!!.luma).isEqualTo(0f)
    assertThat(PixelMeasure.probe(gray(16, 16) { _, _ -> 255 }, 16, 16)!!.luma).isEqualTo(1f)
  }

  /** I byte sono senza segno: 0xFF (che in Kotlin e' -1) vale 255, non -1. */
  @Test
  fun iByteSonoSenzaSegno() {
    val bright = ByteArray(16 * 16) { 0xFF.toByte() }
    assertThat(bright[0].toInt()).isEqualTo(-1)
    assertThat(PixelMeasure.probe(bright, 16, 16)!!.luma).isEqualTo(1f)
    assertThat(PixelMeasure.probe(ByteArray(16 * 16) { 0x80.toByte() }, 16, 16)!!.luma).isWithin(1e-6f).of(128f / 255f)
  }

  @Test
  fun laLuceMediaESullaMeta() {
    val halves = gray(32, 32) { x, _ -> if (x < 16) 0 else 255 }
    assertThat(PixelMeasure.probe(halves, 32, 32)!!.luma).isWithin(1e-6f).of(0.5f)
  }

  /** Una rampa di luce senza bordi ha il laplaciano nullo: nitidezza zero (e' liscia, non e' "mossa" per forza: lo decide la soglia). */
  @Test
  fun unaRampaEscia() {
    val probe = PixelMeasure.probe(gray(64, 64) { x, _ -> x * 4 }, 64, 64)!!
    assertThat(probe.sharpness).isEqualTo(0f)
  }

  /** Una scacchiera a un pixel e' il massimo dei bordi: il laplaciano e' +-1020 e la varianza e' circa 1020 al quadrato. */
  @Test
  fun unaScacchieraEIlMassimo() {
    val probe = PixelMeasure.probe(gray(64, 64) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }, 64, 64)!!
    assertThat(probe.sharpness).isWithin(1_000f).of(1_040_400f)
  }

  /** Sfocare toglie nitidezza, e la soglia di D6 (100) separa una foto netta da una mossa. */
  @Test
  fun sfocareToglieNitidezza() {
    val sharp = noise(128, 96, 5)
    val blurred = blur(sharp, 128, 96, rounds = 3)
    val sharpProbe = PixelMeasure.probe(sharp, 128, 96)!!
    val blurredProbe = PixelMeasure.probe(blurred, 128, 96)!!
    assertThat(blurredProbe.sharpness).isLessThan(sharpProbe.sharpness / 10)
    val threshold = 100f
    assertThat(sharpProbe.sharpness).isGreaterThan(threshold)
    val veryBlurred = PixelMeasure.probe(blur(sharp, 128, 96, rounds = 12), 128, 96)!!.sharpness
    assertThat(veryBlurred).isLessThan(threshold)
    // I numeri restano nel resoconto del test (build/test-results), per `docs/decisioni.md`.
    println("nitidezza: rumore %.1f, sfocata 3 giri %.2f, sfocata 12 giri %.3f".format(sharpProbe.sharpness, blurredProbe.sharpness, veryBlurred))
  }

  /** La misura di una foto mossa fa scattare la penalita' di D6, quella di una foto netta no; una foto buia paga la luce. */
  @Test
  fun laMisuraAlimentaIlPunteggio() {
    val cells = Cells()
    val sharpCell = cells.cell(0)
    val blurryCell = cells.cell(10)
    val darkCell = cells.cell(20)
    val sharp = PixelMeasure.probe(noise(128, 96, 9), 128, 96)!!
    val blurry = PixelMeasure.probe(blur(noise(128, 96, 9), 128, 96, rounds = 12), 128, 96)!!
    val dark = PixelMeasure.probe(gray(128, 96) { x, y -> if ((x + y) % 2 == 0) 0 else 40 }, 128, 96)!!
    val signals = PickSignals(pixels = mapOf(sharpCell.id to sharp, blurryCell.id to blurry, darkCell.id to dark))
    assertThat(PhotoScore.of(sharpCell, signals)).isEqualTo(0.0)
    assertThat(PhotoScore.of(blurryCell, signals)).isEqualTo(-1.5)
    assertThat(dark.luma).isLessThan(0.12f)
    assertThat(PhotoScore.of(darkCell, signals)).isEqualTo(-1.0)
  }

  // --- ARGB ---

  @Test
  fun laLuminanzaArgb() {
    assertThat(PixelMeasure.luminanceOf(0xFF000000.toInt())).isEqualTo(0)
    assertThat(PixelMeasure.luminanceOf(0xFFFFFFFF.toInt())).isEqualTo(255)
    assertThat(PixelMeasure.luminanceOf(0xFFFF0000.toInt())).isEqualTo(54)
    assertThat(PixelMeasure.luminanceOf(0xFF00FF00.toInt())).isEqualTo(182)
    assertThat(PixelMeasure.luminanceOf(0xFF0000FF.toInt())).isEqualTo(18)
    // L'alfa non conta.
    assertThat(PixelMeasure.luminanceOf(0x00FFFFFF)).isEqualTo(255)
  }

  @Test
  fun ilCaminoArgbDaLoStessoRisultatoDelGrigio() {
    val random = Random(3)
    val gray = ByteArray(64 * 48) { random.nextInt(256).toByte() }
    val argb = IntArray(gray.size) { val v = gray[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    assertThat(PixelMeasure.probeArgb(argb, 64, 48)).isEqualTo(PixelMeasure.probe(gray, 64, 48))
  }

  // --- Non misurabile ---

  @Test
  fun unaMiniaturaNonMisurabileDaNull() {
    assertThat(PixelMeasure.probe(ByteArray(0), 0, 0)).isNull()
    assertThat(PixelMeasure.probe(ByteArray(4), 2, 2)).isNull()
    assertThat(PixelMeasure.probe(ByteArray(2 * 100), 2, 100)).isNull()
    // Lunghezza sbagliata.
    assertThat(PixelMeasure.probe(ByteArray(10), 4, 4)).isNull()
    assertThat(PixelMeasure.probeArgb(IntArray(10), 4, 4)).isNull()
    // Troppo grande: la misura non sarebbe confrontabile con la soglia.
    val side = PixelMeasure.MAX_SIDE + 1
    assertThat(PixelMeasure.probe(ByteArray(side * 4), side, 4)).isNull()
    assertThat(PixelMeasure.probe(ByteArray(3 * 3), 3, 3)).isNotNull()
  }

  /** La misura non modifica l'array e da' sempre lo stesso numero. */
  @Test
  fun puraEDeterministica() {
    val source = noise(100, 80, 1)
    val copy = source.copyOf()
    val first = PixelMeasure.probe(source, 100, 80)
    assertThat(source).isEqualTo(copy)
    assertThat(PixelMeasure.probe(source, 100, 80)).isEqualTo(first)
  }

  /** Il lato lungo di 256 px e' il caso d'uso: una miniatura da 256 x 192 si misura in pochi millisecondi anche a freddo. */
  @Test
  fun unaMiniaturaDa256SiMisuraSubito() {
    val thumb = noise(PixelMeasure.TARGET_SIDE, 192, 2)
    val start = System.nanoTime()
    repeat(20) { PixelMeasure.probe(thumb, PixelMeasure.TARGET_SIDE, 192) }
    val perCallMs = (System.nanoTime() - start) / 20 / 1e6
    assertThat(perCallMs).isLessThan(20.0)
  }

  /** Nessun valore fuori dai limiti di `PixelProbe`: luce in 0..1, nitidezza non negativa, mai NaN. */
  @Test
  fun proprietaLeMisureSonoSempreValide() {
    val random = Random(8)
    repeat(200) {
      val width = 3 + random.nextInt(60)
      val height = 3 + random.nextInt(60)
      val probe = PixelMeasure.probe(ByteArray(width * height) { random.nextInt(256).toByte() }, width, height)!!
      assertThat(probe.sharpness.isNaN()).isFalse()
      assertThat(probe.sharpness).isAtLeast(0f)
      assertThat(probe.luma).isAtLeast(0f)
      assertThat(probe.luma).isAtMost(1f)
    }
  }
}
