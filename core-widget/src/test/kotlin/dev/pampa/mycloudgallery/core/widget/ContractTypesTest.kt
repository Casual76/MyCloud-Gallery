package dev.pampa.mycloudgallery.core.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.noop.ClosedWidgetLock
import dev.pampa.mycloudgallery.core.widget.noop.DefaultWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.noop.NoWidgetThumbs
import dev.pampa.mycloudgallery.core.widget.noop.NotConfiguredWidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 (P0): i tipi del contratto del widget, i `toString()` senza dati e i null-object che falliscono chiusi. */
class ContractTypesTest {

  private val media = MediaId(UUID(1L, 2L).toString())
  private val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))
  private val title = WidgetTitle(2, listOf(3_143_244), "Oslo, marzo 2025", null)

  @Test
  fun iToStringNonPortanoIdTitoliNeLuoghi() {
    val plan = WidgetPlan(WidgetSlot(3), WidgetSize.WIDE, WidgetMode.Memory(memory, title), listOf(WidgetCell(0, media, MediaKind.PHOTO, 20251031)), 1)
    val texts = listOf(
      plan.toString(), plan.cells.first().toString(), plan.mode.toString(), title.toString(), WidgetCellRef(media, memory).toString(),
      WidgetTarget.Viewer(media).toString(), WidgetTarget.MemoryPage(memory).toString(),
      WidgetMemoryPick(memory, MemoryKind.TRIP, title, media, listOf(media)).toString(),
    )
    for (text in texts) {
      assertThat(text).doesNotContain(media.value)
      assertThat(text).doesNotContain(memory.value)
      assertThat(text).doesNotContain("Oslo")
      assertThat(text).doesNotContain("3143244")
      assertThat(text).doesNotContain("2025")
    }
  }

  @Test
  fun ilPianoRispettaLeSueRegole() {
    val cell = WidgetCell(0, media, MediaKind.PHOTO, 20251031)
    assertThrows(IllegalArgumentException::class.java) { WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Photos, listOf(cell, cell.copy(index = 1)), 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.ERROR), listOf(cell), 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Photos, emptyList(), 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetPlan(WidgetSlot(1), WidgetSize.LARGE, WidgetMode.Photos, listOf(cell.copy(index = 2)), 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetSlot(0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetTitle(0, emptyList(), null, null) }
    assertThat(WidgetSize.entries.maxOf { it.cells }).isEqualTo(WidgetRules.MAX_IMAGES)
    assertThat(WidgetSize.LARGE.thumbPx).isEqualTo(512)
    assertThat(WidgetSize.SMALL.thumbPx).isEqualTo(384)
  }

  @Test
  fun leScelteDiBaseSonoLeRisposteDellUtente() {
    assertThat(WidgetChoice.DEFAULT).isEqualTo(WidgetChoice.MEMORY_THEN_PHOTOS)
    assertThat(WidgetPeriod.DEFAULT).isEqualTo(WidgetPeriod.HOURS_3)
    assertThat(DefaultWidgetPrefs.choice.value).isEqualTo(WidgetChoice.MEMORY_THEN_PHOTOS)
  }

  @Test
  fun iNullObjectFallisconoChiusi() = runTest {
    assertThat(NotConfiguredWidgetPlanner.plan(WidgetSlot(1), WidgetSize.LARGE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NOT_CONFIGURED))
    assertThat(NoWidgetThumbs.local(listOf(media))).isEmpty()
    assertThat(ClosedWidgetLock.appLocked.value).isTrue()
    assertThat(ClosedWidgetLock.onPhone(listOf(media))).isEmpty()
  }
}
