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
    // Fase 6 (P9, P11): lo strumento di valutazione della ricerca.
    with(dev.pampa.mycloudgallery.debugtools.eval.EvalEntry) { destination(nav) }
    // Fase 7 (P12): lo strumento di valutazione degli album smart e dei viaggi (F88, F89).
    with(dev.pampa.mycloudgallery.debugtools.eval.albums.AlbumsEvalEntry) { destination(nav) }
    // Fase 8 (P11): lo strumento di valutazione delle persone (F105). La porta la lega P13 (`FacesEvalEnvironmentHolder.current`).
    with(dev.pampa.mycloudgallery.debugtools.eval.faces.FacesEvalEntry) { destination(nav) }
    // Fase 9 (P11): lo strumento di valutazione dei ricordi (F125). La porta la lega P13 (MemoriesEvalEnvironmentHolder.current).
    with(dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalEntry) { destination(nav) }
  }

  /**
   * I dati dello strumento di valutazione (etichette e query del set privato, resoconti, set OCR) stanno in `noBackupFilesDir/eval/`
   * ([dev.pampa.mycloudgallery.debugtools.eval.EvalStore.of]): l'azzeramento li toglie con il resto (I11 della revisione di sicurezza della
   * Fase 6). Il nome e' quello di `EvalStore.of`; `DeveloperToolsErasableTest` li tiene allineati.
   */
  fun erasableDirs(): List<String> = listOf("eval")

  /** Fase 6 (P11): la porta dello strumento di valutazione della ricerca (P9) sopra il grafo della ricerca vero. Solo debug. */
  fun installSearch(stack: dev.pampa.mycloudgallery.ai.AiStack, gallery: dev.pampa.mycloudgallery.gallery.GalleryGraph, context: Context) {
    dev.pampa.mycloudgallery.debugtools.eval.EvalEnvironmentHolder.current =
      dev.pampa.mycloudgallery.debugtools.eval.AppEvalEnvironment(stack, gallery, context.noBackupFilesDir)
    // Fase 7 (P12): la porta dello strumento di valutazione degli album e dei viaggi, sopra la stessa Libreria (sola lettura). P13: la cartella
    // `Backup/<dispositivo>/` di questo dispositivo (`ownBackupFolder`), la stessa che usano gli album smart veri (`AlbumsWiring.ownBackupFolder`).
    dev.pampa.mycloudgallery.debugtools.eval.albums.AlbumsEvalEnvironmentHolder.current = dev.pampa.mycloudgallery.debugtools.eval.albums.AppAlbumsEvalEnvironment.over(
      gallery,
      context.applicationContext,
      dev.pampa.mycloudgallery.core.albums.smart.EngineContentSearch(stack.search.engine),
      stack.search.today,
      ownBackupFolder = { dev.pampa.mycloudgallery.albums.AlbumsWiring.ownBackupFolder(gallery) },
    )
  }

  /**
   * Fase 8 (P13, P11): lo strumento di valutazione delle persone (F105), sopra le persone montate ([graph] e [people] sono quelli di `:app`; i tipi sono
   * neutri perche' la release non nomina lo strumento: `FacesEvalSourceParityTest`). Sola lettura: legge i gruppi, i volti mostrabili e i punteggi; non
   * scrive mai nell'archivio dei volti, nelle correzioni, nel NAS o nei dati personali. Solo debug (in release la funzione e' vuota).
   */
  fun installPeople(graph: Any, people: Any) {
    val live = graph as? dev.pampa.mycloudgallery.faces.FacesGraph ?: return
    val facade = people as? dev.pampa.mycloudgallery.faces.FacesPeopleFacade ?: return
    dev.pampa.mycloudgallery.debugtools.eval.faces.FacesEvalEnvironmentHolder.current = AppFacesEvalEnvironment(live, facade)
  }

  /** Fase 9 (P13): la porta dello strumento di valutazione dei ricordi (P11) sopra i ricordi montati, in sola lettura. Solo debug. */
  fun installMemories(graph: Any) {
    val memories = graph as? dev.pampa.mycloudgallery.memories.MemoriesGraph ?: return
    dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalEnvironmentHolder.current = AppMemoriesEvalEnvironment(memories)
  }

  /**
   * Fase 8 (DR-2, F109), SOLO debug, SOLO con il segno: la ricetta dei volti forzata dallo scenario `f109-recipe` (ricetta 2), letta quando si montano le persone.
   * Senza il segno la debug e' l'app vera. In release non esiste (tools/check.ps1 cerca i resti degli scenari nel dex).
   */
  fun facesRecipe(context: Context): Int? = dev.pampa.mycloudgallery.debugtools.probes.FieldProbeFacesRecipe.read(context.filesDir)

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
      FluidListDivider()
      dev.pampa.mycloudgallery.debugtools.eval.EvalEntry.row(nav)
      FluidListDivider()
      dev.pampa.mycloudgallery.debugtools.eval.albums.AlbumsEvalEntry.row(nav)
      FluidListDivider()
      dev.pampa.mycloudgallery.debugtools.eval.faces.FacesEvalEntry.row(nav)
      FluidListDivider()
      dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalEntry.row(nav)
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

  /**
   * D13 (Fase 6, P13): le cartelle recintate dei ritagli del soggetto con gli account di prova (`Pictures/mcg-prova/Ritagli/` e la sua sorgente del
   * backup), solo qui: in release non esistono e la stringa `mcg-prova` non e' nel dex (tools/check.ps1).
   */
  fun fencedCutouts(): dev.pampa.mycloudgallery.backup.FencedCutouts? = DebugFencedCutouts

  /**
   * Fase 7 (P9, D22, F73): qualcosa e' stato nascosto o mostrato. Il widget di prova dei Nascosti (solo debug) si ridisegna subito: un media
   * nascosto un attimo fa non deve restare sulla schermata Home fino al prossimo aggiornamento di Android. In release non esiste.
   */
  fun hiddenChanged(context: Context) {
    dev.pampa.mycloudgallery.debugtools.widget.FieldProbeHiddenWidgetProvider.refreshAll(context)
  }

  /** Il nome del marcatore della famiglia pronta (lo scrive solo `adb`). */
  const val FAMILY_READY_MARKER: String = "debug-family-ready.marker"

  private object ReadyFamily : FamilySetupRepository {
    override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false))
  }
}
