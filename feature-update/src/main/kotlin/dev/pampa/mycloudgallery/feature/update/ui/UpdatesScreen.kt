package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChoiceMark
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoRow
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState

// LA PAGINA IMPOSTAZIONI -> AGGIORNAMENTI (pacchetto U, 12/11/2026; giro 1 delle critiche). Dall'alto: la figura con lo stato e il tasto che serve
// (Controlla adesso, Aggiorna, Consenti, Installa, Riprova); quando c'e' una versione, che cosa cambia in poche righe; le versioni da ricevere come due
// scelte con il segno (Stabile, di serie, e Beta), con la «i» per chi torna a Stabile. La versione installata la dice la figura (UU-7): nessuna riga in
// piu'. Aperta, la pagina controlla da sola se sono passate 24 ore (UU-4) e intanto mostra la versione gia' nota.

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
    item(key = "channel-why") {
      GalleryInfoRow(
        label = stringResource(R.string.update_channel_beta_why_label),
        detail = stringResource(R.string.update_channel_beta_why),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UpdateDimens.CardPadding),
      )
    }
  }
}

/** Che cosa cambia: il primo paragrafo della nota di rilascio dell'elenco, al piu' sei righe; i punti con le righe a capo rientrate sotto il testo. */
@Composable
private fun NotesBlock(notes: String) {
  FluidListGroup {
    Column(
      modifier = Modifier.fillMaxWidth().padding(UpdateDimens.CardPadding),
      verticalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap),
    ) {
      Text(text = stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      for (line in noteLines(notes)) {
        if (line.bullet) {
          Row(horizontalArrangement = Arrangement.spacedBy(UpdateDimens.BulletGap)) {
            Text(text = BULLET, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
          }
        } else {
          Text(text = line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

/** Una riga delle note: con il punto davanti o no. */
internal class NoteLine(val text: String, val bullet: Boolean)

/** Le righe delle note, al piu' [NOTES_LINES]: «• testo» e «- testo» diventano punti. */
internal fun noteLines(notes: String): List<NoteLine> = notes.lines()
  .map { it.trim() }
  .filter { it.isNotEmpty() }
  .take(NOTES_LINES)
  .map { line ->
    val bullet = line.startsWith(BULLET) || line.startsWith("- ")
    NoteLine(text = if (bullet) line.drop(if (line.startsWith(BULLET)) BULLET.length else 2).trim() else line, bullet = bullet)
  }

/**
 * Una scelta del canale: la piastrella (la spunta per le collaudate, la provetta per le beta, nel colore dell'app come tutto l'aggiornamento), il nome,
 * la riga corta e il segno della scelta. Durante un'installazione e' spenta: si vede (piu' tenue) e TalkBack la dice non disponibile.
 */
@Composable
private fun ChannelRow(title: String, detail: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val chosen = stringResource(R.string.update_selected)
  FluidListRow(
    title = title,
    subtitle = detail,
    leading = { GalleryIconTile(icon = icon, family = GalleryIconFamily.Photos) },
    leadingTile = false,
    badge = { GalleryChoiceMark(selected) },
    disclosure = false,
    onClick = if (enabled) onClick else null,
    modifier = Modifier
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        this.selected = selected
        contentDescription = "$title. $detail"
        if (selected) stateDescription = chosen
        if (!enabled) disabled()
      },
  )
}

private const val NOTES_LINES = 6
private const val BULLET = "•"
private const val DISABLED_ALPHA = 0.38f
