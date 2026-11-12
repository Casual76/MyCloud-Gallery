package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState

// L'AVVISO ALL'APERTURA (pacchetto U, 12/11/2026). Discreto e non bloccante: una scheda in basso, sopra la navigazione, con la figura, «Nuova
// versione», la versione e il peso, «Aggiorna» e «Dopo». Compare una volta per versione (dopo un controllo silenzioso che la trova); «Aggiorna» la
// scarica da li' (l'anello si riempie nella scheda), «Dopo» la chiude e non torna per quella versione. La pagina sotto resta usabile.

/** L'avviso alla radice dell'app (`:app`, sotto il blocco): niente finche' non c'e' una versione da annunciare. */
@Composable
fun UpdatePromptHost(viewModel: UpdatesViewModel, modifier: Modifier = Modifier) {
  val visible by viewModel.prompt.collectAsStateWithLifecycle()
  val state by viewModel.state.collectAsStateWithLifecycle()
  // Al ritorno dalla pagina del permesso di Android l'installazione riparte da sola.
  LifecycleResumeEffect(viewModel) {
    viewModel.onResumed()
    onPauseOrDispose { }
  }
  if (visible && (state.found != null || state.install != null)) {
    UpdatePromptCard(state = state, actions = viewModel.actions, modifier = modifier)
  }
}

/** La scheda dell'avviso. */
@Composable
fun UpdatePromptCard(state: UpdatesState, actions: UpdateActions, modifier: Modifier = Modifier) {
  val model = UpdatePanels.of(state)
  val words = panelWords(state, model)
  val fresh = model.figure == UpdateFigure.FOUND
  val title = if (fresh) stringResource(R.string.update_prompt_title) else words.headline.orEmpty()
  val detail = if (fresh) shortLine(state) else words.detail
  val promptTitle = stringResource(R.string.update_prompt_title)
  FluidCard(
    modifier = modifier
      .navigationBarsPadding()
      .padding(UpdateDimens.PromptMargin)
      .widthIn(max = UpdateDimens.PromptMaxWidth)
      .fillMaxWidth()
      .semantics { paneTitle = promptTitle },
    highlighted = true,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UpdateDimens.Gap)) {
      UpdateFigureView(model, state, hero = false, spoken = words.spoken)
      Column(
        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        detail?.takeIf { it.isNotBlank() }?.let {
          Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (button in promptButtons(model)) {
        FluidButton(
          text = if (button == PromptButton.LATER) stringResource(R.string.update_later) else promptLabel(button.action!!, state),
          onClick = if (button == PromptButton.LATER) actions.later else actionFor(button.action!!, actions),
          style = if (button.filled) FluidButtonStyle.Filled else FluidButtonStyle.Plain,
          size = FluidButtonSize.Medium,
        )
      }
    }
  }
}

@Composable
private fun promptLabel(action: UpdateAction, state: UpdatesState): String =
  if (action == UpdateAction.DOWNLOAD_METERED) {
    stringResource(action.label, dev.antigravity.fluidengine.ui.fluid.FluidUpdate.sizeLabel(state.found?.sizeBytes ?: 0L).orEmpty()).trim()
  } else {
    stringResource(action.label)
  }

/** Un tasto dell'avviso: «Dopo» (chiude l'avviso) o un'azione del modello. */
internal class PromptButton private constructor(val action: UpdateAction?, val filled: Boolean) {
  companion object {
    val LATER = PromptButton(null, filled = false)

    fun of(action: UpdateAction, filled: Boolean) = PromptButton(action, filled)
  }
}

/**
 * I tasti dell'avviso, da sinistra: «Dopo» c'e' sempre, tranne quando la domanda dei dati mobili ha gia' il suo «Annulla» (mai tre tasti in fila).
 * Mentre scarica: «Annulla» e «Dopo» (la scheda si chiude, lo scaricamento continua e la pagina Aggiornamenti lo mostra).
 */
internal fun promptButtons(model: UpdatePanelModel): List<PromptButton> {
  val buttons = mutableListOf<PromptButton>()
  when (model.figure) {
    UpdateFigure.METERED -> {
      buttons += PromptButton.of(UpdateAction.CANCEL_METERED, filled = false)
      buttons += PromptButton.of(UpdateAction.DOWNLOAD_METERED, filled = true)
    }
    UpdateFigure.DOWNLOADING -> {
      buttons += PromptButton.of(UpdateAction.CANCEL_DOWNLOAD, filled = false)
      buttons += PromptButton.LATER
    }
    else -> {
      buttons += PromptButton.LATER
      model.primary?.takeIf { it != UpdateAction.CHECK }?.let { buttons += PromptButton.of(it, filled = true) }
    }
  }
  return buttons
}
