package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerNotice
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.viewer.R

/**
 * Il foglio "Testo nella foto" (D46): il testo letto come testo normale, riga per riga. TalkBack lo legge, la selezione di sistema
 * funziona (`SelectionContainer`) e "Copia tutto" lo mette negli appunti come sensibile. E' la via accessibile al testo: le maniglie
 * su un'immagine non si usano con TalkBack. Si apre dal pulsante "Testo" (secondo tocco), dalla voce ⋮ e dall'azione di TalkBack.
 *
 * Il testo sta solo nella composizione: mai nei log (D40).
 */
@Composable
internal fun LiveTextSheet(
  visible: Boolean,
  text: String,
  onCopyAll: () -> Unit,
  onClose: () -> Unit,
) {
  val title = stringResource(R.string.livetext_sheet_title)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onClose, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.TextFields,
      tone = GallerySheetTone.Brand,
      title = title,
      message = null,
      body = {
        SelectionContainer {
          Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      },
      actions = {
        FluidButton(
          text = stringResource(R.string.livetext_copy_all),
          onClick = onCopyAll,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.livetext_close),
          onClick = onClose,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}

/** "Leggo il testo…" (D42): la lettura al volo dell'immagine mostrata, al piu' due secondi. Sta dove stanno gli avvisi brevi. */
@Composable
internal fun LiveTextReadingNotice(backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  GalleryViewerNotice(
    icon = Icons.Rounded.TextFields,
    title = stringResource(R.string.livetext_reading),
    backdrop = backdrop,
    modifier = modifier,
  )
}
