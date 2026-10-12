package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.feature.settings.R

/**
 * Il punto in cui i fogli di conferma delle Impostazioni si aprono davvero.
 *
 * I fogli del design system (`GalleryConfirmSheet`) sono pop-up in vetro che vivono in un
 * `FluidGlassModalHost` alla radice: senza un host nello scope **non si aprono** (e non vanno in crash).
 * Le Impostazioni non devono dipendere dal fatto che :app ne abbia uno, perche' le conferme sono quelle
 * che proteggono da un tocco sbagliato su "perso" o "reimposta": quindi se un host c'e' gia' lo si usa, se
 * non c'e' se ne mette uno qui, sopra la sola pagina corrente.
 */
@Composable
internal fun SettingsHost(content: @Composable () -> Unit) {
  if (LocalFluidGlassModalHostState.current != null) {
    content()
    return
  }
  val host = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .glassBackdropSource(backdrop)
          .fluidGlassModalObscured(),
      ) {
        content()
      }
      FluidGlassModalHost(state = host, backdrop = backdrop)
    }
  }
}

/**
 * Il foglio di conferma di una decisione che l'app non prende mai da sola.
 *
 * Mentre e' aperto la finestra e' protetta dalle sovrapposizioni (`OverlayShield`: un'altra app non puo'
 * far premere il bottone al posto dell'utente) e `GalleryConfirmSheet` scarta i tocchi "oscurati". Il
 * bottone di conferma e' uno solo e dice cosa fa; "Annulla" e' sempre li', e chiudere il foglio non
 * conferma niente.
 */
@Composable
internal fun ConfirmSheet(
  visible: Boolean,
  onDismiss: () -> Unit,
  icon: ImageVector,
  tone: GallerySheetTone,
  title: String,
  message: String?,
  confirmLabel: String,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
  confirmStyle: FluidButtonStyle = FluidButtonStyle.Destructive,
  confirmEnabled: Boolean = true,
  cancelLabel: String = stringResource(R.string.action_cancel),
  body: @Composable ColumnScope.() -> Unit = {},
) {
  OverlayShield(enabled = visible)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = icon,
      tone = tone,
      title = title,
      message = message,
      modifier = modifier,
      body = body,
      actions = {
        FluidButton(
          text = confirmLabel,
          onClick = onConfirm,
          style = confirmStyle,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = confirmEnabled,
        )
        FluidButton(
          text = cancelLabel,
          onClick = onDismiss,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}

/** Una nota dentro la pagina: un titolo corto e la frase, con il tono che dice quanto e' seria. */
@Composable
internal fun Note(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  tone: FluidTone = FluidTone.Info,
) {
  FluidInlineMessage(message = message, modifier = modifier, title = title, tone = tone)
}

/** L'icona di una riga di lista: va nello slot `leading` di `FluidListRow`, che la mette sulla piastrella del tono. */
@Composable
internal fun RowGlyph(icon: ImageVector) {
  Icon(imageVector = icon, contentDescription = null)
}

/** Un numero dentro la piastrella di una riga (i passi di una guida). */
@Composable
internal fun RowNumber(number: Int) {
  Text(
    text = number.toString(),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
  )
}

/** L'etichetta di stato di una riga: pillola colorata, mai il colore dell'intera riga. */
@Composable
internal fun StatusPill(label: String, tone: FluidTone) {
  FluidStatusBadge(label = label, tone = tone)
}
