package dev.pampa.mycloudgallery.nas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * XS2-2, il collegamento: il calcolo AI serve a turno i mesi delle richieste e quelli ordinari (`LeasedWorkUnits(alternatePriority = ...)`), con la quota dei
 * mesi di richiesta di XS-2. Il comportamento vero e' provato in `core-index` (`PriorityMonthsTest`); qui si fissa che il collegamento reale ([NasAiRuntime.build])
 * lo accenda, perche' quel codice non si puo' montare senza un NAS. Mutazione: portare [NasAiRuntime.ALTERNATE_PRIORITY] a falso.
 */
class PriorityWiringTest {
  @Test
  fun ilCalcoloAiServeAlternatoIMesiDelleRichiesteEQuelliOrdinari() {
    assertThat(NasAiRuntime.ALTERNATE_PRIORITY).isTrue()
    assertThat(NasAiRuntime.PRIORITY_MONTHS).isAtLeast(1)
    assertThat(NasAiRuntime.PRIORITY_MONTHS).isAtMost(24)
  }
}
