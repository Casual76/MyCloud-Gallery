package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.noop.NotConfiguredWidgetUi
import dev.pampa.mycloudgallery.feature.widget.testing.WidgetSamples
import java.time.YearMonth
import java.util.UUID
import org.junit.Test

/** Fase 10 (P0, §1.5, D3, D8, D38, E-W4): lo stato sigillato del widget dal piano, gli esempi per ogni caso, nessun dato nei `toString()`. */
class WidgetUiStateTest {

  private fun cells(n: Int) = List(n) { WidgetCell(it, MediaId(UUID(5L, it.toLong()).toString()), MediaKind.PHOTO, 20251031) }

  @Test
  fun dalPianoAlloStato() {
    val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))
    val title = WidgetSamples.title
    val fromMemory = WidgetUiState.of(WidgetPlan(WidgetSlot(1), WidgetSize.WIDE, WidgetMode.Memory(memory, title), cells(4), 1))
    assertThat(fromMemory).isInstanceOf(WidgetUiState.Memory::class.java)
    fromMemory as WidgetUiState.Memory
    assertThat(fromMemory.cover.index).isEqualTo(0)
    assertThat(fromMemory.cells.map { it.index }).containsExactly(1, 2, 3).inOrder()
    assertThat(WidgetUiState.of(WidgetPlan(WidgetSlot(1), WidgetSize.LARGE, WidgetMode.Locked, cells(7), 1))).isInstanceOf(WidgetUiState.Locked::class.java)
    assertThat(WidgetUiState.of(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Photos, cells(1), 1))).isInstanceOf(WidgetUiState.Photos::class.java)
    val error = WidgetUiState.of(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.ERROR), emptyList(), 1))
    assertThat(error).isEqualTo(WidgetUiState.Message(WidgetSize.SMALL, WidgetEmpty.ERROR))
    assertThat(WidgetUiState.of(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.NOT_READY), emptyList(), 1))).isNull()
    assertThat(NotConfiguredWidgetUi.of(WidgetSize.WIDE)).isEqualTo(WidgetUiState.blank(WidgetSize.WIDE, WidgetEmpty.NOT_CONFIGURED))
  }

  @Test
  fun gliEsempiCopronoOgniCasoInOgniMisura() {
    for (size in WidgetSize.entries) {
      val samples = WidgetSamples.all(size)
      assertThat(samples.map { it::class }.toSet()).containsExactly(
        WidgetUiState.Memory::class, WidgetUiState.Photos::class, WidgetUiState.Locked::class, WidgetUiState.Message::class,
      )
      for (sample in samples) {
        assertThat(sample.size).isEqualTo(size)
        assertThat(sample.toString()).doesNotContain("3143244")
        assertThat(sample.toString()).doesNotContain("2025")
      }
    }
  }
}
