package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
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
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlaceMatch
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debito del 27/10 (Fase 3 §2.3): al cambio del dataset dei luoghi i `place_id` si ricalcolano **una volta sola**, a tappe e con la ripresa dopo la morte del
 * processo; con lo stesso dataset non si fa niente. Sul catalogo vero (Room in memoria) e su un dataset finto che conta le domande.
 */
@RunWith(AndroidJUnit4::class)
class PlacesRecomputeTest : PhoneSyncTestBase() {

  /** Una zona del dataset finto: i punti entro mezzo grado di latitudine da [lat] hanno quel luogo, con quella precisione. */
  private class Zone(val lat: Double, val id: Int, val precision: PlacePrecision = PlacePrecision.CITY)

  /** Un dataset finto: conta le domande, e su una latitudine sbagliata lancia (un dataset rovinato). */
  private class FakeGeocoder(override val dataset: Int, private val zones: List<Zone>) : ReverseGeocoder {
    var asked = 0
      private set

    override fun nearest(p: GeoPoint): PlaceMatch? {
      asked++
      check(p.latitude < BROKEN_LATITUDE) { "dataset rovinato" }
      val zone = zones.firstOrNull { abs(p.latitude - it.lat) < 0.5 } ?: return null
      return PlaceMatch(Place(PlaceId(zone.id), "luogo", null, "IT", "Italia", null), 1_000, zone.precision)
    }

    override fun place(id: PlaceId): Place? = null
  }

  private val firenze = 43.77
  private val milano = 45.46
  private val mare = 40.0

  /** Il dataset 2: Firenze ha un altro id, Milano lo stesso, e il punto in mare non ha piu' un luogo vicino. */
  private fun datasetTwo() = FakeGeocoder(2, listOf(Zone(firenze, 112), Zone(milano, 200), Zone(mare, 300, PlacePrecision.REGION_ONLY)))

  private var seed = 0

