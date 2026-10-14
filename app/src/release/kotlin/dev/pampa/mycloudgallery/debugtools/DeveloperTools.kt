package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.gallery.DebugLibraryKit
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

  /** Nessun NAS sintetico in release (Fase 4A, P11): la voce di Impostazioni → Sviluppo non esiste. */
  fun nasLibrary(): DebugLibraryKit? = null

  /** Nessuna scorciatoia: lo stato del dispositivo e' sempre quello vero. */
  @Suppress("UNUSED_PARAMETER")
  fun familyOverride(context: Context): FamilySetupRepository? = null

  /** Nessuna recinzione delle prove (D33 vale solo in debug): `ShareFence` non entra nell'APK di release. */
  fun shareGuard(): ShareGuard? = null

  fun NavGraphBuilder.destinations(nav: NavController) = Unit

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = null
}
