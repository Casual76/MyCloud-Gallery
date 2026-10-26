package dev.pampa.mycloudgallery.feature.albums.sections

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.trips.TripsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LE SEZIONI ALBUM, ALBUM SMART E VIAGGI DELLE RACCOLTE (Fase 7, P10; docs/fase7-progetto.md D33, §2.7; "Fase 7 P11" in docs/decisioni.md). Ogni sezione
// delle Raccolte e' un dato che chi la possiede consegna con un `CollectionsSectionProvider` (feature-library); `:app` lo lega con un
// `@Provides @IntoSet` (P13). Qui nasce il dato.
//
// **Perche' non e' un `CollectionsSectionProvider` qui dentro.** I `feature-*` non si conoscono (Phase4ArchitectureTest) e il fornitore, con
// `CollectionEntryGroup`, sta in feature-library. Queste classi producono quindi la stessa cosa con tipi propri, **della stessa forma campo
// per campo** ([SectionData] = `CollectionEntryGroup`, [SectionEntry] = `CollectionEntry`): il legame in `:app` e' un `map` di poche righe, e
// `CollectionsSectionBindingTest` lo prova su un fornitore vero di feature-library (il test vede feature-library, il codice di produzione no):
//
//     object : CollectionsSectionProvider {
//       override val code = sources.albumsCode
//       override val group = sources.albums.map { it?.toGroup() }.stateIn(...)   // toGroup() e' nel test, 6 righe
//     }
//
// I codici delle voci sono stabili e non portano mai un dato dell'utente: `album/<id>`, `smart/<id>`, `albums/all`, `albums/new`, `trips/all`,
// `trip/<impronta>`. `:app` li riconosce (come fa gia' per `nas/...`) con [AlbumEntryTarget.parse].

/** Una voce di una sezione: la stessa forma di `CollectionEntry` (feature-library). */
@Immutable
data class SectionEntry(
  val code: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val tone: FluidTone,
  val badge: Int = 0,
  val trailing: String? = null,
)

/** Una sezione: la stessa forma di `CollectionEntryGroup`. Senza voci la sezione "non c'e'" (nessuna intestazione). */
@Immutable
data class SectionData(
  val code: String,
  val title: String,
  val entries: List<SectionEntry>,
  val hideable: Boolean = true,
  val hint: String? = null,
)

/** Cosa apre una voce delle sezioni: `:app` lo riconosce dal codice e porta alla pagina giusta. */
sealed interface AlbumEntryTarget {
  data class Album(val id: AlbumId) : AlbumEntryTarget

  data class SmartAlbum(val id: AlbumId) : AlbumEntryTarget

  data object AllAlbums : AlbumEntryTarget

  data object NewAlbum : AlbumEntryTarget

  /** Una proposta di viaggio (l'impronta) o tutte ([id] null): la pagina Viaggi. */
  data class Trip(val id: String?) : AlbumEntryTarget

