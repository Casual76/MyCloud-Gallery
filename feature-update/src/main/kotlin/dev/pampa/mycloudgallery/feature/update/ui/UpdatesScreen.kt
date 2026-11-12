package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState

// LA PAGINA IMPOSTAZIONI -> AGGIORNAMENTI (pacchetto U, 12/11/2026). Dall'alto: la figura con lo stato e il tasto che serve (Controlla adesso, Aggiorna,
// Consenti, Riprova); quando c'e' una versione, che cosa cambia in poche righe; il canale come due scelte con il segno (Stabile, di serie, e Beta);
// la versione installata. Nient'altro da leggere: l'avanzamento e' l'anello comune della galleria, il permesso una riga.

@Composable
fun UpdatesScreen(state: UpdatesState, actions: UpdateActions, onBack: () -> Unit) {
  val whatsNew = stringResource(R.string.update_whats_new)
  FluidScreen(title = stringResource(R.string.update_title), onBack = onBack) {
    item(key = "figure") {
      FluidListGroup { UpdatePanel(state = state, actions = actions) }
    }
    val notes = withoutHeading(notesOf(state), whatsNew)
    if (notes.isNotBlank() && state.install == null && !state.askMetered) {
      item(key = "notes") { NotesBlock(notes) }
    }
    item(key = "channel-header") { GallerySectionHeader(title = stringResource(R.string.update_channel)) }
    item(key = "channel") {
      FluidListGroup {
        ChannelRow(
          title = stringResource(R.string.update_channel_stable),
          detail = stringResource(R.string.update_channel_stable_detail),
          icon = Icons.Rounded.Verified,
          selected = state.channel == UpdateChannel.STABLE,
          enabled = !state.installing,
          onClick = { actions.chooseChannel(UpdateChannel.STABLE) },
        )
        FluidListDivider()
        ChannelRow(
          title = stringResource(R.string.update_channel_beta),
          detail = stringResource(R.string.update_channel_beta_detail),
          icon = Icons.Rounded.Science,
          selected = state.channel == UpdateChannel.BETA,
          enabled = !state.installing,
          onClick = { actions.chooseChannel(UpdateChannel.BETA) },
        )
      }
    }
    item(key = "installed") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.update_installed_version),
          subtitle = "",
          badge = { ValueText(state.installedVersion) },
          leading = { GalleryIconTile(icon = Icons.Rounded.Info, family = GalleryIconFamily.Photos) },
          leadingTile = false,
          disclosure = false,
        )
      }
    }
  }
}

/** Che cosa cambia: il primo paragrafo della nota di rilascio dell'elenco, al piu' sei righe, con il suo titolo. */
@Composable
private fun NotesBlock(notes: String) {
  FluidListGroup {
    Column(
      modifier = Modifier.fillMaxWidth().padding(UpdateDimens.CardPadding),
      verticalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap),
    ) {
      Text(text = stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      Text(
        text = notes,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = NOTES_LINES,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Una scelta del canale: la piastrella (la spunta per le collaudate, la provetta per le beta), il nome, la riga corta e il segno della scelta. Per
 * TalkBack e' una scelta con il nome, la riga e lo stato.
 */
@Composable
private fun ChannelRow(title: String, detail: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val chosen = stringResource(R.string.update_selected)
  FluidListRow(
    title = title,
    subtitle = detail,
    leading = { GalleryIconTile(icon = icon, family = if (icon == Icons.Rounded.Science) GalleryIconFamily.Search else GalleryIconFamily.Places) },
    leadingTile = false,
    badge = { ChoiceMark(selected) },
    disclosure = false,
    onClick = if (enabled) onClick else null,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
      contentDescription = "$title. $detail"
      if (selected) stateDescription = chosen
    },
  )
}

@Composable
private fun ChoiceMark(selected: Boolean) {
  Icon(
    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
    contentDescription = null,
    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    modifier = Modifier.size(UpdateDimens.ChoiceMark),
  )
}

@Composable
private fun ValueText(text: String) {
  Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private const val NOTES_LINES = 6
