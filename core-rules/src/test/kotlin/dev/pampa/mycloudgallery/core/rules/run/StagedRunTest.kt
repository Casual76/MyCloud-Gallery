package dev.pampa.mycloudgallery.core.rules.run

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.AppContext
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.ComposedSystemStateProvider
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.DeviceRoles
import dev.pampa.mycloudgallery.core.rules.FakeAndroidLimitsSignal
import dev.pampa.mycloudgallery.core.rules.FakeAppContextSignal
import dev.pampa.mycloudgallery.core.rules.FakeDeviceRolesSignal
import dev.pampa.mycloudgallery.core.rules.FakeDeviceStateSource
import dev.pampa.mycloudgallery.core.rules.FakeHomeNetworkSignal
import dev.pampa.mycloudgallery.core.rules.FakeNasReachabilitySignal
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.MutableClock
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.PowerState
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.at
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryCheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryTrafficLedger
import dev.pampa.mycloudgallery.core.rules.run.testing.ScriptedStageRules
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import java.io.File
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `StagedRun` (D22, §2.8): stop a meta' lotto, traffico registrato prima del crash, ripresa, tempo della tappa, tetto del
 * giro (rilevamento fuori casa) e tetto mensile. Il lavoro di prova ha il punto di ripresa "ultimo elemento finito".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StagedRunTest {

  @get:Rule val tmp = TemporaryFolder()

  private val ledger = InMemoryTrafficLedger()
  private val store = InMemoryCheckpointStore<Int>()
  private val time = TestTimeSource()

  private class Crash : RuntimeException("il processo e' morto")

  /**
   * [total] elementi da [bytesPerItem] byte. Per ogni elemento: `proceed`, meta' del traffico, (crash?), il resto, il
   * punto di ripresa. Ricorda cosa ha fatto ([processed]) e il budget che vedeva ([budgets]).
   */
  private inner class Work(
    val total: Int,
    val bytesPerItem: Long = 1 * MB,
    val perBatch: Int = Int.MAX_VALUE,
    val itemTime: Duration = Duration.ZERO,
  ) {
    val processed = mutableListOf<Int>()
    val budgets = mutableListOf<Long?>()
    var batches = 0
    var crashAt: Int? = null

    fun batch() =
      StageBatch<Int> { scope, from ->
        batches++
        val start = from ?: 0
        var done = start
        var inBatch = 0
        while (done < total && inBatch < perBatch) {
          if (!scope.proceed()) break
          val item = done + 1
          budgets += scope.byteBudget
          scope.record(bytesPerItem / 2)
          if (item == crashAt) throw Crash()
          scope.record(bytesPerItem - bytesPerItem / 2)
          processed += item
          time += itemTime
          scope.commit(item)
          done = item
          inBatch++
        }
        BatchResult(checkpoint = done.takeIf { it != start }, hasMore = done < total)
      }
  }

  private fun runOn(network: NetworkClass = NetworkClass.HOME_WIFI, remaining: Long? = null, pace: Pace = Pace.NORMAL) =
    RuleDecision.Run(RunScope.FULL, pace, network, remaining)

  private fun always(run: RuleDecision.Run = runOn(), plugged: Boolean = true) =
    ScriptedStageRules(plugged) { run }

  private fun staged(
    rules: ScriptedStageRules,
    request: WorkRequest = WorkRequest.Background(BackgroundActivity.PREPARATION),
    config: StageConfig = StageConfig(recheckEvery = Duration.ZERO, reducedPaceDelay = Duration.ZERO),
    roundCap: ((NetworkClass) -> Long?)? = null,
  ) = StagedRun(request, rules, ledger, store, time, config, roundCap)

  // --- Il protocollo ------------------------------------------------------------------------------------------------

  @Test
  fun unLavoroCheFinisce_registraIlTrafficoEPulisceIlPuntoDiRipresa() = runTest {
    val work = Work(total = 5, perBatch = 2)

    val outcome = staged(always()).run(work.batch())

    outcome as StageOutcome.Finished
    assertThat(work.processed).containsExactly(1, 2, 3, 4, 5).inOrder()
    assertThat(outcome.stats.batches).isEqualTo(3)
    assertThat(outcome.stats.bytes).isEqualTo(5 * MB)
    assertThat(outcome.stats.resumed).isFalse()
    assertThat(ledger.entries.map { it.source to it.network }.toSet())
      .containsExactly(TrafficSource.PREPARATION to TrafficNetwork.HOME_WIFI)
    assertThat(ledger.totalBytes).isEqualTo(5 * MB)
    assertThat(store.value).isNull()
    assertThat(store.clears).isEqualTo(1)
  }

  @Test
  fun ilProtocollo_valutaAllaPartenza_poiDopoOgniLottoConRunningVero() = runTest {
    val rules = always()
    var call = 0
    val batch = StageBatch<Int> { _, _ -> BatchResult(checkpoint = ++call, hasMore = call < 3) }

    staged(rules).run(batch)

    // evaluate(running = false) → lotto 1 → evaluate(running = true) → lotto 2 → evaluate(running = true) → lotto 3.
    assertThat(rules.calls.map { it.running }).containsExactly(false, true, true).inOrder()
    assertThat(rules.calls.all { it.request == WorkRequest.Background(BackgroundActivity.PREPARATION) }).isTrue()
    assertThat(store.saves).isEqualTo(3)
    assertThat(store.clears).isEqualTo(1)
  }

  @Test
  fun seLeRegoleDiconoAspetta_nessunLottoPartePerNullaESiNonRegistraNiente() = runTest {
    val rules = ScriptedStageRules { RuleDecision.Wait(WaitReason.NeedsPower, listOf(WaitReason.PowerSaver)) }
    val work = Work(total = 3)

    val outcome = staged(rules).run(work.batch())

    outcome as StageOutcome.Waiting
    assertThat(outcome.reason).isEqualTo(WaitReason.NeedsPower)
    assertThat(outcome.alsoBlockedBy).containsExactly(WaitReason.PowerSaver)
    assertThat(work.batches).isEqualTo(0)
    assertThat(ledger.entries).isEmpty()
    assertThat(store.saves + store.clears).isEqualTo(0)
  }

  @Test
  fun faiOra_chiedeConferma_nessunLottoParte() = runTest {
    val decision = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null)
    val rules = ScriptedStageRules { decision }
    val work = Work(total = 3)

    val outcome = staged(rules, WorkRequest.UserInitiated(BackgroundActivity.PREPARATION)).run(work.batch())

    assertThat(outcome).isInstanceOf(StageOutcome.NeedsConfirmation::class.java)
    assertThat((outcome as StageOutcome.NeedsConfirmation).decision).isEqualTo(decision)
    assertThat(work.batches).isEqualTo(0)
  }

  // --- Stop a meta' lotto e ripresa ---------------------------------------------------------------------------------

  @Test
  fun stopAMetaLotto_salvaIlPuntoDell_ultimoElementoFinito_eLaTappaDopoRiprende() = runTest {
    val thermal = WaitReason.Thermal(ThermalStatus.SEVERE)
    // Una sola partenza e tre rivalutazioni in corso (una per elemento): alla quarta, Stop.
    val first = ScriptedStageRules { call -> if (call.running && call.index >= 4) RuleDecision.Stop(thermal) else runOn() }
    val work = Work(total = 10)

    val stopped = staged(first).run(work.batch())

    stopped as StageOutcome.Stopped
    assertThat(stopped.reason).isEqualTo(thermal)
    assertThat(work.processed).containsExactly(1, 2, 3).inOrder()
    assertThat(work.batches).isEqualTo(1) // il lotto era da 10: si e' fermato dentro
    assertThat(store.value).isEqualTo(3)
    assertThat(ledger.totalBytes).isEqualTo(3 * MB)
    assertThat(stopped.stats.bytes).isEqualTo(3 * MB)

    // La tappa dopo (processo nuovo, stesso punto di ripresa) riprende dal quarto.
    val resumed = staged(always()).run(work.batch())

    resumed as StageOutcome.Finished
    assertThat(resumed.stats.resumed).isTrue()
    assertThat(work.processed).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).inOrder()
    assertThat(ledger.totalBytes).isEqualTo(10 * MB) // nessun elemento rifatto, nessun byte perso o contato due volte
    assertThat(store.value).isNull()
  }

  @Test
  fun unLottoCheNonChiamaProceed_siFermaComunqueFraUnLottoEL_altro() = runTest {
    val rules = ScriptedStageRules { call -> if (call.running) RuleDecision.Stop(WaitReason.PowerSaver) else runOn() }
    val work = Work(total = 6, perBatch = 2)
    // Lotti che ignorano `proceed()`: la rivalutazione fra due lotti li ferma lo stesso.
    val blind = StageBatch<Int> { scope, from ->
      val next = (from ?: 0) + 2
      scope.record(1 * MB)
      scope.commit(next)
      work.processed += next
      BatchResult(hasMore = next < 6)
    }

    val outcome = staged(rules).run(blind)

    outcome as StageOutcome.Stopped
    assertThat(outcome.reason).isEqualTo(WaitReason.PowerSaver)
    assertThat(work.processed).containsExactly(2)
    assertThat(store.value).isEqualTo(2)
  }

  // --- Il traffico e' registrato subito: prima del crash ------------------------------------------------------------

  @Test
  fun ilProcessoMuoreAMetaElemento_ilTrafficoGiaPassatoContaEIlPuntoEL_ultimoSalvato() = runTest {
    val work = Work(total = 5)
    work.crashAt = 3

    val crash = runCatching { staged(always()).run(work.batch()) }.exceptionOrNull()

    assertThat(crash).isInstanceOf(Crash::class.java)
    // Elementi 1 e 2 interi, e la meta' dell'elemento 3 prima di morire: tutto nel registro, niente "a fine lavoro".
    assertThat(ledger.totalBytes).isEqualTo(2 * MB + MB / 2)
    assertThat(store.value).isEqualTo(2)
    assertThat(work.processed).containsExactly(1, 2).inOrder()

    // Riavvio: stesso registro, stesso punto di ripresa. L'elemento 3 si rifa' (idempotente) e si conta cio' che e' passato.
    work.crashAt = null
    val resumed = staged(always()).run(work.batch())

    resumed as StageOutcome.Finished
    assertThat(resumed.stats.resumed).isTrue()
    assertThat(work.processed).containsExactly(1, 2, 3, 4, 5).inOrder()
    assertThat(ledger.totalBytes).isEqualTo(5 * MB + MB / 2) // la meta' persa e rifatta conta due volte: meglio di zero
  }

  @Test
  fun annullamentoDiWorkManager_ilTrafficoEIlPuntoRestano_eSiRiprende() = runTest {
    val work = Work(total = 6)
    val stuckAfterTwo = StageBatch<Int> { scope, from ->
      var done = from ?: 0
      while (done < 6) {
        scope.proceed()
        scope.record(1 * MB)
        done++
        scope.commit(done)
        work.processed += done
        if (done == 2) awaitCancellation() // `onStopped`: la coroutine viene annullata qui
      }
      BatchResult(hasMore = false)
    }

    val job = launch { staged(always()).run(stuckAfterTwo) }
    runCurrent()
    job.cancelAndJoin()

    assertThat(job.isCancelled).isTrue()
    assertThat(ledger.totalBytes).isEqualTo(2 * MB)
    assertThat(store.value).isEqualTo(2)
    val resumed = staged(always()).run(work.batch())
    assertThat(resumed).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(work.processed).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
  }

  @Test
  fun laReteDavveroUsata_nonQuellaDelVerdetto_eCiNonPortaTraffico_nonSiRegistra() = runTest {
    val batch = StageBatch<Int> { scope, _ ->
      scope.record(10 * MB) // rete del verdetto: Wi-Fi di casa
      scope.record(2 * MB, NetworkClass.MOBILE) // Android ha spostato il socket sui dati mobili (E-R12)
      scope.record(5 * MB, NetworkClass.OFFLINE) // niente rete: niente contatore
      scope.record(0)
      scope.record(-4)
      BatchResult(hasMore = false)
    }

    val outcome = staged(always()).run(batch) as StageOutcome.Finished

    assertThat(ledger.entries.map { it.network to it.bytes })
      .containsExactly(TrafficNetwork.HOME_WIFI to 10 * MB, TrafficNetwork.MOBILE to 2 * MB)
      .inOrder()
    assertThat(outcome.stats.bytes).isEqualTo(12 * MB)
  }

  @Test
  fun laSorgenteDelTrafficoSegueLaRichiesta() = runTest {
    val batch = StageBatch<Int> { scope, _ ->
      scope.record(1 * MB)
      BatchResult(hasMore = false)
    }

    staged(always(), WorkRequest.Background(BackgroundActivity.INDEX_SYNC)).run(batch)
    staged(always(), WorkRequest.UserInitiated(BackgroundActivity.HQ_PREVIEWS)).run(batch)
    staged(always(), WorkRequest.VisibleContent).run(batch)

    assertThat(ledger.entries.map { it.source })
      .containsExactly(TrafficSource.INDEX_SYNC, TrafficSource.HQ_PREVIEWS, TrafficSource.VISIBLE_CONTENT)
      .inOrder()
  }

  // --- Il tempo della tappa e la catena -----------------------------------------------------------------------------

  @Test
  fun dopoOtto_minuti_laTappaCedeIlPassoEIlPuntoEL_ultimoElemento() = runTest {
    val work = Work(total = 10, itemTime = 3.minutes)

    val outcome = staged(always(plugged = true)).run(work.batch())

    outcome as StageOutcome.Yielded
    assertThat(outcome.why).isEqualTo(YieldReason.TIME_SLICE)
    assertThat(work.processed).containsExactly(1, 2, 3).inOrder() // 0, 3 e 6 minuti: al quarto controllo sono 9
    assertThat(store.value).isEqualTo(3)
    assertThat(outcome.stats.elapsed).isEqualTo(9.minutes)
    assertThat(StageConfig.DEFAULT_STAGE_DURATION).isEqualTo(8.minutes)
  }

  @Test
  fun inCarica_laCatenaContinuaSubito_aBatteria_quandoAndroidLoConsente() = runTest {
    val charging = staged(always(plugged = true)).run(Work(total = 10, itemTime = 5.minutes).batch())
    val onBattery = staged(always(plugged = false)).run(Work(total = 10, itemTime = 5.minutes).batch())

    assertThat((charging as StageOutcome.Yielded).next).isEqualTo(NextStage.IMMEDIATELY)
    assertThat((onBattery as StageOutcome.Yielded).next).isEqualTo(NextStage.WHEN_ALLOWED)
  }

  @Test
  fun laDurataDellaTappaEUnaCostante_enonDipendeDalTelefono() {
    assertThat(StageConfig().stageDuration).isEqualTo(8.minutes)
  }

  @Test
  fun leRegoleSiRivalutanoAlMassimoOgniRecheck_ilTempoNonLeAggira() = runTest {
    val rules = always()
    val work = Work(total = 12, itemTime = 1.seconds)

    staged(rules, config = StageConfig(recheckEvery = 5.seconds)).run(work.batch())

    // 12 secondi di lavoro: una valutazione alla partenza, una dopo 5 e una dopo 10 secondi (nessuna fra lotti: lotto unico).
    assertThat(rules.runningCalls).isEqualTo(2)
  }

  @Test
  fun aRitmoRidotto_cePausaFraUnLottoEL_altro() = runTest {
    val rules = always(runOn(pace = Pace.REDUCED))
    val work = Work(total = 6, perBatch = 2)

    val outcome = staged(rules, config = StageConfig(recheckEvery = Duration.ZERO, reducedPaceDelay = 2.seconds)).run(work.batch())

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(work.batches).isEqualTo(3)
    assertThat(testScheduler.currentTime).isEqualTo(4_000L) // due pause: dopo il primo e dopo il secondo lotto, non dopo l'ultimo
  }

  @Test
  fun conLAppInUso_cePausaFraUnLottoEL_altro_eInSecondoPianoNo() = runTest {
    // Review generale, OB-3: `Pace.APP_IN_USE` si esegue come il ritmo ridotto; quando l'app torna in secondo piano la pausa sparisce.
    var inUse = true
    val rules = ScriptedStageRules { runOn(pace = if (inUse) Pace.APP_IN_USE else Pace.NORMAL) }
    val work = Work(total = 8, perBatch = 2)

    val batch = work.batch()
    val outcome = staged(rules, config = StageConfig(recheckEvery = Duration.ZERO, reducedPaceDelay = 2.seconds))
      .run({ scope, from -> batch.run(scope, from).also { if (work.batches == 2) inUse = false } })

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(work.batches).isEqualTo(4)
    // Una pausa dopo il primo lotto (app in uso); dal secondo in poi l'app e' in secondo piano: nessuna pausa.
    assertThat(testScheduler.currentTime).isEqualTo(2_000L)
  }

  // --- Tetto mensile (E-B10) ----------------------------------------------------------------------------------------

  @Test
  fun ilTettoMensileFinito_forzaLaRivalutazioneSubito_senzaAspettareIlTimer() = runTest {
    val cap = 2_500_000L
    // Il motore vero direbbe: Run con quanto resta, poi Stop(CapReached) appena il registro supera il tetto.
    val rules =
      ScriptedStageRules { call ->
        val used = ledger.totalBytes
        if (call.running && used >= cap) RuleDecision.Stop(WaitReason.CapReached(CapKind.MOBILE, used, cap))
        else runOn(NetworkClass.MOBILE, remaining = cap - used)
      }
    val work = Work(total = 10)

    // recheckEvery = 1 ora: senza il tetto nessuna rivalutazione avverrebbe mai nella tappa.
    val outcome = staged(rules, config = StageConfig(recheckEvery = 1.hours)).run(work.batch())

    outcome as StageOutcome.Stopped
    assertThat(outcome.reason).isInstanceOf(WaitReason.CapReached::class.java)
    assertThat(work.processed).containsExactly(1, 2, 3).inOrder()
    assertThat(work.budgets.map { it }).containsExactly(2_500_000L, 1_500_000L, 500_000L).inOrder()
    assertThat(store.value).isEqualTo(3)
  }

  @Test
  fun unVerdettoRunConZeroByteNonFaLavorareNessuno() = runTest {
    // Alla partenza resta 1 MB; poi un'implementazione (sbagliata) continua a dire Run con zero byte residui.
    val rules = ScriptedStageRules { call -> if (call.running) runOn(NetworkClass.MOBILE, remaining = 0L) else runOn(NetworkClass.MOBILE, remaining = 1 * MB) }
    val work = Work(total = 10)

    // Nessuna rivalutazione per timer (1 ora): la prima avviene perche' il budget e' finito.
    val outcome = staged(rules, config = StageConfig(recheckEvery = 1.hours)).run(work.batch())

    outcome as StageOutcome.Yielded
    assertThat(outcome.why).isEqualTo(YieldReason.BYTE_BUDGET)
    assertThat(work.processed).containsExactly(1) // il primo ci stava (1 MB), poi il budget e' zero
    assertThat(store.value).isEqualTo(1)
  }

  // --- Tetto del rilevamento fuori casa (D22): 50 MB per giro -------------------------------------------------------

  @Test
  fun fuoriCasa_ilRilevamentoSiFermaAi50Mb_eLaTappaDicePerCheEIlResto_alGiroDopo() = runTest {
    val work = Work(total = 10, bytesPerItem = 20 * MB)
    val rules = always(runOn(NetworkClass.EXTERNAL_WIFI))

    val outcome = staged(rules, WorkRequest.Background(BackgroundActivity.INDEX_SYNC), roundCap = DetectionCap::forNetwork).run(work.batch())

    outcome as StageOutcome.Yielded
    assertThat(outcome.why).isEqualTo(YieldReason.ROUND_BYTE_CAP)
    assertThat(outcome.next).isEqualTo(NextStage.WHEN_ALLOWED)
    // Il budget che il lotto vede scende 50 → 30 → 10 MB; l'ultimo elemento lo supera (60 MB), poi basta.
    assertThat(work.budgets).containsExactly(50 * MB, 30 * MB, 10 * MB).inOrder()
    assertThat(work.processed).containsExactly(1, 2, 3).inOrder()
    assertThat(outcome.stats.roundBytes).isEqualTo(60 * MB)
    assertThat(store.value).isEqualTo(3)
  }

  @Test
  fun aCasa_ilRilevamentoNonHaTettoDiGiro() = runTest {
    val work = Work(total = 10, bytesPerItem = 20 * MB)

    val outcome = staged(always(runOn(NetworkClass.HOME_WIFI)), roundCap = DetectionCap::forNetwork).run(work.batch())

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(work.processed).hasSize(10)
    assertThat(work.budgets.all { it == null }).isTrue()
  }

  @Test
  fun ilGiroPuoEssereDiPiuTappe_iByteGiaLettiContanoNelTetto() = runTest {
    val rules = always(runOn(NetworkClass.MOBILE))

    // Il giro ha gia' letto 40 MB nelle tappe prima: ne restano 10.
    val work = Work(total = 10, bytesPerItem = 20 * MB)
    val outcome = staged(rules, roundCap = DetectionCap::forNetwork).run(work.batch(), roundBytesBefore = 40 * MB)

    outcome as StageOutcome.Yielded
    assertThat(work.processed).containsExactly(1)
    assertThat(work.budgets).containsExactly(10 * MB)
    assertThat(outcome.stats.roundBytes).isEqualTo(60 * MB)

    // Tetto gia' esaurito: nessun lotto, nessuna lettura.
    val exhausted = Work(total = 10, bytesPerItem = 20 * MB)
    val none = staged(rules, roundCap = DetectionCap::forNetwork).run(exhausted.batch(), roundBytesBefore = 50 * MB)
    assertThat((none as StageOutcome.Yielded).why).isEqualTo(YieldReason.ROUND_BYTE_CAP)
    assertThat(exhausted.batches).isEqualTo(0)
  }

  @Test
  fun siParteACasaESiEsce_dopoLUscitaValeIlTetto() = runTest {
    // Prime due rivalutazioni a casa, poi la rete diventa un altro Wi-Fi via VPN.
    val rules = ScriptedStageRules { call -> if (call.index < 2) runOn(NetworkClass.HOME_WIFI) else runOn(NetworkClass.EXTERNAL_WIFI) }
    val work = Work(total = 10, bytesPerItem = 20 * MB)

    val outcome = staged(rules, roundCap = DetectionCap::forNetwork).run(work.batch())

    outcome as StageOutcome.Yielded
    assertThat(outcome.why).isEqualTo(YieldReason.ROUND_BYTE_CAP)
    // Elemento 1 a casa (nessun tetto), poi fuori casa: restano 30, 10 MB.
    assertThat(work.budgets).containsExactly(null, 30 * MB, 10 * MB).inOrder()
    assertThat(work.processed).containsExactly(1, 2, 3).inOrder()
  }

  // --- Il congelamento (D10) ----------------------------------------------------------------------------------------

  @Test
  fun unaTappaCheScriveSulNas_conLaShareCongelata_nonPartePerNulla() = runTest {
    val rig = EngineRig(backgroundScope)
    rig.freeze.frozen.value = FreezeReason.NOT_MEDIA
    val work = Work(total = 3)

    val outcome = rig.staged(writes = true).run(work.batch())

    outcome as StageOutcome.Waiting
    assertThat(outcome.reason).isEqualTo(WaitReason.Frozen(FreezeReason.NOT_MEDIA))
    assertThat(work.batches).isEqualTo(0)
    assertThat(rig.ledger.currentUsage().totalBytes).isEqualTo(0L)
  }

  @Test
  fun unaTappaCheLeggeSoltanto_nonSiFermaPerIlCongelamento() = runTest {
    val rig = EngineRig(backgroundScope)
    rig.freeze.frozen.value = FreezeReason.VANISHED
    val work = Work(total = 3)

    val outcome = rig.staged(activity = BackgroundActivity.INDEX_SYNC, writes = false).run(work.batch())

    assertThat(outcome).isInstanceOf(StageOutcome.Finished::class.java)
  }

  // --- Con il motore vero -------------------------------------------------------------------------------------------

  /** Il motore intero (impostazioni, contatori su DataStore, stato del sistema) con i dispositivi finti. */
  private inner class EngineRig(scope: CoroutineScope) {
    val clock = MutableClock(at(2))
    val device =
      FakeDeviceStateSource(
        DeviceReading(PowerState(pluggedIn = true, levelPercent = 80), NetworkState(NetworkTransport.WIFI, isHome = true)),
      )
    val freeze = FakeNasFreezeSignal()
    private val home = FakeHomeNetworkSignal(initial = true)
    private val provider =
      ComposedSystemStateProvider(
        device,
        home,
        FakeNasReachabilitySignal(initial = true),
        FakeDeviceRolesSignal(DeviceRoles(indexer = true, preparer = true)),
        FakeAndroidLimitsSignal(),
        FakeAppContextSignal(AppContext()),
        clock,
        freeze = freeze,
      )
    private val prefs: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "run.preferences_pb") }
    val ledger = DataStoreTrafficLedger(prefs, clock)
    private val engine = ActivityRulesEngine(DataStoreRuleSettingsStore(prefs), ledger, provider)

    fun staged(activity: BackgroundActivity = BackgroundActivity.PREPARATION, writes: Boolean = false) =
      StagedRun(
        request = WorkRequest.Background(activity, writes),
        rules = EngineStageRules(engine, provider),
        ledger = ledger,
        checkpoints = store,
        timeSource = time,
        config = StageConfig(recheckEvery = Duration.ZERO, reducedPaceDelay = Duration.ZERO),
      )

    fun unplug() {
      device.reading.value = device.reading.value.copy(power = PowerState(pluggedIn = false, levelPercent = 80))
      provider.current() // il provider ricorda da quando e' scollegato
      clock.advanceSeconds(60) // oltre i 15 s di tolleranza ai falsi distacchi
    }

    fun plug() {
      device.reading.value = device.reading.value.copy(power = PowerState(pluggedIn = true, levelPercent = 80))
    }
  }

  @Test
  fun conIlMotoreVero_ilTelefonoVieneScollegatoAMetaTappa_siFermaESiRiprende() = runTest {
    val rig = EngineRig(backgroundScope)
    val work = Work(total = 10)
    // Dopo il terzo elemento qualcuno stacca il cavo: la rivalutazione dell'elemento dopo dice Stop (NeedsPower).
    val unplugAfterThree = StageBatch<Int> { scope, from ->
      var done = from ?: 0
      while (done < 10 && scope.proceed()) {
        scope.record(1 * MB)
        done++
        work.processed += done
        scope.commit(done)
        if (done == 3) rig.unplug()
      }
      BatchResult(hasMore = done < 10)
    }

    val stopped = rig.staged(writes = true).run(unplugAfterThree)

    stopped as StageOutcome.Stopped
    assertThat(stopped.reason).isEqualTo(WaitReason.NeedsPower)
    assertThat(store.value).isEqualTo(3)
    assertThat(rig.ledger.currentUsage().bytesFor(TrafficSource.PREPARATION, TrafficNetwork.HOME_WIFI)).isEqualTo(3 * MB)

    // Ricollegato (di notte): riprende dal quarto e finisce.
    rig.plug()
    val resumed = rig.staged(writes = true).run(work.batch())
    resumed as StageOutcome.Finished
    assertThat(work.processed).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).inOrder() // riparte da `from` = 3
    assertThat(store.value).isNull()
  }

  @Test
  fun conIlMotoreVero_laShareSiCongelaAMetaTappa_siFermaConFrozen_epoiRipartiDopoIlGesto() = runTest {
    val rig = EngineRig(backgroundScope)
    val work = Work(total = 8)
    val freezeAfterTwo = StageBatch<Int> { scope, from ->
      var done = from ?: 0
      while (done < 8 && scope.proceed()) {
        scope.record(1 * MB)
        done++
        scope.commit(done)
        if (done == 2) rig.freeze.frozen.value = FreezeReason.CHANGED // la scansione ha visto troppi file cambiati
      }
      BatchResult(hasMore = done < 8)
    }

    val stopped = rig.staged(writes = true).run(freezeAfterTwo)

    stopped as StageOutcome.Stopped
    assertThat(stopped.reason).isEqualTo(WaitReason.Frozen(FreezeReason.CHANGED))
    assertThat(store.value).isEqualTo(2)

    // Finche' e' congelata non riparte.
    val waiting = rig.staged(writes = true).run(work.batch())
    assertThat((waiting as StageOutcome.Waiting).reason).isEqualTo(WaitReason.Frozen(FreezeReason.CHANGED))
    assertThat(work.batches).isEqualTo(0)

    // "Ho controllato, continua": si riparte dal punto salvato.
    rig.freeze.frozen.value = null
    val resumed = rig.staged(writes = true).run(work.batch())
    assertThat(resumed).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(work.processed).containsExactly(3, 4, 5, 6, 7, 8).inOrder()
  }

  @Test
  fun unaFasciaOrariaCheSiChiude_fermaLaTappa_conLoStessoPercorso() = runTest {
    // Prova di forma: il motore vero con la fascia notte che finisce fra due elementi.
    val rig = EngineRig(backgroundScope)
    val closing = StageBatch<Int> { scope, from ->
      var done = from ?: 0
      while (done < 5 && scope.proceed()) {
        scope.record(1 * MB)
        done++
        scope.commit(done)
        if (done == 2) rig.clock.set(at(7, 30)) // la notte (23-07) e' finita
      }
      BatchResult(hasMore = done < 5)
    }

    val outcome = rig.staged().run(closing)

    outcome as StageOutcome.Stopped
    assertThat(outcome.reason).isInstanceOf(WaitReason.OutsideWindow::class.java)
    assertThat((outcome.reason as WaitReason.OutsideWindow).window)
      .isEqualTo(TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)))
    assertThat(store.value).isEqualTo(2)
  }

  @Test
  fun leDurateNegativeONulleNonSiAccettano() {
    val zero = runCatching { StageConfig(stageDuration = Duration.ZERO) }.exceptionOrNull()
    val negative = runCatching { StageConfig(recheckEvery = (-1).milliseconds) }.exceptionOrNull()

    assertThat(zero).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(negative).isInstanceOf(IllegalArgumentException::class.java)
  }
}
