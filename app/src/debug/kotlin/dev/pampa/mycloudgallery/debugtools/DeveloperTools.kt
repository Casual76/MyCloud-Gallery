package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.designsystem.catalog.ComponentCatalogPlaceholder
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.debugtools.probes.FieldProbeScreen
import dev.pampa.mycloudgallery.debugtools.probes.FieldProbesRoute
import dev.pampa.mycloudgallery.gallery.DebugLibraryKit
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
 * - la **famiglia pronta per lo smoke sull'emulatore** ([familyOverride]): solo se `adb` ha scritto il marcatore;
 * - il **NAS sintetico** (Fase 4A, P11, M1): Impostazioni → Sviluppo → "NAS sintetico", 20 mila file su un NAS finto in
 *   memoria passati dalla scansione vera, in un database separato ([SyntheticNasKit]); con "libreria mista" (G2) la
 *   griglia mostra tutti i segni di presenza e il piede lo stato del NAS scelto da `adb` ([MixedPresenceRepository]);
 * - la **recinzione delle share di prova** (D33, [shareGuard]);
 * - (Fase 5A) la **recinzione del telefono** del backup con gli account di prova (D13, [backupFence]);
 * - (Fase 5B, P0-B) le **prove guidate** T-09, T-14, T-15 e T-16 (`probes/`): Impostazioni → Sviluppo → "Prove guidate", sulle sole cartelle `mcg-prova`
 *   del telefono, con un resoconto di soli numeri. I loro componenti di prova stanno nel manifest di debug (`app/src/debug/AndroidManifest.xml`).
 *
 * La versione release di questo file (src/release) non ha niente: nessuna di queste classi entra nell'APK di release
 * (tools/check.ps1 cerca il catalogo e i package `testing` nel dex di release).
 */
internal object DeveloperTools {

  /** La Libreria sintetica con il suo database separato. */
  fun syntheticKit(): SyntheticLibraryKit? = SyntheticCatalogKit

  /** I gradienti della Libreria sintetica, per la pipeline delle miniature vera (riferimenti `MediaRef.Synthetic`). */
  fun syntheticThumbnails(): ThumbnailSource = SyntheticThumbnailSource()

  /** Il NAS sintetico (Fase 4A, P11, M1): media solo sul NAS dalla scansione vera su un NAS finto in memoria. */
  fun nasLibrary(): DebugLibraryKit? = SyntheticNasKit

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
    // Fase 5B (P0-B): le prove guidate sul campo (T-09, T-14, T-15, T-16).
    composable<FieldProbesRoute> {
      FieldProbeScreen(onBack = { nav.popBackStack() })
    }
  }

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.debug_catalog_title),
        subtitle = stringResource(R.string.debug_catalog_detail),
        onClick = { nav.navigate(CatalogRoute) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.debug_nas_title),
        subtitle = stringResource(R.string.debug_nas_detail),
        onClick = {
          SyntheticNasKit.request()
          nav.popBackStack()
        },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.debug_nas_mixed_title),
        subtitle = stringResource(R.string.debug_nas_mixed_detail),
        onClick = {
          SyntheticNasKit.request(mixed = true)
          nav.popBackStack()
        },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.probe_entry_title),
        subtitle = stringResource(R.string.probe_entry_detail),
        onClick = { nav.navigate(FieldProbesRoute) },
      )
    }
  }

  /**
   * D33 (Fase 4): la recinzione delle prove sul campo. Con un account di prova (`test`, `test2`) la sessione rifiuta ogni
   * share fuori da `test`, `test2`, `test-comune` prima di qualunque richiesta (anche `NAS` e `Public`, pubbliche). Solo
   * qui, nella build debug: la release non la referenzia e R8 la toglie (tools/check.ps1 la cerca nel dex di release).
   */
  fun shareGuard(): ShareGuard? = ShareFence

  /**
   * SOLO debug, SOLO con il marcatore: la prova sul campo F20 della pulizia del cestino del NAS in minuti invece che in 30 giorni
   * ([DebugTrashRetention]). Senza marcatore la debug e' l'app vera. In release non esiste (tools/check.ps1 lo cerca nel dex).
   */
  fun trashRetentionMillis(context: Context): Long? = DebugTrashRetention.read(context)

  /**
   * D13 (Fase 5A): la recinzione del telefono del backup con gli account di prova: solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/`, scritture
   * sotto `mcg-campo/` delle share di prova ([DebugBackupFence]). Punto di aggancio di P0, la lega P7 (piano, porta del telefono,
   * destinazioni). In release non esiste (tools/check.ps1 la cerca nel dex di release).
   */
  fun backupFence(): BackupFence? = DebugBackupFence

  /** Il nome del marcatore della famiglia pronta (lo scrive solo `adb`). */
  const val FAMILY_READY_MARKER: String = "debug-family-ready.marker"

  private object ReadyFamily : FamilySetupRepository {
    override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false))
  }
}
