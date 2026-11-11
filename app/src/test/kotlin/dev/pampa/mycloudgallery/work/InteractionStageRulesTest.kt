package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.*
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InteractionStageRulesTest {
  @Test fun aRiposoToglieSoloIlRallentamentoDellInterfaccia() = runTest {
    var busy = false
    var decision: RuleDecision = RuleDecision.Run(RunScope.FULL, Pace.APP_IN_USE, NetworkClass.HOME_WIFI, null)
    val rules = InteractionStageRules(object : StageRules {
      override suspend fun decide(request: WorkRequest, running: Boolean) = decision
      override suspend fun isPluggedIn() = true
    }, { busy })
    val request = WorkRequest.Background(BackgroundActivity.AI_COMPUTE)
    assertThat((rules.decide(request, false) as RuleDecision.Run).pace).isEqualTo(Pace.NORMAL)
    busy = true
    assertThat((rules.decide(request, false) as RuleDecision.Run).pace).isEqualTo(Pace.APP_IN_USE)
    busy = false
    decision = (decision as RuleDecision.Run).copy(pace = Pace.REDUCED)
    assertThat(rules.decide(request, false)).isSameInstanceAs(decision)
    decision = RuleDecision.Wait(WaitReason.Offline)
    assertThat(rules.decide(request, false)).isSameInstanceAs(decision)
  }
}
