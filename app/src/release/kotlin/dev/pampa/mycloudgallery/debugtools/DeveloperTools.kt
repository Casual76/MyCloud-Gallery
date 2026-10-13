package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.gallery.SyntheticLibraryKit

/**
 * Release: nessuno strumento di sviluppo. Stessa forma della versione debug (src/debug), tutto vuoto: il catalogo dei
 * componenti, la Libreria sintetica e la famiglia pronta per l'emulatore non esistono, quindi non entrano nell'APK
 * (tools/check.ps1 lo verifica sul dex).
 */
internal object DeveloperTools {

  /** Nessuna Libreria sintetica in release: il menu "Vista" non la offre. */
  fun syntheticKit(): SyntheticLibraryKit? = null

  fun syntheticThumbnails(): ThumbnailSource = NoThumbnailSource

  /** Nessuna scorciatoia: lo stato del dispositivo e' sempre quello vero. */
  @Suppress("UNUSED_PARAMETER")
  fun familyOverride(context: Context): FamilySetupRepository? = null

  fun NavGraphBuilder.destinations(nav: NavController) = Unit

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = null
}
