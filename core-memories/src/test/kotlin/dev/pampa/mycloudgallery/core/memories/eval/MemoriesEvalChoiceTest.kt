package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

/** La regola di scelta di §5.3 punto 3, provata a mano su prove inventate (poi gira sulle misure del sintetico: `MemoriesEvalCalibrationTest`). */
class MemoriesEvalChoiceTest {

  private fun trial(value: Int, holds: Boolean, margin: Double = 0.0) = Trial(value, "v$value", holds, margin)

  private val distance: (Int, Int) -> Double = { a, b -> abs(a - b).toDouble() }

  @Test
  fun ilValoreProvvisorioCheRegge_Resta() {
    val choice = MemoriesEvalChoice.choose(100, listOf(trial(60, true), trial(100, true), trial(200, true)), distance)
    assertThat(choice.value).isEqualTo(100)
    assertThat(choice.changed).isFalse()
    assertThat(choice.reachable).isTrue()
  }

  @Test
  fun seIlProvvisorioNonRegge_SiPrendeIlPiuVicinoCheRegge() {
    val trials = listOf(trial(60, true), trial(80, false), trial(100, false), trial(130, true), trial(160, true), trial(300, true))
    val choice = MemoriesEvalChoice.choose(100, trials, distance)
    // 130 dista 30, 60 ne dista 40: il cambiamento piu' piccolo.
    assertThat(choice.value).isEqualTo(130)
    assertThat(choice.changed).isTrue()
    assertThat(choice.reachable).isTrue()
  }

  @Test
  fun aParitaDiDistanzaIlPiuBasso() {
    val choice = MemoriesEvalChoice.choose(100, listOf(trial(70, true), trial(100, false), trial(130, true)), distance)
    assertThat(choice.value).isEqualTo(70)
  }

  @Test
  fun seNessunoRegge_SiPrendeIlMargineMigliorePerDireCheNonEraggiungibile() {
    val choice = MemoriesEvalChoice.choose(100, listOf(trial(60, false, -0.3), trial(100, false, -0.2), trial(200, false, -0.1)), distance)
    assertThat(choice.value).isEqualTo(200)
    assertThat(choice.changed).isTrue()
    assertThat(choice.reachable).isFalse()
  }

  @Test
  fun ilProvvisorioDeveEssereFraIProvati() {
    assertThrows(IllegalArgumentException::class.java) { MemoriesEvalChoice.choose(100, listOf(trial(60, true)), distance) }
  }

  @Test
  fun leMisureSannoDirsiInTesto() {
    val dedupe = DedupeFigures(5.0, 427, 408, 3394, 113).toString()
    assertThat(dedupe).contains("408/427")
    assertThat(dedupe).contains("95.6%")
    val sharp = SharpnessFigures(100, 50, 1000, 20, 2, 0.05).toString()
    assertThat(sharp).contains("soglia 100")
    assertThat(sharp).contains("2.00%")
    assertThat(sharp).contains("0.20%")
    val cosine = CosineFigures(0.92, 100, 100, 50, 0).toString()
    assertThat(cosine).contains("100.0%")
    assertThat(cosine).contains("0.0%")
  }
}
