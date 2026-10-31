package dev.pampa.mycloudgallery.feature.widget.testing

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiCell
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import java.time.YearMonth

/**
 * Un esempio per ogni caso dello stato del widget, in ogni misura (§1.5): li usano gli screenshot `10-P3` (chiaro e scuro, tre misure, testo
 * 1,3x, contrasto alto) e la fase grafica. Nessun dato personale: luoghi e date di prova.
 */
object WidgetSamples {
  private fun cells(n: Int, from: Int = 0) = List(n) { WidgetUiCell(from + it, if (it % 3 == 2) MediaKind.VIDEO else MediaKind.PHOTO, 20251031 - it) }

  /** "Un anno fa · Oslo" (come dati). */
  val title: WidgetTitle = WidgetTitle(yearsAgo = 1, placeIds = listOf(3_143_244), tripLabel = null, month = null)

  /** "Il meglio di settembre". */
  val monthTitle: WidgetTitle = WidgetTitle(yearsAgo = null, placeIds = emptyList(), tripLabel = null, month = YearMonth.of(2026, 9))

  fun all(size: WidgetSize): List<WidgetUiState> = listOf(
    WidgetUiState.Memory(size, cells(1).first(), cells(size.cells - 1, from = 1), title),
    WidgetUiState.Memory(size, cells(1).first(), cells(size.cells - 1, from = 1), monthTitle),
    WidgetUiState.Photos(size, cells(size.cells)),
    WidgetUiState.Locked(size, cells(size.cells)),
  ) + WidgetEmpty.entries.filter { it != WidgetEmpty.NOT_READY }.map { WidgetUiState.Message(size, it) }
}
