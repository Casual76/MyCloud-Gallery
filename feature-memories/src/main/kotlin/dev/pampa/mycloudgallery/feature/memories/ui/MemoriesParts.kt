package dev.pampa.mycloudgallery.feature.memories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer

// Le PARTI COMUNI delle schermate dei ricordi (Fase 9, P9): il segno di una scelta, una riga a scelta singola, "Annulla" per 10 secondi, i messaggi.
// Solo componenti dell'engine e del design system (G2); nessun colore, raggio o dimensione di testo scritti a mano (NoHandWrittenStyleTest).
// **Mai testo sopra una foto** e mai due controlli sovrapposti: l'offerta di "Annulla" e' una riga della pagina, non un pop-up che galleggia.

/** Il segno di un'opzione scelta: un pallino dell'accento con la spunta, o un anello vuoto. */
@Composable
internal fun RadioMark(selected: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .size(MemoriesDimens.Radio)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(scheme.primary)
        } else {
          Modifier.border(width = MemoriesDimens.RadioRing, color = scheme.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(MemoriesDimens.RadioCheck), tint = scheme.onPrimary)
    }
  }
}

/**
 * Una riga a scelta singola dei fogli e delle impostazioni: il pallino, il titolo e la frase sotto. Tutta la riga si tocca (almeno 48 dp);
 * per TalkBack e' un'opzione sola ("Mostra meno, selezionata").
 */
@Composable
internal fun ChoiceRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = MemoriesDimens.Touch)
      .fluidPressable(onClick = onClick, role = Role.RadioButton)
      .semantics(mergeDescendants = true) { this.selected = selected }
      .padding(vertical = MemoriesDimens.TightGap),
    horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioMark(selected)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 4)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      if (detail != null) Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** "Annulla" in fondo a un foglio: un bottone semplice da 50 dp (sopra il bersaglio da 48), senza riempimento. */
@Composable
internal fun CancelButton(onClick: () -> Unit, label: String = stringResource(R.string.memorie_cancel)) {
  FluidButton(text = label, onClick = onClick, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true)
}

/**
 * L'offerta di "Annulla" per 10 secondi (§2.3): una riga in testa alla pagina con la frase e il tasto. E' una **regione viva** (TalkBack la legge
 * quando compare) e il tasto sta sopra i 48 dp.
 */
@Composable
internal fun UndoBar(offer: UndoOffer, onUndo: () -> Unit, modifier: Modifier = Modifier) {
  val text = stringResource(
    when (offer.kind) {
      UndoKind.DISMISS -> R.string.memorie_undo_dismiss
      UndoKind.LESS_PERSON -> R.string.memorie_undo_less
      UndoKind.NEVER_DAYS -> R.string.memorie_undo_never
    },
  )
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.large)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(start = MemoriesDimens.Inset, end = MemoriesDimens.TightGap)
      .semantics { liveRegion = LiveRegionMode.Polite },
    horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = text,
      modifier = Modifier.weight(1f).padding(vertical = MemoriesDimens.TightGap),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    FluidButton(text = stringResource(R.string.memorie_undo), onClick = onUndo, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large)
  }
}

/** Il testo di un messaggio lasciato da un'azione (il contratto dice solo il codice). */
@Composable
internal fun memoriesMessageText(message: MemoriesMessage): String = stringResource(
  when (message) {
    MemoriesMessage.GONE -> R.string.memorie_message_gone
    MemoriesMessage.FAILED -> R.string.memorie_message_failed
  },
)

/** Il messaggio in testa a una schermata: un riquadro dell'engine che si chiude con un tocco. */
@Composable
internal fun MemoriesMessageBlock(message: MemoriesMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  FluidInlineMessage(
    message = memoriesMessageText(message),
    title = stringResource(R.string.memorie_message_title),
    tone = FluidTone.Warning,
    onDismiss = onDismiss,
    modifier = modifier.fillMaxWidth().padding(vertical = MemoriesDimens.TightGap / 2),
  )
}
