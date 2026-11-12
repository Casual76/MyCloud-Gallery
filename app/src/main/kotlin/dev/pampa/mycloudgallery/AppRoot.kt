package dev.pampa.mycloudgallery

import dev.pampa.mycloudgallery.release.ReleaseRoot
import dev.pampa.mycloudgallery.release.ReleaseRootViewModel
import dev.pampa.mycloudgallery.release.ReleaseUi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconFigure
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconViewModel
import dev.pampa.mycloudgallery.feature.update.ui.LocalUpdateAppIcon
import dev.pampa.mycloudgallery.feature.update.ui.UpdateInline
import dev.pampa.mycloudgallery.feature.update.ui.UpdateLifecycleEffect
import dev.pampa.mycloudgallery.feature.update.ui.UpdatePromptHost
import dev.pampa.mycloudgallery.feature.update.ui.UpdatesViewModel
import dev.pampa.mycloudgallery.main.LocalTabBarNotice
import androidx.compose.runtime.withFrameNanos
import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockGate
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.lock.LockedScreen
import dev.pampa.mycloudgallery.navigation.AppNavHost
import dev.pampa.mycloudgallery.navigation.KeystoreActions
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
  releaseViewModel: ReleaseRootViewModel = hiltViewModel(),
  updatesViewModel: UpdatesViewModel = hiltViewModel(),
  appIconViewModel: AppIconViewModel = hiltViewModel(),
) {
  // Pacchetto U (giro 1, UU-1/UU-3): il ritorno davanti (dalla pagina del permesso, dalla finestra di Android) riprende l'aggiornamento, anche con l'app bloccata.
  UpdateLifecycleEffect(updatesViewModel)
  AppLockGate(
    controller = lockController,
    authenticator = authenticator,
    lockedContent = {
      LockedScreen(it)
      // Avvio istantaneo: con il blocco app la prima schermata e' questa; il lavoro rimandato parte dopo il suo primo fotogramma.
      LaunchedEffect(Unit) {
        withFrameNanos { }
        AfterFirstDraw.open()
      }
    },
  ) {
    AvvioTrace.markOnce(AvvioTrace.UNLOCKED)
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
        // R7: la pagina "Keystore non disponibile".
        keystore = KeystoreActions(
          state = rootViewModel.keystore,
          retry = rootViewModel::retryKeystore,
          askStartOver = rootViewModel::askKeystoreStartOver,
          cancelStartOver = rootViewModel::cancelKeystoreStartOver,
          confirmStartOver = rootViewModel::confirmKeystoreStartOver,
        ),
      )
    }
    // Fase 10 (P13, §2.8, E-REL5, E-REL6): il manifest firmato alla radice. Con la versione fermata solo la sua pagina e la Diagnostica; in sola lettura l'avviso "Aggiorna".
    val release by releaseViewModel.status.collectAsStateWithLifecycle()
    val releaseUi = ReleaseUi.of(release)
    // Pacchetto U (12/11/2026; giro 1, UU-5/UU-7): l'aggiornamento dentro l'app. Con la versione fermata sta nella sua pagina (e' il rimedio); altrimenti
    // l'avviso appoggiato sopra la barra delle schede della galleria (lo posa `MainScaffold`, mai sopra la pillola, il visore o le altre pagine). La
    // figura e' l'icona vera dell'app, quella scelta in «Icona dell'app».
    val updates by updatesViewModel.state.collectAsStateWithLifecycle()
    val iconChoice by appIconViewModel.choice.collectAsStateWithLifecycle()
    val iconArt = appIconViewModel.art
    val choice = iconChoice
    val appIcon: (@Composable (Dp) -> Unit)? = if (choice != null && iconArt != null) {
      remember(choice, iconArt) { { side: Dp -> AppIconFigure(choice = choice, art = iconArt, side = side) } }
    } else {
      null
    }
    val notice: @Composable (Modifier) -> Unit = remember(updatesViewModel) { { modifier -> UpdatePromptHost(updatesViewModel, modifier) } }
    CompositionLocalProvider(LocalUpdateAppIcon provides appIcon, LocalTabBarNotice provides notice) {
      Box(modifier = Modifier.fillMaxSize()) {
        // In sola lettura l'avviso del manifest ha «Aggiorna» quando c'e' una versione nuova: il tocco la scarica (l'avviso dell'aggiornamento mostra l'anello).
        ReleaseRoot(releaseUi, update = { UpdateInline(updatesViewModel) }, onUpdate = if (updates.found != null) updatesViewModel.actions.install else null) {
          AppNavHost(destination = destination, status = status, actions = actions)
        }
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
