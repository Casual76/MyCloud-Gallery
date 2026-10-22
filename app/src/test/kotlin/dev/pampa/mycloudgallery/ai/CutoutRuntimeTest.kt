package dev.pampa.mycloudgallery.ai

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeSubjectCutter
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il ritagliatore del processo (Fase 6, P13/P11b, D35 per lo stesso criterio del motore delle query): costruito al primo uso, rilasciato 3 minuti
 * dopo l'ultimo, a memoria bassa e all'azzeramento. Tempo virtuale, nessun dispositivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutoutRuntimeTest {

  @Test
  fun siCostruisceAlPrimoUsoESiRilasciaTreMinutiDopoL_UltimoUso() = runTest {
    val fake = FakeSubjectCutter()
    var built = 0
    val runtime = CutoutRuntime(this, { built++; fake }, maxPixels = 4096L * 3072L, idle = 3.minutes)
    assertThat(runtime.loaded).isFalse()

    runtime.cutting()
    assertThat(built).isEqualTo(1)
    advanceTimeBy(2.minutes)
    // Un altro uso riarma l'attesa: a 2 minuti dal primo non e' ancora il momento.
    runtime.cutting()
    advanceTimeBy(2.minutes)
    assertThat(fake.releases).isEqualTo(0)
    advanceTimeBy(1.minutes + 1.minutes)
    advanceUntilIdle()
    assertThat(fake.releases).isEqualTo(1)
    // Il prossimo uso riapre da solo (lo stesso ritagliatore, un modello da riaprire).
    runtime.cutting()
    assertThat(built).isEqualTo(1)
    runtime.stop()
  }

  @Test
  fun aMemoriaBassaSiRilasciaSubitoMaSoloDaMemoriaBassaInPoi() = runTest {
    val fake = FakeSubjectCutter()
    val runtime = CutoutRuntime(this, { fake }, maxPixels = 1_000_000, idle = 3.minutes)
    runtime.cutting()

    runtime.trim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
    runCurrent()
    assertThat(fake.releases).isEqualTo(0)

    runtime.trim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    runCurrent()
    assertThat(fake.releases).isEqualTo(1)
    runtime.stop()
  }

  @Test
  fun senzaUsoNienteDaRilasciareEDopoLAzzeramentoNonSiRitagliaPiu() = runTest {
    val fake = FakeSubjectCutter()
    val runtime = CutoutRuntime(this, { fake }, maxPixels = 1_000_000, idle = 3.minutes)

    runtime.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    runCurrent()
    runtime.stop()
    assertThat(fake.releases).isEqualTo(0)

    val failed = runCatching { runtime.cutting() }.exceptionOrNull()
    assertThat(failed).isInstanceOf(IllegalStateException::class.java)
  }
}
