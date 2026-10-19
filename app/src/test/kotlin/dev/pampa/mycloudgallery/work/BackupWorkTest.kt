package dev.pampa.mycloudgallery.work

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I lavori del backup, le parti pure (Fase 5A, P7; docs/fase5-progetto.md §1.4, §2.8): cosa fare dopo una tappa, i vincoli per un'attesa
 * delle regole, il ritardo fino alla fascia notturna e il giro del lavoro avviato dall'utente (foto prima dei video, "Ferma", cancello).
 */
class BackupWorkTest {

  private val stats = StageStats(0, 0L, 0L, Duration.ZERO, resumed = false)

  private fun outcome(stage: StageOutcome, stop: RunStop? = null, problem: BackupProblemCode? = null) =
    BackupStageOutcome(stage, stop?.let { BackupRunResult.nothing(it, problem) })

  private val finished = StageOutcome.Finished(stats)

  @Test
  fun ilSeguitoDiUnaTappaDelMotore() {
    assertThat(BackupFollowUp.of(null)).isEqualTo(BackupFollowUp.None)
    assertThat(BackupFollowUp.of(outcome(finished, RunStop.COMPLETE))).isEqualTo(BackupFollowUp.None)
    assertThat(BackupFollowUp.of(outcome(finished, RunStop.STOPPED))).isEqualTo(BackupFollowUp.None)
    assertThat(BackupFollowUp.of(outcome(finished, RunStop.GATE))).isEqualTo(BackupFollowUp.None)
    // Il NAS non risponde (pieno, quota, irraggiungibile): si riprova con l'attesa crescente di WorkManager.
    assertThat(BackupFollowUp.of(outcome(finished, RunStop.NAS))).isEqualTo(BackupFollowUp.Retry)
    assertThat(BackupFollowUp.of(outcome(StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.IMMEDIATELY, stats), RunStop.BUDGET)))
      .isEqualTo(BackupFollowUp.Continue)
    assertThat(BackupFollowUp.of(outcome(StageOutcome.Waiting(WaitReason.NeedsPower, emptyList(), stats))))
      .isEqualTo(BackupFollowUp.Wait(WaitReason.NeedsPower))
    assertThat(BackupFollowUp.of(outcome(StageOutcome.Stopped(WaitReason.Offline, emptyList(), stats), RunStop.BUDGET)))
      .isEqualTo(BackupFollowUp.Wait(WaitReason.Offline))
  }

  @Test
  fun ilSeguitoDelleVerifiche() {
    val none = SafetyStageOutcome(listOf(finished), 3, 0, 0, null)
    val yielded = SafetyStageOutcome(listOf(StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.WHEN_ALLOWED, stats)), 1, 0, 0, null)
    val waiting = SafetyStageOutcome(listOf(StageOutcome.Waiting(WaitReason.PowerSaver, emptyList(), stats)), 0, 0, 0, null)
    assertThat(BackupFollowUp.ofSafety(null)).isEqualTo(BackupFollowUp.None)
    assertThat(BackupFollowUp.ofSafety(none)).isEqualTo(BackupFollowUp.None)
    assertThat(BackupFollowUp.ofSafety(yielded)).isEqualTo(BackupFollowUp.Continue)
    assertThat(BackupFollowUp.ofSafety(waiting)).isEqualTo(BackupFollowUp.Wait(WaitReason.PowerSaver))
  }

  @Test
  fun iVincoliDiWorkManagerSoloPerCicheSiEsprimeEsattamente() {
    // La carica e la batteria si dicono a WorkManager (che avvisa quando cambiano); le altre attese non programmano niente.
    assertThat(BackupWorkScheduling.constraintsFor(WaitReason.NeedsPower).requiresCharging()).isTrue()
    assertThat(BackupWorkScheduling.constraintsFor(WaitReason.BatteryLow(15, 20)).requiresBatteryNotLow()).isTrue()
    assertThat(BackupWorkScheduling.constraintsFor(WaitReason.PowerSaver).requiresBatteryNotLow()).isTrue()
    val plain = BackupWorkScheduling.constraintsFor(null)
    assertThat(plain.requiresCharging()).isFalse()
    assertThat(plain.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    assertThat(BackupWorkScheduling.constraintsFor(WaitReason.Offline).requiresCharging()).isFalse()
  }

  @Test
  fun ilRitardoFinoAllaFasciaNotturnaEIlRestoNonProgrammaNiente() {
    val now = LocalDateTime.of(2026, 10, 16, 12, 0)
    val window = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))
    val start = LocalDateTime.of(2026, 10, 16, 23, 0)
    val delay = BackupWorkScheduling.delayFor(WaitReason.OutsideWindow(window, start), now)
    // 11 ore alla fascia, piu' un minuto di margine.
    assertThat(delay).isEqualTo(11L * 3_600_000 + 60_000)
    // Una fascia gia' iniziata (orologi che non coincidono): non si va indietro nel tempo.
    assertThat(BackupWorkScheduling.delayFor(WaitReason.OutsideWindow(window, now.minusHours(1)), now)).isEqualTo(60_000L)
    assertThat(BackupWorkScheduling.delayFor(WaitReason.NeedsPower, now)).isEqualTo(0L)
    // Niente polling: la rete, il roaming, il profilo, il congelamento non programmano un altro lavoro.
    for (wait in listOf(WaitReason.Offline, WaitReason.NasUnreachable, WaitReason.Roaming, WaitReason.ProfileInactive, WaitReason.HeavyNeedsHomeWifi, WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))) {
      assertThat(BackupWorkScheduling.delayFor(wait, now)).isNull()
    }
  }

  // --- Il lavoro avviato dall'utente ---------------------------------------------------------------------------------------------

  private class Script(private val outcomes: Map<BackupKind, MutableList<BackupStageOutcome?>>) {
    val asked = mutableListOf<BackupKind>()

    suspend fun stage(kind: BackupKind): BackupStageOutcome? {
      asked += kind
      return outcomes.getValue(kind).removeFirstOrNull()
    }
  }

  private fun yielded() = outcome(StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.IMMEDIATELY, stats), RunStop.BUDGET)

  private fun done() = outcome(finished, RunStop.COMPLETE)

  @Test
  fun ilLavoroDellUtenteFaPrimaLeFotoPoiIVideoATappe() = runBlocking {
    val script = Script(mapOf(BackupKind.PHOTOS to mutableListOf(yielded(), yielded(), done()), BackupKind.VIDEOS to mutableListOf(yielded(), done())))
    val end = BackupJobRun(script::stage, userInitiated = true).run()
    assertThat(end).isEqualTo(BackupJobEnd.DONE)
    assertThat(script.asked).containsExactly(
      BackupKind.PHOTOS, BackupKind.PHOTOS, BackupKind.PHOTOS, BackupKind.VIDEOS, BackupKind.VIDEOS,
    ).inOrder()
  }

  @Test
  fun unLavoroNormaleFaUnaTappaPerTipoESiFaRiprogrammare() = runBlocking {
    val script = Script(mapOf(BackupKind.PHOTOS to mutableListOf(yielded(), done()), BackupKind.VIDEOS to mutableListOf(done())))
    assertThat(BackupJobRun(script::stage, userInitiated = false).run()).isEqualTo(BackupJobEnd.RESCHEDULE)
    assertThat(script.asked).containsExactly(BackupKind.PHOTOS)
    Unit
  }

  @Test
  fun ferma_ilCancelloChiusoEIPermessiFinisconoTuttoSenzaProvareConIVideo() = runBlocking {
    for (stop in listOf(RunStop.STOPPED, RunStop.GATE, RunStop.PHONE)) {
      val script = Script(mapOf(BackupKind.PHOTOS to mutableListOf(outcome(finished, stop)), BackupKind.VIDEOS to mutableListOf(done())))
      assertThat(BackupJobRun(script::stage, userInitiated = true).run()).isEqualTo(BackupJobEnd.DONE)
      assertThat(script.asked).containsExactly(BackupKind.PHOTOS)
    }
  }

  @Test
  fun unErroreDelNasSiRiprovaEUnaAttesaDelleRegoleFinisce() = runBlocking {
    val nas = Script(mapOf(BackupKind.PHOTOS to mutableListOf(outcome(finished, RunStop.NAS)), BackupKind.VIDEOS to mutableListOf(done())))
    assertThat(BackupJobRun(nas::stage, userInitiated = true).run()).isEqualTo(BackupJobEnd.RESCHEDULE)
    val waiting = Script(
      mapOf(
        BackupKind.PHOTOS to mutableListOf(outcome(StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats))),
        BackupKind.VIDEOS to mutableListOf(done()),
      ),
    )
    // Le regole non lasciano partire (o serve la conferma): lo dice la pagina, il lavoro non ritenta e non passa ai video.
    assertThat(BackupJobRun(waiting::stage, userInitiated = true).run()).isEqualTo(BackupJobEnd.DONE)
    assertThat(waiting.asked).containsExactly(BackupKind.PHOTOS)
    Unit
  }

  @Test
  fun senzaNienteDaFareIlLavoroFinisce() = runBlocking {
    val script = Script(mapOf(BackupKind.PHOTOS to mutableListOf(null), BackupKind.VIDEOS to mutableListOf(done())))
    assertThat(BackupJobRun(script::stage, userInitiated = true).run()).isEqualTo(BackupJobEnd.DONE)
    assertThat(script.asked).containsExactly(BackupKind.PHOTOS)
    Unit
  }

  @Test
  fun unLavoroDellUtenteHaUnTettoDiTappe() = runBlocking {
    val endless = Script(mapOf(BackupKind.PHOTOS to MutableList(100) { yielded() }, BackupKind.VIDEOS to mutableListOf(done())))
    assertThat(BackupJobRun(endless::stage, userInitiated = true, maxSlices = 3).run()).isEqualTo(BackupJobEnd.RESCHEDULE)
    assertThat(endless.asked).hasSize(3)
  }

  // --- R1 della revisione 5A: un errore inatteso non e' ne' un successo silenzioso ne' un RESCHEDULE a oltranza ----------------------

  @Test
  fun r1_unErroreInattesoSiSalvaComeProblemaEIlLavoroFinisceSenzaRiprogrammarsi() = runBlocking {
    var saved = 0
    val attempt = attemptStage("BackupWorkTest", onError = { saved++ }) { throw IllegalStateException("errore inatteso di prova") }
    // Il problema e' stato salvato una volta (la pagina dice `UNEXPECTED`), e il chiamante sa che e' fallito: nessun `success` muto.
    assertThat(saved).isEqualTo(1)
    assertThat(attempt).isEqualTo(StageAttempt.Failed)
    // Il lavoro dell'utente: lo stesso errore a ogni giro finirebbe in RESCHEDULE a oltranza; adesso il servizio chiude con DONE.
    val end = when (val a = attemptStage("BackupWorkTest", onError = { saved++ }) { throw IllegalStateException("ancora") }) {
      StageAttempt.Failed -> BackupJobEnd.DONE
      is StageAttempt.Done -> a.value
    }
    assertThat(end).isEqualTo(BackupJobEnd.DONE)
    assertThat(saved).isEqualTo(2)
  }

  @Test
  fun r1_unaTappaCheRiesceDaIlSuoValoreENonSalvaNiente() = runBlocking {
    var saved = 0
    val attempt = attemptStage("BackupWorkTest", onError = { saved++ }) { BackupFollowUp.Continue }
    assertThat((attempt as StageAttempt.Done).value).isEqualTo(BackupFollowUp.Continue)
    assertThat(saved).isEqualTo(0)
  }

  @Test
  fun r1_lAnnullamentoPassaEUnErroreDelSalvataggioNonLoNasconde() {
    // L'annullamento (il lavoro e' stato fermato) risale: non e' un errore del backup e non si salva nessun problema.
    var saved = 0
    val cancelled = runCatching {
      runBlocking { attemptStage("BackupWorkTest", onError = { saved++ }) { throw kotlinx.coroutines.CancellationException("fermato") } }
    }.exceptionOrNull()
    assertThat(cancelled).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    assertThat(saved).isEqualTo(0)
    // Se il salvataggio stesso fallisce, il lavoro e' comunque `Failed` (mai un successo).
    val attempt = runBlocking { attemptStage("BackupWorkTest", onError = { throw IllegalStateException("memoria piena") }) { throw IllegalStateException("x") } }
    assertThat(attempt).isEqualTo(StageAttempt.Failed)
  }

  @Test
  fun r1_ilGuardianoCheFallisceNonTogleLeVerifiche() = runBlocking {
    val order = mutableListOf<String>()
    var error: Exception? = null
    BackupStages.guardThenVerify(
      guard = {
        order += "guardiano"
        throw IllegalStateException("errore inatteso del guardiano")
      },
      onGuardError = {
        order += "errore"
        error = it
      },
      verify = { order += "verifiche" },
    )
    assertThat(order).containsExactly("guardiano", "errore", "verifiche").inOrder()
    assertThat(error).isInstanceOf(IllegalStateException::class.java)
    // Col guardiano che va bene, le verifiche seguono lo stesso e nessun errore si dice.
    order.clear()
    error = null
    BackupStages.guardThenVerify(guard = { order += "guardiano" }, onGuardError = { order += "errore" }, verify = { order += "verifiche" })
    assertThat(order).containsExactly("guardiano", "verifiche").inOrder()
    // L'annullamento passa: nessuna verifica parte dopo un lavoro fermato.
    order.clear()
    val cancelled = runCatching {
      BackupStages.guardThenVerify(
        guard = { throw kotlinx.coroutines.CancellationException("fermato") },
        onGuardError = { order += "errore" },
        verify = { order += "verifiche" },
      )
    }.exceptionOrNull()
    assertThat(cancelled).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    assertThat(order).isEmpty()
  }
}
