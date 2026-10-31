package dev.pampa.mycloudgallery.feature.memories.noop

import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// LE PREFERENZE DI SERIE QUANDO `:app` NON LE LEGA (Fase 9, P13). Prima le porte di serie di `MemoriesDependencies` erano i fake di `core-memories/testing/`
// (`InMemoryNotifySettings`, `InMemoryMusicSettings`): con `:app` che dipende da questo modulo finivano nel dex di release, e `tools/check.ps1` vieta ogni classe
// di un package `testing` nella release (P10, punto 7). Questi due null-object fanno la stessa cosa (le scelte valgono finche' vive il processo, mai su disco)
// ma stanno in `noop/`. In produzione `:app` lega le preferenze vere (`FileNotifySettings`, la scelta della musica su file) e questi non si usano.

/** Le preferenze delle notifiche in memoria, di serie le piu' riservate ([NotifyPrefs] di serie). */
class SessionNotifySettings(initial: NotifyPrefs = NotifyPrefs()) : NotifySettings {
  private val state = MutableStateFlow(initial)
  override val prefs: StateFlow<NotifyPrefs> = state.asStateFlow()

  override suspend fun set(prefs: NotifyPrefs) {
    state.value = prefs
  }
}

/** La scelta della musica in memoria, di serie nessuna musica (Q3). */
class SessionMusicSettings(initial: MusicChoice = MusicChoice.None) : MusicSettings {
  private val state = MutableStateFlow(initial)
  override val choice: StateFlow<MusicChoice> = state.asStateFlow()

  override suspend fun set(choice: MusicChoice) {
    state.value = choice
  }
}
