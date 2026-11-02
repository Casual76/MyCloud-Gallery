package dev.pampa.mycloudgallery.window

import android.content.Context
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWidth
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes

/**
 * `WINDOW_STATE` (Fase 10, P13; docs/fase10-progetto.md §2.13): quante finestre dell'app sono aperte (al piu' due, D16) e la classe di larghezza della finestra dell'app
 * (quella della configurazione di adesso: `GalleryWidth`, le soglie dei pannelli). Solo un numero e una costante; mai che cosa mostrano.
 */
class WindowDiagnostics(private val context: Context, private val registry: WindowRegistry) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.PROFILES

  override suspend fun collect(): List<DiagItem> {
    val width = GalleryWidth.of(context.resources.configuration.screenWidthDp.coerceAtLeast(0))
    return listOf(
      DiagItem(
        code = DiagCodes.WINDOW_STATE,
        section = section,
        severity = DiagCatalog.entry(DiagCodes.WINDOW_STATE)?.severity ?: DiagSeverity.INFO,
        titleRes = DiagCatalog.titleOf(DiagCodes.WINDOW_STATE),
        export = mapOf("windows" to ExportValue.Num(registry.count.toLong()), "widthClass" to ExportValue.Enum.of(width)),
      ),
    )
  }
}
