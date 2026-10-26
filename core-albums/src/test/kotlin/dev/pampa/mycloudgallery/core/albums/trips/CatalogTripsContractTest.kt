package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.contract.TripsContract
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.albums.testing.TripDismissals
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.LocalDate
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After

/**
 * IL VERO `Trips` PASSA LA SUITE DI CONTRATTO DI P0 (`TripsContract`, riga P7 del §4.4): le stesse cinque prove dell'esecutore di riferimento
 * (`InMemoryTrips`), ma sopra le regole vere di trips/ (casa nel tempo, serie di giorni, finestre degli eventi, nascosti da `VisibleMedia` e
 * filtro finale). La Libreria e' la `FakeAlbumLibrary` della suite; le proposte scartate stanno in un solo posto per tutti i "dispositivi" della
 * prova che condividono lo stesso insieme (come i dati personali di un account).
 */
class CatalogTripsContractTest : TripsContract() {

  private val scopes = ArrayList<CoroutineScope>()
  private val sharedDismissals = IdentityHashMap<TripDismissals, MemoryDismissals>()

  override fun trips(library: FakeAlbumLibrary, today: LocalDate, albums: Albums, dismissals: TripDismissals): Trips {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
    val shared = sharedDismissals.getOrPut(dismissals) { MemoryDismissals() }
    return CatalogTrips(
      library, TripVisibility(library, library), MemoryTripStore(), shared, albums, TripTitles(FakePlaces()), { today }, FakeAlbumClocks(), scope,
      refreshDelayMs = 0,
    )
  }

  override fun albumsFor(library: FakeAlbumLibrary): Albums {
    val ref = ReferenceWorld()
    return InMemoryAlbums(library, ref.family, ref.clocks)
  }

  @After
  fun stop() {
    scopes.forEach { it.cancel() }
  }
}
