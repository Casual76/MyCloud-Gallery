package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.nas.R as NasScreensR
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.hub.NasHubEntry
import dev.pampa.mycloudgallery.feature.nas.hub.NasScreenContent
import dev.pampa.mycloudgallery.feature.nas.hub.nasHubEntries
import dev.pampa.mycloudgallery.feature.nas.hub.openNas
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.main.GalleryHostViewModel
import dev.pampa.mycloudgallery.main.ViewerHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Fase 4B (P16, docs/fase4-progetto.md §1.4, §1.5): le quattro pagine della 4B nella navigazione dell'app. `feature-library` riceve le
// voci di Raccolte come dati (`CollectionEntry`), `feature-nas` le pagine (`NasScreenContent`, `nasGraph`): i due non si conoscono, li
// collega questo file. Le rotte sono le stringhe stabili di `NasScreen` (mai un percorso del NAS dentro una rotta).

/** La pagina della 4B con questo codice (la rotta di `NasScreen`), o null per una voce che questa versione non conosce. */
internal fun nasScreenOf(code: String): NasScreen? = NasScreen.entries.firstOrNull { it.route == code }

/** Una voce della 4B come riga delle Raccolte: lo stesso codice della rotta, gli stessi testi, glifo, tono e numero. */
internal fun NasHubEntry.toCollectionEntry(): CollectionEntry = CollectionEntry(
  code = screen.route,
  title = title,
  subtitle = subtitle,
  icon = glyph,
  tone = tone,
  badge = badge,
)

/** Il gruppo "Sul NAS" delle Raccolte, solo con il NAS collegato (senza, il gruppo non c'e' nemmeno come intestazione). */
@Composable
internal fun nasCollectionGroups(configured: Boolean): List<CollectionEntryGroup> {
  if (!configured) return emptyList()
  val entries = nasHubEntries()
  val title = stringResource(NasScreensR.string.nas_hub_group)
  return remember(entries, title) { listOf(CollectionEntryGroup(title, entries.map { it.toCollectionEntry() })) }
}

/** La pagina di una voce "Sul NAS" nel pannello di dettaglio del tablet: senza tasto indietro, sopra la pillola. */
@Composable
internal fun NasEntryDetail(entry: CollectionEntry, nav: NavController, hostPadding: PaddingValues, opener: NasFileOpener) {
  val screen = nasScreenOf(entry.code) ?: return
  NasScreenContent(screen = screen, onNavigate = nav::openNas, hostPadding = hostPadding, onOpenFile = opener::open)
}

/**
 * Toccare un file in Cartelle NAS: lo apre nel viewer **della Libreria** sul suo media, se il catalogo lo conosce e la Libreria lo
 * mostra (un media nascosto, escluso o non ancora scansionato non si apre: niente scorciatoie verso i Nascosti, E-H1). Il percorso
 * viene dall'elenco che l'utente sta guardando e serve solo a trovare il media nel catalogo.
 */
@Stable
internal class NasFileOpener(
  private val scope: CoroutineScope,
  private val gallery: GalleryHostViewModel?,
  private val viewer: ViewerHostState?,
) {
  /** La Libreria di adesso e i suoi servizi del viewer: li scrive la galleria quando si compone. */
  @Volatile var library: LibraryTabState? = null

  @Volatile var services: ViewerServices? = null

  fun open(share: String, path: SharePath) {
    val host = gallery ?: return
    val target = viewer ?: return
    scope.launch {
      val state = library ?: return@launch
      val id = host.mediaIdOfNasFile(share, path) ?: return@launch
      if (state.grid.view.viewIndexOf(id) < 0) return@launch
      target.open(ViewerRequest(state.viewerItems, id, state.grid.viewerAnchor, secure = false), services ?: host.viewerServicesFor(state))
    }
  }
}
