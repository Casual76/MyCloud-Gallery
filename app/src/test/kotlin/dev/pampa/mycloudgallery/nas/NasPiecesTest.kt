package dev.pampa.mycloudgallery.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.notify.NasDailySummary
import dev.pampa.mycloudgallery.notify.NasDailySummary.Problem
import java.io.File
import java.nio.file.Files
import org.junit.Test

/** Le regole pure del collegamento della 4A in :app (P11): riepilogo giornaliero, mesi, congelamento, qualita', interruttori. */
class NasPiecesTest {

  // --- Riepilogo giornaliero (proposta 12, Q6) --------------------------------------------------------------------

  private val now = 10L * DAY

  @Test
  fun senzaNasConfiguratoIlRiepilogoTace() {
    assertThat(NasDailySummary.problems(frozenAreas = 2, lastScanAt = null, lastSyncAt = null, configuredSinceMs = null, now = now)).isEmpty()
  }

  @Test
  fun tuttoInOrdineNessunProblema() {
    val problems = NasDailySummary.problems(0, lastScanAt = now - HOUR, lastSyncAt = now - HOUR, configuredSinceMs = now - 5 * DAY, now = now)
    assertThat(problems).isEmpty()
  }

  @Test
  fun ilCongelamentoEntraNelRiepilogoConIlSoloNumeroDelleAree() {
    val problems = NasDailySummary.problems(2, lastScanAt = now - HOUR, lastSyncAt = now - HOUR, configuredSinceMs = now - 5 * DAY, now = now)
    assertThat(problems).containsExactly(Problem.Frozen(2))
  }

  @Test
  fun unNasMutoDaPiuDi26OreEUnaVerificaFermaDaTreGiorni() {
    val silent = NasDailySummary.problems(0, lastScanAt = now - 27 * HOUR, lastSyncAt = now - HOUR, configuredSinceMs = now - 5 * DAY, now = now)
    assertThat(silent).containsExactly(Problem.NasSilent)
    val stalled = NasDailySummary.problems(0, lastScanAt = now - HOUR, lastSyncAt = now - 4 * DAY, configuredSinceMs = now - 9 * DAY, now = now)
    assertThat(stalled).containsExactly(Problem.Stalled(4))
  }

  @Test
  fun unDispositivoAppenaConfiguratoSenzaBattitiNonEFermo() {
    val problems = NasDailySummary.problems(0, lastScanAt = null, lastSyncAt = null, configuredSinceMs = now - HOUR, now = now)
    assertThat(problems).isEmpty()
  }

  // --- Mese delle unita' di lavoro (D13) ----------------------------------------------------------------------------

  @Test
  fun ilMeseDiUnUnitaDalGiornoESenzaDataVaInFondo() {
    assertThat(PrepClasses.monthOf(20240315)).isEqualTo("2024-03")
    assertThat(PrepClasses.monthOf(null)).isEqualTo(WorkUnitKey.NO_DATE)
    assertThat(PrepClasses.monthOf(0)).isEqualTo(WorkUnitKey.NO_DATE)
    assertThat(PrepClasses.monthOf(20241399)).isEqualTo(WorkUnitKey.NO_DATE)
  }

  // --- Congelamento verso le regole (D10) ---------------------------------------------------------------------------

  @Test
  fun leRegoleVedonoLaRagionePiuGraveFraLeAreeCongelate() {
    val signal = MutableNasFreezeSignal()
    assertThat(signal.frozen.value).isNull()
    signal.update(listOf(FreezeReason.NOT_MEDIA, FreezeReason.VANISHED, FreezeReason.CHANGED))
    assertThat(signal.frozen.value).isEqualTo(FreezeReason.VANISHED)
    signal.update(emptyList())
    assertThat(signal.frozen.value).isNull()
  }

  // --- Qualita' delle anteprime (D16, E-L6) -------------------------------------------------------------------------

  @Test
  fun iLivelliDelleImpostazioniSonoQuelliDellaReplica() {
    for (level in PreviewLevel.entries) assertThat(NasPreviewQuality.toPreview(NasPreviewQuality.toLevel(level))).isEqualTo(level)
    val state = ReplicaQualityState(
      level = QualityLevel.HIGH,
      estimates = mapOf(QualityLevel.SAVER to SpaceEstimate(100, 3), QualityLevel.HIGH to SpaceEstimate(900, 3)),
      currentBytes = 400,
      converging = true,
    )
    val shown = NasPreviewQuality.toSettings(state, covers = true)
    assertThat(shown.level).isEqualTo(PreviewLevel.HIGH)
    assertThat(shown.estimates[PreviewLevel.SAVER]?.bytes).isEqualTo(100)
    assertThat(shown.estimates[PreviewLevel.HIGH]?.items).isEqualTo(3)
    assertThat(shown.currentBytes).isEqualTo(400)
    assertThat(shown.converging).isTrue()
    assertThat(shown.showAlbumCovers).isTrue()
  }

  @Test
  fun leCopertineDegliAlbumSopravvivonoAlRiavvio() {
    val dir = Files.createTempDirectory("covers").toFile()
    try {
      val file = File(dir, NasGraph.COVERS_FILE)
      val first = FileSwitch(file)
      assertThat(first.on).isFalse()
      first.set(true)
      assertThat(FileSwitch(file).on).isTrue()
      first.set(false)
      assertThat(FileSwitch(file).on).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun lEmergenzaSopravviveAlRiavvio() {
    val dir = Files.createTempDirectory("emergency").toFile()
    try {
      val file = File(dir, NasGraph.EMERGENCY_FILE)
      EmergencyStop(file).set(true)
      assertThat(EmergencyStop(file).stopped).isTrue()
      EmergencyStop(file).set(false)
      assertThat(EmergencyStop(file).stopped).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  private companion object {
    const val HOUR = 60L * 60 * 1000
    const val DAY = 24 * HOUR
  }
}