  /** Un media del telefono con i suoi metadati; ritorna la sua versione. */
  private suspend fun photoAt(lat: Double?, place: PlaceRef?): VersionId {
    val n = ++seed
    val bytes = Random(n.toLong()).nextBytes(512)
    val fingerprint = Fingerprint.ofContent(bytes)
    val where = Location.Phone(PRIMARY, "DCIM/Camera/", "IMG_$n.jpg", 10_000L + n, "store-v1")
    val takenAt = T0 + n * MINUTE
    val media = checkNotNull(
      catalog.record(Observation(where, FileStamp(fingerprint.size, T0 + n, 7), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, takenAt), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId,
    )
    val version = checkNotNull(catalog.media(media)).currentVersionId
    catalog.updateMetadata(
      version,
      MediaMetadata(
        mimeType = "image/jpeg", width = 4_000, height = 3_000,
        captureTime = CaptureTime(takenAt, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
        location = lat?.let { GeoPoint(it, 11.0) }, place = place,
      ),
    )
    return version
  }

  private fun recompute(geocoder: ReverseGeocoder, batch: Int = PlacesRecompute.BATCH, clock: () -> Long = { 0L }) =
    PlacesRecompute(catalog, geocoder, monotonicMs = clock, dispatchers = InlineDispatchers, batch = batch)

  private suspend fun placeOf(version: VersionId): Pair<Int?, Int?> =
    checkNotNull(db.versionDao().metadata(version.value)).let { it.placeId to it.placeDataset }

  private suspend fun cellPlaceOf(version: VersionId): Int? {
    val media = MediaId(checkNotNull(db.versionDao().get(version.value)).mediaId)
    return checkNotNull(db.mediaDao().get(media.value)).placeId
  }

  /** La Libreria del dataset 1: due luoghi, uno senza GPS, uno mai calcolato, uno in mare. */
  private class Library(val renamed: VersionId, val same: VersionId, val noGps: VersionId, val neverComputed: VersionId, val atSea: VersionId)

  private suspend fun libraryOfDatasetOne(): Library {
    catalog.saveMediaStoreCursor(MediaStoreCursor(PRIMARY, msVersion = "store-v1"))
    catalog.setPlacesDataset(1)
    return Library(
      renamed = photoAt(firenze, PlaceRef(111, 1)),
      same = photoAt(milano, PlaceRef(200, 1)),
      noGps = photoAt(null, null),
      neverComputed = photoAt(firenze, null),
      atSea = photoAt(mare, PlaceRef(300, 1)),
    )
  }

  @Test
  fun conUnDatasetNuovoIPlaceIdSiRicalcolanoUnaVoltaSola() = runBlocking<Unit> {
    val library = libraryOfDatasetOne()
    assertThat(cellPlaceOf(library.renamed)).isEqualTo(111)
    val geocoder = datasetTwo()

    val first = recompute(geocoder).run(5.minutes)

    assertThat(first.outcome).isEqualTo(PlacesRecompute.Outcome.DONE)
    // Quattro versioni con GPS; il luogo cambia a tre (id nuovo, mai calcolato, perso), non a quella che ha lo stesso id.
    assertThat(first.recomputed).isEqualTo(4)
    assertThat(first.changed).isEqualTo(3)
    assertThat(geocoder.asked).isEqualTo(4)
    assertThat(placeOf(library.renamed)).isEqualTo(112 to 2)
    assertThat(placeOf(library.same)).isEqualTo(200 to 2)
    assertThat(placeOf(library.neverComputed)).isEqualTo(112 to 2)
    // Un punto che nel dataset nuovo non ha un luogo vicino perde il luogo, e porta lo stesso il dataset: non torna in coda.
    assertThat(placeOf(library.atSea)).isEqualTo(null to 2)
    // Senza GPS non c'e' niente da ricalcolare.
    assertThat(placeOf(library.noGps)).isEqualTo(null to null)
    // La cella della Libreria (`media.place_id`) segue il luogo nuovo.
    assertThat(cellPlaceOf(library.renamed)).isEqualTo(112)
    assertThat(cellPlaceOf(library.neverComputed)).isEqualTo(112)
    assertThat(cellPlaceOf(library.atSea)).isNull()
    assertThat(catalog.placesDataset()).isEqualTo(2)

    // Una volta sola: il secondo giro (e quello della notte dopo) non fa nemmeno una domanda al dataset.
    val second = recompute(geocoder).run(5.minutes)
    assertThat(second.outcome).isEqualTo(PlacesRecompute.Outcome.CURRENT)
    assertThat(second.recomputed).isEqualTo(0)
    assertThat(geocoder.asked).isEqualTo(4)
  }

  @Test
  fun conLoStessoDatasetNonSiFaNiente() = runBlocking<Unit> {
    val library = libraryOfDatasetOne()
    catalog.setPlacesDataset(2)
    val geocoder = datasetTwo()

    val report = recompute(geocoder).run(5.minutes)

    assertThat(report.outcome).isEqualTo(PlacesRecompute.Outcome.CURRENT)
    assertThat(geocoder.asked).isEqualTo(0)
    assertThat(placeOf(library.renamed)).isEqualTo(111 to 1)
    assertThat(cellPlaceOf(library.renamed)).isEqualTo(111)
  }

  @Test
  fun senzaIlDatasetApertoNonSiFaNienteENonSiSegnaNulla() = runBlocking<Unit> {
    val library = libraryOfDatasetOne()
    val geocoder = FakeGeocoder(0, emptyList())

    val report = recompute(geocoder).run(5.minutes)

    assertThat(report.outcome).isEqualTo(PlacesRecompute.Outcome.NOT_READY)
    assertThat(geocoder.asked).isEqualTo(0)
    assertThat(catalog.placesDataset()).isEqualTo(1)
    assertThat(placeOf(library.renamed)).isEqualTo(111 to 1)
  }

  @Test
  fun aTappe_ConLaRipresaDopoLaMorteDelProcesso_SenzaRifareNiente() = runBlocking<Unit> {
    val library = libraryOfDatasetOne()
    val geocoder = datasetTwo()
    var now = 0L
    // Un'ora per ogni lettura dell'orologio e un budget di un millisecondo: ogni giro fa un lotto solo (due versioni) e si ferma.
    val tick = { now += 3_600_000L; now }

    val first = recompute(geocoder, batch = 2, clock = tick).run(Duration.ZERO)
    assertThat(first.outcome).isEqualTo(PlacesRecompute.Outcome.PARTIAL)
    assertThat(first.recomputed).isEqualTo(2)
    // A meta' non si dichiara fatto: il dataset del catalogo e' ancora il vecchio.
    assertThat(catalog.placesDataset()).isEqualTo(1)

    // Il processo muore: un oggetto nuovo sullo stesso database riprende dalle versioni che mancano.
    val second = recompute(geocoder, batch = 2, clock = tick).run(Duration.ZERO)
    assertThat(second.outcome).isEqualTo(PlacesRecompute.Outcome.PARTIAL)
    assertThat(second.recomputed).isEqualTo(2)
    assertThat(catalog.placesDataset()).isEqualTo(1)

    val third = recompute(geocoder, batch = 2, clock = tick).run(Duration.ZERO)
    assertThat(third.outcome).isEqualTo(PlacesRecompute.Outcome.DONE)
    assertThat(third.recomputed).isEqualTo(0)
    assertThat(catalog.placesDataset()).isEqualTo(2)
    // Ogni versione con GPS e' stata chiesta al dataset una volta sola, in tre giri.
    assertThat(geocoder.asked).isEqualTo(4)
    assertThat(placeOf(library.renamed)).isEqualTo(112 to 2)
    assertThat(placeOf(library.atSea)).isEqualTo(null to 2)
  }

  @Test
  fun unPuntoCheIlDatasetNonSaLeggereValeNessunLuogo_ENonBloccaIlGiro() = runBlocking<Unit> {
    val library = libraryOfDatasetOne()
    val broken = photoAt(BROKEN_LATITUDE + 1, PlaceRef(400, 1))
    val geocoder = datasetTwo()

    val report = recompute(geocoder).run(5.minutes)

    assertThat(report.outcome).isEqualTo(PlacesRecompute.Outcome.DONE)
    assertThat(placeOf(broken)).isEqualTo(null to 2)
    assertThat(placeOf(library.renamed)).isEqualTo(112 to 2)
    assertThat(catalog.placesDataset()).isEqualTo(2)
  }

  private companion object {
    const val BROKEN_LATITUDE = 60.0
  }
}
