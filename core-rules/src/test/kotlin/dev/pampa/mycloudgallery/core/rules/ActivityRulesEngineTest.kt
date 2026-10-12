package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActivityRulesEngineTest {

  @Test
  fun senzaRegoleConfigurate_nessunaAttivitaParte() {
    val decision = ActivityRulesEngine().evaluate()

    assertThat(decision).isEqualTo(RuleDecision.Wait(WaitReason.NOT_CONFIGURED))
  }
}
