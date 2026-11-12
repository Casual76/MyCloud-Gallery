package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.search.R

// I TASTI E I FOGLI DELLA PAGINA RICERCA E MODELLI (I2 della fase I; giro 1: CU-19 e le regole comuni). Gli anelli e la «i» sono quelli della galleria
// (`GalleryProgressRing`, `GalleryInfoButton` / `GalleryInfoDetail` di core-designsystem): qui restano solo il tasto con l'icona ([ActionButton]: mai un
// tasto di solo testo) e il foglio delle scelte ([ChoiceSheet]: un foglio e' per decidere; le spiegazioni si aprono nella pagina, sotto la «i»).

/** Un tasto con la sua icona (CU-19). [fill] per i tasti dei fogli, larghi quanto il foglio. */
@Composable
internal fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit, fill: Boolean = false, style: FluidButtonStyle = FluidButtonStyle.Tinted) {
  FluidButton(
    text = label,
    onClick = onClick,
    style = style,
    size = FluidButtonSize.Large,
    fillWidth = fill,
    grip = if (fill) FluidPressGrip.YieldsToScroll else FluidPressGrip.HoldsFinger,
    leading = { Icon(icon, contentDescription = null) },
  )
}

/**
 * Le scelte dietro un tocco su una riga: un foglio con il titolo, le frasi intere dello stato, le azioni che non sono quella principale ([actions],
 * ciascuna con la sua icona) e «Chiudi».
 */
@Composable
internal fun ChoiceSheet(
  title: String,
  message: String,
  onDismiss: () -> Unit,
  icon: ImageVector = Icons.Rounded.Info,
  actions: @Composable ColumnScope.() -> Unit = {},
) {
  GalleryConfirmSheet(visible = true, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = icon,
      tone = GallerySheetTone.Brand,
      title = title,
      message = message,
      actions = {
        actions()
        FluidButton(text = stringResource(R.string.models_close), onClick = onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}
