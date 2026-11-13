package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * L'avviso piccolo sotto un gruppo di impostazioni (BU-10): **una resa sola** per «non sono riuscito a salvare la scelta» (le cartelle del Backup e il livello di batteria stanno nella stessa pagina):
 * l'icona d'avviso del tema e il testo piccolo; chi usa TalkBack lo sente quando compare (`liveRegion`). Niente rosso: un avviso che si vede quando serve, non il blocco piu' forte della pagina.
 */
@Composable
fun GalleryWarningNote(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    horizontalArrangement = Arrangement.spacedBy(GalleryDimens.BadgeInset),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(imageVector = Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Glyph))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private val Glyph = 18.dp
