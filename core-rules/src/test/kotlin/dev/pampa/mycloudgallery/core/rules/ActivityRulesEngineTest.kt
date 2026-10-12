package dev.pampa.mycloudgallery.core.rules

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Il motore intero: impostazioni + contatori + stato del sistema, tutti veri tranne il telefono. */
class ActivityRulesEngineTest {

  @get:Rule val tmp = TemporaryFolder()

  private val clock = MutableClock(at(2))
  private val device =
    FakeDeviceStateSource(DeviceReading(PowerState(pluggedIn = true, levelPercent = 80), NetworkState(NetworkTransport.WIFI)))
  private val home = FakeHomeNetworkSignal(initial = true)
  private val nas = FakeNasReachabilitySignal(initial = true)
  private val roles = FakeDeviceRolesSignal(DeviceRoles(indexer = true, preparer = true))
  private val limits = FakeAndroidLimitsSignal()
  private val app = FakeAppContextSignal()

  private class Rig(
    val engine: ActivityRulesEngine,
    val settings: DataStoreRuleSettingsStore,
    val ledger: DataStoreTrafficLedger,
  )

  private fun TestScope.rig(): Rig {
    val prefs: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "engine.preferences_pb") }
    val settings = DataStoreRuleSettingsStore(prefs)
    val ledger = DataStoreTrafficLedger(prefs, clock)
    val provider = ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock)
    return Rig(ActivityRulesEngine(settings, ledger, provider), settings, ledger)
  }

  private fun power(plugged: Boolean = true, level: Int = 80) = PowerState(pluggedIn = plugged, levelPercent = level)

  private fun maximumWithCap(capBytes: Long) =
    RuleSettings(preset = Preset.MAXIMUM, capsOverride = MonthlyCaps(mobileBytes = capBytes, wifiBytes = null))

  @Test
  fun predefinito_notteInCaricaInWifiDiCasa_tuttoParte() = runTest {
    val rig = rig()

    val all = rig.engine.evaluateAll()

    assertThat(all.keys).containsExactlyElementsIn(BackgroundActivity.entries)
    assertThat(all.values.all { it is RuleDecision.Run }).isTrue()
  }

  @Test
  fun predefinito_dopoLeSette_ilGiornoNonParteNulla_eDiceQuandoRiprende() = runTest {
    val rig = rig()
    clock.set(at(7, 0, 0))

    val decision = rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP))

    decision as RuleDecision.Wait
    assertThat(decision.reason).isEqualTo(WaitReason.OutsideWindow(NightWindows.DEFAULT, at(23)))
  }

  @Test
  fun cambiarePreset_cambiaIVerdettiSubito() = runTest {
    val rig = rig()
    clock.set(at(12))
    device.reading.value = device.reading.value.copy(power = power(plugged = false))
    assertThat(rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)).isRun).isFalse()

    rig.settings.update { it.copy(preset = Preset.BALANCED) }

    assertThat(rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)).isRun).isTrue()
    // Le pesanti aspettano la corrente anche con "Bilanciato".
    assertThat(rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.VIDEO_BACKUP)).blockingReason)
      .isEqualTo(WaitReason.NeedsPower)
  }

  @Test
  fun tettoDatiMobili_siRaggiungeRegistrandoIlTraffico_eSiAzzeraAlCambioMese() = runTest {
    val rig = rig()
    rig.settings.save(maximumWithCap(1 * GB))
    device.reading.value = DeviceReading(power(plugged = false), NetworkState(NetworkTransport.CELLULAR, viaVpn = true, metered = true))
    home.isAtHome.value = false
    clock.set(at(12, day = 31))
    val request = WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)

    // Sotto il tetto: parte e dice quanto resta.
    rig.ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 400 * MB)
    assertThat(rig.engine.evaluate(request))
      .isEqualTo(RuleDecision.Run(RunScope.FULL, Pace.NORMAL, NetworkClass.MOBILE, remainingBytes = 600 * MB))

    // Un lavoro lo supera: il valutatore dice "fermati" con il consumo esatto.
    rig.ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 800 * MB)
    val stop = rig.engine.evaluate(request, running = true)
    stop as RuleDecision.Stop
    assertThat(stop.reason).isEqualTo(WaitReason.CapReached(CapKind.MOBILE, 1_200 * MB, 1 * GB))
    assertThat(stop.reason.message).isEqualTo("Tetto dati mobili raggiunto (1,2/1 GB)")

    // Scocca il primo del mese: i contatori ripartono da zero e il lavoro puo' ripartire.
    clock.set(LocalDateTime.of(2026, 11, 1, 0, 0, 0))
    assertThat(rig.engine.evaluate(request))
      .isEqualTo(RuleDecision.Run(RunScope.FULL, Pace.NORMAL, NetworkClass.MOBILE, remainingBytes = 1 * GB))
  }

  @Test
  fun faiOra_suiDatiMobili_chiedeConferma_ePoiParte() = runTest {
    val rig = rig()
    device.reading.value = DeviceReading(power(plugged = false), NetworkState(NetworkTransport.CELLULAR, viaVpn = true, metered = true))
    home.isAtHome.value = false
    clock.set(at(12))
    app.context.value = AppContext(inForeground = true)

    val ask = rig.engine.evaluate(WorkRequest.UserInitiated(BackgroundActivity.VIDEO_BACKUP, estimatedBytes = 200 * MB))
    assertThat(ask).isInstanceOf(RuleDecision.NeedsConfirmation::class.java)

    val go = rig.engine.evaluate(WorkRequest.UserInitiated(BackgroundActivity.VIDEO_BACKUP, 200 * MB, confirmed = true))
    assertThat(go.isRun).isTrue()
  }

  @Test
  fun nasSpento_ilCalcoloAiLavoraSulBufferELeAltreAspettano() = runTest {
    val rig = rig()
    nas.isReachable.value = false

    val all = rig.engine.evaluateAll()

    assertThat(all.getValue(BackgroundActivity.AI_COMPUTE))
      .isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.HOME_WIFI))
    assertThat((all.getValue(BackgroundActivity.PHOTO_BACKUP) as RuleDecision.Wait).reason).isEqualTo(WaitReason.NasUnreachable)
  }

  @Test
  fun profiloNonAttivo_E_P19() = runTest {
    val rig = rig()
    app.context.value = AppContext(profileActive = false)

    assertThat(rig.engine.evaluateAll().values.all { it.blockingReason == WaitReason.ProfileInactive }).isTrue()
  }

  @Test
  fun osservare_riemetteQuandoCambiaIlSistema() = runTest {
    val rig = rig()

    rig.engine.observeAll(tick = Duration.ofHours(1)).test {
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).isRun).isTrue()

      nas.isReachable.value = false
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).blockingReason).isEqualTo(WaitReason.NasUnreachable)

      nas.isReachable.value = true
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).isRun).isTrue()

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun osservare_riemetteQuandoCambiaLeImpostazioniOIConsumi() = runTest {
    val rig = rig()
    rig.settings.save(maximumWithCap(1 * GB))
    device.reading.value = DeviceReading(power(plugged = false), NetworkState(NetworkTransport.CELLULAR, viaVpn = true, metered = true))
    home.isAtHome.value = false
    clock.set(at(12))

    rig.engine.observeAll(tick = Duration.ofHours(1)).test {
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).isRun).isTrue()

      rig.ledger.record(TrafficSource.VIDEO_BACKUP, TrafficNetwork.MOBILE, 2 * GB)
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).blockingReason)
        .isInstanceOf(WaitReason.CapReached::class.java)

      rig.settings.update { it.copy(capsOverride = MonthlyCaps(mobileBytes = 5 * GB, wifiBytes = null)) }
      assertThat(awaitItem().getValue(BackgroundActivity.PHOTO_BACKUP).isRun).isTrue()

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun osservare_ilPassareDelTempoRivalutaConIlTimer() = runTest {
    val rig = rig()
    clock.set(at(6, 59, 0))

    rig.engine.observeAll(tick = Duration.ofMinutes(1)).test {
      assertThat(awaitItem().getValue(BackgroundActivity.AI_COMPUTE).isRun).isTrue()

      // Nessun evento di sistema: e' solo scoccata la fine della fascia.
      clock.set(at(7, 0, 1))
      val after = awaitItem()
      assertThat(after.getValue(BackgroundActivity.AI_COMPUTE).blockingReason).isInstanceOf(WaitReason.OutsideWindow::class.java)

      cancelAndIgnoreRemainingEvents()
    }
  }
}
