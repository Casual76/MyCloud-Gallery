package dev.pampa.mycloudgallery.core.rules.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.ActivityRule
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MonthlyCaps
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuleSettingsTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun TestScope.dataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "rules.preferences_pb") }

  // --- toRuleSet ----------------------------------------------------------------------------------------

  @Test
  fun leImpostazioniDiDefaultSonoIlPresetNotte() {
    val settings = RuleSettings()

    assertThat(settings.preset).isEqualTo(Preset.NIGHT)
    assertThat(settings.toRuleSet()).isEqualTo(PresetCatalog.ruleSet(Preset.NIGHT, NightWindows.DEFAULT))
    assertThat(settings.batteryThresholdPercent).isEqualTo(20)
    assertThat(settings.allowRoaming).isFalse()
  }

  @Test
  fun lafasciaNotteScelta_arrivaNelleRegole() {
    val window = TimeWindow(LocalTime.of(0, 30), LocalTime.of(5, 30))

    val rules = RuleSettings(nightWindow = window).toRuleSet()

    for (activity in BackgroundActivity.entries) assertThat(rules.ruleFor(activity).window).isEqualTo(window)
  }

  @Test
  fun sogliaTettiERisparmio_valgonoConQualunquePreset() {
    for (preset in Preset.entries) {
      val rules =
        RuleSettings(
            preset = preset,
            batteryThresholdPercent = 35,
            capsOverride = MonthlyCaps(mobileBytes = 3 * GB, wifiBytes = 200 * GB),
            pauseOnPowerSaver = false,
          )
          .toRuleSet()
      assertThat(rules.batteryThresholdPercent).isEqualTo(35)
      assertThat(rules.caps).isEqualTo(MonthlyCaps(3 * GB, 200 * GB))
      assertThat(rules.pauseOnPowerSaver).isFalse()
    }
  }

  @Test
  fun senzaTettiScelti_valgonoQuelliDelPreset() {
    assertThat(RuleSettings(preset = Preset.MAXIMUM).toRuleSet().caps.mobileBytes)
      .isEqualTo(PresetCatalog.MAXIMUM_MOBILE_CAP_BYTES)
    assertThat(RuleSettings(preset = Preset.NIGHT).toRuleSet().caps.mobileBytes)
      .isEqualTo(PresetCatalog.DEFAULT_MOBILE_CAP_BYTES)
  }

  @Test
  fun attivitaSpente_valgonoConQualunquePreset() {
    val rules =
      RuleSettings(preset = Preset.BALANCED, disabledActivities = setOf(BackgroundActivity.HQ_PREVIEWS)).toRuleSet()

    assertThat(rules.ruleFor(BackgroundActivity.HQ_PREVIEWS).enabled).isFalse()
    assertThat(rules.ruleFor(BackgroundActivity.PHOTO_BACKUP).enabled).isTrue()
  }

  @Test
  fun interruttoreRoaming_aggiungeIlRoamingATutteLeAttivita() {
    val off = RuleSettings(preset = Preset.MAXIMUM).toRuleSet()
    val on = RuleSettings(preset = Preset.MAXIMUM, allowRoaming = true).toRuleSet()

    for (activity in BackgroundActivity.entries) {
      assertThat(off.ruleFor(activity).networks).doesNotContain(AllowedNetwork.ROAMING)
      assertThat(on.ruleFor(activity).networks).contains(AllowedNetwork.ROAMING)
    }
  }

  @Test
  fun personalizzato_irolisonoSeparatiPerAttivita_iVideoHannoRegoleProprie() {
    val videoNightOnly =
      ActivityRule(
        window = TimeWindow(LocalTime.of(1, 0), LocalTime.of(6, 0)),
        power = PowerPolicy.PLUGGED_ONLY,
        networks = setOf(AllowedNetwork.HOME_WIFI),
      )
    val photoAnytime =
      ActivityRule(
        window = null,
        power = PowerPolicy.BATTERY_ALLOWED,
        networks = setOf(AllowedNetwork.HOME_WIFI, AllowedNetwork.ANY_WIFI_VPN, AllowedNetwork.MOBILE_DATA),
      )

    val rules =
      RuleSettings(
          preset = Preset.CUSTOM,
          customRules = mapOf(BackgroundActivity.VIDEO_BACKUP to videoNightOnly, BackgroundActivity.PHOTO_BACKUP to photoAnytime),
          customHeavyOnlyAtHome = false,
        )
        .toRuleSet()

    assertThat(rules.ruleFor(BackgroundActivity.VIDEO_BACKUP)).isEqualTo(videoNightOnly)
    assertThat(rules.ruleFor(BackgroundActivity.PHOTO_BACKUP)).isEqualTo(photoAnytime)
    // Le attivita' non personalizzate restano quelle del preset Notte.
    assertThat(rules.ruleFor(BackgroundActivity.AI_COMPUTE))
      .isEqualTo(PresetCatalog.ruleSet(Preset.NIGHT).ruleFor(BackgroundActivity.AI_COMPUTE))
    assertThat(rules.heavyOnlyAtHome).isFalse()
  }

  @Test
  fun valoriNonValidi_sonoRifiutati() {
    assertThrows(IllegalArgumentException::class.java) { RuleSettings(batteryThresholdPercent = 120) }
    assertThrows(IllegalArgumentException::class.java) { RuleSettings(billingCycleStartDay = 31) }
    assertThrows(IllegalArgumentException::class.java) { MonthlyCaps(mobileBytes = -1, wifiBytes = null) }
  }

  // --- Persistenza --------------------------------------------------------------------------------------

  @Test
  fun senzaNullaSalvato_sonoLeImpostazioniDiDefault() = runTest {
    assertThat(DataStoreRuleSettingsStore(dataStore()).current()).isEqualTo(RuleSettings())
  }

  @Test
  fun salvaERilegge_tuttiICampi() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())
    val custom =
      mapOf(
        BackgroundActivity.VIDEO_BACKUP to
          ActivityRule(TimeWindow(LocalTime.of(22, 30), LocalTime.of(6, 15)), PowerPolicy.PLUGGED_ONLY, setOf(AllowedNetwork.HOME_WIFI)),
        BackgroundActivity.PHOTO_BACKUP to
          ActivityRule(null, PowerPolicy.BATTERY_ALLOWED, setOf(AllowedNetwork.HOME_WIFI, AllowedNetwork.MOBILE_DATA), enabled = false),
      )
    val settings =
      RuleSettings(
        preset = Preset.CUSTOM,
        nightWindow = TimeWindow(LocalTime.of(0, 15), LocalTime.of(5, 45)),
        batteryThresholdPercent = 33,
        capsOverride = MonthlyCaps(mobileBytes = 2 * GB, wifiBytes = null),
        disabledActivities = setOf(BackgroundActivity.HQ_PREVIEWS, BackgroundActivity.PREPARATION),
        allowRoaming = true,
        pauseOnPowerSaver = false,
        customRules = custom,
        customHeavyOnlyAtHome = false,
        billingCycleStartDay = 15,
        protectNewPhotos = false,
      )

    store.save(settings)

    assertThat(store.current()).isEqualTo(settings)
  }

  @Test
  fun ognunDeiPresetSiSalvaESiRilegge() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())

    for (preset in Preset.entries) {
      store.save(RuleSettings(preset = preset))
      assertThat(store.current().preset).isEqualTo(preset)
    }
  }

  @Test
  fun capsOverrideNullo_siRicordaComeNullo() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())
    store.save(RuleSettings(capsOverride = MonthlyCaps(1 * GB, 5 * GB)))
    store.save(RuleSettings(capsOverride = null))

    assertThat(store.current().capsOverride).isNull()
  }

  @Test
  fun update_modificaSoloQuelloCheServe() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())
    store.save(RuleSettings(preset = Preset.BALANCED, batteryThresholdPercent = 30))

    store.update { it.copy(allowRoaming = true) }

    val now = store.current()
    assertThat(now.preset).isEqualTo(Preset.BALANCED)
    assertThat(now.batteryThresholdPercent).isEqualTo(30)
    assertThat(now.allowRoaming).isTrue()
  }

  @Test
  fun valoriCorrottiNelloStore_ripiegano_sulDefaultCampoPerCampo() = runTest {
    val ds = dataStore()
    ds.edit {
      it[stringPreferencesKey("rule.preset")] = "ROSA_SHOCKING" // un preset che non esiste piu'
      it[intPreferencesKey("rule.night.start")] = 99999
      it[intPreferencesKey("rule.night.end")] = 60
      it[intPreferencesKey("rule.battery.threshold")] = 400
      it[intPreferencesKey("rule.cycle.startday")] = 0
      it[stringPreferencesKey("rule.disabled")] = "NON_ESISTE,HQ_PREVIEWS"
      it[stringPreferencesKey("rule.custom.VIDEO_BACKUP")] = "spazzatura"
      it[stringPreferencesKey("rule.custom.PHOTO_BACKUP")] = "enabled=1|power=BOH|nets=HOME_WIFI|win=-"
    }

    val read = DataStoreRuleSettingsStore(ds).current()

    assertThat(read.preset).isEqualTo(Preset.NIGHT)
    assertThat(read.nightWindow).isEqualTo(NightWindows.DEFAULT)
    assertThat(read.batteryThresholdPercent).isEqualTo(20)
    assertThat(read.billingCycleStartDay).isEqualTo(1)
    assertThat(read.disabledActivities).containsExactly(BackgroundActivity.HQ_PREVIEWS)
    assertThat(read.customRules).isEmpty()
  }

  @Test
  fun proteggiSubitoLeFotoNuove_D34_eAccesoDiDefault_esiRicordaSpento() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())

    assertThat(RuleSettings().protectNewPhotos).isTrue()
    assertThat(store.current().protectNewPhotos).isTrue()

    store.update { it.copy(protectNewPhotos = false) }
    assertThat(store.current().protectNewPhotos).isFalse()

    store.update { it.copy(protectNewPhotos = true, preset = Preset.BALANCED) }
    assertThat(store.current().protectNewPhotos).isTrue()
    assertThat(store.current().preset).isEqualTo(Preset.BALANCED)
  }

  @Test
  fun osservare_riemetteAOgniModifica() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())

    store.observe().test {
      assertThat(awaitItem().preset).isEqualTo(Preset.NIGHT)
      store.update { it.copy(preset = Preset.MAXIMUM) }
      assertThat(awaitItem().preset).isEqualTo(Preset.MAXIMUM)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
