package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// La lettura del testo nelle foto, nella pagina Ricerca e modelli (Fase 6, P8, D41, Q4, Q13): spenta di serie; "Scopri e attiva" porta alla pagina di
// attivazione (l'unica che puo' dare il consenso); attiva per l'account con "Non su questo dispositivo" e "Spegni". Se il testo della spiegazione e'
// cambiato, la funzione si richiede (la versione del testo e' salvata col consenso).

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingBlock(state: SearchSettingsState, handlers: SearchSettingsHandlers) {
  val reading = state.textReading
  var disabling by remember { mutableStateOf(false) }
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.models_reading_title),
      subtitle = stringResource(readingSummary(reading.state, reading.excludedHere)),
      tone = readingTone(reading.state),
      leading = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 3,
    )
    if (reading.state is TextReadingState.OnForAccount && reading.needsConsent) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.models_reading_changed),
        subtitle = "",
        tone = FluidTone.Warning,
        leading = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        disclosure = false,
        titleMaxLines = 4,
      )
    }
  }
  // K4: i tasti vanno a capo interi se non entrano affiancati ("Non su questo dispositivo" si spezzava in "disposit/ivo" a 360 dp).
  FlowRow(
    modifier = Modifier.padding(top = SearchDimens.TightGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
    horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
    verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) {
    when (val s = reading.state) {
      TextReadingState.Off -> {
        Button(R.string.models_reading_off_action, handlers::onOpenActivation)
        // R8: l'esclusione si da' anche da spenta, prima che qualcuno accenda la lettura da un altro dispositivo.
        if (reading.excludedHere) Button(R.string.models_reading_include, handlers::onIncludeThisDevice) else Button(R.string.models_reading_exclude, handlers::onExcludeThisDevice)
      }
      TextReadingState.Disabling -> Unit
      // LT-2, R8: l'account l'ha accesa altrove; qui ML Kit non parte finche' non si conferma (o ci si esclude).
      is TextReadingState.AwaitingNotice -> {
        Button(R.string.models_reading_notice_action, handlers::onOpenActivation)
        Button(R.string.models_reading_exclude, handlers::onExcludeThisDevice)
        Button(R.string.models_reading_disable) { disabling = true }
      }
      is TextReadingState.OnForAccount -> {
        if (reading.needsConsent) Button(R.string.models_reading_changed_action, handlers::onOpenActivation)
        if (s.thisDevice) Button(R.string.models_reading_exclude, handlers::onExcludeThisDevice) else Button(R.string.models_reading_include, handlers::onIncludeThisDevice)
        Button(R.string.models_reading_disable) { disabling = true }
      }
    }
  }
  // R6: la scelta vale subito qui; se non e' ancora arrivata agli altri dispositivi lo si dice. I8: e un cambio fatto altrove vale qui
  // quando i dati personali si sincronizzano.
  if (reading.syncPending) FluidSectionFootnote(text = stringResource(R.string.models_reading_sync_pending))
  FluidSectionFootnote(text = stringResource(R.string.models_reading_other_devices))
  if (disabling) {
    val title = stringResource(R.string.models_reading_disable_title)
    GalleryConfirmSheet(visible = true, onDismissRequest = { disabling = false }, paneTitle = title) {
      GallerySheetScaffold(
        icon = Icons.Rounded.TextFields,
        tone = GallerySheetTone.Caution,
        title = title,
        message = stringResource(R.string.models_reading_disable_message),
        actions = {
          FluidButton(
            text = stringResource(R.string.models_reading_disable),
            onClick = { disabling = false; handlers.onDisableReading() },
            style = FluidButtonStyle.Destructive,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
          FluidButton(text = stringResource(R.string.models_cancel), onClick = { disabling = false }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        },
      )
    }
  }
}

@Composable
private fun Button(label: Int, onClick: () -> Unit) {
  FluidButton(text = stringResource(label), onClick = onClick, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
}

internal fun readingSummary(state: TextReadingState, excludedHere: Boolean = false): Int = when (state) {
  TextReadingState.Off -> if (excludedHere) R.string.models_reading_off_excluded else R.string.models_reading_off
  TextReadingState.Disabling -> R.string.models_reading_disabling
  is TextReadingState.AwaitingNotice -> R.string.models_reading_awaiting_notice
  is TextReadingState.OnForAccount -> if (state.thisDevice) R.string.models_reading_on_here else R.string.models_reading_on_not_here
}

private fun readingTone(state: TextReadingState): FluidTone = when (state) {
  TextReadingState.Off -> FluidTone.Neutral
  TextReadingState.Disabling -> FluidTone.Info
  is TextReadingState.AwaitingNotice -> FluidTone.Warning
  is TextReadingState.OnForAccount -> if (state.thisDevice) FluidTone.Success else FluidTone.Warning
}
