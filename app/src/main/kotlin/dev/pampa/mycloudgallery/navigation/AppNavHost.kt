package dev.pampa.mycloudgallery.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.diagnostics.diagnosticsGraph
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingResumeRoute
import dev.pampa.mycloudgallery.feature.onboarding.onboardingGraph
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.feature.settings.settingsGraph
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.root.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.root.BlockedScreen
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.root.WipedScreen
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.StatusAction
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
  openWireGuard: () -> Boolean,
) {
  if (destination == RootDestination.Loading) {
    FluidScreenSurface(modifier = Modifier.fillMaxSize()) {}
    return
  }
  // Calcolati qui, nella composizione: il costruttore del grafo qui sotto non e' @Composable.
  val onboardingDone = DeveloperTools.onboardingDone()
  val stateSwitcher = DeveloperTools.stateSwitcher
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
        MainDestination(nav = nav, status = status, openWireGuard = openWireGuard)
      }
      composable<AwaitingApprovalRoute> { entry ->
        AwaitingApprovalScreen(code = entry.toRoute<AwaitingApprovalRoute>().code, extra = stateSwitcher)
      }
      composable<WipedRoute> {
        WipedScreen(extra = stateSwitcher)
      }
      composable<BlockedRoute> { entry ->
        BlockedScreen(reason = entry.toRoute<BlockedRoute>().reason, extra = stateSwitcher)
      }
      onboardingGraph(nav = nav, onDone = onboardingDone)
      settingsGraph(
        nav = nav,
        openDiagnostics = { nav.navigate(DiagnosticsGraphRoute) },
        resumeStep = { step -> nav.navigate(OnboardingResumeRoute(step)) },
        developerSection = DeveloperTools.settingsSection(nav),
      )
      diagnosticsGraph(nav = nav)
      with(DeveloperTools) { destinations(nav) }
    }
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
        StatusAction.ENTER_PASSWORD -> nav.navigate(SettingsGraphRoute)
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
