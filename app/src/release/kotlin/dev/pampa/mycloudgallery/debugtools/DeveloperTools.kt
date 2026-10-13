package dev.pampa.mycloudgallery.debugtools

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState

/**
 * Release: nessuno strumento di sviluppo. Stessa forma della versione debug (src/debug), tutto vuoto: il catalogo dei
 * componenti non e' referenziato da niente, quindi non entra nell'APK (tools/check.ps1 lo verifica sul dex).
 */
internal object DeveloperTools {

  /** Nessuna Libreria sintetica in release: la scheda Libreria resta quella vuota, sui null-object. */
  @Composable
  fun libraryTabState(): LibraryTabState? = null

  fun NavGraphBuilder.destinations(nav: NavController) = Unit

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = null
}
