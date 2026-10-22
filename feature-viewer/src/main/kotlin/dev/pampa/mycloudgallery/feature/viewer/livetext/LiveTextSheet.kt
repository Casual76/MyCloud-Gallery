package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import dev.pampa.mycloudgallery.feature.viewer.R

/**
 * Il foglio "Testo nella foto" (D46): il testo letto come testo normale, riga per riga. TalkBack lo legge, la selezione di sistema
 * funziona ([SensitiveSelectionContainer]) e sia "Copia tutto" sia il "Copia" della selezione lo mettono negli appunti come sensibile (R17). E' la via accessibile al testo: le maniglie
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
        SensitiveSelectionContainer {
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

/**
 * La selezione di sistema (`SelectionContainer`) i cui "Copia" scrivono negli appunti **segnando il testo come sensibile** (R17, `EXTRA_IS_SENSITIVE`): un
 * codice o una password fotografati non finiscono nell'anteprima degli appunti ne' nella cronologia di Gboard e della tastiera Samsung. Prima solo "Copia" e
 * "Copia tutto" dell'app erano sensibili; il "Copia" del menu di sistema usava gli appunti di Compose, senza il segno. Qui gli appunti di Compose si sostituiscono
 * per la sola selezione del foglio, con la stessa copia, piu' il segno ([SensitiveClipboards]).
 */
@Composable
internal fun SensitiveSelectionContainer(content: @Composable () -> Unit) {
  val clipboard = LocalClipboard.current
  val sensitive = remember(clipboard) { SensitiveClipboards.wrap(clipboard) }
  CompositionLocalProvider(LocalClipboard provides sensitive) {
    SelectionContainer(content = content)
  }
}

/** Gli appunti di Compose con il segno "sensibile" su ogni testo che si copia (R17). */
internal object SensitiveClipboards {

  fun wrap(inner: Clipboard): Clipboard = object : Clipboard by inner {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
      clipEntry?.let { mark(it.clipData) }
      inner.setClipEntry(clipEntry)
    }
  }

  /** Il segno sul [ClipData], come `SensitiveClipboard.copy` (core-security): Android 13+ non ne mostra l'anteprima. */
  fun mark(clip: ClipData) {
    clip.description.extras = PersistableBundle().apply { putBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE, true) }
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
