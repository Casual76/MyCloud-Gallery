package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.viewer.R

/**
 * Il foglio di "Salva come nuova foto" **dai Nascosti** (I6, docs/review/6-fase6.md): il ritaglio nel telefono e' una foto pubblica, la vedono Galleria di
 * Samsung e le altre app che leggono le foto, anche se la foto da cui viene e' nascosta. Il foglio lo dice prima, con il nome che avra' ("Ritaglio.png",
 * non quello del file d'origine), e il salvataggio parte solo dal tocco su "Salva". E' un foglio di conferma del design system, con la sua difesa dal
 * tapjacking.
 */
@Composable
internal fun CutoutHiddenSaveSheet(
  visible: Boolean,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val title = stringResource(R.string.cutout_hidden_sheet_title)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onCancel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.SaveAlt,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.cutout_hidden_sheet_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.cutout_hidden_sheet_save),
          onClick = onConfirm,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.cutout_hidden_sheet_cancel),
          onClick = onCancel,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}
