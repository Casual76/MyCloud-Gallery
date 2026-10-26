package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import dev.pampa.mycloudgallery.feature.diagnostics.assertExportKeysAllowed
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** «Spazio» (cartelle dell'app e memoria del telefono) e «Ultimi esiti ed errori» (l'anello degli eventi). */
@RunWith(RobolectricTestRunner::class)
class StorageAndEventsContributorsTest {

  // --- Spazio ---------------------------------------------------------------------------------------------------

  private fun snapshot(free: Long? = 20_000_000_000L, total: Long? = 128_000_000_000L) = StorageSnapshot(
    databaseBytes = 3_200_000,
    cacheBytes = 12_000_000,
    noBackupBytes = 400_000,
    filesBytes = 700_000,
    freeBytes = free,
    totalBytes = total,
  )

  @Test
  fun loSpazioDellAppSiEsportaComeNumeri() = runTest {
    val items = StorageContributor { snapshot() }.collect()
    assertExportKeysAllowed(items)

    val usage = items.single()
    assertThat(usage.code).isEqualTo(DiagCodes.STORAGE_USAGE)
    assertThat(usage.section).isEqualTo(DiagSection.STORAGE)
    assertThat(usage.export).containsExactly(
      "databaseBytes", ExportValue.Num(3_200_000),
      "cacheBytes", ExportValue.Num(12_000_000),
      "noBackupBytes", ExportValue.Num(400_000),
      "filesBytes", ExportValue.Num(700_000),
    )
  }

  /** DG-5: i modelli (con i programmi compilati), l'indice di ricerca e il buffer dell'analisi sono i tre pesi grossi dell'app, e Spazio non li diceva. */
  @Test
  fun lAreeDellaRicercaSiEsportanoComeNumeriESiVedonoNelDettaglio() = runTest {
    val aiSnapshot = StorageSnapshot(
      databaseBytes = 3_200_000,
      cacheBytes = 12_000_000,
      noBackupBytes = 1_900_000_000,
      filesBytes = 700_000,
      freeBytes = 20_000_000_000L,
      totalBytes = 128_000_000_000L,
      modelsBytes = 1_100_000_000,
      searchBytes = 60_000_000,
      aiBufferBytes = 480_000_000,
    )
    val items = StorageContributor { aiSnapshot }.collect()
    assertExportKeysAllowed(items)

    val usage = items.single { it.code == DiagCodes.STORAGE_USAGE }
    assertThat(usage.export).containsAtLeast(
      "modelsBytes", ExportValue.Num(1_100_000_000),
      "searchBytes", ExportValue.Num(60_000_000),
      "aiBufferBytes", ExportValue.Num(480_000_000),
    )
    // Il dettaglio dice le tre aree (6 argomenti), non solo i tre pesi di sempre.
    assertThat(usage.detail!!.args).hasSize(6)
  }

  @Test
  fun senzaLeAreeDellaRicercaIlDettaglioEQuelloDiSempre() = runTest {
    val usage = StorageContributor { snapshot() }.collect().single { it.code == DiagCodes.STORAGE_USAGE }
    assertThat(usage.export.keys).containsNoneOf("modelsBytes", "searchBytes", "aiBufferBytes")
    assertThat(usage.detail!!.args).hasSize(3)
  }

