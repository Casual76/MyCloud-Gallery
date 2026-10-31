package dev.pampa.mycloudgallery.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumPageDestination
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.albums.nav.openRules
import dev.pampa.mycloudgallery.feature.albums.nav.openSuggestions
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageExits
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageRoute
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.nav.MemoryDestination
import dev.pampa.mycloudgallery.feature.memories.nav.openMemoryPlayer
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageExits
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageRoute
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.people.nav.PersonDestination
import dev.pampa.mycloudgallery.feature.people.nav.openReview
import dev.pampa.mycloudgallery.feature.people.page.PersonPageExits
import dev.pampa.mycloudgallery.feature.people.page.PersonPageRoute

// IL DETTAGLIO DI ALBUM, PERSONE E RICORDI NELLE RACCOLTE (Fase 10, P5, D13 (1), (3); docs/fase10-progetto.md §2.3): da finestra estesa (840 dp) la pagina di un album, di una persona o di un ricordo sta nel pannello
// di destra delle Raccolte, accanto all'elenco, invece di aprirsi a pagina intera. E' la pagina di sempre (la stessa rotta, lo stesso ViewModel): nel pannello vive in una navigazione propria che parte
// dalla sua rotta, cosi' argomenti, ViewModel e "Indietro" non cambiano; l'"Indietro" della pagina chiude il pannello e riporta alla raccolta di prima. Cio' che porta fuori dalla pagina (un altro album,
// l'editor delle regole, la revisione dei volti, la presentazione) usa la navigazione dell'app, a pagina intera. Le liste ("Tutti gli album", "Tutte le persone", "Tutti i ricordi") e i viaggi restano pagine.

/** Quale pagina sta nel pannello: l'identita' (mai un dato dell'utente nel `toString()`). */
internal sealed interface PanePage {
  /** Chiave stabile per riconoscere "la stessa pagina" fra due disegni. */
  val key: String

  data class Album(val id: AlbumId) : PanePage {
    override val key: String get() = "album"
    override fun toString(): String = "PanePage.Album"
  }

  data class Person(val id: PersonId) : PanePage {
    override val key: String get() = "persona"
    override fun toString(): String = "PanePage.Person"
  }

  data class Memory(val id: MemoryId) : PanePage {
    override val key: String get() = "ricordo"
    override fun toString(): String = "PanePage.Memory"
  }
}

/**
 * Cio' che serve per mettere le pagine di album, persone e ricordi nel pannello: le loro uscite e miniature, quelle che `AppNavGraph` gia' ha per le rotte. Una parte nulla (la funzione non e' legata:
 * ricordi o persone spenti) vuol dire che quella pagina non ha il pannello e si apre come sul telefono.
 */
@Immutable
internal class EntryPanes(
  val albumActions: AlbumActions?,
  val albumThumbnails: AlbumThumbnails?,
  val peopleActions: PeopleActions?,
  val peopleThumbnails: PeopleThumbnails?,
  val peopleCrops: PeopleCrops?,
  val memoriesActions: MemoriesActions?,
  val memoriesThumbnails: MemoriesThumbnails?,
  /** La voce `m_<16 caratteri>` -> il ricordo dello scaffale di adesso (null se e' uscito). */
  val resolveMemory: (String) -> MemoryId?,
) {
  /** La pagina nel pannello per un bersaglio, o null se non ne ha una (e' una lista, un viaggio, una voce di stato, o la funzione non e' legata). */
  fun pageOf(target: EntryTarget): PanePage? = when (target) {
    is EntryTarget.Album -> when (val album = target.target) {
      is AlbumEntryTarget.Album -> album.id.takeIf { albumActions != null && albumThumbnails != null }?.let(PanePage::Album)
      is AlbumEntryTarget.SmartAlbum -> album.id.takeIf { albumActions != null && albumThumbnails != null }?.let(PanePage::Album)
      AlbumEntryTarget.AllAlbums, AlbumEntryTarget.NewAlbum, is AlbumEntryTarget.Trip -> null
    }
    is EntryTarget.People -> (target.target as? PeopleEntryTarget.Person)?.id?.takeIf { peopleActions != null && peopleThumbnails != null && peopleCrops != null }?.let(PanePage::Person)
    is EntryTarget.Memories -> (target.target as? MemoriesEntryTarget.Memory)?.code?.takeIf { memoriesActions != null && memoriesThumbnails != null }?.let(resolveMemory)?.let(PanePage::Memory)
    EntryTarget.FreeSpace, is EntryTarget.Utility, is EntryTarget.Nas, is EntryTarget.Places, EntryTarget.Unknown -> null
  }
}

/** Una navigazione del pannello senza movimento proprio: il cambio fra due pagine lo fa `FluidDetailContent` (movimento fra pari). */
@Composable
private fun PaneNavHost(start: Any, modifier: Modifier = Modifier, graph: androidx.navigation.NavGraphBuilder.() -> Unit) {
  val paneNav = rememberNavController()
  NavHost(
    navController = paneNav,
    startDestination = start,
    modifier = modifier,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { ExitTransition.None },
    builder = graph,
  )
}

/**
 * La pagina [page] nel pannello di dettaglio delle Raccolte. [outer] e' la navigazione dell'app (per cio' che esce dalla pagina), [close] chiude il pannello (l'"Indietro" della pagina, o la pagina che
 * non c'e' piu': un album eliminato). Cambiare pagina ricomincia da capo (una navigazione per pagina scelta).
 */
@Composable
internal fun EntryPagePane(page: PanePage, panes: EntryPanes, outer: NavController, close: () -> Unit) {
  key(page) {
    when (page) {
      is PanePage.Album -> {
        val actions = panes.albumActions ?: return@key
        val thumbnails = panes.albumThumbnails ?: return@key
        PaneNavHost(AlbumPageDestination(page.id.value)) {
          composable<AlbumPageDestination> {
            AlbumPageRoute(
              actions = actions,
              thumbnails = thumbnails,
              exits = AlbumPageExits(
                onBack = close,
                onBackToList = close,
                onOpenAlbum = outer::openAlbum,
                onOpenRules = outer::openRules,
                onOpenSuggestions = outer::openSuggestions,
              ),
            )
          }
        }
      }
      is PanePage.Person -> {
        val actions = panes.peopleActions ?: return@key
        val thumbnails = panes.peopleThumbnails ?: return@key
        val crops = panes.peopleCrops ?: return@key
        PaneNavHost(PersonDestination(page.id.value)) {
          composable<PersonDestination> {
            PersonPageRoute(actions = actions, thumbnails = thumbnails, crops = crops, exits = PersonPageExits(onBack = close, onReview = outer::openReview))
          }
        }
      }
      is PanePage.Memory -> {
        val actions = panes.memoriesActions ?: return@key
        val thumbnails = panes.memoriesThumbnails ?: return@key
        PaneNavHost(MemoryDestination(page.id.value)) {
          composable<MemoryDestination> {
            MemoryPageRoute(actions = actions, thumbnails = thumbnails, exits = MemoryPageExits(onBack = close, onPlay = outer::openMemoryPlayer))
          }
        }
      }
    }
  }
}
