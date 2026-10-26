package dev.pampa.mycloudgallery.core.personal.noop

import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook

// I null-object della Fase 8 (P0, D30) in core-personal.

/** Nessun gancio: si pubblica sempre (il comportamento di prima della Fase 8). E' il default del motore quando P8 ci mette la chiamata. */
object NoPrePublishHook : PrePublishHook {
  override suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish = PrePublish.Proceed
}

/** Nessuna testa nota: nessun dispositivo ha ancora confermato niente ("Disattiva e cancella" aspetta). */
object NoPersonalHeadsView : PersonalHeadsView {
  override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> = emptyMap()
}
