package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone

/**
 * Uno stato vuoto o d'attesa di queste pagine: l'illustrazione del design system, un titolo, una frase e, se serve,
 * un'azione. Nessun posto muto: una raccolta senza elementi dice perche' e cosa si puo' fare (piano, 2.1).
 *
 * E' costruito come `GalleryStateView`, che pero' ha frasi fisse (Libreria vuota, nessun risultato, NAS, permesso): le
 * raccolte hanno le loro e non si aggiungono voci a un modulo che non e' di questo pacchetto.
 */
@Composable
internal fun CollectionEmptyState(
  icon: ImageVector,
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  actionStyle: FluidButtonStyle = FluidButtonStyle.Filled,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(CollectionsDimens.StatePadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(CollectionsDimens.StateGap),
  ) {
    GalleryIllustration(icon = icon, tone = tone)
    Text(
      text = title,
      modifier = Modifier.widthIn(max = CollectionsDimens.StateTextMaxWidth),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = detail,
      modifier = Modifier.widthIn(max = CollectionsDimens.StateTextMaxWidth),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && onAction != null) {
      FluidButton(
        text = actionLabel,
        onClick = onAction,
        size = FluidButtonSize.Large,
        style = actionStyle,
      )
    }
  }
}
