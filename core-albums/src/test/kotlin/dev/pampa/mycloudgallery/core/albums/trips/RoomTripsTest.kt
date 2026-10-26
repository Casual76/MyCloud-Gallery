package dev.pampa.mycloudgallery.core.albums.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.personal.AlbumsRoom
import dev.pampa.mycloudgallery.core.albums.personal.T0
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.database.CandidateKey
import dev.pampa.mycloudgallery.core.database.CandidateQuery
import dev.pampa.mycloudgallery.core.database.CandidateSource
import dev.pampa.mycloudgallery.core.database.entity.TripProposalEntity
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VIAGGI ED EVENTI SUL CATALOGO VERO (Room in memoria, Robolectric): le tabelle v6 `home_estimate` e `trip_proposal` di P1, le query dei candidati
 * di P1 (con la traduzione che legheranno `:app` e P13), la visibilita' vera di P9 (`CatalogVisibleMedia`, `CatalogHiddenMask`) e lo store vero
 * dei dati personali per "Non mi interessa". Una storia piccola (quattro mesi, un viaggio a Roma, una festa); i numeri grandi sono nei test sulla
 * JVM pura.
 */
@RunWith(AndroidJUnit4::class)
class RoomTripsTest {

  private val today = LocalDate.of(2026, 10, 22)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val rooms = ArrayList<AlbumsRoom>()

  @After
  fun stop() {
    scope.cancel()
    rooms.forEach { it.close() }
  }

  /** I candidati del catalogo di P1 nella forma di `AlbumCandidates`: la traduzione senza logica che P13 legherà in `:app`. */
  private class RoomCandidates(private val room: AlbumsRoom) : AlbumCandidates {
    override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage {
      val days = filter.days?.let { it.from.toInt()..it.to.toInt() }
      val query = CandidateQuery(
        days = days, sources = filter.sources.mapTo(HashSet()) { s -> CandidateSource.entries.first { it.code == s.code } }, withGpsOnly = filter.withGpsOnly,
      )
      val cursor = after?.value?.split('|')?.let { CandidateKey(it[0].toInt(), it[1].toInt(), it[2].toLong(), it[3]) }
      val page = room.catalog.albums.candidates(query, cursor, limit)
      val items = page.items.map { c ->
        AlbumCandidate(
          c.media, c.day, c.takenAtUtcMs, c.localTime, c.gps, c.placeId ?: 0, c.originDevice, SmartSource.entries.first { it.code == c.source.code }, c.kind, c.screen, c.pixels,
        )
      }
      return CandidatePage(items, page.next?.let { CandidateCursor("${it.placeId}|${it.day}|${it.time}|${it.media}") })
    }

    override suspend fun visibleTotal(): Int = room.catalog.albums.visibleCount()

    private fun java.time.LocalDate.toInt(): Int = year * 10_000 + monthValue * 100 + dayOfMonth
  }

  private class World(val room: AlbumsRoom, val story: Story) {
    /** Il media del catalogo di ogni scatto della storia. */
    val ids = HashMap<MediaId, MediaId>()

    suspend fun load() {
      for ((i, c) in story.shots.withIndex()) {
        val fingerprint = Fingerprint.ofContent(Random(1_000L + i).nextBytes(128))
        val where = Location.Phone("external_primary", "DCIM/Camera/", "IMG_$i.jpg", 1_000L + i, "store-v1")
        val kind = if (c.isVideo) MediaKind.VIDEO else MediaKind.PHOTO
        val recorded = room.catalog.record(Observation(where, FileStamp(fingerprint.size, T0 + i, 7), fingerprint, kind, if (c.screen) MediaSource.SCREENSHOT else MediaSource.CAMERA, c.takenAtUtcMs))
        val id = checkNotNull(recorded.mediaId)
        val offset = ((c.day.atTime(c.localTime!!).toEpochSecond(ZoneOffset.UTC) - c.takenAtUtcMs / 1_000) / 60).toInt()
        room.catalog.updateMetadata(
          room.catalog.media(id)!!.currentVersionId,
          MediaMetadata(
            mimeType = "image/jpeg", width = 4_000, height = 3_000,
            captureTime = CaptureTime(c.takenAtUtcMs, offset, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
            location = c.gps?.let { GeoPoint(it.latitude, it.longitude) }, place = c.placeId.takeIf { it != 0 }?.let { PlaceRef(it, 1) },
          ),
        )
        ids[c.media] = id
      }
    }

    fun idOf(c: AlbumCandidate): MediaId = checkNotNull(ids[c.media])
  }

