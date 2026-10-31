package dev.pampa.mycloudgallery.core.widget.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test

/** I tetti di D7: al piu' 7 immagini, 2 MiB in tutto, 384 px (512 nella misura grande) sul lato lungo. */
class WidgetBudgetTest {

  @Test
  fun iNumeriDiD7() {
    assertThat(WidgetRules.MAX_IMAGES).isEqualTo(7)
    assertThat(WidgetRules.MAX_BITMAP_BYTES).isEqualTo(2L * 1024 * 1024)
    assertThat(WidgetSize.entries.maxOf { it.cells }).isEqualTo(WidgetRules.MAX_IMAGES)
    assertThat(WidgetSize.SMALL.thumbPx).isEqualTo(384)
    assertThat(WidgetSize.WIDE.thumbPx).isEqualTo(384)
    assertThat(WidgetSize.LARGE.thumbPx).isEqualTo(512)
  }

  @Test
  fun illatoLungoSiAbbassaQuandoLeCelleSonoTante() {
    // Il caso peggiore (miniature quadrate) deve stare nei 2 MiB: per ogni misura e numero di celle.
    for (size in WidgetSize.entries) {
      for (cells in 1..size.cells) {
        val side = WidgetBudget.longSidePx(size, cells)
        assertThat(side).isAtMost(size.thumbPx)
        assertWithMessage("${size.code} con $cells celle").that(cells * WidgetBudget.bytesOf(side, side)).isAtMost(WidgetRules.MAX_BITMAP_BYTES)
      }
    }
    assertThat(WidgetBudget.longSidePx(WidgetSize.SMALL, 1)).isEqualTo(384)
    assertThat(WidgetBudget.longSidePx(WidgetSize.WIDE, 4)).isEqualTo(362)
    assertThat(WidgetBudget.longSidePx(WidgetSize.LARGE, 7)).isEqualTo(273)
    assertThrows(IllegalArgumentException::class.java) { WidgetBudget.longSidePx(WidgetSize.LARGE, 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetBudget.longSidePx(WidgetSize.LARGE, 8) }
  }

  @Test
  fun lePrimeCelleCheStannoNelTetto() {
    // 4 immagini 4:3 da 384 px di lato lungo (384 x 288 x 4 = 432 KiB): quattro stanno (1,69 MiB), la quinta no (2,1 MiB).
    val landscape = List(7) { 384 to 288 }
    assertThat(WidgetBudget.fitting(landscape)).isEqualTo(4)
    assertThat(WidgetBudget.fitting(emptyList())).isEqualTo(0)
    // Una bitmap sola sopra il tetto: nessuna.
    assertThat(WidgetBudget.fitting(listOf(1500 to 1500, 100 to 100))).isEqualTo(0)
    // Mai piu' di 7, anche se i byte lo permetterebbero.
    assertThat(WidgetBudget.fitting(List(12) { 10 to 10 })).isEqualTo(7)
    assertThrows(IllegalArgumentException::class.java) { WidgetBudget.fitting(listOf(0 to 10)) }
  }

  private fun plan(mode: WidgetMode, cells: Int) = WidgetPlan(
    WidgetSlot(2), WidgetSize.LARGE, mode,
    List(cells) { WidgetCell(it, MediaId(UUID(5L, it.toLong()).toString()), MediaKind.PHOTO, 20261031) }, epoch = 3,
  )

  @Test
  fun ilPianoRidottoTieneLePrimeCelleEIlRegistroLoVedeCosi() {
    val full = plan(WidgetMode.Photos, 7)
    val trimmed = WidgetBudget.trimmed(full, 4)
    assertThat(trimmed.cells.map { it.media }).containsExactlyElementsIn(full.cells.take(4).map { it.media }).inOrder()
    assertThat(trimmed.epoch).isEqualTo(full.epoch)
    assertThat(trimmed.mode).isEqualTo(WidgetMode.Photos)
    assertThat(WidgetBudget.trimmed(full, 7)).isSameInstanceAs(full)
    assertThat(WidgetBudget.trimmed(full, 99)).isSameInstanceAs(full)
    assertThat(WidgetBudget.trimmed(full, 0).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_LOCAL_THUMBS))
    assertThat(WidgetBudget.trimmed(full, 0).cells).isEmpty()
    val message = WidgetPlan(WidgetSlot(2), WidgetSize.LARGE, WidgetMode.Message(WidgetEmpty.ERROR), emptyList(), 1)
    assertThat(WidgetBudget.trimmed(message, 0)).isSameInstanceAs(message)
    assertThrows(IllegalArgumentException::class.java) { WidgetBudget.trimmed(full, -1) }
  }
}
