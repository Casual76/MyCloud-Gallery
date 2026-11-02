package dev.pampa.mycloudgallery.release

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepArea
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepAttention
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepCertainty
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepFolder
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepForecast
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepIdle
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepPlace
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulation
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepUnavailable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le fonti vere della simulazione (P10, §2.10): dai fatti alle previsioni di ognuna delle quattro aree, lo spazio libero e la prova che il giro
 * non scrive niente sul NAS. Tempo virtuale (`runTest`).
 */
class StepSimulationSourcesTest {

  private fun count(photos: Int, bytes: Long) = SummaryCount(photos = photos, videos = 0, photoBytes = bytes, videoBytes = 0)

  private fun summary(fresh: SummaryCount = SummaryCount.ZERO, similar: SummaryCount = SummaryCount.ZERO, settled: Boolean = true) = BackupSummary.EMPTY.copy(
    existing = if (settled) ExistingCopiesState.SETTLED else ExistingCopiesState(privateAreaScanned = false, similarPending = 3),
    fresh = fresh,
    similar = similar,
  )

  // --- Backup ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilBackupDaiNumeriDelPiano() {
    assertThat(StepForecasts.backup(null)).isEqualTo(StepForecast.Unavailable(StepArea.BACKUP, StepUnavailable.NOT_READY))
    assertThat(StepForecasts.backup(BackupSummary.EMPTY)).isEqualTo(StepForecast.Idle(StepArea.BACKUP, StepIdle.NOTHING_TO_DO))
    val w = StepForecasts.backup(summary(fresh = count(1000, 5L shl 30), similar = count(20, 100L shl 20))) as StepForecast.Writes
    assertThat(w.certainty).isEqualTo(StepCertainty.EXACT)
    assertThat(w.files).isEqualTo(1020L)
    assertThat(w.bytes).isEqualTo((5L shl 30) + (100L shl 20))
    assertThat(w.writes.single().folder).isEqualTo(StepFolder.BACKUP)
    assertThat(w.writes.single().place).isEqualTo(StepPlace.PRIVATE_AREA)
  }

  @Test
  fun ilBackupEUnaStimaFincheIlConfrontoNonEChiuso() {
    val w = StepForecasts.backup(summary(fresh = count(10, 1L shl 20), settled = false)) as StepForecast.Writes
    assertThat(w.certainty).isEqualTo(StepCertainty.ESTIMATE)
  }

  // --- Indice e dati personali ---------------------------------------------------------------------------------------------------------

  @Test
  fun indiceDaiMediaDelCatalogoPerArea() {
    assertThat(StepForecasts.index(null)).isEqualTo(StepForecast.Unavailable(StepArea.INDEX, StepUnavailable.NOT_READY))
    assertThat(StepForecasts.index(StepIndexFacts(0, 0, null))).isEqualTo(StepForecast.Idle(StepArea.INDEX, StepIdle.NOTHING_TO_DO))
    val w = StepForecasts.index(StepIndexFacts(privateRecords = 10_000, commonRecords = 2_000, bytesPerRecord = 30_000)) as StepForecast.Writes
    assertThat(w.certainty).isEqualTo(StepCertainty.ESTIMATE)
    val priv = w.writes.single { it.place == StepPlace.PRIVATE_AREA }
    val common = w.writes.single { it.place == StepPlace.COMMON_AREA }
    assertThat(priv.folder).isEqualTo(StepFolder.APP_FOLDER)
    assertThat(priv.bytes).isEqualTo(300_000_000L)
    // 300 MB in segmenti da 12 MiB (24) piu' una testa.
    assertThat(priv.files).isEqualTo(25L)
    assertThat(common.bytes).isEqualTo(60_000_000L)
    assertThat(common.files).isEqualTo(6L)
  }

  @Test
  fun indiceSenzaStimaDellaReplicaUsaLaRegolaDiSerie() {
    val w = StepForecasts.index(StepIndexFacts(privateRecords = 1_000, commonRecords = 0, bytesPerRecord = null)) as StepForecast.Writes
    assertThat(w.writes.single().bytes).isEqualTo(1_000 * StepForecasts.DEFAULT_INDEX_BYTES_PER_RECORD)
    assertThat(w.writes).hasSize(1)
    val many = StepForecasts.index(StepIndexFacts(privateRecords = 1_000_000, commonRecords = 0, bytesPerRecord = 100)) as StepForecast.Writes
    // Pochi byte ma tante voci: il tetto di voci per segmento comanda.
    assertThat(many.writes.single().files).isEqualTo((1_000_000L + TrustLimits.MAX_SEGMENT_ENTRIES - 1) / TrustLimits.MAX_SEGMENT_ENTRIES + 1)
  }

  // --- Volti e album -------------------------------------------------------------------------------------------------------------------

  @Test
  fun iVoltiSoloSePersoneEAttiva() {
    assertThat(StepForecasts.faces(null)).isEqualTo(StepForecast.Unavailable(StepArea.FACES, StepUnavailable.NOT_READY))
    assertThat(StepForecasts.faces(StepFacesFacts(activeHere = false, mediaCount = 50_000))).isEqualTo(StepForecast.Idle(StepArea.FACES, StepIdle.FEATURE_OFF))
    assertThat(StepForecasts.faces(StepFacesFacts(activeHere = true, mediaCount = 0))).isEqualTo(StepForecast.Idle(StepArea.FACES, StepIdle.NOTHING_TO_DO))
    val w = StepForecasts.faces(StepFacesFacts(activeHere = true, mediaCount = 10_000)) as StepForecast.Writes
    assertThat(w.certainty).isEqualTo(StepCertainty.ESTIMATE)
    assertThat(w.bytes).isEqualTo(15_000L * StepForecasts.FACE_BYTES)
    assertThat(w.files).isEqualTo(5L)
    assertThat(w.writes.single().place).isEqualTo(StepPlace.PRIVATE_AREA)
    assertThat(w.writes.single().folder).isEqualTo(StepFolder.APP_FOLDER)
  }

  @Test
  fun gliAlbumScrivonoSoloQuandoLUtenteCondivide() = runTest {
    val sources = LiveStepSources({ null }, { null }, { null }, { null }, StepNasSpace { null })
    assertThat(sources.forecast(StepArea.ALBUMS)).isEqualTo(StepForecast.Idle(StepArea.ALBUMS, StepIdle.ON_USER_ACTION))
  }

  // --- Spazio ----------------------------------------------------------------------------------------------------------------------------

  private fun spaceOf(shares: StepShares?, free: Map<String, Long?>): suspend () -> Long? = {
    LiveStepSources({ null }, { null }, { null }, { shares }, StepNasSpace { free[it] }).space()?.freeBytes
  }

  @Test
  fun loSpazioEIlMinoreFraLeDueShare() = runTest {
    val shares = StepShares("alessio", "comune")
    assertThat(spaceOf(shares, mapOf("alessio" to (80L shl 30), "comune" to (50L shl 30)))()).isEqualTo(50L shl 30)
    assertThat(spaceOf(shares, mapOf("alessio" to (40L shl 30), "comune" to (50L shl 30)))()).isEqualTo(40L shl 30)
  }

  @Test
  fun loSpazioNonLettoDiUnaShareONessunaShareDaNull() = runTest {
    val shares = StepShares("alessio", "comune")
    assertThat(spaceOf(shares, mapOf("alessio" to (80L shl 30), "comune" to null))()).isNull()
    assertThat(spaceOf(null, emptyMap())()).isNull()
  }

  @Test
  fun unaShareSenzaAreaComuneELaStessaShareDueVolteSiLeggonoUnaVolta() = runTest {
    val reads = AtomicInteger(0)
    val one = LiveStepSources({ null }, { null }, { null }, { StepShares("alessio", null) }, StepNasSpace { reads.incrementAndGet(); 10L shl 30 })
    assertThat(one.space()?.freeBytes).isEqualTo(10L shl 30)
    assertThat(reads.get()).isEqualTo(1)
    val same = LiveStepSources({ null }, { null }, { null }, { StepShares("Alessio", "alessio") }, StepNasSpace { reads.incrementAndGet(); 10L shl 30 })
    same.space()
    assertThat(reads.get()).isEqualTo(2)
  }

  // --- Il NAS finto che non lascia scrivere ------------------------------------------------------------------------------------------------

  /** Conta le interrogazioni di spazio che passano da qui: l'unico contatto della simulazione con il NAS. */
  private class CountingSpace(private val inner: NasSpace) : NasSpace {
    val loads = AtomicInteger(0)
    val shares = mutableListOf<String>()

    override suspend fun load(share: String): NasSpaceState {
      loads.incrementAndGet()
      shares += share
      return inner.load(share)
    }
  }

  @Test
  fun conUnNasCheNonLasciaScrivereLaSimulazioneNonScriveNienteEDaiNumeri() = runTest {
    // Il NAS finto con il motore di produzione di core-nasops: le due share (privata e comune) segnate in sola lettura per questo account, quindi ogni
    // scrittura dei servizi del NAS sarebbe rifiutata; 50 GiB liberi; qualche file gia' sul NAS.
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    kit.readOnlyShares += setOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE)
    kit.setCallerFreeBytes(50L shl 30)
    kit.putFile(NasOpsTestKit.PRIVATE_SHARE, "Foto/uno.jpg", ByteArray(100))
    kit.putFile(NasOpsTestKit.COMMON_SHARE, "Comune/due.jpg", ByteArray(200))
    val before = listOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE).associateWith { kit.filesIn(it).sorted() }
    val bytesBefore = before.mapValues { (share, paths) -> paths.map { kit.bytes(share, it)!!.toList() } }

    val space = CountingSpace(kit.space)
    val sources = LiveStepSources(
      backup = { summary(fresh = count(2_000, 10L shl 30)) },
      index = { StepIndexFacts(privateRecords = 12_000, commonRecords = 3_000, bytesPerRecord = 29_000) },
      faces = { StepFacesFacts(activeHere = true, mediaCount = 12_000) },
      shares = { StepShares(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE) },
      nasSpace = StepNasSpaces.over(space),
    )
    val report = StepSimulation(sources).run()

    // I numeri ci sono, per ognuna delle quattro aree.
    assertThat(report.forecasts.map { it.area }).containsExactly(StepArea.INDEX, StepArea.BACKUP, StepArea.FACES, StepArea.ALBUMS).inOrder()
    assertThat(report.forecasts.filterIsInstance<StepForecast.Writes>().map { it.area }).containsExactly(StepArea.INDEX, StepArea.BACKUP, StepArea.FACES)
    assertThat(report.space?.freeBytes).isEqualTo(50L shl 30)
    assertThat(report.freeAfterBytes).isEqualTo((50L shl 30) - report.totalBytes)
    assertThat(report.attention).doesNotContain(StepAttention.SPACE_UNKNOWN)

    // Nessuna scrittura: l'unico contatto con il NAS e' lo spazio delle due share; i file, i loro byte e il diario delle operazioni sono quelli di prima.
    assertThat(space.loads.get()).isEqualTo(2)
    assertThat(space.shares).containsExactly(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE)
    val after = listOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE).associateWith { kit.filesIn(it).sorted() }
    assertThat(after).isEqualTo(before)
    assertThat(after.mapValues { (share, paths) -> paths.map { kit.bytes(share, it)!!.toList() } }).isEqualTo(bytesBefore)
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
  }

  @Test
  fun conUnNasCheNonRispondeLoSpazioNonSiLeggeEIlPassoNonParte() = runTest {
    val broken = object : NasSpace {
      override suspend fun load(share: String): NasSpaceState = NasSpaceState.Loading
    }
    val sources = LiveStepSources(
      backup = { BackupSummary.EMPTY },
      index = { StepIndexFacts(0, 0, null) },
      faces = { StepFacesFacts(false, 0) },
      shares = { StepShares("test", "test-comune") },
      nasSpace = StepNasSpaces.over(broken),
    )
    val report = StepSimulation(sources).run()
    assertThat(report.space).isNull()
    assertThat(report.attention).contains(StepAttention.SPACE_UNKNOWN)
    assertThat(report.blocked).isTrue()
  }
}
