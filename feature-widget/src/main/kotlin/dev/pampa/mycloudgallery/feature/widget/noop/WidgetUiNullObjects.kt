package dev.pampa.mycloudgallery.feature.widget.noop

import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState

// Fase 10 (P0, D42): lo stato di un widget che non ha ancora un piano (processo freddo, app non configurata): "Configura MyCloud Gallery",
// senza immagini. Mai una foto da un default.

/** Lo stato di partenza di ogni misura. */
object NotConfiguredWidgetUi {
  fun of(size: WidgetSize): WidgetUiState = WidgetUiState.Message(size, WidgetEmpty.NOT_CONFIGURED)
}
