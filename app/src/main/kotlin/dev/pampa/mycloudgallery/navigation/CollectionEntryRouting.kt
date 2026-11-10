package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.albums.nav.openEntry
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.map.places.PlacesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.LocalCollectionDetailClose
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPage
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPageRoute
import dev.pampa.mycloudgallery.feature.library.utility.openUtility
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.hub.openNas

// DOVE PORTA UNA VOCE DELLE RACCOLTE (Fase 7, P13; P11: "onOpenEntry ed entryDetail per i codici nuovi"). Le voci consegnate come dati hanno un codice
// stabile (mai un dato dell'utente): `album/<id>`, `smart/<id>`, `albums/all`, `albums/new`, `trips/all`, `trip/<impronta>` (P10), `utility/<pagina>`
// (P8), le rotte di `NasScreen` (4B) e `backup/free-space` (5B); dalla Fase 9 `m_<16 caratteri>`, `memories_all` (Ricordi), `map` e `place_<id>` (Luoghi). Qui si riconoscono, una volta, per il telefono (la rotta) e per il tablet (il
// pannello di dettaglio, dove la pagina c'e').

/** Il bersaglio di una voce. */
internal sealed interface EntryTarget {
  data object FreeSpace : EntryTarget

  data class Utility(val page: UtilityPage) : EntryTarget

  data class Nas(val screen: NasScreen) : EntryTarget

  data class Album(val target: AlbumEntryTarget) : EntryTarget

  /** Fase 8 (P13): la sezione Persone (le persone, "Tutte le persone", "Altri volti", l'attivazione, l'avviso, Impostazioni). */
  data class People(val target: PeopleEntryTarget) : EntryTarget

  /** Fase 9 (P13): la sezione Ricordi (`m_<16 caratteri>` per un ricordo, `memories_all`, le righe di stato). */
  data class Memories(val target: MemoriesEntryTarget) : EntryTarget

  /** Fase 9 (P13): la sezione Luoghi (`map`, `place_<id GeoNames>`, la riga dei senza posizione). */
  data class Places(val target: PlacesEntryTarget) : EntryTarget

  /** Un codice che questa versione non conosce (di una versione piu' nuova): non fa niente. */
  data object Unknown : EntryTarget

  companion object {
    fun of(code: String): EntryTarget = when {
      isFreeSpaceEntry(code) -> FreeSpace
      else -> UtilityPage.fromEntryCode(code)?.let(::Utility)
        ?: nasScreenOf(code)?.let(::Nas)
        ?: AlbumEntryTarget.parse(code)?.let(::Album)
        ?: PeopleEntryTarget.parse(code)?.let(::People)
        ?: MemoriesEntryTarget.parse(code)?.let(::Memories)
        ?: PlacesEntryTarget.parse(code)?.let(::Places)
        ?: Unknown
    }
  }
}

/**
 * Se la voce ha una pagina nel pannello di dettaglio del tablet: le pagine dell'Utility, della 4B e "Libera spazio" si'; dalla Fase 10 (P5) anche la pagina di un album, di una persona e di un ricordo
 * ([panes]); le liste, "Nuovo album" (dalla fase grafica non si mostra piu' nelle Raccolte: se arrivasse apre l'elenco degli album, FAB-21), i viaggi e i Luoghi si aprono come sul telefono.
 */
internal fun hasEntryDetail(code: String, panes: EntryPanes? = null): Boolean = when (val target = EntryTarget.of(code)) {
  EntryTarget.FreeSpace, is EntryTarget.Utility, is EntryTarget.Nas -> true
  // Fase 10 (P5, D13): la pagina di un album, di una persona o di un ricordo sta nel pannello (da estesa) se la sua funzione e' legata; le liste e i viaggi restano pagine.
  is EntryTarget.Album, is EntryTarget.People, is EntryTarget.Memories -> panes?.pageOf(target) != null
  is EntryTarget.Places, EntryTarget.Unknown -> false
}

/** Apre la voce [code] sul telefono (o, sul tablet, una voce senza dettaglio). */
internal fun openCollectionEntry(
  code: String,
  nav: NavController,
  openFreeSpace: () -> Unit,
  onPeople: (PeopleEntryTarget) -> Unit = {},
  onMemories: (MemoriesEntryTarget) -> Unit = {},
  onPlaces: (PlacesEntryTarget) -> Unit = {},
) {
  when (val target = EntryTarget.of(code)) {
    EntryTarget.FreeSpace -> openFreeSpace()
    is EntryTarget.Utility -> nav.openUtility(target.page)
    is EntryTarget.Nas -> nav.openNas(target.screen)
    is EntryTarget.Album -> nav.openEntry(target.target)
    is EntryTarget.People -> onPeople(target.target)
    is EntryTarget.Memories -> onMemories(target.target)
    is EntryTarget.Places -> onPlaces(target.target)
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
  panes: EntryPanes? = null,
) {
  when (val target = EntryTarget.of(entry.code)) {
    EntryTarget.FreeSpace -> FreeSpaceEntryDetail(hostPadding)
    is EntryTarget.Utility -> UtilityPageRoute(page = target.page, onOpenViewer = onOpenViewer, contentPadding = hostPadding)
    is EntryTarget.Nas -> NasEntryDetail(entry, nav, hostPadding, opener)
    is EntryTarget.Album, is EntryTarget.People, is EntryTarget.Memories -> {
      val page = panes?.pageOf(target)
      if (page != null && panes != null) EntryPagePane(page = page, panes = panes, outer = nav, close = LocalCollectionDetailClose.current)
    }
    is EntryTarget.Places, EntryTarget.Unknown -> Unit
  }
}
