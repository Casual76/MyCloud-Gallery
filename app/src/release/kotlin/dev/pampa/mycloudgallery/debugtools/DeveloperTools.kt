package dev.pampa.mycloudgallery.debugtools

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * Release: nessuno strumento di sviluppo. Stessa forma della versione debug (src/debug), tutto vuoto: il
 * catalogo dei componenti e i fake non sono referenziati da niente, quindi non entrano nell'APK
 * (tools/check.ps1 lo verifica sul dex).
 */
internal object DeveloperTools {

  fun NavGraphBuilder.destinations(nav: NavController) = Unit

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = null

  val stateSwitcher: (@Composable () -> Unit)? = null

  /** In release l'uscita dall'onboarding e' guidata solo dallo stato (SetupState). */
  @Composable
  fun onboardingDone(): () -> Unit = {}
}
