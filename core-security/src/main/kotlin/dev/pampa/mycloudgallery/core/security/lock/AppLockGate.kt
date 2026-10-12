package dev.pampa.mycloudgallery.core.security.lock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.mycloudgallery.core.security.R
import dev.pampa.mycloudgallery.core.security.screen.RecentsPrivacy
import dev.pampa.mycloudgallery.core.security.screen.findActivity
import kotlinx.coroutines.launch

/** Cosa sa la schermata di blocco (disegnata da chi usa il gate) per mostrarsi e reagire. */
class LockedScreenState(
  /** Il telefono puo' autenticare? Se non c'e' un blocco schermo ([AuthAvailability.NO_SCREEN_LOCK]) l'app resta chiusa. */
  val availability: AuthAvailability,
  /** L'esito dell'ultimo tentativo non riuscito, o `null` (nessun tentativo, o in corso). */
  val lastResult: AuthResult?,
  /** La schermata di sistema e' aperta in questo momento. */
  val isAuthenticating: Boolean,
  /** Riapre la schermata di sistema (pulsante "Sblocca"). */
  val onUnlock: () -> Unit,
  /** Apre le impostazioni di sicurezza del telefono (per chi non ha un blocco schermo). */
  val onOpenSecuritySettings: () -> Unit,
)

/**
 * Il cancello del blocco app (piano 1.5): mostra [content] solo con l'app sbloccata, altrimenti [lockedContent].
 * Si mette UNA volta alla radice dell'interfaccia, sopra la navigazione. Non e' ancora collegato all'app.
 *
 * - **Con l'app bloccata [content] non e' in composizione**: non solo coperto, proprio assente (niente
 *   disegno, niente TalkBack, niente foto in memoria della UI). Lo stato di `rememberSaveable` di [content]
 *   si conserva fra blocco e sblocco, cosi' si riprende dov'eri.
 * - **Sblocco automatico** una volta per ogni ritorno in primo piano; se l'utente annulla non si
 *   ripropone da solo (si usa [LockedScreenState.onUnlock]).
 * - **Ciclo di vita**: [AppLockController.onAppBackgrounded] su `ON_STOP` (non per la rotazione) e
 *   [AppLockController.onAppForegrounded] su `ON_START`.
 * - **Recenti**: finche' il blocco e' attivo, l'anteprima dell'app e' oscurata ([RecentsPrivacy]).
 * - **Nessun blocco schermo**: se il telefono non puo' autenticare, l'app RESTA chiusa e
 *   [LockedScreenState.availability] lo dice; non c'e' scorciatoia (fail closed).
 *
 * Le impostazioni arrivano al [controller] con [AppLockController.follow] (da un `LaunchedEffect` o dal
 * ViewModel). Finche' non ci sono ([AppLockState.LOADING]) non si disegna niente.
 *
 * Il [controller] e' unico per processo (singleton) e il gate presume UNA sola activity: con piu' activity
 * ogni passaggio dall'una all'altra conterebbe come uscita dall'app (con il timeout "subito" richiuderebbe).
 * Serve una `FragmentActivity` (la `MainActivity` dovra' estenderla) per `BiometricPrompt`.
 */
@Composable
fun AppLockGate(
  controller: AppLockController,
  authenticator: SystemCredentialAuthenticator,
  lockedContent: @Composable (LockedScreenState) -> Unit,
  promptText: AuthPromptText = AuthPromptText(
    title = stringResource(R.string.core_security_prompt_title),
    subtitle = stringResource(R.string.core_security_prompt_subtitle),
  ),
  content: @Composable () -> Unit,
) {
  val state by controller.state.collectAsState()
  val settings by controller.settings.collectAsState()
  val lifecycleOwner = LocalLifecycleOwner.current
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val scope = rememberCoroutineScope()
  val saveableStateHolder = rememberSaveableStateHolder()

  var availability by remember { mutableStateOf(authenticator.availability()) }
  var lastResult by remember { mutableStateOf<AuthResult?>(null) }
  var authenticating by remember { mutableStateOf(false) }
  var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  val autoPrompt = remember { AutoPromptGuard() }

  // Ciclo di vita -> controller.
  DisposableEffect(lifecycleOwner, activity) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> {
          started = true
          controller.onAppForegrounded()
        }
        Lifecycle.Event.ON_RESUME -> availability = authenticator.availability()
        Lifecycle.Event.ON_STOP -> {
          started = false
          if (activity?.isChangingConfigurations != true) controller.onAppBackgrounded()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Anteprima dei recenti oscurata finche' il blocco e' attivo.
  val lockEnabled = settings?.isEnabled == true
  DisposableEffect(activity, lockEnabled) {
    if (activity != null && lockEnabled) RecentsPrivacy.instance.setHidden(activity, true)
    onDispose { if (activity != null && lockEnabled) RecentsPrivacy.instance.setHidden(activity, false) }
  }

  val unlock: () -> Unit = {
    if (controller.beginAuthentication()) {
      authenticating = true
      lastResult = null
      scope.launch {
        var result: AuthResult = AuthResult.Interrupted
        try {
          result = authenticator.authenticate(promptText)
        } finally {
          controller.endAuthentication(result is AuthResult.Success)
          authenticating = false
        }
        lastResult = if (result is AuthResult.Success) null else result
      }
    }
  }

  // Sblocco automatico: una volta per ogni ritorno in primo piano, solo se il telefono puo' autenticare.
  LaunchedEffect(state, started, availability) {
    if (state != AppLockState.LOCKED || !started) {
      autoPrompt.done = false
      return@LaunchedEffect
    }
    if (availability == AuthAvailability.AVAILABLE && !autoPrompt.done) {
      autoPrompt.done = true
      unlock()
    }
  }

  when (state) {
    AppLockState.LOADING -> Unit
    AppLockState.LOCKED -> lockedContent(
      LockedScreenState(
        availability = availability,
        lastResult = lastResult,
        isAuthenticating = authenticating,
        onUnlock = unlock,
        onOpenSecuritySettings = {
          try {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          } catch (_: ActivityNotFoundException) {
            // Nessuna schermata di impostazioni: non c'e' altro da fare.
          }
        },
      ),
    )
    AppLockState.UNLOCKED -> saveableStateHolder.SaveableStateProvider(CONTENT_KEY) { content() }
  }
}

private class AutoPromptGuard {
  var done = false
}

private const val CONTENT_KEY = "app-lock-gate-content"
