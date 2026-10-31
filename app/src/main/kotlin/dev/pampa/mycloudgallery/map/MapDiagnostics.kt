package dev.pampa.mycloudgallery.map

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetCounts

/**
 * La sezione "Mappa" della Diagnostica (Fase 9, P13; docs/fase9-progetto.md §2.12, D33): mappa dettagliata accesa o no, regioni, punti e senza posizione
 * (solo se la mappa e' stata aperta in questo processo: la Diagnostica non costruisce l'indice), richieste lasciate passare e fermate per motivo (i contatori
 * del client delle tessere, da quando MapLibre e' partita). **Solo numeri, flag e costanti**: mai coordinate, riquadri, tessere, nomi delle regioni o dei
 * luoghi, l'ultima camera. Un `HOST` o un `PATH` fermato e' un difetto: la voce diventa un avviso ("Segnala").
 */
class MapDiagnostics(
  private val map: MapGraph,
  private val counts: () -> MapNetCounts? = { if (MapLibreInit.isReady) MapLibreInit.counts() else null },
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.MAP

  override suspend fun collect(): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val online = map.settings.online.value
    val state = mutableMapOf<String, ExportValue>(
      "online" to ExportValue.Flag(online is MapOnline.On),
      "available" to ExportValue.Flag(online !is MapOnline.Unavailable),
      "regions" to ExportValue.Num(map.regions.regions.value.size.toLong()),
    )
    (online as? MapOnline.On)?.let { state["byDefault"] = ExportValue.Flag(it.byDefault) }
    val index = map.indexIfOpened()
    state["opened"] = ExportValue.Flag(index != null)
    var invalid = 0
    when (index) {
      is GeoIndexState.Ready -> {
        state["points"] = ExportValue.Num(index.index.size.toLong())
        state["withoutPosition"] = ExportValue.Num(index.withoutPosition.toLong())
        invalid = index.invalid
      }
      is GeoIndexState.Empty -> {
        state["points"] = ExportValue.Num(0)
        state["withoutPosition"] = ExportValue.Num(index.withoutPosition.toLong())
      }
      GeoIndexState.Building, null -> Unit
    }
    items += item(DiagCodes.MAP_STATE, state)
    counts()?.let { c ->
      items += item(
        DiagCodes.MAP_REQUESTS,
        mapOf(
          "allowed" to ExportValue.Num(c.allowed),
          "bytes" to ExportValue.Num(c.bytes),
          "errors" to ExportValue.Num(c.errors),
          "paused" to ExportValue.Num(c.paused),
          "withoutVersion" to ExportValue.Num(c.withoutVersion),
          "tileJson" to ExportValue.Num(c.tileJsonFetches),
        ),
      )
      val refused = c.refused.filterValues { it > 0 }
      if (refused.isNotEmpty()) {
        // Un indirizzo o un percorso fuori dalle regole non dovrebbe mai partire da MapLibre: e' un difetto da segnalare.
        val defect = (refused[RefuseReason.HOST] ?: 0) > 0 || (refused[RefuseReason.PATH] ?: 0) > 0
        items += item(
          DiagCodes.MAP_REFUSED,
          refused.entries.associate { (reason, n) -> "reason.${reason.name}" to ExportValue.Num(n) },
          if (defect) DiagSeverity.WARNING else null,
        )
      }
    }
    if (invalid > 0) items += item(DiagCodes.MAP_INVALID_POINTS, mapOf("count" to ExportValue.Num(invalid.toLong())))
    return items
  }

  private fun item(code: DiagCode, export: Map<String, ExportValue>, severity: DiagSeverity? = null) = DiagItem(
    code = code,
    section = section,
    severity = severity ?: DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO,
    titleRes = DiagCatalog.titleOf(code),
    export = export,
  )
}
