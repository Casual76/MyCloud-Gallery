package dev.pampa.mycloudgallery.integrations

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.share.EditCounter
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCounter
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LA-1 della verifica funzionale della Fase 5B (`docs/review/funzionale-5b.md`): `INTEGRATIONS_COUNTERS` si esportava senza nessun
 * numero, perche' nessuna delle sue chiavi era nell'elenco chiuso di `ExportKeys` (le chiavi fuori elenco si lasciano fuori in
 * silenzio). Qui il contributore vero della voce: ogni sua chiave e' nell'elenco, e ogni valore arriva nel file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class IntegrationsCountersExportTest {

  @After
  fun tearDown() {
    IntegrationCounters.reset()
    FlowCounters.reset()
  }

  private fun item(entryAllowed: Boolean = true, receiveReady: Boolean = true): DiagItem =
    DiagItem(
      IntegrationsGraph.COUNTERS,
      DiagSection.BACKUP,
      DiagSeverity.INFO,
      DiagCatalog.titleOf(IntegrationsGraph.COUNTERS),
      null,
      null,
      IntegrationsGraph.counterExport(entryAllowed, receiveReady),
    )

  private fun exported(item: DiagItem): JSONObject {
    val snapshot = DiagnosticsSnapshot(mapOf(item.section to listOf(item)), recentEvents = 0)
    val sections = JSONObject(DiagnosticsExporter.render(snapshot).text).getJSONObject("sections")
    return sections.getJSONArray(item.section.name).getJSONObject(0)
  }

  @Test
  fun ogniChiaveDellaVoceEnellElencoChiuso() {
    val keys = item().export.keys
    assertThat(keys).isNotEmpty()
    val outside = keys.filterNot(ExportKeys::isAllowed)
    assertWithMessage("chiavi fuori da ExportKeys: $outside").that(outside).isEmpty()
  }

  @Test
  fun ognuniDeiContatoriDelCodiceEllInElencoCosiUnoNuovoNonSiPerdeInSilenzio() {
    val codes = IntegrationCounter.entries.map { it.code } +
      ReceiveCounter.entries.map { it.code } + EditCounter.entries.map { it.code } + ShareCounter.entries.map { it.code }
    assertThat(codes).isNotEmpty()
    val missing = codes.filterNot(ExportKeys::isAllowed)
    assertWithMessage("contatori che l'esportazione lascerebbe fuori: $missing").that(missing).isEmpty()
    // E il contributore li porta tutti.
    assertThat(item().export.keys).containsAtLeastElementsIn(codes)
  }

  @Test
  fun ilFileEsportatoHaINumeriEIFlag() {
    IntegrationCounters.add(IntegrationCounter.PICKER_OPENED, 3)
    IntegrationCounters.add(IntegrationCounter.PROVIDER_QUERIES, 12)
    FlowCounters.receive.add(ReceiveCounter.SAVED, 2)
    FlowCounters.edit.add(EditCounter.BEGUN)
    FlowCounters.share.add(ShareCounter.STOPPED_LOCATION_REMAINS, 4)

    val item = item(entryAllowed = true, receiveReady = false)
    val entry = exported(item)

    // Prima il file aveva solo `code` e `severity`: nessun valore.
    assertThat(entry.has("values")).isTrue()
    val values = entry.getJSONObject("values")
    assertThat(values.length()).isEqualTo(item.export.size)
    assertThat(values.getLong("picker_opened")).isEqualTo(3L)
    assertThat(values.getLong("provider_queries")).isEqualTo(12L)
    assertThat(values.getLong("receive_saved")).isEqualTo(2L)
    assertThat(values.getLong("edit_begun")).isEqualTo(1L)
    assertThat(values.getLong("share_stopped_location_remains")).isEqualTo(4L)
    assertThat(values.getBoolean("entryAllowed")).isTrue()
    assertThat(values.getBoolean("receiveReady")).isFalse()
  }

  @Test
  fun unaChiaveFuoriElencoNonEntraMaiNelFile() {
    // Il rovescio: l'elenco resta chiuso. Un nome qualunque, anche con la forma di un contatore, non passa.
    listOf("share_mario_rossi", "receive_whatsapp", "picker_com_example_app", "share.mario", "entryallowed").forEach {
      assertWithMessage(it).that(ExportKeys.isAllowed(it)).isFalse()
    }
  }
}
