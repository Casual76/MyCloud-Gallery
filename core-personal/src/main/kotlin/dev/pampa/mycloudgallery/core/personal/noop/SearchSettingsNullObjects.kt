package dev.pampa.mycloudgallery.core.personal.noop

import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 6 (P0): i null-object delle impostazioni personali nuove. Falliscono CHIUSI: la lettura del testo resta spenta (ML Kit non
// parte, D41) e nessuna scelta si salva; l'ordine e' il default.

object NoSearchPersonalSettings : SearchPersonalSettings {
  override val textReading: StateFlow<TextReadingChoice> = MutableStateFlow(TextReadingChoice.Off)

  override suspend fun setTextReading(choice: TextReadingChoice) = Unit

  override val searchOrder: StateFlow<SearchOrderSetting> = MutableStateFlow(SearchOrderSetting.RECENT)

  override suspend fun setSearchOrder(order: SearchOrderSetting) = Unit
}

/** Il default della risposta Q16 (b): l'animazione e' accesa; nessuna scelta si salva. */
object DefaultMotionPersonalSettings : MotionPersonalSettings {
  override val motionAnimation: StateFlow<Boolean> = MutableStateFlow(true)

  override suspend fun setMotionAnimation(on: Boolean) = Unit
}