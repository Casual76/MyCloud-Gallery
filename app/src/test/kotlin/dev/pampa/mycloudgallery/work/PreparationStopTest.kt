package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageBatch
import dev.pampa.mycloudgallery.core.rules.run.StageConfig
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryCheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryTrafficLedger
import dev.pampa.mycloudgallery.core.rules.run.testing.ScriptedStageRules
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto B, BS-3 (critico Sol): la preparazione che si ferma perche' le regole lo dicono (la batteria scesa sotto il livello durante il lotto, o il cursore alzato) deve restare
 * **uno stop con ripresa**, non «finito». Si prova con `StagedRun` vero e le regole a sceneggiatura, con lo stesso ponte che usa `NasStages.prepare` ([preparationHasMore]); e che
 * un lotto davvero concluso resti `Finished`.
 */
class PreparationStopTest {

  private val run = RuleDecision.Run(RunScope.FULL, Pace.NORMAL, NetworkClass.HOME_WIFI, null)
  private val low = RuleDecision.Wait(WaitReason.BatteryLow(19, 20))

  private fun staged(rules: ScriptedStageRules) = StagedRun(
    request = WorkRequest.Background(BackgroundActivity.PREPARATION, writesToNas = true),
    rules = rules,
    ledger = InMemoryTrafficLedger(),
    checkpoints = InMemoryCheckpointStore<Int>(),
    timeSource = TestTimeSource(),
    config = StageConfig(recheckEvery = Duration.ZERO, reducedPaceDelay = Duration.ZERO),
  )

  /** Un lotto come quello di `NasStages.prepare`: `proceed()` falso = lo stop delle regole del runner; il resto lo decide il ponte. */
  private fun batch(finishes: Boolean) = StageBatch<Int> { scope, _ ->
    val stop = if (finishes) PrepStop.NO_WORK else if (scope.proceed()) PrepStop.NO_WORK else PrepStop.RULES
    BatchResult(hasMore = preparationHasMore(stop))
  }

  @Test
  fun laBatteriaScendeDuranteIlLotto_lEsitoEStopped_conIlMotivo_eLaRipresaInCarica() = runTest {
    // Parte (running = false: Run), poi la prima rivalutazione a lotto in corso (running = true) dice «batteria».
    val rules = ScriptedStageRules { call -> if (call.running) low else run }

    val outcome = staged(rules).run(batch(finishes = false))

    assertThat(outcome).isInstanceOf(StageOutcome.Stopped::class.java)
    assertThat((outcome as StageOutcome.Stopped).reason).isEqualTo(WaitReason.BatteryLow(19, 20))
    // E da qui si riprende: il follow-up del NAS e' una attesa, e la regola unica chiede la carica.
    val follow = NasFollowUp.of(outcome)
    assertThat(follow).isEqualTo(NasFollowUp.Wait(WaitReason.BatteryLow(19, 20)))
    assertThat(RuleWaits.followUpOf((follow as NasFollowUp.Wait).reason, LocalDateTime.of(2026, 11, 13, 12, 0))!!.charging).isTrue()
  }

  @Test
  fun unLottoDavveroConcluso_restaFinished_ancheConLeRegoleCheDirebberoStop() = runTest {
    val rules = ScriptedStageRules { call -> if (call.running) low else run }

    val outcome = staged(rules).run(batch(finishes = true))

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(NasFollowUp.of(outcome)).isEqualTo(NasFollowUp.None)
  }

  @Test
  fun senzaNiente_daFare_restaFinished_conLeRegoleLibere() = runTest {
    val rules = ScriptedStageRules { run }

    val outcome = staged(rules).run(batch(finishes = true))

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
  }

  @Test
  fun ilPonte_diceCheCeAncoraLavoro_soloPerIlTempoELeRegole() {
    assertThat(preparationHasMore(PrepStop.BUDGET)).isTrue()
    assertThat(preparationHasMore(PrepStop.RULES)).isTrue()
    for (stop in listOf(PrepStop.NO_WORK, PrepStop.UNAVAILABLE, PrepStop.BLOCKED, PrepStop.STORAGE)) {
      assertThat(preparationHasMore(stop)).isFalse()
    }
    assertThat(preparationHasMore(null)).isFalse()
  }
}
