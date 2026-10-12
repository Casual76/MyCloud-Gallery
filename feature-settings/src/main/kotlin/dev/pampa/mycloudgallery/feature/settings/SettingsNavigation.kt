package dev.pampa.mycloudgallery.feature.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import kotlinx.serialization.Serializable

@Serializable
data object SettingsGraphRoute

@Serializable
data object SettingsHomeRoute

/**
 * Le Impostazioni (scheletro P0, schermate P7: Dispositivi, Famiglia, Account NAS, Rete e VPN, Sicurezza,
 * avviso sul backup, "Da completare").
 *
 * Le uscite verso altre funzioni sono callback di :app, perche' i feature non si conoscono:
 * [openDiagnostics] apre la Diagnostica, [resumeStep] riapre un passo saltato dell'onboarding.
 * [developerSection] e' la sezione "Sviluppo" che :app passa **solo in debug** (catalogo dei componenti,
 * stati finti dello scaffold); in release e' null e non esiste.
 */
fun NavGraphBuilder.settingsGraph(
  nav: NavController,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)? = null,
) {
  navigation<SettingsGraphRoute>(startDestination = SettingsHomeRoute) {
    composable<SettingsHomeRoute> {
      SettingsRoute(
        onBack = { nav.popBackStack() },
        openDiagnostics = openDiagnostics,
        resumeStep = resumeStep,
        developerSection = developerSection,
      )
    }
  }
}
