package dev.pampa.mycloudgallery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockGate
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.lock.LockedScreen
import dev.pampa.mycloudgallery.navigation.AppNavHost
import dev.pampa.mycloudgallery.navigation.RootNavActions
import dev.pampa.mycloudgallery.root.RootViewModel

/**
 * La radice dell'interfaccia: il blocco app ([AppLockGate], core-security) **sopra** tutta la navigazione.
 * Con l'app bloccata la navigazione non e' nemmeno in composizione (nessuna foto in memoria della UI, niente
 * per TalkBack); allo sblocco riprende da dove era. Le impostazioni del blocco le sceglie l'utente in
 * Impostazioni → Sicurezza (P7) e arrivano al controller attraverso [RootViewModel].
 *
 * Sotto il gate, [AppNavHost] parte dalla destinazione che [RootViewModel] ricava dallo stato del dispositivo.
 */
@Composable
internal fun AppRoot(
  lockController: AppLockController,
  authenticator: SystemCredentialAuthenticator,
  rootViewModel: RootViewModel = hiltViewModel(),
) {
  AppLockGate(
    controller = lockController,
    authenticator = authenticator,
    lockedContent = { LockedScreen(it) },
  ) {
    val destination by rootViewModel.destination.collectAsStateWithLifecycle()
    val status by rootViewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(rootViewModel, context) {
      RootNavActions(
        openWireGuard = {
          val intent = rootViewModel.wireGuardIntent()
          if (intent == null) {
            false
          } else {
            try {
              context.startActivity(intent)
              true
            } catch (_: ActivityNotFoundException) {
              false
            }
          }
        },
        deviceKeyQr = rootViewModel::deviceKeyQr,
        watchApproval = rootViewModel::watchApproval,
        reconfigure = rootViewModel::reconfigure,
        // "Continua piu' tardi" senza passi locali da fare: la configurazione e' salvata, l'app va in secondo piano.
        pause = { context.findActivity()?.moveTaskToBack(true) },
      )
    }
    AppNavHost(destination = destination, status = status, actions = actions)
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
