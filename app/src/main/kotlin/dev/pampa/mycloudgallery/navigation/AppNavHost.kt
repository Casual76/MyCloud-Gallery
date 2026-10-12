package dev.pampa.mycloudgallery.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.diagnostics.diagnosticsGraph
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingResumeRoute
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.onboarding.onboardingGraph
import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsPasswordRoute
import dev.pampa.mycloudgallery.feature.settings.settingsGraph
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.root.BlockedScreen
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.root.WipedScreen
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.StatusAction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** La galleria: schede, indicatore, Impostazioni. */
@Serializable
data object MainRoute

@Serializable
data class AwaitingApprovalRoute(val code: String)

@Serializable
data object WipedRoute

/** Il motivo viaggia per nome: `core-family` non conosce kotlinx.serialization. */
@Serializable
data class BlockedRoute(val reasonName: String) {
  val reason: BlockReason get() = BlockReason.entries.firstOrNull { it.name == reasonName } ?: BlockReason.NEEDS_APP_UPDATE
}

/**
 * La navigazione dell'app. La destinazione di partenza la decide lo stato del dispositivo ([destination],
 * da `RootViewModel`): quando cambia (onboarding finito, approvazione arrivata, azzeramento) il grafo
 * riparte dalla nuova destinazione, senza una pila vecchia da cui "tornare indietro" in uno stato che non
 * vale piu'.
 *
 * I tre `feature-*` non si conoscono: ogni passaggio fra loro e' una callback scritta qui.
 *
 * Le transizioni sono opache e laterali (regola 3 di Fluid: una pagina in arrivo non sfuma mai), con le
 * molle di `FluidMotion`.
 */
@Composable
fun AppNavHost(
  destination: RootDestination,
  status: GlobalStatus,
  actions: RootNavActions,
) {
  if (destination == RootDestination.Loading) {
    FluidScreenSurface(modifier = Modifier.fillMaxSize()) {}
    return
  }
  key(destination) {
    val nav = rememberNavController()
    NavHost(
      navController = nav,
      startDestination = startRoute(destination),
      enterTransition = { enter() },
      exitTransition = { exit() },
      popEnterTransition = { popEnter() },
      popExitTransition = { popExit() },
    ) {
      composable<MainRoute> {
        MainDestination(nav = nav, status = status, openWireGuard = actions.openWireGuard)
      }
      composable<AwaitingApprovalRoute> { entry ->
        AwaitingDestination(code = entry.toRoute<AwaitingApprovalRoute>().code, actions = actions)
      }
      composable<WipedRoute> {
        WipedScreen(onReconfigure = actions.reconfigure)
      }
      composable<BlockedRoute> { entry ->
        BlockedScreen(reason = entry.toRoute<BlockedRoute>().reason)
      }
      // L'uscita dall'onboarding la decide lo stato (SetupState): onDone non deve fare niente. "Continua piu' tardi"
      // quando non c'e' un passo locale da fare (ingresso, recupero) manda l'app in secondo piano, a configurazione salvata.
      onboardingGraph(nav = nav, onDone = {}, onPause = actions.pause)
      settingsGraph(
        nav = nav,
        openDiagnostics = { nav.navigate(DiagnosticsGraphRoute) },
        resumeStep = { step -> nav.navigate(OnboardingResumeRoute(step)) },
        developerSection = DeveloperTools.settingsSection(nav),
      )
      diagnosticsGraph(
        nav = nav,
        appActions = DIAGNOSTICS_APP_ACTIONS,
        onAppAction = { action -> onDiagnosticsAction(nav, action) },
      )
      with(DeveloperTools) { destinations(nav) }
    }
  }
}

/** Cio' che la navigazione chiede alla radice (RootViewModel e Android). */
class RootNavActions(
  /** "Apri WireGuard": falso se non e' installato. */
  val openWireGuard: () -> Boolean,
  val deviceKeyQr: () -> String?,
  val watchApproval: () -> Flow<TrustStatus>,
  val reconfigure: () -> Unit,
  val pause: () -> Unit,
)

/**
 * L'attesa dell'approvazione (E-S14): codice e QR della chiave (pagina di feature-onboarding). Il registro si rilegge
 * ogni 10 secondi **solo** mentre la pagina e' visibile (`repeatOnLifecycle(STARTED)`): niente lavoro in background.
 * Quando l'admin approva, lo stato del dispositivo cambia e la radice porta alla Libreria.
 */
@Composable
private fun AwaitingDestination(code: String, actions: RootNavActions) {
  val qr = remember { actions.deviceKeyQr() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      actions.watchApproval().collect { }
    }
  }
  AwaitingApprovalScreen(code = code, deviceKeyQr = qr)
}

/** Le azioni della Diagnostica che portano fuori dalla Diagnostica: le sa fare solo :app (i feature non si conoscono). */
private val DIAGNOSTICS_APP_ACTIONS = setOf(DiagAction.REENTER_NAS_PASSWORD, DiagAction.OPEN_DEVICES, DiagAction.RESUME_ONBOARDING)

private fun onDiagnosticsAction(nav: NavHostController, action: DiagAction) {
  when (action) {
    DiagAction.REENTER_NAS_PASSWORD -> nav.navigate(SettingsPasswordRoute)
    DiagAction.OPEN_DEVICES -> nav.navigate(SettingsDevicesRoute)
    DiagAction.RESUME_ONBOARDING -> nav.navigate(SettingsGraphRoute)
    else -> Unit
  }
}

@Composable
private fun MainDestination(nav: NavHostController, status: GlobalStatus, openWireGuard: () -> Boolean) {
  var tab by rememberSaveable { mutableStateOf(GalleryTab.Library) }
  MainScaffold(
    status = status,
    selectedTab = tab,
    onSelectTab = { tab = it },
    onStatusClick = {
      when (status.action) {
        StatusAction.OPEN_WIREGUARD -> if (!openWireGuard()) nav.navigate(DiagnosticsGraphRoute)
        StatusAction.ENTER_PASSWORD -> nav.navigate(SettingsPasswordRoute)
        StatusAction.OPEN_DIAGNOSTICS -> nav.navigate(DiagnosticsGraphRoute)
      }
    },
    onOpenSettings = { nav.navigate(SettingsGraphRoute) },
  )
}

/** La rotta di partenza per ogni destinazione della radice. */
internal fun startRoute(destination: RootDestination): Any = when (destination) {
  RootDestination.Loading, RootDestination.Onboarding -> OnboardingGraphRoute
  is RootDestination.AwaitingApproval -> AwaitingApprovalRoute(destination.code)
  RootDestination.Main -> MainRoute
  RootDestination.Wiped -> WipedRoute
  is RootDestination.Blocked -> BlockedRoute(destination.reason.name)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
  slideInHorizontally(FluidMotion.intOffset()) { width -> width }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
  slideOutHorizontally(FluidMotion.intOffset()) { width -> -(width * FluidMotion.PeerSlideFraction).toInt() }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
  slideInHorizontally(FluidMotion.intOffset()) { width -> -(width * FluidMotion.PeerSlideFraction).toInt() }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
  slideOutHorizontally(FluidMotion.intOffset()) { width -> width }