  private fun smallStory(): Story {
    val b = StoryBuilder("room", 5, today, spanDays = 120)
    b.trip(today.minusDays(60), 4, Places.ROMA, perDay = 5..8, expected = false)
    b.event(today.minusDays(30), 19, 0, 35, 150, Places.FIRENZE)
    b.residence(b.first, today, Habits(Places.FIRENZE, dayProbability = 0.8, perDay = 1..3, backupOnly = 0.0, screenshots = 0.0, gpsMissing = 0.0))
    return b.build()
  }

  private suspend fun newWorld(): World {
    val room = AlbumsRoom().also { rooms += it }
    return World(room, smallStory()).also { it.load() }
  }

  private fun trips(world: World, store: LedgerPersonalStore, albums: RecordingAlbums = RecordingAlbums()): CatalogTrips {
    val db = world.room.db
    return CatalogTrips(
      db, RoomCandidates(world.room), CatalogVisibleMedia(db, MutableStateFlow(null)), CatalogHiddenMask(db), store, albums, FakePlaces(), { today }, FakeAlbumClocks(), scope,
    )
  }

  private fun personal() = LedgerPersonalStore(TrustIds.random(), { T0 }, InMemoryPersonalLedger())

  @Test
  fun laRilevazioneSulCatalogoVeroScriveLeTabelleLocali() = runBlocking<Unit> {
    val world = newWorld()
    val trips = trips(world, personal())
    val report = trips.detect(2.minutes)
    assertThat(report.homeKnown).isTrue()
    assertThat(report.trips).isEqualTo(1)
    assertThat(report.events).isEqualTo(1)
    val proposals = trips.proposals.value.proposals
    val trip = proposals.single { it.kind == TripKind.TRIP }
    assertThat(trip.days.from).isEqualTo(today.minusDays(60))
    assertThat(trip.days.to).isEqualTo(today.minusDays(57))
    val members = world.story.shots.count { it.day in trip.days }
    assertThat(trip.count).isEqualTo(members)
    assertThat(world.ids.values).contains(trip.cover)
    // Le tabelle di P1: la casa (una riga, con il centro vicino a Firenze) e le proposte (con l'impronta, il titolo e il periodo).
    val home = world.room.db.tripDao().home()
    assertThat(home).hasSize(1)
    assertThat(home.single().kind).isEqualTo("home")
    assertThat(TripGeometry.km(home.single().latitude, home.single().longitude, Places.FIRENZE.lat, Places.FIRENZE.lon)).isLessThan(3.0)
    val rows = world.room.db.tripDao().proposals()
    assertThat(rows.map { it.tripId }).containsExactlyElementsIn(proposals.map { it.id.value })
    assertThat(rows.first { it.kind == "trip" }.label).isEqualTo("Roma, ${trip.label.substringAfter(", ")}")
  }

  @Test
  fun alRiavvioLeProposteSiRileggonoDaRoomEUnNascostoDopoNonSiMostra() = runBlocking<Unit> {
    val world = newWorld()
    val store = personal()
    val first = trips(world, store)
    first.detect(2.minutes)
    val shown = first.proposals.value
    // Il processo muore e rinasce sullo stesso database: le proposte tornano come prima.
    val reborn = trips(world, store)
    reborn.load()
    assertThat(reborn.proposals.value).isEqualTo(shown)
    // Poi l'utente nasconde la copertina del viaggio con il gesto del catalogo: nessuna rilevazione, solo il ricontrollo.
    val trip = shown.proposals.single { it.kind == TripKind.TRIP }
    world.room.catalog.setPersonalFlags(listOf(trip.cover)) { it.copy(hidden = true) }
    reborn.refresh()
    val after = reborn.proposals.value.proposals.single { it.kind == TripKind.TRIP }
    assertThat(after.cover).isNotEqualTo(trip.cover)
    assertThat(after.count).isEqualTo(trip.count - 1)
  }

