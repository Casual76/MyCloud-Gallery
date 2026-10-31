package dev.pampa.mycloudgallery.core.widget.plan

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlannerContract
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.SyntheticWidgetWorld

/** Fase 10 P2: `DefaultWidgetPlanner` passa la suite di contratto di P0, con il `WidgetFeed` vero (`RotatingWidgetFeed`) sopra la stessa visibilita'. */
class DefaultWidgetPlannerContractTest : WidgetPlannerContract() {
  override fun planner(world: SyntheticWidgetWorld, visible: VisibleMedia, mask: HiddenMask): WidgetPlanner =
    defaultPlanner(world, visible, mask)
}

/** Il pianificatore vero sopra un mondo sintetico: lo usano la suite di contratto, la parita' e le prove di sicurezza. */
internal fun defaultPlanner(
  world: SyntheticWidgetWorld,
  visible: VisibleMedia = world.visibility,
  mask: HiddenMask = world.visibility,
  ledger: InMemoryWidgetLedger = InMemoryWidgetLedger(),
  clock: FakeWidgetClock = FakeWidgetClock(),
): DefaultWidgetPlanner = DefaultWidgetPlanner(
  feed = RotatingWidgetFeed(visible, mask),
  memories = world.memories,
  visible = visible,
  mask = mask,
  thumbs = world.thumbs,
  lock = world.lock,
  prefs = world.prefs,
  clock = clock,
  ledger = ledger,
)
