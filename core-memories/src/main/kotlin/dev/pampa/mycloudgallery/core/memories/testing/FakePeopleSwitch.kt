package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.settings.less.PeoplePhase
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lo stato di Persone dell'account scelto dal test (P8, §5.2): per `PeopleLessJanitor`, P9 e le prove di integrazione di P13. */
class FakePeopleSwitch(initial: PeoplePhase = PeoplePhase.ON) : PeopleSwitch {
  private val state = MutableStateFlow(initial)
  override val phase: StateFlow<PeoplePhase> = state

  fun set(phase: PeoplePhase) {
    state.value = phase
  }

  override fun toString(): String = "FakePeopleSwitch"
}
