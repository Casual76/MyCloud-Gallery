package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.feature.onboarding.R

/**
 * Il punto in cui un foglio di conferma dell'onboarding si apre davvero. I fogli del design system sono pop-up in vetro che
 * vivono in un `FluidGlassModalHost` alla radice: durante l'onboarding la radice non ne ha uno (la Libreria non c'e'), e senza
 * host il foglio **non si apre**. Se un host c'e' gia' lo si usa, se no se ne mette uno qui, sopra la sola pagina corrente
 * (la stessa regola di `SettingsHost`). Pubblico per la pagina della radice "Keystore non disponibile" di `:app` (R7), che non ha
 * un host suo.
 */
@Composable
fun OnboardingSheetHost(content: @Composable () -> Unit) {
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
 * La conferma di "Configura di nuovo" dalla pagina "fuori dalla famiglia" (R7 della revisione 5B). Il gesto toglie account,
 * password e chiavi di questo dispositivo: non parte con un tocco solo. Il pulsante di conferma e' uno, dice cosa fa; "Annulla"
 * e chiudere il foglio non confermano niente. Mentre e' aperto le sovrapposizioni delle altre app sono nascoste.
 *
 * Su un admin ([holdsKey]) il foglio dice cosa si perde (la chiave di famiglia: senza non si approvano dispositivi) e come si
 * riprende (il kit di recupero con il suo codice, "Recupera l'admin" dal benvenuto). Se la causa e' sconosciuta
 * ([unknownCause], "lo stato non c'e' piu'") ricorda che puo' essere un guasto passeggero del telefono.
 *
 * Pubblico perche' lo usa anche la pagina della radice "Keystore non disponibile" di `:app` (R7): la stessa conferma, con lo stesso
 * avviso dell'admin, per l'uscita secondaria di chi vuole davvero ricominciare.
 */
@Composable
fun StartOverSheet(
  visible: Boolean,
  holdsKey: Boolean,
  unknownCause: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  OverlayShield(enabled = visible)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.removed_sheet_title)) {
    StartOverSheetContent(holdsKey = holdsKey, unknownCause = unknownCause, onDismiss = onDismiss, onConfirm = onConfirm)
  }
}

/** Il contenuto del foglio, senza il pop-up (che e' `GalleryConfirmSheet`, provato dal design system): cosi' si prova da solo. */
@Composable
internal fun StartOverSheetContent(
  holdsKey: Boolean,
  unknownCause: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.Warning,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.removed_sheet_title),
    message = stringResource(
      when {
        holdsKey && unknownCause -> R.string.removed_sheet_message_admin_other
        holdsKey -> R.string.removed_sheet_message_admin
        else -> R.string.removed_sheet_message
      },
    ),
    actions = {
      FluidButton(
        text = stringResource(R.string.removed_sheet_confirm),
        onClick = onConfirm,
        style = FluidButtonStyle.Destructive,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.action_cancel),
        onClick = onDismiss,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}
