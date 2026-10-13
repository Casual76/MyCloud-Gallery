package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone

/**
 * Il foglio "Elimina dal telefono" (D14). In Fase 3 si elimina **solo dal telefono, nel cestino di sistema** (30 giorni,
 * ripristinabile da "Eliminati di recente"), e il foglio lo dice prima di tutto il resto: **non c'e' ancora una copia
 * sul NAS**. Niente parte finche' l'utente non preme il bottone; poi la conferma vera la chiede il dialogo di sistema.
 *
 * E' un foglio di conferma del design system, con la sua difesa dal tapjacking (`filterTouchesWhenObscured`).
 */
@Composable
internal fun DeleteSheet(
  visible: Boolean,
  isVideo: Boolean,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val title = stringResource(if (isVideo) R.string.viewer_delete_title_video else R.string.viewer_delete_title_photo)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onCancel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.DeleteForever,
      tone = GallerySheetTone.Destructive,
      title = title,
      message = stringResource(R.string.viewer_delete_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_delete_confirm),
          onClick = onConfirm,
          style = FluidButtonStyle.Destructive,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.viewer_cancel),
          onClick = onCancel,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}

/**
 * Il foglio "Apri in Mappe" (D18): la posizione esce dall'app **solo** con questa conferma. La Fase 3 non genera
 * traffico di rete; l'app Mappe, invece, potrebbe usarla, e il foglio lo dice.
 */
@Composable
internal fun MapsSheet(
  visible: Boolean,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val title = stringResource(R.string.viewer_maps_title)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onCancel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Map,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.viewer_maps_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_maps_confirm),
          onClick = onConfirm,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.viewer_cancel),
          onClick = onCancel,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}
