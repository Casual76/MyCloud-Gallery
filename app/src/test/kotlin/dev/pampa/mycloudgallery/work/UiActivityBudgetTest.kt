package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiActivityBudgetTest {
  @Test fun scorrimentoLungoEPiuFinestreRallentanoPoiLaCalmaRiprendePienoRitmo() {
    var now = 0L
    val budget = UiActivityBudget { now }
    assertThat(budget.busy).isFalse()
    budget.interacted(); assertThat(budget.busy).isTrue()
    now = 801; assertThat(budget.busy).isFalse()
    val first = budget.lease(); val second = budget.lease()
    first.set(true); second.set(true); first.close()
    now += 10_000; assertThat(budget.busy).isTrue()
    second.close(); now += 801; assertThat(budget.busy).isFalse()
  }
}
