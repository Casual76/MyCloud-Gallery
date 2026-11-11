package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class DeviceMeasurementRulesTest {
  @Test fun usaLaSogliaConfigurataELIsteresi() {
    val evaluator = RuleEvaluator(EvaluatorConfig(headroomReduce = 0.4f))
    val cool = PowerState(pluggedIn = true, levelPercent = 80, thermalHeadroom = 0.3f)
    assertThat(evaluator.canMeasureDevice(cool)).isTrue()
    assertThat(evaluator.canMeasureDevice(cool.copy(thermalHeadroom = 0.5f))).isFalse()
    assertThat(evaluator.canMeasureDevice(cool.copy(thermalStatus = ThermalStatus.LIGHT))).isFalse()
    assertThat(evaluator.canMeasureDevice(cool.copy(timeSinceThermalSevere = Duration.ofSeconds(10)))).isFalse()
  }
}
