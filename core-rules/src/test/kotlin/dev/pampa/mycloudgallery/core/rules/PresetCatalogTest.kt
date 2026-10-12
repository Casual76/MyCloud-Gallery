package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork.ANY_WIFI_VPN
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork.HOME_WIFI
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork.MOBILE_DATA
import dev.pampa.mycloudgallery.core.rules.PowerPolicy.BATTERY_ALLOWED
import dev.pampa.mycloudgallery.core.rules.PowerPolicy.PLUGGED_ONLY
import org.junit.Test

/** La tabella preset x attivita' (piano 4.6) cosi' come e' codificata. */
class PresetCatalogTest {

  private val night = NightWindows.DEFAULT
  private val wifi = setOf(HOME_WIFI, ANY_WIFI_VPN)
  private val light = BackgroundActivity.entries.filter { it.weight == ActivityWeight.LIGHT }
  private val heavy = BackgroundActivity.entries.filter { it.weight == ActivityWeight.HEAVY }

  @Test
  fun classificazioneLeggeraPesante() {
    // Proposta 1: leggere = sync indice e backup foto; pesanti = tutto il resto.
    assertThat(light)
      .containsExactly(BackgroundActivity.PHOTO_BACKUP, BackgroundActivity.INDEX_SYNC)
    assertThat(heavy)
      .containsExactly(
        BackgroundActivity.VIDEO_BACKUP,
        BackgroundActivity.PREPARATION,
        BackgroundActivity.AI_COMPUTE,
        BackgroundActivity.HQ_PREVIEWS,
      )
    assertThat(BackgroundActivity.VIDEO_BACKUP.isVideo).isTrue()
    assertThat(BackgroundActivity.PHOTO_BACKUP.isVideo).isFalse()
  }

  @Test
  fun notte_tuttoDiNotteInCaricaInWifi() {
    val rules = PresetCatalog.ruleSet(Preset.NIGHT, night)

    for (activity in BackgroundActivity.entries) {
      assertThat(rules.ruleFor(activity)).isEqualTo(ActivityRule(night, PLUGGED_ONLY, wifi))
    }
    assertThat(rules.heavyOnlyAtHome).isTrue()
    assertThat(rules.batteryThresholdPercent).isEqualTo(20)
  }

  @Test
  fun notte_usaLaFasciaChePassaLaFunzione() {
    val early = TimeWindow(java.time.LocalTime.of(1, 0), java.time.LocalTime.of(5, 0))

    assertThat(PresetCatalog.ruleSet(Preset.NIGHT, early).ruleFor(BackgroundActivity.AI_COMPUTE).window)
      .isEqualTo(early)
  }

  @Test
  fun bilanciato_leggereAncheABatteria_pesantiInCarica_senzaFascia() {
    val rules = PresetCatalog.ruleSet(Preset.BALANCED, night)

    for (activity in light) assertThat(rules.ruleFor(activity)).isEqualTo(ActivityRule(null, BATTERY_ALLOWED, wifi))
    for (activity in heavy) assertThat(rules.ruleFor(activity)).isEqualTo(ActivityRule(null, PLUGGED_ONLY, wifi))
    assertThat(rules.heavyOnlyAtHome).isTrue()
  }

  @Test
  fun wifiSempre_tuttoInWifiAncheABatteria() {
    val rules = PresetCatalog.ruleSet(Preset.WIFI_ALWAYS, night)

    for (activity in BackgroundActivity.entries) {
      assertThat(rules.ruleFor(activity)).isEqualTo(ActivityRule(null, BATTERY_ALLOWED, wifi))
    }
    assertThat(rules.heavyOnlyAtHome).isTrue()
  }

  @Test
  fun massimo_tuttoAncheConIDatiMobili_entroITetti_ePesantiOvunque() {
    val rules = PresetCatalog.ruleSet(Preset.MAXIMUM, night)

    for (activity in BackgroundActivity.entries) {
      assertThat(rules.ruleFor(activity)).isEqualTo(ActivityRule(null, BATTERY_ALLOWED, wifi + MOBILE_DATA))
    }
    assertThat(rules.heavyOnlyAtHome).isFalse()
    assertThat(rules.caps.mobileBytes).isEqualTo(PresetCatalog.MAXIMUM_MOBILE_CAP_BYTES)
  }

  @Test
  fun ilRoamingNonEInNessunPreset_nemmenoMassimo() {
    for (preset in Preset.entries) {
      for (activity in BackgroundActivity.entries) {
        assertWithMessage("$preset / $activity")
          .that(PresetCatalog.ruleSet(preset, night).ruleFor(activity).networks)
          .doesNotContain(AllowedNetwork.ROAMING)
      }
    }
  }

  @Test
  fun tuttiIPresetHannoUnTetto_eNessunoLoDisattivaDiNascosto() {
    for (preset in Preset.entries) {
      val rules = PresetCatalog.ruleSet(preset, night)
      assertThat(rules.caps.mobileBytes).isNotNull()
      assertThat(rules.caps.wifiBytes).isNull() // il tetto Wi-Fi e' una scelta dell'utente
      assertThat(rules.pauseOnPowerSaver).isTrue()
    }
    assertThat(PresetCatalog.ruleSet(Preset.NIGHT, night).caps.mobileBytes)
      .isEqualTo(PresetCatalog.DEFAULT_MOBILE_CAP_BYTES)
  }

  @Test
  fun personalizzato_partedallaNotte() {
    assertThat(PresetCatalog.ruleSet(Preset.CUSTOM, night)).isEqualTo(PresetCatalog.ruleSet(Preset.NIGHT, night))
  }

  @Test
  fun ognunoDeiPresetHaLaDescrizioneInItaliano() {
    val infos = PresetCatalog.allInfo()

    assertThat(infos.map { it.preset }).containsExactlyElementsIn(Preset.entries).inOrder()
    assertThat(infos.map { it.title })
      .containsExactly("Notte", "Bilanciato", "Wi-Fi sempre", "Massimo", "Personalizzato")
      .inOrder()
    for (info in infos) {
      assertThat(info.summary).isNotEmpty()
      assertThat(info.forWhom).isNotEmpty()
    }
    assertThat(PresetCatalog.info(Preset.NIGHT).forWhom).isEqualTo("Chi lascia il telefono in carica di notte")
    assertThat(PresetCatalog.info(Preset.MAXIMUM).summary).contains("roaming")
  }

  @Test
  fun unaRegolaSenzaTutteLeAttivita_nonEAmmessa() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      RuleSet(rules = mapOf(BackgroundActivity.PHOTO_BACKUP to ActivityRule(null, PLUGGED_ONLY, wifi)), caps = MonthlyCaps.NONE)
    }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      PresetCatalog.ruleSet(Preset.NIGHT, night).copy(batteryThresholdPercent = 101)
    }
  }
}
