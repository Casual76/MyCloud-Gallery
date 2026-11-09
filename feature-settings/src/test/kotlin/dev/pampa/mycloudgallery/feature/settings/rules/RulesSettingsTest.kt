package dev.pampa.mycloudgallery.feature.settings.rules

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.MonthlyCaps
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.feature.settings.support.InMemoryRuleStore
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * "Quando lavora l'app" (verifica funzionale, RE-8): preset, tetto dei dati mobili e roaming si cambiano dopo l'onboarding, con lo stesso
 * effetto delle scelte dell'onboarding (docs/regole-attivita.md §3, §5).
 */
class RulesSettingsTest {

  @get:Rule
  val main = MainDispatcherRule()

  @Test
  fun ilPresetDelleImpostazioniHaLoStessoEffettoDelPassoDellOnboarding() {
    // Il passo 8 scrive preset e fascia notte ricavata dall'orario del NAS (sempre acceso: 23-07), niente altro dei tetti.
    for (preset in PresetChoices) {
      val after = RulesChoices.withPreset(RuleSettings(), preset)
      assertThat(after.preset).isEqualTo(preset)
      assertThat(after.nightWindow).isEqualTo(NightWindows.DEFAULT)
      assertThat(after.capsOverride).isNull()
      assertThat(after.allowRoaming).isFalse()
    }
    // I quattro preset sono quelli dell'onboarding: Personalizzato non ha una pagina.
    assertThat(PresetChoices).containsExactly(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM).inOrder()
  }

  @Test
  fun cambiarePresetCambiaIlVerdettoDelMotore() {
    val night = RulesChoices.withPreset(RuleSettings(), Preset.NIGHT).toRuleSet()
    val maximum = RulesChoices.withPreset(RuleSettings(), Preset.MAXIMUM).toRuleSet()

    // Notte: finestra e solo Wi-Fi. Massimo: sempre, anche con i dati mobili.
    assertThat(night.rules.getValue(BackgroundActivity.PHOTO_BACKUP).window).isNotNull()
    assertThat(maximum.rules.getValue(BackgroundActivity.PHOTO_BACKUP).window).isNull()
    assertThat(AllowedNetwork.MOBILE_DATA in night.rules.getValue(BackgroundActivity.PHOTO_BACKUP).networks).isFalse()
    assertThat(AllowedNetwork.MOBILE_DATA in maximum.rules.getValue(BackgroundActivity.PHOTO_BACKUP).networks).isTrue()
  }

  @Test
  fun ilTettoDeiDatiMobili_comeIlPreset_unLimite_nessunTetto() {
    val base = RulesChoices.withPreset(RuleSettings(), Preset.MAXIMUM)
    assertThat(RulesChoices.choiceOf(base)).isEqualTo(CapChoice.FromPreset)
    assertThat(RulesChoices.effectiveCaps(base).mobileBytes).isEqualTo(PresetCatalog.MAXIMUM_MOBILE_CAP_BYTES)

    val limited = RulesChoices.withMobileCap(base, CapChoice.Limit(2_000_000_000L))
    assertThat(limited.toRuleSet().caps.mobileBytes).isEqualTo(2_000_000_000L)
    assertThat(RulesChoices.choiceOf(limited)).isEqualTo(CapChoice.Limit(2_000_000_000L))

    val unlimited = RulesChoices.withMobileCap(limited, CapChoice.Unlimited)
    assertThat(unlimited.toRuleSet().caps.mobileBytes).isNull()
    assertThat(RulesChoices.choiceOf(unlimited)).isEqualTo(CapChoice.Unlimited)

    // Tornare al preset toglie la scelta: il tetto torna quello del preset di adesso.
    val back = RulesChoices.withPreset(RulesChoices.withMobileCap(unlimited, CapChoice.FromPreset), Preset.NIGHT)
    assertThat(back.capsOverride).isNull()
    assertThat(back.toRuleSet().caps.mobileBytes).isEqualTo(PresetCatalog.DEFAULT_MOBILE_CAP_BYTES)
  }

  @Test
  fun scegliereIlTettoMobileLasciaIlTettoWifiCom_era() {
    val withWifiCap = RuleSettings(capsOverride = MonthlyCaps(mobileBytes = 1_000_000_000L, wifiBytes = 50_000_000_000L))

    val after = RulesChoices.withMobileCap(withWifiCap, CapChoice.Limit(5_000_000_000L))

    assertThat(after.capsOverride).isEqualTo(MonthlyCaps(mobileBytes = 5_000_000_000L, wifiBytes = 50_000_000_000L))
  }

  @Test
  fun ilRoamingAggiungeLaReteATutteLeAttivita_spentoNoNe_eNonToccaIlResto() {
    val on = RulesChoices.withRoaming(RuleSettings(), allow = true)
    assertThat(on.toRuleSet().rules.values.all { AllowedNetwork.ROAMING in it.networks }).isTrue()
    val off = RulesChoices.withRoaming(on, allow = false)
    assertThat(off.toRuleSet().rules.values.none { AllowedNetwork.ROAMING in it.networks }).isTrue()
    assertThat(on.copy(allowRoaming = false)).isEqualTo(off)
  }

  /** FAB-4: «Metti in pausa con il risparmio energetico» cambia solo `pauseOnPowerSaver`, e il motore lo rilegge (`toRuleSet`). */
  @Test
  fun ilRisparmioEnergeticoSiCambiaENonTocaIlResto() {
    val base = RuleSettings()
    assertThat(base.pauseOnPowerSaver).isTrue()

    val off = RulesChoices.withPauseOnPowerSaver(base, pause = false)
    assertThat(off.pauseOnPowerSaver).isFalse()
    assertThat(off.toRuleSet().pauseOnPowerSaver).isFalse()
    assertThat(off.copy(pauseOnPowerSaver = true)).isEqualTo(base)

    val on = RulesChoices.withPauseOnPowerSaver(off, pause = true)
    assertThat(on).isEqualTo(base)
    assertThat(on.toRuleSet().pauseOnPowerSaver).isTrue()
    // Cambiare preset non ripristina la scelta fatta dall'utente.
    assertThat(RulesChoices.withPreset(off, Preset.MAXIMUM).pauseOnPowerSaver).isFalse()
  }

  @Test
  fun ilViewModelScriveNelleImpostazioniDelMotore_eLaPaginaSiAggiorna() = runTest {
    val store = InMemoryRuleStore()
    val vm = RulesViewModel(store)
    keepAlive(vm.state)
    assertThat(vm.state.value?.preset).isEqualTo(Preset.NIGHT)

    vm.choosePreset(Preset.WIFI_ALWAYS)
    vm.chooseMobileCap(CapChoice.Limit(500_000_000L))
    vm.setRoaming(true)
    vm.setPauseOnPowerSaver(false)

    val saved = store.current()
    assertThat(saved.preset).isEqualTo(Preset.WIFI_ALWAYS)
    assertThat(saved.capsOverride?.mobileBytes).isEqualTo(500_000_000L)
    assertThat(saved.allowRoaming).isTrue()
    assertThat(saved.pauseOnPowerSaver).isFalse()
    val ui = vm.state.value!!
    assertThat(ui.preset).isEqualTo(Preset.WIFI_ALWAYS)
    assertThat(ui.cap).isEqualTo(CapChoice.Limit(500_000_000L))
    assertThat(ui.allowRoaming).isTrue()
    assertThat(ui.pauseOnPowerSaver).isFalse()
    assertThat(ui.presetCapBytes).isEqualTo(PresetCatalog.DEFAULT_MOBILE_CAP_BYTES)
  }
}
