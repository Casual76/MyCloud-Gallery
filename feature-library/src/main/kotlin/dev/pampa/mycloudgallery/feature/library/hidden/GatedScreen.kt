package dev.pampa.mycloudgallery.feature.library.hidden

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.core.security.screen.RecentsPrivacy
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.feature.library.R

/**
 * Le parole che cambiano fra le schermate dietro lo stesso cancello (Nascosti, Eliminati di recente): il resto dei
 * testi di sblocco (rifiuti, troppi tentativi, nessun blocco schermo) e' lo stesso.
 */
@Immutable
internal class GateTexts(
  @StringRes val promptTitle: Int,
  @StringRes val lockedTitle: Int,
  @StringRes val lockedDetail: Int,
  @StringRes val cancelledDetail: Int,
)

internal val HiddenGateTexts = GateTexts(
  promptTitle = R.string.hidden_prompt_title,
  lockedTitle = R.string.hidden_locked_title,
  lockedDetail = R.string.hidden_locked_detail,
  cancelledDetail = R.string.hidden_cancelled_detail,
)

internal val TrashGateTexts = GateTexts(
  promptTitle = R.string.trash_prompt_title,
  lockedTitle = R.string.trash_locked_title,
  lockedDetail = R.string.trash_locked_detail,
  cancelledDetail = R.string.trash_cancelled_detail,
)

/**
 * Tutto cio' che una **schermata protetta** fa con la finestra e con il ciclo di vita, sia per i Nascosti sia per gli
 * Eliminati di recente (E-S5, E-H3):
 *
 *  - `SecureScreen` **per tutto il tempo in cui la pagina e' in composizione**, anche chiusa: la schermata di sblocco non
 *    mostra niente di riservato, ma la finestra non ha motivo di essere fotografabile mentre si e' li';
 *  - i recenti oscurati (`RecentsPrivacy`), da riportare com'erano all'uscita;
 *  - il ciclo di vita: la pagina visibile ([onShown]) o no ([onHidden]; una rotazione non conta come uscita);
 *  - lo sblocco automatico, una volta per ogni ingresso; dopo un rifiuto si riprova solo col pulsante.
 *
 * Restituisce l'azione "Sblocca": chiede la credenziale con [authenticator], o, se non c'e' modo di mostrare la schermata
 * di sistema, dichiara lo sblocco non disponibile (mai una scorciatoia senza credenziale).
 *
 * @param locked la pagina e' chiusa e non ha ancora chiesto la credenziale (stato "Locked").
 * @param authenticating la schermata di sistema e' aperta.
 */
@Composable
internal fun GatedWindow(
  texts: GateTexts,
  authenticator: SystemCredentialAuthenticator?,
  keepRecentsHidden: () -> Boolean,
  locked: Boolean,
  authenticating: Boolean,
  onShown: () -> Unit,
  onHidden: () -> Unit,
  onUnlock: (SystemCredentialAuthenticator, AuthPromptText) -> Unit,
  onUnlockUnavailable: () -> Unit,
): () -> Unit {
  SecureScreen()

  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  DisposableEffect(activity) {
    if (activity != null) RecentsPrivacy.instance.setHidden(activity, true)
    onDispose { if (activity != null) RecentsPrivacy.instance.setHidden(activity, keepRecentsHidden()) }
  }

  val lifecycle = LocalLifecycleOwner.current.lifecycle
  // `started` diventa vero solo DOPO `onShown()`: lo sblocco automatico non deve mai precedere la valutazione
  // dell'ingresso (che riporta un rifiuto a "chiuso"), qualunque sia l'ordine in cui Compose avvia gli effetti.
  var started by remember { mutableStateOf(false) }
  var promptedThisEntry by remember { mutableStateOf(false) }
  val currentAuthenticating = rememberUpdatedState(authenticating)
  DisposableEffect(lifecycle, activity) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> {
          onShown()
          started = true
        }
        Lifecycle.Event.ON_STOP -> {
          started = false
          // Se a portare la pagina in secondo piano e' la schermata di sistema (su Android 10 il ripiego sul PIN e' una
          // activity), il ritorno dopo "annulla" non e' un ingresso nuovo: niente sblocco automatico in ciclo.
          if (!currentAuthenticating.value) promptedThisEntry = false
          // Una rotazione ricrea l'activity ma non e' un'uscita: la tolleranza non deve cominciare.
          if (activity?.isChangingConfigurations != true) onHidden()
        }
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      onHidden()
    }
  }

  val prompt = AuthPromptText(
    title = stringResource(texts.promptTitle),
    subtitle = stringResource(R.string.hidden_prompt_subtitle),
  )
  val unlock: () -> Unit = {
    if (authenticator != null) onUnlock(authenticator, prompt) else onUnlockUnavailable()
  }

  // Sblocco automatico: una volta per ogni ingresso, e solo se la pagina e' davvero in primo piano.
  LaunchedEffect(locked, started) {
    if (locked && started && !promptedThisEntry) {
      promptedThisEntry = true
      unlock()
    }
  }
  return unlock
}

/** Apre le impostazioni di sicurezza del telefono (per chi non ha un blocco schermo). */
@Composable
internal fun rememberOpenSecuritySettings(): () -> Unit {
  val context = LocalContext.current
  return remember(context) {
    {
      try {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (_: ActivityNotFoundException) {
        // Nessuna schermata di impostazioni: non c'e' altro da fare.
      }
    }
  }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
