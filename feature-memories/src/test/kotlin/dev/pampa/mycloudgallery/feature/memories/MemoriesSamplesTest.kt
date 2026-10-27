package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.testing.MemoriesSamples
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 9 (P0): ogni caso degli stati del §1.5 ha un esempio (P9 e P10 partono da qui), e le regole degli stati tengono. */
class MemoriesSamplesTest {

  // I `when` sono esaustivi: un caso nuovo senza esempio non compila qui o fa fallire il conteggio.
  private fun caseOf(state: MemoriesSectionState): String = when (state) {
    is MemoriesSectionState.Ready -> "ready"
    is MemoriesSectionState.Empty -> "empty"
    MemoriesSectionState.Working -> "working"
  }

  private fun caseOf(state: MemoryPageState): String = when (state) {
    MemoryPageState.Loading -> "loading"
    is MemoryPageState.Ready -> "ready"
    MemoryPageState.Gone -> "gone"
    MemoryPageState.NotFound -> "not_found"
  }

  private fun caseOf(state: MemoriesListState): String = when (state) {
    is MemoriesListState.Ready -> "ready"
    MemoriesListState.Empty -> "empty"
  }

  private fun caseOf(state: PlayerState): String = when (state) {
    is PlayerState.Playing -> "playing"
    is PlayerState.Paused -> "paused"
    is PlayerState.Ended -> "ended"
  }

  private fun caseOf(state: LessSheetState): String = when (state) {
    is LessSheetState.Person -> "person"
    is LessSheetState.Days -> "days"
    is LessSheetState.Done -> "done"
    LessSheetState.Undone -> "undone"
  }

  @Test
  fun ogniCasoHaUnEsempio() {
    assertThat(MemoriesSamples.sections.map { caseOf(it) }.toSet()).containsExactly("ready", "empty", "working")
    assertThat(MemoriesSamples.pages.map { caseOf(it) }.toSet()).containsExactly("loading", "ready", "gone", "not_found")
    assertThat(MemoriesSamples.lists.map { caseOf(it) }.toSet()).containsExactly("ready", "empty")
    assertThat(MemoriesSamples.players.map { caseOf(it) }.toSet()).containsExactly("playing", "paused", "ended")
    assertThat(MemoriesSamples.sheets.map { caseOf(it) }.toSet()).containsExactly("person", "days", "done", "undone")
    assertThat(MemoriesSamples.settings.map { it.permission }.toSet()).containsExactlyElementsIn(NotifyPermission.entries)
    // Animazioni ridotte e TalkBack hanno ognuno un esempio (E-MEM19).
    assertThat(MemoriesSamples.players.any { it.reducedMotion }).isTrue()
    assertThat(MemoriesSamples.players.any { it.talkBack }).isTrue()
  }

  /** Le regole: al piu' 6 voci nella sezione (D14), al piu' 2 immagini precaricate (D13). */
  @Test
  fun leRegoleDegliStati() {
    val ready = MemoriesSamples.sections.first() as MemoriesSectionState.Ready
    assertThrows(IllegalArgumentException::class.java) { MemoriesSectionState.Ready(List(7) { ready.cards.first() }, 0) }
    val playing = MemoriesSamples.players.first() as PlayerState.Playing
    assertThrows(IllegalArgumentException::class.java) { playing.copy(next = List(3) { playing.item }) }
  }

  /** Q6 (a): nessuna riga del filtro automatico; Q3: la musica c'e'; D33: niente nomi o luoghi nei `toString()`. */
  @Test
  fun impostazioniSenzaFiltroEStringheSicure() {
    val fields = MemoriesSettingsState::class.java.declaredFields.map { it.name.lowercase() }
    assertThat(fields.none { "sad" in it || "filter" in it || "trist" in it }).isTrue()
    assertThat(fields).containsAtLeast("music", "tracks")
    val texts = (MemoriesSamples.sections + MemoriesSamples.pages + MemoriesSamples.lists + MemoriesSamples.sheets + MemoriesSamples.settings).map { it.toString() }
    for (text in texts) for (secret in listOf("Persona di prova", "Citta' di prova", "2024", "content://", MemoriesSamples.media(1).value)) {
      assertThat(text).doesNotContain(secret)
    }
  }
}