  companion object {
    const val ALBUM_PREFIX = "album/"
    const val SMART_PREFIX = "smart/"
    const val TRIP_PREFIX = "trip/"
    const val ALL_ALBUMS = "albums/all"
    const val NEW_ALBUM = "albums/new"
    const val ALL_TRIPS = "trips/all"

    /** Il bersaglio di una voce, o null se il codice non e' di queste sezioni (o e' di una versione piu' nuova). */
    fun parse(code: String): AlbumEntryTarget? = when {
      code == ALL_ALBUMS -> AllAlbums
      code == NEW_ALBUM -> NewAlbum
      code == ALL_TRIPS -> Trip(null)
      code.startsWith(ALBUM_PREFIX) -> idOf(code.removePrefix(ALBUM_PREFIX))?.let(::Album)
      code.startsWith(SMART_PREFIX) -> idOf(code.removePrefix(SMART_PREFIX))?.let(::SmartAlbum)
      code.startsWith(TRIP_PREFIX) -> code.removePrefix(TRIP_PREFIX).takeIf { it.isNotEmpty() }?.let { Trip(it) }
      else -> null
    }

    private fun idOf(text: String): AlbumId? = try {
      AlbumId(text)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

/** I codici stabili delle sezioni (gli stessi di `CollectionSections` di feature-library: lo prova `CollectionsSectionBindingTest`). */
object AlbumsSectionCodes {
  const val ALBUMS: String = "albums"
  const val SMART: String = "smart"
  const val TRIPS: String = "trips"
}

class AlbumsSectionSources(
  private val resources: Resources,
  albumsSource: Albums,
  tripsSource: Trips,
  private val people: AlbumPeople,
  clock: AlbumScreensClock,
  scope: CoroutineScope,
) {
  /** La sezione Album (i miei e i condivisi insieme; non si nasconde mai, D33): i primi [FIRST] e "Tutti gli album", piu' "Nuovo album". */
  val albums: StateFlow<SectionData?> = albumsSource.all.map { list -> albumsSection(list.manualAndShared) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  /** La sezione Album smart: senza album smart non c'e'. */
  val smart: StateFlow<SectionData?> = albumsSource.all.map { list -> smartSection(list.smart) }.stateIn(scope, SharingStarted.Eagerly, null)

  /** La sezione Viaggi: le proposte dal piu' recente; senza GPS la spiegazione e gli eventi (E-AL16); senza niente non c'e'. */
  val trips: StateFlow<SectionData?> = combine(tripsSource.proposals, albumsSource.all) { proposals, _ ->
    tripsSection(TripsController.stateOf(proposals, showOlder = false, today = clock.today()))
  }.stateIn(scope, SharingStarted.Eagerly, null)

  private fun items(n: Int): String = resources.getQuantityString(R.plurals.albums_items, n, GalleryNumbers.format(n))

  private fun albumsSection(all: List<Album>): SectionData {
    val shown = all.take(FIRST).map { album ->
      val shared = album as? Album.Shared
      SectionEntry(
        code = AlbumEntryTarget.ALBUM_PREFIX + album.id.value,
        title = album.title,
        subtitle = if (shared != null && !shared.mine) resources.getString(R.string.albums_row_by, items(album.count), people.nameOf(shared.creator)) else items(album.count),
        icon = if (shared != null) Icons.Rounded.Group else Icons.Rounded.PhotoAlbum,
        tone = if (shared != null) FluidTone.Info else FluidTone.Primary,
        trailing = if (shared != null) resources.getString(R.string.albums_badge_shared) else null,
      )
    }
    val more = if (all.size > FIRST) {
      listOf(
        SectionEntry(
          AlbumEntryTarget.ALL_ALBUMS, resources.getString(R.string.albums_all), resources.getQuantityString(R.plurals.albums_count, all.size, all.size),
          Icons.Rounded.PhotoLibrary, FluidTone.Neutral,
        ),
      )
    } else {
      emptyList()
    }
    val create = SectionEntry(
      AlbumEntryTarget.NEW_ALBUM, resources.getString(R.string.albums_new), resources.getString(R.string.albums_new_detail), Icons.Rounded.Add, FluidTone.Primary,
    )
    return SectionData(AlbumsSectionCodes.ALBUMS, resources.getString(R.string.albums_section), shown + more + create, hideable = false)
  }

  private fun smartSection(smart: List<Album.Smart>): SectionData {
    val entries = smart.take(FIRST).map { album ->
      val detail = if (album.suggested > 0) {
        resources.getString(R.string.albums_row_suggested, items(album.count), resources.getQuantityString(R.plurals.albums_suggested, album.suggested, album.suggested))
      } else {
        items(album.count)
      }
      SectionEntry(AlbumEntryTarget.SMART_PREFIX + album.id.value, album.title, detail, Icons.Rounded.AutoAwesome, FluidTone.Primary, badge = album.newSinceSeen)
    }
    return SectionData(AlbumsSectionCodes.SMART, resources.getString(R.string.albums_smart_section), entries)
  }

  private fun tripsSection(state: TripsState): SectionData {
    val title = resources.getString(R.string.trips_section)
    val entries = when (state) {
      TripsState.Empty -> emptyList()
      is TripsState.Proposals -> state.proposals.take(FIRST_TRIPS).map(::tripEntry) + moreTrips(state.proposals.size, state.hasOlder)
      is TripsState.NoGps -> listOf(
        SectionEntry(
          AlbumEntryTarget.ALL_TRIPS, resources.getString(R.string.trips_no_gps_row), resources.getString(R.string.trips_no_gps_row_detail), Icons.Rounded.Flight, FluidTone.Neutral,
        ),
      ) + state.events.take(FIRST_TRIPS).map(::tripEntry)
    }
    return SectionData(AlbumsSectionCodes.TRIPS, title, entries)
  }

  private fun tripEntry(trip: TripProposal): SectionEntry = SectionEntry(
    code = AlbumEntryTarget.TRIP_PREFIX + trip.id.value,
    title = trip.label,
    subtitle = resources.getQuantityString(R.plurals.trips_count, trip.count, GalleryNumbers.format(trip.count)),
    icon = if (trip.kind == TripKind.TRIP) Icons.Rounded.Flight else Icons.Rounded.Event,
    tone = FluidTone.Info,
    trailing = resources.getString(if (trip.kind == TripKind.TRIP) R.string.trips_kind_trip else R.string.trips_kind_event),
  )

  private fun moreTrips(shown: Int, hasOlder: Boolean): List<SectionEntry> =
    if (shown > FIRST_TRIPS || hasOlder) {
      listOf(SectionEntry(AlbumEntryTarget.ALL_TRIPS, resources.getString(R.string.trips_all), "", Icons.Rounded.PhotoLibrary, FluidTone.Neutral))
    } else {
      emptyList()
    }

  private companion object {
    /** "Tutti gli album" oltre i primi 6 (§2.7). */
    const val FIRST = 6
    const val FIRST_TRIPS = 3
  }
}
