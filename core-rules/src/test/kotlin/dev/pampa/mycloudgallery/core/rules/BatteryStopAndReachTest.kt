package dev.pampa.mycloudgallery.core.rules

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.batteryReach
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pacchetto B, giro 2. **BU-7**: il numero che le pagine dicono accanto a «Batteria sotto il…» e' quello del **verdetto** che ha fermato il lavoro (`ActivityRulesEngine.batteryStopThreshold`), non
 * l'impostazione di adesso. **BU-3**: chi puo' lavorare a batteria con le regole di adesso (`batteryReach`): con «Notte» l'analisi no, il backup si' (solo «Proteggi subito le foto nuove»).
 */
class BatteryStopAndReachTest {

  @get:Rule val tmp = TemporaryFolder()

  private val clock = MutableClock(at(12))
  private val device = FakeDeviceStateSource(DeviceReading(PowerState(pluggedIn = false, levelPercent = 55), NetworkState(NetworkTransport.WIFI)))
  private val home = FakeHomeNetworkSignal(initial = true)
  private val nas = FakeNasReachabilitySignal(initial = true)
  private val roles = FakeDeviceRolesSignal(DeviceRoles(indexer = true, preparer = true))
  private val limits = FakeAndroidLimitsSignal()
  private val app = FakeAppContextSignal()

  private class Rig(val engine: ActivityRulesEngine, val settings: DataStoreRuleSettingsStore)

  private fun TestScope.rig(): Rig {
    val prefs: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "engine.preferences_pb") }
    val settings = DataStoreRuleSettingsStore(prefs)
    val ledger = DataStoreTrafficLedger(prefs, clock)
    return Rig(ActivityRulesEngine(settings, ledger, ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock)), settings)
  }

  // --- BU-7 ---------------------------------------------------------------------------------------------------

  @Test
  fun bu7_finoAChe_nessunLavoroEFermatoPerLaBatteria_nonCEUnNumero() = runTest {
    val rig = rig()
    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 40))

    rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP))

    assertThat(rig.engine.batteryStopThreshold.value).isNull()
  }

  @Test
  fun bu7_ilNumeroEQuelloDelVerdettoCheHaFermato_nonL_impostazioneDiAdesso() = runTest {
    val rig = rig()
    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60))
    val stopped = rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP))
    assertThat(stopped).isEqualTo(RuleDecision.Wait(WaitReason.BatteryLow(55, 60)))
    assertThat(rig.engine.batteryStopThreshold.value).isEqualTo(60)

    // L'utente abbassa il livello a 40: finche' nessun lavoro e' rivalutato, il fermo e' ancora quello del 60.
    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 40))
    assertThat(rig.engine.batteryStopThreshold.value).isEqualTo(60)

    // La rivalutazione: ora il lavoro parte (55 >= 40) e non c'e' un verdetto di batteria nuovo; il numero resta quello dell'ultimo fermo, che nessuna pagina mostra piu' (il motivo e' sparito).
    assertThat(rig.engine.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)).isRun).isTrue()
    assertThat(rig.engine.batteryStopThreshold.value).isEqualTo(60)
  }

  @Test
  fun bu7_unNuovoFermoPerLaBatteria_portaIlNumeroNuovo_ancheDaEvaluateAll() = runTest {
    val rig = rig()
    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60))
    rig.engine.evaluateAll()
    assertThat(rig.engine.batteryStopThreshold.value).isEqualTo(60)

    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 80))
    rig.engine.evaluateAll()

    assertThat(rig.engine.batteryStopThreshold.value).isEqualTo(80)
  }

  @Test
  fun bu7_unRichiestaDellUtente_nonDaNumeri_perche_NonSiFermaMaiPerLaBatteria() = runTest {
    val rig = rig()
    rig.settings.save(RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 80))

    rig.engine.evaluate(WorkRequest.UserInitiated(BackgroundActivity.PHOTO_BACKUP, confirmed = true))

    assertThat(rig.engine.batteryStopThreshold.value).isNull()
  }

  // --- BU-3 ---------------------------------------------------------------------------------------------------

  @Test
  fun bu3_conNotte_l_analisiLavoraSoloInCarica_ilBackupSoloPerProteggiSubito() {
    val reach = RuleSettings().batteryReach()

    assertThat(reach.analysisOnBattery).isFalse()
    assertThat(reach.backupOnBattery).isTrue() // «Proteggi subito le foto nuove», di serie acceso
    assertThat(reach.backupNeedsPower).isTrue() // il backup normale e' solo in carica
  }

  @Test
  fun bu3_conNotteEProteggiSubitoSpento_nessunoDeiDueLavoraABatteria() {
    val reach = RuleSettings(protectNewPhotos = false).batteryReach()

    assertThat(reach.backupOnBattery).isFalse()
    assertThat(reach.analysisOnBattery).isFalse()
    assertThat(reach.backupNeedsPower).isTrue()
  }

  @Test
  fun bu3_conWifiSempre_ETuttoABatteria() {
    val reach = RuleSettings(preset = Preset.WIFI_ALWAYS).batteryReach()

    assertThat(reach.backupOnBattery).isTrue()
    assertThat(reach.analysisOnBattery).isTrue()
    assertThat(reach.backupNeedsPower).isFalse()
  }

  @Test
  fun bu3_conBilanciato_ilBackupDelleFotoABatteria_l_analisiNo() {
    val reach = RuleSettings(preset = Preset.BALANCED).batteryReach()

    assertThat(reach.backupOnBattery).isTrue()
    assertThat(reach.backupNeedsPower).isFalse()
    assertThat(reach.analysisOnBattery).isFalse() // i lavori pesanti aspettano la corrente
  }

  @Test
  fun bu3_unaAttivitaSpenta_nonLavoraABatteria() {
    val reach = RuleSettings(preset = Preset.WIFI_ALWAYS, disabledActivities = setOf(BackgroundActivity.AI_COMPUTE), protectNewPhotos = false).batteryReach()

    assertThat(reach.analysisOnBattery).isFalse()
    assertThat(reach.backupOnBattery).isTrue()
  }
}
