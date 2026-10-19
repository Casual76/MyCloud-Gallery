package dev.pampa.mycloudgallery.feature.diagnostics.recording

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Ultimi esiti ed errori" riceve gli eventi dai punti veri (verifica funzionale, RE-4): lavori, sessione NAS, backup, VPN. Solo codici e
 * numeri; niente rumore.
 */
class DiagnosticsRecorderTest {

  private var now = 1_000_000L
  private val log = RingDiagnosticsLog()
  private val recorder = DiagnosticsRecorder(log) { now }

  private fun stats(batches: Int = 2, bytes: Long = 5_000_000L) = StageStats(batches, bytes, bytes, 12.seconds, resumed = false)

  @Test
  fun unaTappaConUnEsitoSiScriveConIlLavoroEIlMotivo() {
    recorder.stage(DiagJob.PHOTO_BACKUP, StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats(batches = 0, bytes = 0L)))

    val event = log.snapshot().single()
    assertThat(event.code).isEqualTo(DiagCodes.EVT_JOB_RESULT)
    assertThat(event.severity).isEqualTo(DiagSeverity.INFO)
    assertThat(event.values["job"]).isEqualTo(ExportValue.Enum.of(DiagJob.PHOTO_BACKUP))
    assertThat(event.values["result"]).isEqualTo(ExportValue.Enum.of(DiagJobResult.WAITING))
    assertThat(event.values.keys).contains("reason")
    assertThat(event.values["batches"]).isEqualTo(ExportValue.Num(0))
  }

  @Test
  fun unGiroAVuotoNonSiScrive_unoVeroSi() {
    recorder.stage(DiagJob.NAS_SCAN, StageOutcome.Finished(stats(batches = 0, bytes = 0L)))
    assertThat(log.snapshot()).isEmpty()

    recorder.stage(DiagJob.NAS_SCAN, StageOutcome.Finished(stats(batches = 3, bytes = 40_000_000L)))
    val event = log.snapshot().single()
    assertThat(event.values["result"]).isEqualTo(ExportValue.Enum.of(DiagJobResult.FINISHED))
    assertThat(event.values["bytes"]).isEqualTo(ExportValue.Num(40_000_000L))
    assertThat(event.values["elapsed"]).isEqualTo(ExportValue.Dur(12_000L))
  }

  @Test
  fun lStessoEsitoDelloStessoLavoroNonRiempieLAnello() {
    val waiting = StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats(0, 0L))
    repeat(4) {
      recorder.stage(DiagJob.INDEX_SYNC, waiting)
      now += 1.hours.inWholeMilliseconds
    }
    // Quattro ore di "in attesa" per lo stesso motivo: una riga sola. Dopo sei ore ne compare un'altra.
    assertThat(log.snapshot()).hasSize(1)
    now += 3.hours.inWholeMilliseconds
    recorder.stage(DiagJob.INDEX_SYNC, waiting)
    assertThat(log.snapshot()).hasSize(2)
    // Un altro motivo (o un altro lavoro) e' un'informazione nuova.
    recorder.stage(DiagJob.INDEX_SYNC, StageOutcome.Waiting(WaitReason.NasUnreachable, emptyList(), stats(0, 0L)))
    recorder.stage(DiagJob.PREPARATION, waiting)
    assertThat(log.snapshot()).hasSize(4)
  }

  @Test
  fun unaTappaChePerDiStradaVieneRicordataComeFermataODaRipetere() {
    recorder.stage(DiagJob.VIDEO_BACKUP, StageOutcome.Stopped(WaitReason.PowerSaver, emptyList(), stats()))
    recorder.stage(DiagJob.HQ_PREVIEWS, StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.WHEN_ALLOWED, stats()))

    val results = log.snapshot().map { (it.values["result"] as ExportValue.Enum).constant }
    assertThat(results).containsExactly("STOPPED", "YIELDED").inOrder()
  }

  @Test
  fun unLavoroCheFallisceSiRicordaConIlSoloNomeDelLavoro() {
    recorder.jobFailed(DiagJob.PHOTO_BACKUP)

    val event = log.snapshot().single()
    assertThat(event.code).isEqualTo(DiagCodes.EVT_JOB_FAILED)
    assertThat(event.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(event.values.keys).containsExactly("job", "result")
  }

  @Test
  fun ilMotivoDelloStopDiAndroidSiSalva() {
    recorder.jobStopped(DiagJob.PREPARATION, DiagStopCause.QUOTA)

    val event = log.snapshot().single()
    assertThat(event.code).isEqualTo(DiagCodes.ACT_JOB_STOPPED)
    assertThat(event.values["cause"]).isEqualTo(ExportValue.Enum.of(DiagStopCause.QUOTA))
  }

  @Test
  fun glErroriDelNasHannoGliStessiCodiciDellaDiagnostica_senzaNomiENessunaRipetizione() {
    // Il nome della share e' un dato dell'utente: non puo' arrivare all'anello.
    recorder.nasFailure(NasError.ShareMissing("Foto di Mario Rossi"))
    recorder.nasFailure(NasError.ShareMissing("Foto di Mario Rossi"))
    recorder.nasFailure(NasError.Timeout)
    recorder.nasBlocked(AuthBlockReason.ACCOUNT_LOCKED)

    val events = log.snapshot()
    assertThat(events.map { it.code.id }).containsExactly("NAS_SHARE_MISSING", "NAS_TIMEOUT", "NAS_ACCOUNT_LOCKED").inOrder()
    assertThat(events.all { it.values.isEmpty() }).isTrue()
    assertThat(events.joinToString { it.toString() }).doesNotContain("Mario")
    assertThat(events.last().severity).isAtLeast(DiagSeverity.WARNING)
  }

  @Test
  fun laSessioneTornataProntaSiScriveSoloDopoUnGuasto() {
    recorder.nasRecovered()
    assertThat(log.snapshot()).isEmpty()

    recorder.nasFailure(NasError.Timeout)
    now += 1.minutes.inWholeMilliseconds
    recorder.nasRecovered()
    recorder.nasRecovered()

    assertThat(log.snapshot().map { it.code.id }).containsExactly("NAS_TIMEOUT", "NAS_SESSION_OK").inOrder()
  }

  @Test
  fun unProblemaDelBackupHaIlCodiceDellaSezioneBackup() {
    recorder.backupProblem("NAS_FULL")
    // Un nome che non e' nel catalogo non diventa un codice inventato: vale come errore inatteso del backup.
    recorder.backupProblem("CODICE_CHE_NON_ESISTE")

    val codes = log.snapshot().map { it.code }
    assertThat(codes).containsExactly(DiagCodes.BACKUP_NAS_FULL, DiagCodes.BACKUP_UNEXPECTED).inOrder()
    assertThat(codes.all { it in DiagCatalog }).isTrue()
  }

  @Test
  fun laSospensioneDellaVpnSiScrive() {
    recorder.vpnSuspended(VpnSuspendReason.LOCKDOWN_SUSPECTED)
    recorder.vpnSuspended(VpnSuspendReason.NOT_CONTROLLABLE)

    assertThat(log.snapshot().map { it.code }).containsExactly(DiagCodes.VPN_LOCKDOWN_SUSPECTED, DiagCodes.VPN_NOT_CONTROLLABLE).inOrder()
  }

  @Test
  fun leVociDellaSezioneUsanoIlCatalogoELeRigheDiLavoroDiconoLavoroEEsito() = runTest {
    recorder.stage(DiagJob.PHOTO_BACKUP, StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats(0, 0L)))
    recorder.jobStopped(DiagJob.INDEX_SYNC, DiagStopCause.QUOTA)
    recorder.jobFailed(DiagJob.TRUST_SYNC)
    val contributor = RecentEventsContributor(log, nowMillis = { now + 5_000 }, text = { "T$it" })

    val items = contributor.collect()

    assertThat(items.map { it.code }).containsExactly(DiagCodes.EVT_JOB_FAILED, DiagCodes.ACT_JOB_STOPPED, DiagCodes.EVT_JOB_RESULT).inOrder()
    assertThat(items.all { it.titleRes != 0 }).isTrue()
    // Il dettaglio c'e' solo per lo schermo; l'esportazione ha le costanti e l'eta', non il dettaglio.
    assertThat(items.all { it.detail != null }).isTrue()
    assertThat(items.last().detail!!.args).hasSize(3)
    assertThat(items.last().export["ageMs"]).isEqualTo(ExportValue.Dur(5_000L))
    assertThat(items.flatMap { it.export.values }.all { it is ExportValue.Num || it is ExportValue.Dur || it is ExportValue.Flag || it is ExportValue.Enum }).isTrue()
  }

  @Test
  fun unEventoSenzaLavoroNonHaDettaglioERestaComeSempre() = runTest {
    recorder.nasFailure(NasError.Timeout)
    val items = RecentEventsContributor(log, nowMillis = { now }).collect()
    assertThat(items.single().detail).isNull()
    assertThat(items.single().code.id).isEqualTo("NAS_TIMEOUT")
  }

  @Test
  fun lAvvioFallitoHaIlSuoCodice() {
    recorder.startFailed()
    assertThat(log.snapshot().single().code).isEqualTo(DiagCodes.EVT_START_FAILED)
  }
}
