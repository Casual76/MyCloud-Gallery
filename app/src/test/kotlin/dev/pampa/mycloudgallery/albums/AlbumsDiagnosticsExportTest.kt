package dev.pampa.mycloudgallery.albums

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.library.duplicates.NightlyReport
import dev.pampa.mycloudgallery.core.library.duplicates.SigningReport
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.utility.UtilityDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La Diagnostica **esportata** degli album ha i conteggi (revisione di fine fase, R22): le prove sul campo (F72-F93) leggono il file, e l'esportazione scarta le
 * chiavi che non stanno nell'elenco chiuso. Qui si passa dall'esportatore vero: ogni valore delle voci degli album e dell'Utility arriva nel file, e nel file
 * non c'e' nient'altro che numeri e segni.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumsDiagnosticsExportTest {

  @Test
  fun iConteggiDegliAlbumEDellUtilityArrivanoNelFile() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val albums = AlbumsDiagnostics(AlbumsGraph(scope, { null }, MutableStateFlow(false)))
      val facts = AlbumsFacts(
        mounted = true, manual = 4, smart = 3, shared = 2, openShares = 1, contributions = 17, syncIncomplete = true, roundErrors = 2, searchReady = false,
        tripsSearched = true, tripsHomeKnown = false, rejected = 1, described = 2,
      )
      val utility = UtilityDiagnostics { NightlyReport(true, 120, 7, 2, false) to SigningReport(40, 3, 1, true) }
      val items = albums.render(facts) + utility.render(NightlyReport(true, 120, 7, 2, false), SigningReport(40, 3, 1, true))
      assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.ALBUMS)

      val json = JSONObject(DiagnosticsExporter.render(DiagnosticsSnapshot(items.groupBy { it.section }, recentEvents = 0)).text)
      val exported = json.getJSONObject("sections").getJSONArray(DiagSection.ALBUMS.name)
      assertThat(exported.length()).isEqualTo(items.size)
      for (index in items.indices) {
        val entry = exported.getJSONObject(index)
        val item = items[index]
        assertThat(entry.getString("code")).isEqualTo(item.code.id)
        val values = if (entry.has("values")) entry.getJSONObject("values") else JSONObject()
        // Nessun valore lasciato fuori dall'elenco chiuso: prima dell'integrazione di R22 arrivavano solo `mounted` e `count`.
        assertWithMessage("valori esportati di ${item.code.id}").that(values.length()).isEqualTo(item.export.size)
      }
      val state = (0 until exported.length()).map { exported.getJSONObject(it) }.first { it.getString("code") == "ALBUMS_STATE" }.getJSONObject("values")
      assertThat(state.keys().asSequence().toSet()).containsAtLeast("mounted", "manual", "smart", "shared", "openShares", "contributions", "syncIncomplete", "roundErrors")
      val codes = (0 until exported.length()).map { exported.getJSONObject(it).getString("code") }
      assertThat(codes).containsAtLeast("UTILITY_FULL_HASH", "UTILITY_SIGNING", "SMART_NO_SEARCH", "TRIP_NO_GPS", "ALBUM_CONTRIB_REJECTED")
    } finally {
      scope.cancel()
    }
  }
}
