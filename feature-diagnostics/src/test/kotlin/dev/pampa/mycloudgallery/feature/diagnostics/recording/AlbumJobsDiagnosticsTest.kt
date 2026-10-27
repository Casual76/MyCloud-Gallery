package dev.pampa.mycloudgallery.feature.diagnostics.recording

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * I lavori degli album e della manutenzione notturna lasciano traccia in «Ultimi esiti ed errori» (DG-2) e i loro numeri stanno nell'elenco chiuso delle chiavi
 * di esportazione (R22). Prima `ShareWorker` e `LibraryMaintenanceWorker` giravano senza scrivere niente: dopo un blocco la Diagnostica non diceva nemmeno se
 * erano partiti.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumJobsDiagnosticsTest {

  private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Test
  fun iDueLavoriNuoviHannoUnTitoloEUnEsitoSiLegge() = runTest {
    val log = RingDiagnosticsLog()
    val recorder = DiagnosticsRecorder(log) { 5_000L }
    recorder.result(DiagJob.ALBUM_SHARE, DiagJobResult.FINISHED)
    recorder.result(DiagJob.LIBRARY_MAINTENANCE, DiagJobResult.YIELDED)
    recorder.jobFailed(DiagJob.ALBUM_SHARE)

    val items = RecentEventsContributor(log, nowMillis = { 6_000L }).collect()
    // Le tre righe dicono quale lavoro e com'e' andato (prima i due lavori non scrivevano niente).
    assertThat(items).hasSize(3)
    assertThat(items.mapNotNull { it.detail }).hasSize(3)
    assertThat(context.getString(RecentEventsContributor.jobRes(DiagJob.ALBUM_SHARE))).isEqualTo("Condivisione degli album")
    assertThat(context.getString(RecentEventsContributor.jobRes(DiagJob.LIBRARY_MAINTENANCE))).isEqualTo("Manutenzione notturna della Libreria")
  }

  @Test
  fun ogniLavoroHaUnTitoloDistinto() {
    val titles = DiagJob.entries.map { context.getString(RecentEventsContributor.jobRes(it)) }
    assertThat(titles).containsNoDuplicates()
    for (title in titles) assertThat(title).isNotEmpty()
  }

  @Test
  fun leChiaviDeiNumeriDegliAlbumEDellUtilitySonoNellElencoChiuso() {
    // Quelle che `AlbumsDiagnostics` e `UtilityDiagnostics` di :app scrivono (il test di :app verifica che non ne usino altre).
    val keys = listOf(
      "mounted", "count", "manual", "smart", "shared", "openShares", "contributions", "syncIncomplete", "roundErrors", "tripsSearched", "tripsHomeKnown",
      "searchReady", "enabled", "scanned", "withdrawn", "signed", "finished", "queued", "waiting", "skipped",
    )
    val missing = keys.filterNot(ExportKeys::isAllowed)
    assertWithMessage("Chiavi della Diagnostica degli album fuori da ExportKeys: $missing").that(missing).isEmpty()
  }

  @Test
  fun nessunaChiaveDegliAlbumSembraUnDatoPersonale() {
    // Titoli, cartelle, luoghi, casa e coordinate non sono mai chiavi.
    val personal = listOf("title", "name", "path", "uri", "folder", "latitude", "longitude", "coordinates", "albumId", "mediaId", "creator")
    for (key in personal) assertWithMessage("«$key» non deve essere una chiave").that(ExportKeys.isAllowed(key)).isFalse()
  }

  @Test
  fun iCodiciDellUtilityStannoNelCatalogoDegliAlbum() {
    for (code in listOf(DiagCodes.UTILITY_FULL_HASH, DiagCodes.UTILITY_SIGNING)) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage("manca ${code.id} nel catalogo").that(entry).isNotNull()
      assertThat(entry!!.section).isEqualTo(DiagSection.ALBUMS)
    }
  }

  @Test
  fun iCodiciPreparatiPerGliAltriPacchettiHannoVoceTitoloEGuida() {
    // R22 (pacchetti A e B) e R11 (pacchetto C): i codici ci sono nel catalogo, nella sezione giusta, con testo; chi unisce i pacchetti li collega.
    val expected = mapOf(
      DiagCodes.ALBUM_REGISTRY_UNREADABLE to DiagSection.ALBUMS,
      DiagCodes.ALBUM_CONTRIB_HELD to DiagSection.ALBUMS,
      DiagCodes.SHARE_PAUSED_CATALOG to DiagSection.ALBUMS,
      DiagCodes.NAS_WRITES_STOP_NOT_SAVED to DiagSection.NAS_LIBRARY,
    )
    for ((code, section) in expected) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage("manca ${code.id} nel catalogo").that(entry).isNotNull()
      assertThat(entry!!.section).isEqualTo(section)
      assertThat(context.getString(entry.titleRes)).isNotEmpty()
      assertThat(context.getString(entry.guideRes)).isNotEmpty()
    }
  }
}
