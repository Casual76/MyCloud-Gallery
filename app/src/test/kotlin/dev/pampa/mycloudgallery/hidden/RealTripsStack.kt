package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.albums.CatalogAlbumCandidates
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.trips.CatalogTrips
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 7 (P9/P7, D22, E-H7; 26/10): lo STACK VERO dei viaggi (P7: `CatalogTrips`, le tabelle v6 `home_estimate` e `trip_proposal`, la rilevazione sulle query
// dei candidati di P1) sopra il catalogo VERO di un mondo casuale, con la visibilita' vera (`CatalogVisibleMedia`, `CatalogHiddenMask`) e lo store vero dei
// dati personali. Il mondo e' una **copia** privata di quello della prova (stesso seme, stesso catalogo vero, ma un database suo): la sonda scrive i metadati
// (il GPS, l'ora) e nasconde altri media dopo la rilevazione, e le altre sonde non devono accorgersene.
//
// La storia che il mondo racconta e' decisa dagli indici, non dal caso: i media del 7, 8, 9 e 10 marzo sono a Oslo di giorno (9-18), tutti gli altri sono a casa a
// Firenze di sera (19-22). Cosi' la casa e i giorni lontani si ricavano a mano dall'oracolo del mondo, senza usare il codice che si prova.

internal class RealTripsStack private constructor(val w: BuiltWorld) : AutoCloseable {
  val today: LocalDate = LocalDate.of(2026, 10, 22)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  val store = LedgerPersonalStore(TrustIds.random(), { NOW }, InMemoryPersonalLedger(), WorldFingerprints(w))

  val trips: CatalogTrips = CatalogTrips(
    w.db, CatalogAlbumCandidates { w.catalog.albums }, CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot)), CatalogHiddenMask(w.db), store, NoAlbums, FakePlaces(), { today },
    FakeAlbumClocks(), scope,
  )

  /** Da casa a Oslo, e il contrario: i media del mondo prendono il GPS, il luogo e l'ora della storia (il 7, 8, 9 e 10 marzo a Oslo di giorno, il resto a Firenze di sera). */
  private suspend fun tellTheStory() {
    for (m in w.spec.media) {
      val away = isAway(m)
      val local = m.day.atTime(if (away) 9 + m.index % 10 else 19 + m.index % 4, m.index % 60)
      val utc = local.toInstant(ZoneOffset.ofHours(2)).toEpochMilli()
      val place = if (away) FakePlaceData.OSLO else FakePlaceData.FIRENZE
      val jitter = (m.index % 7 - 3) * 0.001
      val id = w.ids[m.index]
      w.catalog.updateMetadata(
        w.catalog.media(id)!!.currentVersionId,
        MediaMetadata(
          mimeType = if (m.kind.isVideo) "video/mp4" else "image/jpeg", width = 4_000, height = 3_000, durationMillis = if (m.kind.isVideo) 8_000L else null,
          captureTime = CaptureTime(utc, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
          location = GeoPoint(place.latitude + jitter, place.longitude + jitter), place = PlaceRef(place.place.id.value, 1),
        ),
      )
    }
  }

  override fun close() {
    scope.cancel()
    w.close()
  }

  companion object {
    private const val NOW = 1_780_000_000_000L

    /** Il mondo e' lontano da casa il 7, 8, 9 e 10 marzo (giorni 6-9 dal primo). */
    fun isAway(m: MediaSpec): Boolean = m.day.dayOfMonth in 7..10

    suspend fun build(world: BuiltWorld): RealTripsStack = RealTripsStack(world).also { it.tellTheStory() }
  }
}
