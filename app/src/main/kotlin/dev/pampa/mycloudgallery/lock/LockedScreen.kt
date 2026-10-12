package dev.pampa.mycloudgallery.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.LockedScreenState

/**
 * La schermata dell'app bloccata (AppLockGate di core-security, alla radice). Mentre e' visibile il
 * contenuto dell'app non e' nemmeno in composizione.
 *
 * Fail closed: senza un blocco schermo del telefono l'app resta chiusa e propone le impostazioni di
 * sicurezza; i tentativi sbagliati li conta e li limita il sistema (E-S4), qui si dice solo cosa succede.
 */
@Composable
fun LockedScreen(state: LockedScreenState) {
  FluidScreen(
    title = stringResource(R.string.locked_title),
    subtitle = stringResource(R.string.locked_subtitle),
  ) {
    item {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = LockPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LockGap),
      ) {
        GalleryIllustration(icon = Icons.Rounded.Lock, tone = GalleryIllustrationTone.Brand)
        when (state.availability) {
          AuthAvailability.AVAILABLE -> FluidButton(
            text = stringResource(R.string.locked_unlock),
            onClick = state.onUnlock,
            size = FluidButtonSize.Large,
            loading = state.isAuthenticating,
            enabled = !state.isAuthenticating,
          )
          AuthAvailability.NO_SCREEN_LOCK, AuthAvailability.UNSUPPORTED -> FluidButton(
            text = stringResource(R.string.locked_open_security_settings),
            onClick = state.onOpenSecuritySettings,
            size = FluidButtonSize.Large,
            style = FluidButtonStyle.Tinted,
          )
          AuthAvailability.TEMPORARILY_UNAVAILABLE -> FluidButton(
            text = stringResource(R.string.locked_unlock),
            onClick = state.onUnlock,
            size = FluidButtonSize.Large,
            style = FluidButtonStyle.Tinted,
          )
        }
      }
    }
    val note = when {
      state.availability == AuthAvailability.NO_SCREEN_LOCK || state.availability == AuthAvailability.UNSUPPORTED ->
        R.string.locked_no_screen_lock
      state.availability == AuthAvailability.TEMPORARILY_UNAVAILABLE -> R.string.locked_temporarily_unavailable
      state.lastResult is AuthResult.LockedOut -> R.string.locked_locked_out
      else -> null
    }
    if (note != null) item { FluidSectionFootnote(text = stringResource(note)) }
  }
}

private val LockPadding = 24.dp
private val LockGap = 16.dp
