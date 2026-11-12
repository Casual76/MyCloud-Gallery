package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep

// L'AVVISO (pacchetto U, 12/11/2026; giro 1 delle critiche, UU-5). Discreto e non bloccante: una scheda appoggiata SOPRA la barra delle schede della
// galleria (la mette li' `:app`, nello scaffold: mai sopra la pillola, mai sopra il visore o le altre pagine), con la figura, due parole e i tasti.
// Compare quando c'e' una versione da offrire e il promemoria lo permette, quando un tentativo aspetta il permesso, e una volta dopo un aggiornamento
// riuscito («Aggiornata»). «Dopo» la rimanda di tre giorni (al terzo di fila, trenta); «Chiudi» su un guasto la chiude senza contare come «Dopo»
// (giro 3, UU3-6: torna al prossimo avvio); mentre lavora c'e' la «×» che la nasconde e basta (lo scaricamento continua). Con il file pronto «Dopo»
// e «Installa», senza la «×» (giro 3, UU3-4: «Annulla», che butta il file, sta nella pagina).

/**
 * Il ciclo di vita dell'activity per l'aggiornamento, alla radice (`:app`): ON_RESUME apre il cancello del passaggio ad Android e riprende (dopo la
 * pagina del permesso, dopo la finestra di Android); ON_PAUSE, e l'uscita dalla composizione, lo chiudono (giro 2, US2-1). E' il ciclo di vita
 * dell'activity che ospita la radice, non l'importanza del processo.
 */
@Composable
fun UpdateLifecycleEffect(viewModel: UpdatesViewModel) {
  // Un gettone per questa composizione, cioe' per questa activity (giro 3, US3-2): il cancello sa chi e' davanti, e la pausa di una finestra non
  // chiude quello di un'altra. Nessun riferimento all'activity.
  val owner = remember { Any() }
  LifecycleResumeEffect(viewModel, owner) {
    viewModel.onResumed(owner)
    onPauseOrDispose { viewModel.onPaused(owner) }
  }
}

/** L'avviso: niente finche' non c'e' qualcosa da dire. [onOpenNotes]: il tasto «Novità» sull'«Aggiornata» (la pagina delle novita' e' di `:app`). */
@Composable
fun UpdatePromptHost(viewModel: UpdatesViewModel, modifier: Modifier = Modifier, onOpenNotes: (() -> Unit)? = null) {
  val open by viewModel.prompt.collectAsStateWithLifecycle()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val visible = open && (state.found != null || state.install != null || state.justUpdated != null)
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val shift = with(LocalDensity.current) { UpdateDimens.PromptEnter.roundToPx() }
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = if (reduced) EnterTransition.None else slideInVertically(FluidMotion.standard()) { shift } + fadeIn(FluidMotion.standard()),
    exit = if (reduced) ExitTransition.None else slideOutVertically(FluidMotion.standard()) { shift } + fadeOut(FluidMotion.standard()),
  ) {
    UpdatePromptCard(state = state, actions = viewModel.actions, onOpenNotes = onOpenNotes)
  }
}

/** La scheda dell'avviso. */
@Composable
fun UpdatePromptCard(state: UpdatesState, actions: UpdateActions, modifier: Modifier = Modifier, onOpenNotes: (() -> Unit)? = null) {
  val model = UpdatePanels.of(state)
  val words = panelWords(state, model)
  val updated = state.justUpdated
  val fresh = updated == null && model.figure == UpdateFigure.FOUND
  val title = when {
    updated != null -> stringResource(R.string.update_installed)
    fresh -> stringResource(R.string.update_prompt_title)
    else -> words.headline
  }
  val detail = when {
    updated != null -> updated
    fresh -> shortLine(state)
    else -> words.detail
  }
  val promptTitle = stringResource(R.string.update_prompt_title)
  // «Aggiornata» senza «Novità»: una riga sola, con «Ok» al posto della «×» (giro 2, UU2-11).
  val okInline = updated != null && onOpenNotes == null
  val buttons = when {
    okInline -> emptyList()
    updated != null -> updatedButtons(withNotes = true)
    else -> promptButtons(model)
  }
  FluidCard(
    modifier = modifier
      .padding(UpdateDimens.PromptMargin)
      .widthIn(max = UpdateDimens.PromptMaxWidth)
      .fillMaxWidth()
      .semantics { paneTitle = promptTitle },
    highlighted = true,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UpdateDimens.Gap)) {
      if (updated != null) AppFigure(size = UpdateDimens.PromptIcon, status = GalleryIconStatus.Done) else UpdateFigureView(model, hero = false, step = state.install)
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
          // Quattro righe: a 1,3x la riga di Play Protect («scegli «Installa comunque»») entra intera (giro 2, UU2-10).
          Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
      }
      when {
        okInline -> FluidButton(text = stringResource(R.string.update_ok), onClick = actions.later, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large)
        updated == null && hidesOnly(model) -> HideButton(onClick = actions.hide)
      }
    }
    if (buttons.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (button in buttons) {
          FluidButton(
            text = stringResource(button.label),
            onClick = button.onClick(actions, onOpenNotes),
            style = if (button.filled) FluidButtonStyle.Filled else FluidButtonStyle.Plain,
            size = FluidButtonSize.Large,
          )
        }
      }
    }
  }
}

