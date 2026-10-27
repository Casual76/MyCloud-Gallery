package dev.pampa.mycloudgallery.core.personal.noop

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 9 (P0): nessuna scelta sui ricordi (nessun visto, nessuno scartato, nessun "Mostra meno") e nessuna scrittura. E' quello che c'e'
 * finche' :app non lega `SyncedMemoriesSettings` (P13): con questo i ricordi si generano, ma "Non mostrare" non resta.
 */
object NoMemoriesPersonalSettings : MemoriesPersonalSettings {
  override val choices: StateFlow<MemoriesChoices> = MutableStateFlow(MemoriesChoices.NONE).asStateFlow()

  override fun current(): MemoriesChoices = MemoriesChoices.NONE

  override suspend fun markSeen(memoryId: String, day: Int) = Unit

  override suspend fun setDismissed(memoryId: String, dismissed: Boolean) = Unit

  override suspend fun setLess(person: PersonId, level: MemoryLessLevel?) = Unit

  override suspend fun setNeverDays(days: MemoryDays, never: Boolean) = Unit

  override suspend fun setPeopleMemories(on: Boolean) = Unit

  override suspend fun dropAllPeopleChoices(): Int = 0
}
