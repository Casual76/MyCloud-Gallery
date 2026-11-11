package dev.pampa.mycloudgallery.work

import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageRules

/** Raffina solo il dosaggio APP_IN_USE: rete, caldo, fascia e fermi conservano il verdetto originale. */
internal class InteractionStageRules(private val delegate: StageRules, private val busy: () -> Boolean) : StageRules {
  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision {
    val decision = delegate.decide(request, running)
    return if (decision is RuleDecision.Run && decision.pace == Pace.APP_IN_USE && !busy()) decision.copy(pace = Pace.NORMAL) else decision
  }
  override suspend fun isPluggedIn() = delegate.isPluggedIn()
}