  @Test
  fun creaAlbumESegnaLaPropostaSuRoomENonMiInteressaVaNeiDatiPersonali() = runBlocking<Unit> {
    val world = newWorld()
    val store = personal()
    val albums = RecordingAlbums()
    val trips = trips(world, store, albums)
    trips.detect(2.minutes)
    val trip = trips.proposals.value.proposals.single { it.kind == TripKind.TRIP }
    val event = trips.proposals.value.proposals.single { it.kind == TripKind.EVENT }
    val created = trips.accept(trip.id) as TripAcceptOutcome.Created
    assertThat(world.room.db.tripDao().proposal(trip.id.value)!!.albumId).isEqualTo(created.album.value)
    assertThat((albums.created.single().rules!!.period as SmartPeriod.Ranges).ranges).containsExactly(trip.days)
    // "Non mi interessa": una voce dei dati personali, niente nel catalogo.
    trips.dismiss(event.id)
    assertThat(trips.proposals.value.proposals).isEmpty()
    assertThat(store.state().keys.map { it.field }).containsExactly(PersonalFields.tripDismissed(event.id.value))
  }

  @Test
  fun senzaGpsLaCasaSiTogliedaRoom() = runBlocking<Unit> {
    val world = newWorld()
    val trips = trips(world, personal())
    trips.detect(2.minutes)
    assertThat(world.room.db.tripDao().home()).isNotEmpty()
    // Le coordinate spariscono dalle foto (il telefono non le scrive piu'): al giro dopo la stima di prima non resta.
    world.room.db.openHelper.writableDatabase.execSQL("UPDATE version_metadata SET latitude = NULL, longitude = NULL")
    val report = trips.detect(2.minutes)
    assertThat(report.homeKnown).isFalse()
    assertThat(world.room.db.tripDao().home()).isEmpty()
    assertThat(trips.proposals.value.proposals.map { it.kind }.toSet()).containsExactly(TripKind.EVENT)
  }

  /** Una riga di `trip_proposal` che non si capisce (tipo sconosciuto, giorno impossibile) non e' una proposta: si salta, mai un'eccezione. */
  @Test
  fun unaRigaCheNonSiCapisceNonEUnaProposta() = runBlocking<Unit> {
    val room = AlbumsRoom().also { rooms += it }
    val dao = room.db.tripDao()
    val good = "c".repeat(64)
    dao.upsertProposals(
      listOf(
        TripProposalEntity(tripId = "a".repeat(64), kind = "boh", fromDay = 20_260_101, toDay = 20_260_102, label = "x", mediaCount = 1, coverMediaId = null, detectedAt = 1),
        TripProposalEntity(tripId = "b".repeat(64), kind = "trip", fromDay = 20_261_301, toDay = 20_261_302, label = "x", mediaCount = 1, coverMediaId = null, detectedAt = 1),
        TripProposalEntity(tripId = "short", kind = "trip", fromDay = 20_260_101, toDay = 20_260_102, label = "x", mediaCount = 1, coverMediaId = null, detectedAt = 1),
        TripProposalEntity(tripId = good, kind = "event", fromDay = 20_260_101, toDay = 20_260_101, label = "Giovedi 1 gennaio 2026", mediaCount = 31, coverMediaId = null, placeIds = "5,x,7", detectedAt = 1),
      ),
    )
    val read = RoomTripStore(room.db).proposals()
    assertThat(read.map { it.id.value }).containsExactly(good)
    assertThat(read.single().placeIds).containsExactly(5, 7).inOrder()
    assertThat(read.single().days.from).isEqualTo(LocalDate.of(2026, 1, 1))
  }

  @Test
  fun leTabelleSonoLocaliENienteDiViaggiNegliAltriDatiPersonali() = runBlocking<Unit> {
    val world = newWorld()
    val store = personal()
    val trips = trips(world, store)
    trips.detect(2.minutes)
    trips.proposals.value.proposals.forEach { trips.dismiss(it.id) }
    // I dati personali hanno solo l'impronta della proposta scartata: nessuna voce con un nome, un luogo o una coordinata.
    for ((key, entry) in store.state()) {
      assertThat(key.field).startsWith("trip_dismissed:")
      assertThat(entry.fingerprint).isNull()
    }
    assertThat(store.state()).hasSize(2)
  }
}
