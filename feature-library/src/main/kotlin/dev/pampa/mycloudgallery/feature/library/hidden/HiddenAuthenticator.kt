package dev.pampa.mycloudgallery.feature.library.hidden

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
 * Chi chiede la credenziale di sistema ai Nascosti. Di norma e' nullo e si costruisce dall'Activity
 * ([rememberHiddenAuthenticator]); i test e gli screenshot ne forniscono uno finto, e `:app` puo' passare quello che ha
 * gia' per il blocco dell'app.
 */
val LocalHiddenAuthenticator = staticCompositionLocalOf<SystemCredentialAuthenticator?> { null }

/**
 * Il `BiometricPrompt` della finestra corrente, o null se l'Activity non e' una `FragmentActivity` (la `MainActivity` lo
 * e', apposta). Con null i Nascosti dicono che lo sblocco non e' disponibile e restano chiusi: mai una scorciatoia senza
 * credenziale.
 */
@Composable
internal fun rememberHiddenAuthenticator(): SystemCredentialAuthenticator? {
  val provided = LocalHiddenAuthenticator.current
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
