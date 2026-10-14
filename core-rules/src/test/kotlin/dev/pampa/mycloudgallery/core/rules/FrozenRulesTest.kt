package dev.pampa.mycloudgallery.core.rules

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PREPARATION
import dev.pampa.mycloudgallery.core.rules.run.FakeNasFreezeSignal
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `WaitReason.Frozen` (D10, risposta Q6): una share congelata per cambiamenti di massa ferma **i lavori che scrivono sul
 * NAS** (`WorkRequest.writesToNas`) e solo quelli. La tabella dei preset sta in `PresetMatrixTest`.
 */
class FrozenRulesTest {

  @get:Rule val tmp = TemporaryFolder()

  private val evaluator = RuleEvaluator()
  private val night = ruleSetOf(Preset.NIGHT)
  private val frozen = state(frozen = FreezeReason.VANISHED)
  private val reason = WaitReason.Frozen(FreezeReason.VANISHED)

  @Test
  fun ognunaDelleTreRagioniHaUnTestoChiaro_ediceCosaFare() {
    val texts = FreezeReason.entries.map { WaitReason.Frozen(it).message }

    assertThat(texts.toSet()).hasSize(FreezeReason.entries.size)
    texts.forEach { assertThat(it).contains("Ho controllato, continua") }
    assertThat(WaitReason.Frozen(FreezeReason.NOT_MEDIA).message).contains("non sono più foto o video")
  }

  @Test
  fun eUnMotivoRelativoAlNas_perCheIlCalcoloAiPossaLavorareSulBuffer() {
    assertThat(reason.isNetworkRelated).isTrue()
  }

  @Test
  fun senzaCongelamento_scrivereONonScrivereEUguale() {
    val normal = state()
    for (activity in BackgroundActivity.entries) {
      assertThat(evaluator.background(activity, night, normal, writesToNas = true))
        .isEqualTo(evaluator.background(activity, night, normal, writesToNas = false))
    }
  }

  @Test
  fun chiScrive_siFermaConFrozen_chiLegge_no() {
    assertThat(evaluator.background(PHOTO_BACKUP, night, frozen, writesToNas = true)).isEqualTo(RuleDecision.Wait(reason))
    assertThat(evaluator.background(PHOTO_BACKUP, night, frozen, writesToNas = false).isRun).isTrue()
  }

  @Test
  fun unLavoroInCorso_quandoLaShareSiCongela_riceveStop() {
    val decision = evaluator.background(PREPARATION, night, frozen, running = true, writesToNas = true)

    assertThat(decision).isEqualTo(RuleDecision.Stop(reason))
    // La scansione che legge soltanto continua (un Run resta Run anche con `running = true`).
    assertThat(evaluator.background(INDEX_SYNC, night, frozen, running = true).isRun).isTrue()
  }

  @Test
  fun faiOra_chiScriveNonParte_comeUnaSicurezza_chiLegge_parte() {
    val write = evaluator.evaluate(WorkRequest.UserInitiated(PHOTO_BACKUP, writesToNas = true), night, frozen, usage())
    val read = evaluator.evaluate(WorkRequest.UserInitiated(PHOTO_BACKUP), night, frozen, usage())

    assertThat(write).isEqualTo(RuleDecision.Wait(reason))
    assertThat(read.isRun).isTrue()
  }

  @Test
  fun faiOraDelCalcoloAi_chiScrive_restaSulBuffer() {
    val decision = evaluator.evaluate(WorkRequest.UserInitiated(AI_COMPUTE, writesToNas = true), night, frozen, usage())

    decision as RuleDecision.Run
    assertThat(decision.scope).isEqualTo(RunScope.LOCAL_BUFFER_ONLY)
  }

  @Test
  fun conLAppAperta_laSyncDellIndiceChePubblicaSiFerma_quellaCheLegge_no() {
    val open = state(foreground = true, frozen = FreezeReason.CHANGED)

    val write = evaluator.evaluate(WorkRequest.Background(INDEX_SYNC, writesToNas = true), night, open, usage())
    val read = evaluator.evaluate(WorkRequest.Background(INDEX_SYNC), night, open, usage())

    assertThat(write).isEqualTo(RuleDecision.Wait(WaitReason.Frozen(FreezeReason.CHANGED)))
    assertThat(read.isRun).isTrue()
  }

  @Test
  fun ilContenutoVisibile_nonDipendeDalCongelamento() {
    val open = state(foreground = true, frozen = FreezeReason.NOT_MEDIA)

    assertThat(evaluator.evaluate(WorkRequest.VisibleContent, night, open, usage()).isRun).isTrue()
  }

  @Test
  fun ilCongelamentoElencaGliAltriOstacoli_perLaDiagnostica() {
    val day = state(now = at(12), plugged = false, frozen = FreezeReason.VANISHED)

    val decision = evaluator.background(PREPARATION, night, day, writesToNas = true) as RuleDecision.Wait

    assertThat(decision.reason).isEqualTo(reason)
    assertThat(decision.alsoBlockedBy.map { it::class })
      .containsAtLeast(WaitReason.OutsideWindow::class, WaitReason.NeedsPower::class)
  }

  // --- Dal segnale al verdetto -------------------------------------------------------------------------------------

  private val clock = MutableClock(at(2))
  private val device =
    FakeDeviceStateSource(DeviceReading(PowerState(pluggedIn = true, levelPercent = 80), NetworkState(NetworkTransport.WIFI)))
  private val home = FakeHomeNetworkSignal(initial = true)
  private val nas = FakeNasReachabilitySignal(initial = true)
  private val roles = FakeDeviceRolesSignal(DeviceRoles(indexer = true, preparer = true))
  private val limits = FakeAndroidLimitsSignal()
  private val app = FakeAppContextSignal()
  private val freeze = FakeNasFreezeSignal()
  private val provider = ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock, freeze = freeze)

  @Test
  fun ilProviderLeggeIlSegnale_eLoRiemetteQuandoCambia() = runTest {
    assertThat(provider.current().nasFrozen).isNull()

    provider.observe().test {
      assertThat(awaitItem().nasFrozen).isNull()

      freeze.frozen.value = FreezeReason.CHANGED
      assertThat(awaitItem().nasFrozen).isEqualTo(FreezeReason.CHANGED)

      freeze.frozen.value = null
      assertThat(awaitItem().nasFrozen).isNull()

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun senzaIlSegnale_nonSiCongelaMai() {
    val plain = ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock)

    assertThat(plain.current().nasFrozen).isNull()
  }

  @Test
  fun ilMotoreVedeIlCongelamentoEIlGestoDellUtenteLoToglie() = runTest {
    val prefs: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "frozen.preferences_pb") }
    val engine = ActivityRulesEngine(DataStoreRuleSettingsStore(prefs), DataStoreTrafficLedger(prefs, clock), provider)
    val publish = WorkRequest.Background(PREPARATION, writesToNas = true)

    assertThat(engine.evaluate(publish).isRun).isTrue()

    freeze.frozen.value = FreezeReason.VANISHED
    assertThat(engine.evaluate(publish)).isEqualTo(RuleDecision.Wait(reason))
    // A meta' lavoro: "fermati".
    assertThat(engine.evaluate(publish, running = true)).isEqualTo(RuleDecision.Stop(reason))
    // La lettura (la scansione che si accorge che e' tornato tutto normale) continua.
    assertThat(engine.evaluate(WorkRequest.Background(INDEX_SYNC)).isRun).isTrue()

    freeze.frozen.value = null
    assertThat(engine.evaluate(publish).isRun).isTrue()
  }
}
