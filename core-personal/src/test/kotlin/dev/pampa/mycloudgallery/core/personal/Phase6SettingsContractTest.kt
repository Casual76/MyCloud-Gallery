package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.noop.DefaultMotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.noop.NoSearchPersonalSettings
import org.junit.Test

/** Fase 6 (P0): le firme delle impostazioni personali nuove (D41, D48, D50; Q16 (b)). */
class Phase6SettingsContractTest {

  @Test
  fun iCampiDelleVociSonoStabili() {
    assertThat(PersonalFields.SETTING_TEXT_READING).isEqualTo("text_reading")
    assertThat(PersonalFields.SETTING_SEARCH_ORDER).isEqualTo("search_order")
    assertThat(PersonalFields.SETTING_MOTION_ANIMATION).isEqualTo("motion_animation")
  }

  /** D41: spenta di default; un valore strano non accende mai la lettura del testo. */
  @Test
  fun laLetturaDelTestoESpentaSeLaVoceNonDiceAltro() {
    assertThat(TextReadingChoice.fromValue(null)).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Number(0))).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Number(-3))).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Flag(true))).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Text("1"))).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Number(Long.MAX_VALUE))).isEqualTo(TextReadingChoice.Off)
    assertThat(TextReadingChoice.fromValue(PersonalValue.Number(2))).isEqualTo(TextReadingChoice.OnForAccount(2))
    for (choice in listOf(TextReadingChoice.Off, TextReadingChoice.OnForAccount(1), TextReadingChoice.OnForAccount(7))) {
      assertThat(TextReadingChoice.fromValue(TextReadingChoice.toValue(choice))).isEqualTo(choice)
    }
    assertThat(NoSearchPersonalSettings.textReading.value).isEqualTo(TextReadingChoice.Off)
  }

  /** D48: "Recenti" di default, anche per un codice sconosciuto. Q16 (b): l'animazione e' accesa di default. */
  @Test
  fun iDefaultSonoQuelliDecisi() {
    assertThat(SearchOrderSetting.fromCode(null)).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(SearchOrderSetting.fromCode("by_color")).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(SearchOrderSetting.fromCode("relevant")).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(NoSearchPersonalSettings.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(DefaultMotionPersonalSettings.motionAnimation.value).isTrue()
  }
}
