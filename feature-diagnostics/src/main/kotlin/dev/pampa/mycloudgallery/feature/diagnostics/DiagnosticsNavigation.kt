package dev.pampa.mycloudgallery.feature.diagnostics

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import kotlinx.serialization.Serializable

@Serializable
data object DiagnosticsGraphRoute

@Serializable
data object DiagnosticsHomeRoute

/**
 * La Diagnostica (scheletro P0, schermate P8: sezioni del §2.6, test di connessione, esportazione con
 * anteprima e ACTION_CREATE_DOCUMENT). Nessuna uscita verso altre funzioni: si torna indietro e basta.
 */
fun NavGraphBuilder.diagnosticsGraph(nav: NavController) {
  navigation<DiagnosticsGraphRoute>(startDestination = DiagnosticsHomeRoute) {
    composable<DiagnosticsHomeRoute> {
      DiagnosticsRoute(onBack = { nav.popBackStack() })
    }
  }
}
