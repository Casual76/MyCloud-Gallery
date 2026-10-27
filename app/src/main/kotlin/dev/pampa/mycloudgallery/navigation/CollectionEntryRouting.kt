package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.albums.nav.openEntry
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPage
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPageRoute
import dev.pampa.mycloudgallery.feature.library.utility.openUtility
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.hub.openNas

// DOVE PORTA UNA VOCE DELLE RACCOLTE (Fase 7, P13; P11: "onOpenEntry ed entryDetail per i codici nuovi"). Le voci consegnate come dati hanno un codice
// stabile (mai un dato dell'utente): `album/<id>`, `smart/<id>`, `albums/all`, `albums/new`, `trips/all`, `trip/<impronta>` (P10), `utility/<pagina>`
// (P8), le rotte di `NasScreen` (4B) e `backup/free-space` (5B). Qui si riconoscono, una volta, per il telefono (la rotta) e per il tablet (il
// pannello di dettaglio, dove la pagina c'e').

/** Il bersaglio di una voce. */
internal sealed interface EntryTarget {
  data object FreeSpace : EntryTarget

  data class Utility(val page: UtilityPage) : EntryTarget

  data class Nas(val screen: NasScreen) : EntryTarget

  data class Album(val target: AlbumEntryTarget) : EntryTarget

  /** Un codice che questa versione non conosce (di una versione piu' nuova): non fa niente. */
  data object Unknown : EntryTarget

  companion object {
    fun of(code: String): EntryTarget = when {
      isFreeSpaceEntry(code) -> FreeSpace
      else -> UtilityPage.fromEntryCode(code)?.let(::Utility)
        ?: nasScreenOf(code)?.let(::Nas)
        ?: AlbumEntryTarget.parse(code)?.let(::Album)
        ?: Unknown
    }
  }
}

/**
 * Se la voce ha una pagina nel pannello di dettaglio del tablet: le pagine dell'Utility, della 4B e "Libera spazio" si', le pagine degli album no (sono
 * rotte con la loro barra, e "Nuovo album" e' un foglio): si aprono come sul telefono.
 */
internal fun hasEntryDetail(code: String): Boolean = when (EntryTarget.of(code)) {
  EntryTarget.FreeSpace, is EntryTarget.Utility, is EntryTarget.Nas -> true
  is EntryTarget.Album, EntryTarget.Unknown -> false
}

/** Apre la voce [code] sul telefono (o, sul tablet, una voce senza dettaglio). */
internal fun openCollectionEntry(code: String, nav: NavController, openFreeSpace: () -> Unit, onNewAlbum: () -> Unit) {
  when (val target = EntryTarget.of(code)) {
    EntryTarget.FreeSpace -> openFreeSpace()
    is EntryTarget.Utility -> nav.openUtility(target.page)
    is EntryTarget.Nas -> nav.openNas(target.screen)
    is EntryTarget.Album -> if (target.target == AlbumEntryTarget.NewAlbum) onNewAlbum() else nav.openEntry(target.target)
    EntryTarget.Unknown -> Unit
  }
}

/** La pagina della voce nel pannello di dettaglio del tablet: senza tasto indietro, sopra la pillola. */
@Composable
internal fun CollectionEntryDetail(
  entry: CollectionEntry,
  nav: NavController,
  hostPadding: PaddingValues,
  opener: NasFileOpener,
  onOpenViewer: (ViewerRequest) -> Unit,
) {
  when (val target = EntryTarget.of(entry.code)) {
    EntryTarget.FreeSpace -> FreeSpaceEntryDetail(hostPadding)
    is EntryTarget.Utility -> UtilityPageRoute(page = target.page, onOpenViewer = onOpenViewer, contentPadding = hostPadding)
    is EntryTarget.Nas -> NasEntryDetail(entry, nav, hostPadding, opener)
    is EntryTarget.Album, EntryTarget.Unknown -> Unit
  }
}
