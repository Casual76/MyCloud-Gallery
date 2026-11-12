package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
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
// Giro 1 della fase I (CU-19): **un tasto solo**, con la sua icona, per la cosa che serve adesso (accendere, confermare, rileggere, riprendere qui); le
// altre scelte e le frasi intere stanno dietro il tocco sulla riga. Nessuna scelta e' sparita: tutte sono nel foglio.

/** Una scelta della lettura del testo: la sua parola, la sua icona, che cosa fa. */
private class ReadingChoice(val label: Int, val icon: ImageVector, val run: () -> Unit)

@Composable
internal fun ReadingBlock(state: SearchSettingsState, handlers: SearchSettingsHandlers) {
  val reading = state.textReading
  var disabling by remember { mutableStateOf(false) }
  var open by remember { mutableStateOf(false) }
  val title = stringResource(R.string.models_reading_title)
  val summary = stringResource(readingSummary(reading.state, reading.excludedHere))
  val changedText = stringResource(R.string.models_reading_changed)
  val syncText = stringResource(R.string.models_reading_sync_pending)
  val activate = ReadingChoice(R.string.models_reading_off_action, Icons.Rounded.PowerSettingsNew, handlers::onOpenActivation)
  val confirm = ReadingChoice(R.string.models_reading_notice_action, Icons.AutoMirrored.Rounded.Article, handlers::onOpenActivation)
  val reread = ReadingChoice(R.string.models_reading_changed_action, Icons.AutoMirrored.Rounded.Article, handlers::onOpenActivation)
  val exclude = ReadingChoice(R.string.models_reading_exclude, Icons.Rounded.MobileOff, handlers::onExcludeThisDevice)
  val include = ReadingChoice(R.string.models_reading_include, Icons.Rounded.Smartphone, handlers::onIncludeThisDevice)
  val disable = ReadingChoice(R.string.models_reading_disable, Icons.Rounded.Block) { disabling = true }
  // La scelta principale (null: niente da fare, tutto dietro il tocco) e tutte le scelte, la principale per prima.
  val (primary, choices) = when (val s = reading.state) {
    // R8: l'esclusione si da' anche da spenta, prima che qualcuno accenda la lettura da un altro dispositivo.
    TextReadingState.Off -> if (reading.excludedHere) include to listOf(include, activate) else activate to listOf(activate, exclude)
    TextReadingState.Disabling -> null to emptyList()
    // LT-2, R8: l'account l'ha accesa altrove; qui ML Kit non parte finche' non si conferma (o ci si esclude).
    is TextReadingState.AwaitingNotice -> confirm to listOf(confirm, exclude, disable)
    is TextReadingState.OnForAccount -> {
      val here = if (s.thisDevice) exclude else include
      when {
        reading.needsConsent -> reread to listOf(reread, here, disable)
        s.thisDevice -> null to listOf(exclude, disable)
        else -> include to listOf(include, disable)
      }
    }
  }
  FluidListGroup {
    // I2: lo stato e' il segno sulla piastrella e due parole; la frase intera la dice TalkBack. Il tocco apre il perche' e le altre scelte.
    FluidListRow(
      title = title,
      subtitle = stringResource(readingShort(reading.state, reading.excludedHere)),
      leading = { GalleryIconTile(icon = Icons.Rounded.TextFields, family = GalleryIconFamily.Search, status = readingMark(reading.state), statusDescription = null) },
      leadingTile = false,
      disclosure = true,
      titleMaxLines = 2,
      onClick = { open = true },
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
    // R6: la scelta vale subito qui; se non e' ancora arrivata agli altri dispositivi lo dice un segno: e' un'attesa, quindi la clessidra (regola comune).
    if (reading.syncPending) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.models_reading_sync_short),
        subtitle = "",
        leading = { GalleryIconTile(icon = Icons.Rounded.HourglassTop, family = GalleryIconFamily.Network, statusDescription = null) },
        leadingTile = false,
        disclosure = false,
        titleMaxLines = 2,
        modifier = Modifier.semantics { contentDescription = syncText },
      )
    }
  }
  if (primary != null) {
    Row(modifier = Modifier.padding(top = SearchDimens.TightGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter)) {
      ActionButton(stringResource(primary.label), primary.icon, onClick = primary.run)
    }
  }
  if (open) {
    ChoiceSheet(
      title = stringResource(R.string.models_section_reading),
      message = summary + "\n\n" + stringResource(R.string.models_reading_other_devices),
      onDismiss = { open = false },
      icon = Icons.Rounded.TextFields,
    ) {
      choices.forEach { choice -> ActionButton(stringResource(choice.label), choice.icon, onClick = { open = false; choice.run() }, fill = true) }
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

/** Il segno dello stato della lettura sulla piastrella: spenta o non usata qui = `Stopped`, si spegne = `Running`, aspetta la conferma = `Attention`. */
private fun readingMark(state: TextReadingState): GalleryIconStatus = when (state) {
  TextReadingState.Off -> GalleryIconStatus.Stopped
  TextReadingState.Disabling -> GalleryIconStatus.Running
  is TextReadingState.AwaitingNotice -> GalleryIconStatus.Attention
  is TextReadingState.OnForAccount -> if (state.thisDevice) GalleryIconStatus.Done else GalleryIconStatus.Stopped
}
