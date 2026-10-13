package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.catalog.ComponentCatalogPlaceholder
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.gallery.SyntheticLibraryKit
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** SOLO debug: il catalogo dei componenti di core-designsystem. */
@Serializable
data object CatalogRoute

/**
 * SOLO build debug: gli strumenti di sviluppo.
 *
 * - l'ingresso al **Catalogo componenti** (Impostazioni → Sviluppo), come nella Fase 1;
 * - la **Libreria sintetica** (docs/fase3-progetto.md §1.4, §5.2): menu "Vista" → "Sintetica: 100.000", sopra il catalogo
 *   vero ma in un **database separato** (`gallery-synthetic.db`), con miniature a gradiente;
 * - la **famiglia pronta per lo smoke sull'emulatore** ([familyOverride]): solo se `adb` ha scritto il marcatore.
 *
 * La versione release di questo file (src/release) non ha niente: nessuna di queste classi entra nell'APK di release
 * (tools/check.ps1 cerca il catalogo e i package `testing` nel dex di release).
 */
internal object DeveloperTools {

  /** La Libreria sintetica con il suo database separato. */
  fun syntheticKit(): SyntheticLibraryKit? = SyntheticCatalogKit

  /** I gradienti della Libreria sintetica, per la pipeline delle miniature vera (riferimenti `MediaRef.Synthetic`). */
  fun syntheticThumbnails(): ThumbnailSource = SyntheticThumbnailSource()

  /**
   * SOLO debug, SOLO con il marcatore: la famiglia "pronta" senza NAS, per provare la Libreria su un **emulatore** (che
   * un NAS non ce l'ha). Il marcatore `debug-family-ready.marker` sta nella cartella dei file esterni dell'app e lo crea
   * solo `adb shell` (`/sdcard/Android/data/<id>/files/`, privata dell'app da Android 11). Senza marcatore la debug e'
   * l'app vera, quella della verifica sul campo: non cambia niente. In release non esiste.
   */
  fun familyOverride(context: Context): FamilySetupRepository? {
    val marker = context.getExternalFilesDir(null)?.let { File(it, FAMILY_READY_MARKER) } ?: return null
    return if (marker.exists()) ReadyFamily else null
  }

  fun NavGraphBuilder.destinations(nav: NavController) {
    composable<CatalogRoute> {
      ComponentCatalogPlaceholder(onBack = { nav.popBackStack() })
    }
  }

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.debug_catalog_title),
        subtitle = stringResource(R.string.debug_catalog_detail),
        onClick = { nav.navigate(CatalogRoute) },
      )
    }
  }

  /** Il nome del marcatore della famiglia pronta (lo scrive solo `adb`). */
  const val FAMILY_READY_MARKER: String = "debug-family-ready.marker"

  private object ReadyFamily : FamilySetupRepository {
    override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false))
  }
}