/** La «×»: nasconde l'avviso mentre lavora (lo scaricamento continua). Un tasto da 48 dp, con il nome per TalkBack. */
@Composable
private fun HideButton(onClick: () -> Unit) {
  val label = stringResource(R.string.update_hide)
  Box(
    modifier = Modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics {
        contentDescription = label
        role = Role.Button
      }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * Mentre lavora l'avviso si nasconde con la «×», non con «Dopo»: «Dopo» vorrebbe dire rimandare. Con il file pronto no (giro 3, UU3-4): «Dopo» la
 * nasconde, e una «×» accanto ad «Annulla» si confonderebbe con chi butta il file.
 */
internal fun hidesOnly(model: UpdatePanelModel): Boolean = when (model.figure) {
  UpdateFigure.DOWNLOADING, UpdateFigure.WORKING, UpdateFigure.AWAITING, UpdateFigure.INSTALLED -> true
  else -> false
}

/**
 * Un tasto dell'avviso: «Dopo» (rimanda), «Chiudi» su un guasto (chiude l'avviso: giro 2, UU2-2; senza contare come «Dopo»: giro 3, UU3-6), «Ok» e
 * «Novità» (dopo l'aggiornamento), o un'azione del modello.
 */
internal class PromptButton private constructor(
  val label: Int,
  val action: UpdateAction?,
  val filled: Boolean,
  private val kind: Kind,
) {
  private enum class Kind { LATER, OK, NOTES, CLOSE, ACTION }

  fun onClick(actions: UpdateActions, onOpenNotes: (() -> Unit)?): () -> Unit = when (kind) {
    Kind.LATER, Kind.OK -> actions.later
    Kind.CLOSE -> {
      {
        actions.close()
        actions.hide()
      }
    }
    Kind.NOTES -> {
      {
        actions.later()
        onOpenNotes?.invoke()
      }
    }
    Kind.ACTION -> actionFor(checkNotNull(action), actions)
  }

  companion object {
    val LATER = PromptButton(R.string.update_later, null, filled = false, kind = Kind.LATER)
    val CLOSE = PromptButton(R.string.update_close, UpdateAction.CLOSE, filled = false, kind = Kind.CLOSE)
    val OK = PromptButton(R.string.update_ok, null, filled = false, kind = Kind.OK)
    val NOTES = PromptButton(R.string.update_whats_new, null, filled = true, kind = Kind.NOTES)

    fun of(action: UpdateAction, filled: Boolean) = PromptButton(action.label, action, filled, Kind.ACTION)
  }
}

/** «Aggiornata»: «Ok», e «Novità» se c'e' dove aprirle. */
internal fun updatedButtons(withNotes: Boolean): List<PromptButton> =
  if (withNotes) listOf(PromptButton.OK, PromptButton.NOTES) else listOf(PromptButton.OK)

/**
 * I tasti dell'avviso, da sinistra, mai piu' di due: il leggero, poi il pieno. Con una versione nuova o il permesso «Dopo» e il tasto che risolve;
 * sulla domanda dei dati mobili «Annulla» e «Scarica lo stesso»; mentre scarica solo «Annulla» (la «×» nasconde); con il file pronto «Dopo» e
 * «Installa» (UU3-4); con un guasto «Chiudi» (che chiude davvero l'avviso, UU2-2) e, se ha senso, «Riprova».
 */
internal fun promptButtons(model: UpdatePanelModel): List<PromptButton> = when (model.figure) {
  UpdateFigure.METERED ->
    listOfNotNull(model.secondary?.let { PromptButton.of(it, filled = false) }, model.primary?.let { PromptButton.of(it, filled = true) })
  UpdateFigure.READY -> listOfNotNull(PromptButton.LATER, model.primary?.let { PromptButton.of(it, filled = true) })
  UpdateFigure.FAILED -> listOfNotNull(PromptButton.CLOSE, model.primary?.let { PromptButton.of(it, filled = true) })
  UpdateFigure.DOWNLOADING, UpdateFigure.WORKING -> listOfNotNull(model.secondary?.let { PromptButton.of(it, filled = false) })
  UpdateFigure.FOUND, UpdateFigure.PERMISSION -> listOfNotNull(PromptButton.LATER, model.primary?.let { PromptButton.of(it, filled = true) })
  else -> emptyList()
}
