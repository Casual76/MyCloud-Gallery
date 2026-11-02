package dev.pampa.mycloudgallery.widget

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.widget.contract.WidgetBlankCause
import dev.pampa.mycloudgallery.core.widget.contract.WidgetDiag
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.schedule.DefaultWidgetSchedule
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La sezione "Widget" della Diagnostica (Fase 10, P13; docs/fase10-progetto.md §2.13, D39, C449): widget per misura, minuti dall'ultimo aggiornamento (l'ora del file del
 * registro, che sopravvive al processo), frequenza scelta, foto saltate per miniatura o per blocco, svuotamenti per causa, giri periodici in ritardo negli ultimi 7 giorni,
 * l'ultimo errore. **Solo numeri e costanti**: mai le foto mostrate, i titoli dei ricordi, i luoghi, le date. Le voci oltre lo stato compaiono solo se c'e' da dire.
 */
class WidgetDiagnostics(
  private val widget: WidgetGraph,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.WIDGET

  override suspend fun collect(): List<DiagItem> {
    val diag = read()
    val items = ArrayList<DiagItem>()
    items += item(
      DiagCodes.WIDGET_STATE,
      buildMap {
        put("widgetsSmall", ExportValue.Num((diag.widgets[WidgetSize.SMALL] ?: 0).toLong()))
        put("widgetsWide", ExportValue.Num((diag.widgets[WidgetSize.WIDE] ?: 0).toLong()))
        put("widgetsLarge", ExportValue.Num((diag.widgets[WidgetSize.LARGE] ?: 0).toLong()))
        diag.minutesSinceUpdate?.let { put("minutesSinceUpdate", ExportValue.Num(it)) }
        put("period", ExportValue.Enum.of(diag.period))
      },
    )
    if (diag.skippedNoThumb + diag.skippedLocked > 0) {
      items += item(DiagCodes.WIDGET_SKIPPED, mapOf("noThumb" to ExportValue.Num(diag.skippedNoThumb.toLong()), "locked" to ExportValue.Num(diag.skippedLocked.toLong())))
    }
    if (diag.blanked.values.any { it > 0 }) {
      items += item(
        DiagCodes.WIDGET_BLANKED,
        mapOf(
          "blankHidden" to ExportValue.Num((diag.blanked[WidgetBlankCause.HIDDEN] ?: 0).toLong()),
          "blankMemory" to ExportValue.Num((diag.blanked[WidgetBlankCause.MEMORY_DROPPED] ?: 0).toLong()),
          "blankReset" to ExportValue.Num((diag.blanked[WidgetBlankCause.RESET] ?: 0).toLong()),
        ),
      )
    }
    if (diag.lateRuns7d > 0) items += item(DiagCodes.WIDGET_LATE, mapOf("lateRuns" to ExportValue.Num(diag.lateRuns7d.toLong())))
    diag.lastError?.let { items += item(DiagCodes.WIDGET_ERROR, mapOf("lastError" to ExportValue.Enum.of(it))) }
    return items
  }

  /** I numeri di adesso: i widget dal sistema, l'ultimo aggiornamento dal file del registro, il resto dai contatori del processo. */
  internal suspend fun read(): WidgetDiag {
    val sizes = widget.sizes()
    val lastMs = withContext(Dispatchers.IO) { widget.ledgerFile.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 } }
    val now = nowMs()
    val minutes = lastMs?.let { ((now - it).coerceAtLeast(0)) / MINUTE_MS }
    val period = widget.prefs.period.value
    val late = if (sizes.values.sum() == 0) {
      0
    } else {
      WidgetCounters.lateRuns(
        schedule = DefaultWidgetSchedule(),
        period = period,
        last = lastMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone()) },
        now = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone()),
      )
    }
    return widget.counters.diag(sizes, minutes, period, late)
  }

  private fun item(code: DiagCode, export: Map<String, ExportValue>) = DiagItem(
    code = code,
    section = section,
    severity = DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO,
    titleRes = DiagCatalog.titleOf(code),
    export = export,
  )

  private companion object {
    const val MINUTE_MS = 60_000L
  }
}
