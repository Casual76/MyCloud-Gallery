package dev.pampa.mycloudgallery.feature.settings.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator

/**
 * Chi chiede la credenziale di sistema del telefono (impronta, volto, PIN). Di norma e' nullo e si costruisce
 * dall'Activity ([rememberSystemAuthenticator]); i test e gli screenshot ne forniscono uno finto.
 */
internal val LocalSystemAuthenticator = staticCompositionLocalOf<SystemCredentialAuthenticator?> { null }

/**
 * Il `BiometricPrompt` della finestra corrente, o null se l'Activity non e' una `FragmentActivity` (la
 * `MainActivity` lo e', apposta: vedi `BiometricPromptAuthenticator`). Con null le azioni che chiedono lo
 * sblocco del telefono dicono che non si puo' e non partono: mai una scorciatoia senza credenziale.
 */
@Composable
internal fun rememberSystemAuthenticator(): SystemCredentialAuthenticator? {
  val provided = LocalSystemAuthenticator.current
  val context = LocalContext.current
  return remember(provided, context) {
    provided ?: context.findFragmentActivity()?.let { BiometricPromptAuthenticator(it) }
  }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
  is FragmentActivity -> this
  is ContextWrapper -> baseContext.findFragmentActivity()
  else -> null
}
