package dev.pampa.mycloudgallery.core.albums.noop

import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fase 8 (P0, D27, E-F37): nessuna persona su questo dispositivo. La regola delle persone non inserisce niente e l'album lo dice. */
object NoPeopleMembership : PeopleMembership {
  override val available: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> = emptyMap()
}