  @Test
  fun conLaMemoriaQuasiPienaCEUnAvviso() = runTest {
    val items = StorageContributor { snapshot(free = 300_000_000L) }.collect()

    val low = items.single { it.code == DiagCodes.STORAGE_PHONE_LOW }
    assertThat(low.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(low.export["freeBytes"]).isEqualTo(ExportValue.Num(300_000_000L))
    assertThat(low.export["totalBytes"]).isEqualTo(ExportValue.Num(128_000_000_000L))
    assertThat(DiagCatalog.guideOf(low.code)).isNotEqualTo(0)
  }

  @Test
  fun lasogliaESiaAssolutaSiaPercentuale() {
    // Meno di 500 MB, qualunque sia il telefono...
    assertThat(StorageContributor.isLow(499_999_999L, 64_000_000_000L)).isTrue()
    // ...o meno del 5% di un telefono piccolo.
    assertThat(StorageContributor.isLow(1_000_000_000L, 32_000_000_000L)).isTrue()
    assertThat(StorageContributor.isLow(2_000_000_000L, 32_000_000_000L)).isFalse()
    assertThat(StorageContributor.isLow(20_000_000_000L, 128_000_000_000L)).isFalse()
  }

  @Test
  fun seLoSpazioLiberoNonSiLeggeNonSiInventaUnAvviso() = runTest {
    val codes = StorageContributor { snapshot(free = null, total = null) }.collect().map { it.code }

    assertThat(codes).containsExactly(DiagCodes.STORAGE_USAGE)
  }

  @Test
  fun lo_spazio_vero_sileggeDalleCartelleDellApp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    java.io.File(context.cacheDir, "a/b").mkdirs()
    java.io.File(context.cacheDir, "a/b/x.bin").writeBytes(ByteArray(1500))
    java.io.File(context.cacheDir, "a/y.bin").writeBytes(ByteArray(500))
    java.io.File(context.noBackupFilesDir, "diagnostics").mkdirs()
    java.io.File(context.noBackupFilesDir, "diagnostics/events.log").writeBytes(ByteArray(300))
    val unconfined = object : DispatcherProvider {
      override val main: CoroutineDispatcher = Dispatchers.Unconfined
      override val io: CoroutineDispatcher = Dispatchers.Unconfined
      override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    val storage = AppStorageSource(context, unconfined).read()

    assertThat(storage.cacheBytes).isAtLeast(2000L)
    assertThat(storage.noBackupBytes).isEqualTo(300L)
    assertThat(storage.databaseBytes).isAtLeast(0L)
  }

  @Test
  fun unaCartellaGrandissimaSiFermaAlTetto() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    repeat(30) { java.io.File(context.noBackupFilesDir, "f$it").writeBytes(ByteArray(10)) }
    val unconfined = object : DispatcherProvider {
      override val main: CoroutineDispatcher = Dispatchers.Unconfined
      override val io: CoroutineDispatcher = Dispatchers.Unconfined
      override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    val storage = AppStorageSource(context, unconfined, maxFiles = 5).read()

    assertThat(storage.noBackupBytes).isAtMost(50L) // al massimo cinque file da dieci byte: una stima per difetto
  }

  // --- Ultimi esiti ----------------------------------------------------------------------------------------------

  @Test
  fun gliEventiSonoDalPiuRecenteEPortanoLEtaNonLOraEsatta() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(1_000L, DiagCode("NAS_TIMEOUT"), DiagSeverity.WARNING, mapOf("count" to ExportValue.Num(2))))
    log.record(DiagEvent(9_000L, DiagCode("NAS_AUTH_FAILED"), DiagSeverity.ERROR))

    val items = RecentEventsContributor(log, nowMillis = { 10_000L }).collect()

    assertThat(items.map { it.code.id }).containsExactly("NAS_AUTH_FAILED", "NAS_TIMEOUT").inOrder()
    assertThat(items[0].export[RecentEventsContributor.AGE_KEY]).isEqualTo(ExportValue.Dur(1_000L))
    assertThat(items[1].export[RecentEventsContributor.AGE_KEY]).isEqualTo(ExportValue.Dur(9_000L))
    assertThat(items[1].export["count"]).isEqualTo(ExportValue.Num(2))
    assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.RECENT_EVENTS)
    assertThat(items[0].severity).isEqualTo(DiagSeverity.ERROR)
  }

  @Test
  fun unEventoCheIlCatalogoNonConosceSiMostraComeEvento() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(1L, DiagCode("QUALCOSA_DI_NUOVO"), DiagSeverity.INFO))

    val item = RecentEventsContributor(log, nowMillis = { 5L }).collect().single()

    assertThat(item.titleRes).isEqualTo(DiagCatalog.titleOf(DiagCodes.EVT_GENERIC))
    assertThat(item.code.id).isEqualTo("QUALCOSA_DI_NUOVO")
  }

  @Test
  fun unEventoCheSiConosceHaIlSuoTitolo() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(1L, DiagCodes.EVT_QR_REJECTED, DiagSeverity.WARNING))

    val item = RecentEventsContributor(log, nowMillis = { 5L }).collect().single()

    assertThat(item.titleRes).isEqualTo(DiagCatalog.titleOf(DiagCodes.EVT_QR_REJECTED))
  }

  @Test
  fun siMostranoSoloGliUltimiN() = runTest {
    val log = RingDiagnosticsLog(capacity = 100)
    (1L..80L).forEach { log.record(DiagEvent(it, DiagCode("NAS_TIMEOUT"), DiagSeverity.INFO)) }

    val items = RecentEventsContributor(log, nowMillis = { 100L }, limit = 50).collect()

    assertThat(items).hasSize(50)
    assertThat((items.first().export[RecentEventsContributor.AGE_KEY] as ExportValue.Dur).millis).isEqualTo(20L) // il piu' recente (80)
  }

  @Test
  fun unOrologioIndietroNonDaEtaNegativa() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(50_000L, DiagCode("NAS_TIMEOUT"), DiagSeverity.INFO))

    val item = RecentEventsContributor(log, nowMillis = { 10_000L }).collect().single()

    assertThat(item.export[RecentEventsContributor.AGE_KEY]).isEqualTo(ExportValue.Dur(0L))
  }

  @Test
  fun lEtaVincePerChiaveUnValoreDellEventoCheSiChiamaAllaStessaManiera() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(1_000L, DiagCode("NAS_TIMEOUT"), DiagSeverity.INFO, mapOf("ageMs" to ExportValue.Num(999))))

    val item = RecentEventsContributor(log, nowMillis = { 3_000L }).collect().single()

    assertThat(item.export["ageMs"]).isEqualTo(ExportValue.Dur(2_000L))
  }
}
