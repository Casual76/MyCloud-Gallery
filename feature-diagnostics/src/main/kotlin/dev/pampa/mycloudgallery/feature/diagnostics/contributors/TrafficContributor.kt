package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes

/**
 * «Consumi del mese» (docs/fase2-progetto.md §2.6): quanti byte sono passati nel ciclo in corso, in totale e per
 * attivita', dal [TrafficLedger] di `core-rules`. Sono numeri e nomi di costanti: niente da nascondere
 * nell'esportazione.
 *
 * `ledger` e' null finche' `:app` non lo lega (P9): in quel caso non ci sono voci.
 *
 * Il roaming ha una voce a parte (le attivita' automatiche non partono in roaming: se ci sono byte, vengono da un
 * «Fai ora» confermato o dal contenuto aperto, e l'utente deve saperlo).
 */
class TrafficContributor(private val ledger: TrafficLedger?) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.MONTHLY_TRAFFIC

  override suspend fun collect(): List<DiagItem> {
    // Senza contatori (finche' `:app` non li lega) la sezione resta vuota: nessun «0 byte» inventato.
    val usage = ledger?.currentUsage() ?: return emptyList()
    return buildList {
      add(month(usage))
      TrafficSource.entries.forEach { source -> activity(usage, source)?.let(::add) }
      roaming(usage)?.let(::add)
    }
  }

  private fun month(usage: CycleUsage): DiagItem = DiagItem(
    code = DiagCodes.TRAFFIC_MONTH,
    section = DiagSection.MONTHLY_TRAFFIC,
    severity = DiagSeverity.INFO,
    titleRes = DiagCatalog.titleOf(DiagCodes.TRAFFIC_MONTH),
    detail = DiagText(
      R.string.diagnostics_traffic_month_detail,
      listOf(ByteFormat.size(usage.totalBytes), ByteFormat.size(usage.wifiBytes), ByteFormat.size(usage.mobileBytes)),
    ),
    export = buildMap {
      put("totalBytes", ExportValue.Num(usage.totalBytes))
      put("wifiBytes", ExportValue.Num(usage.wifiBytes))
      put("mobileBytes", ExportValue.Num(usage.mobileBytes))
      TrafficNetwork.entries.forEach { network ->
        put("bytes.${network.name.lowercase()}", ExportValue.Num(usage.bytesFor(network)))
      }
    },
  )

  private fun activity(usage: CycleUsage, source: TrafficSource): DiagItem? {
    val total = usage.bytesFor(source)
    if (total <= 0L) return null
    val mobile = TrafficNetwork.entries.filter { it.isMobile }.sumOf { usage.bytesFor(source, it) }
    return DiagItem(
      code = DiagCodes.TRAFFIC_ACTIVITY,
      section = DiagSection.MONTHLY_TRAFFIC,
      severity = DiagSeverity.INFO,
      titleRes = titleOf(source),
      detail = DiagText(R.string.diagnostics_traffic_activity_detail, listOf(ByteFormat.size(total - mobile), ByteFormat.size(mobile))),
      export = mapOf(
        "source" to ExportValue.Enum.of(source),
        "totalBytes" to ExportValue.Num(total),
        "mobileBytes" to ExportValue.Num(mobile),
      ),
    )
  }

  private fun roaming(usage: CycleUsage): DiagItem? {
    val bytes = usage.bytesFor(TrafficNetwork.ROAMING)
    if (bytes <= 0L) return null
    return DiagItem(
      code = DiagCodes.TRAFFIC_ROAMING,
      section = DiagSection.MONTHLY_TRAFFIC,
      severity = DiagSeverity.WARNING,
      titleRes = DiagCatalog.titleOf(DiagCodes.TRAFFIC_ROAMING),
      detail = DiagText(R.string.diagnostics_traffic_roaming_detail, listOf(ByteFormat.size(bytes))),
      export = mapOf("roamingBytes" to ExportValue.Num(bytes)),
    )
  }

  companion object {
    @StringRes
    fun titleOf(source: TrafficSource): Int = when (source) {
      TrafficSource.PHOTO_BACKUP -> R.string.diagnostics_activity_photo_backup
      TrafficSource.VIDEO_BACKUP -> R.string.diagnostics_activity_video_backup
      TrafficSource.INDEX_SYNC -> R.string.diagnostics_activity_index_sync
      TrafficSource.PREPARATION -> R.string.diagnostics_activity_preparation
      TrafficSource.AI_COMPUTE -> R.string.diagnostics_activity_ai_compute
      TrafficSource.HQ_PREVIEWS -> R.string.diagnostics_activity_hq_previews
      TrafficSource.VISIBLE_CONTENT -> R.string.diagnostics_activity_visible_content
    }
  }
}
