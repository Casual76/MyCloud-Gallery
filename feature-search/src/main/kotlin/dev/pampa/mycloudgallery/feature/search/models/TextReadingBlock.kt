package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
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
  var explaining by remember { mutableStateOf(false) }
  val title = stringResource(R.string.models_reading_title)
  val summary = stringResource(readingSummary(reading.state, reading.excludedHere))
  val changedText = stringResource(R.string.models_reading_changed)
  val syncText = stringResource(R.string.models_reading_sync_pending)
  FluidListGroup {
    // I2: lo stato e' il segno sulla piastrella e due parole; la frase intera la dice TalkBack, il perche' sta dietro la «i».
    FluidListRow(
      title = title,
      subtitle = stringResource(readingShort(reading.state, reading.excludedHere)),
      leading = { GalleryIconTile(icon = Icons.Rounded.TextFields, family = GalleryIconFamily.Search, status = readingMark(reading.state), statusDescription = null) },
      leadingTile = false,
      disclosure = false,
      titleMaxLines = 2,
      badge = { InfoButton(label = stringResource(R.string.models_reading_info), onClick = { explaining = true }) },
      modifier = Modifier.semantics { contentDescription = "$title. $summary" },
    )
    if (reading.state is TextReadingState.OnForAccount && reading.needsConsent) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.models_reading_changed_short),
        subtitle = "",
        leading = { GalleryIconTile(icon = Icons.Rounded.Warning, family = GalleryIconFamily.Search, status = GalleryIconStatus.Attention, statusDescription = null) },
        leadingTile = false,
        disclosure = false,
        titleMaxLines = 2,
        modifier = Modifier.semantics { contentDescription = changedText },
      )
    }
    // R6: la scelta vale subito qui; se non e' ancora arrivata agli altri dispositivi lo dice un segno.
    if (reading.syncPending) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.models_reading_sync_short),
        subtitle = "",
        leading = { GalleryIconTile(icon = Icons.Rounded.Sync, family = GalleryIconFamily.Network, status = GalleryIconStatus.Paused, statusDescription = null) },
        leadingTile = false,
        disclosure = false,
        titleMaxLines = 2,
        modifier = Modifier.semantics { contentDescription = syncText },
      )
    }
  }
  if (explaining) {
    InfoSheet(
      title = stringResource(R.string.models_section_reading),
      message = summary + "\n\n" + stringResource(R.string.models_reading_other_devices),
      onDismiss = { explaining = false },
    )
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

/** I2: lo stato in due parole («Spenta», «Attiva», «Attiva, non qui»...). */
internal fun readingShort(state: TextReadingState, excludedHere: Boolean = false): Int = when (state) {
  TextReadingState.Off -> if (excludedHere) R.string.models_reading_short_off_excluded else R.string.models_reading_short_off
  TextReadingState.Disabling -> R.string.models_reading_short_disabling
  is TextReadingState.AwaitingNotice -> R.string.models_reading_short_awaiting
  is TextReadingState.OnForAccount -> if (state.thisDevice) R.string.models_reading_short_on else R.string.models_reading_short_on_not_here
}

/** Il segno dello stato della lettura sulla piastrella. */
private fun readingMark(state: TextReadingState): GalleryIconStatus = when (state) {
  TextReadingState.Off -> GalleryIconStatus.Stopped
  TextReadingState.Disabling -> GalleryIconStatus.Running
  is TextReadingState.AwaitingNotice -> GalleryIconStatus.Attention
  is TextReadingState.OnForAccount -> if (state.thisDevice) GalleryIconStatus.Done else GalleryIconStatus.Paused
}
