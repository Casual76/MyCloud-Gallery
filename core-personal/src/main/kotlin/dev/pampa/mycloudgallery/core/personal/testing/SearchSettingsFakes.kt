package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 6 (P0): le impostazioni personali nuove in memoria, per i test di P4, P5, P8, P12, P14 e P11. Due istanze che condividono
// gli stessi flussi fanno da "due dispositivi dello stesso account" (la sincronizzazione vera e' quella di 4.7).

class FakeSearchPersonalSettings(
  textReading: TextReadingChoice = TextReadingChoice.Off,
  order: SearchOrderSetting = SearchOrderSetting.RECENT,
) : SearchPersonalSettings {
  override val textReading: MutableStateFlow<TextReadingChoice> = MutableStateFlow(textReading)
  override val searchOrder: MutableStateFlow<SearchOrderSetting> = MutableStateFlow(order)

  override suspend fun setTextReading(choice: TextReadingChoice) {
    textReading.value = choice
  }

  override suspend fun setSearchOrder(order: SearchOrderSetting) {
    searchOrder.value = order
  }
}

class FakeMotionPersonalSettings(on: Boolean = true) : MotionPersonalSettings {
  override val motionAnimation: MutableStateFlow<Boolean> = MutableStateFlow(on)

  override suspend fun setMotionAnimation(on: Boolean) {
    motionAnimation.value = on
  }
}