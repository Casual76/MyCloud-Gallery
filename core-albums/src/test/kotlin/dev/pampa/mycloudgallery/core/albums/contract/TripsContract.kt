package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.TripDismissals
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * LA SUITE DI CONTRATTO di [Trips] (docs/fase7-progetto.md §2.3, D18-D20; E-AL15, E-AL16, E-H7). La passa l'esecutore di riferimento
 * (`InMemoryTrips`); P7 la fa passare a trips/ (con `home_estimate` solo locale). Storie piccole e chiare: casa a Firenze, un viaggio a
 * Oslo, una festa con molte foto in poche ore.
 */
abstract class TripsContract {

  protected abstract fun trips(library: FakeAlbumLibrary, today: LocalDate, albums: Albums, dismissals: TripDismissals): Trips

  protected abstract fun albumsFor(library: FakeAlbumLibrary): Albums

  private val today = LocalDate.of(2026, 10, 22)
  private val firenze = GeoPoint(43.7696, 11.2558)
  private val oslo = GeoPoint(59.9139, 10.7522)
  private var n = 0

  private fun shot(day: LocalDate, hour: Int, gps: GeoPoint?, minute: Int = 0, hidden: Boolean = false, place: Int = 0): FakeMedia {
    val utc = day.atTime(hour, 0).plusMinutes(minute.toLong()).toInstant(ZoneOffset.ofHours(1)).toEpochMilli()
    return FakeMedia(FakeAlbumLibrary.id(++n), utc, zoneOffsetMinutes = 60, gps = gps, hidden = hidden, placeId = place, onPhone = true)
  }

  /** 60 giorni di sere a casa negli ultimi 12 mesi (salvo i giorni dei viaggi, [away]): la casa e' stimabile. */
  private fun home(library: FakeAlbumLibrary, vararg away: LocalDayRange) {
    for (d in 0 until 60) {
      val day = today.minusDays(10L + d * 4L)
      if (away.any { day in it }) continue
      library.put(shot(day, 21, firenze), shot(day, 13, firenze))
    }
  }

  /** Un viaggio a Oslo di [days] giorni a partire da [from], 5 foto al giorno. */
  private fun oslo(library: FakeAlbumLibrary, from: LocalDate, days: Int, hidden: Boolean = false): List<MediaId> =
    (0 until days).flatMap { d -> (0 until 5).map { h -> shot(from.plusDays(d.toLong()), 10 + h, oslo, hidden = hidden, place = 3143244) } }
      .also { library.put(*it.toTypedArray()) }.map { it.id }

  private suspend fun Trips.detected(): TripProposals {
    detect(1.minutes)
    return proposals.value
  }

  @Test
  fun unViaggioLontanoDaCasaDiventaUnaProposta() = runBlocking<Unit> {
    val lib = FakeAlbumLibrary()
    home(lib, LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 17)))
    val trip = oslo(lib, LocalDate.of(2026, 3, 14), 4)
    val t = trips(lib, today, albumsFor(lib), TripDismissals())
    val found = t.detected()
    assertThat(found.homeKnown).isTrue()
    val proposal = found.proposals.single { it.kind == TripKind.TRIP }
    assertThat(proposal.days).isEqualTo(LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 17)))
    assertThat(proposal.count).isEqualTo(trip.size)
    assertThat(proposal.cover).isIn(trip)
    assertThat(proposal.placeIds).containsExactly(3143244)
    assertThat(proposal.id).isEqualTo(TripId.of(TripKind.TRIP, proposal.days, listOf(3143244)))
  }

  /** E-AL16: senza GPS niente casa e niente viaggi; gli eventi (molte foto in poche ore) si', perche' non vogliono il GPS. */
  @Test
  fun senzaGpsSoloEventi() = runBlocking<Unit> {
    val lib = FakeAlbumLibrary()
    for (d in 1..40) lib.put(shot(today.minusDays(d.toLong()), 12, null))
    val party = LocalDate.of(2026, 4, 11)
    val festa = (0 until 40).map { shot(party, 18, null, minute = it * 3) }
    lib.put(*festa.toTypedArray())
    val found = trips(lib, today, albumsFor(lib), TripDismissals()).detected()
    assertThat(found.homeKnown).isFalse()
    assertThat(found.proposals.map { it.kind }).containsExactly(TripKind.EVENT)
    assertThat(found.proposals.single().count).isEqualTo(40)
    assertThat(found.proposals.single().days).isEqualTo(LocalDayRange(party, party))
  }

  /** E-H7: un viaggio di soli nascosti non si propone; uno con qualche nascosto si propone senza di loro. */
  @Test
  fun iNascostiNonFannoNeContanoViaggi() = runBlocking<Unit> {
    val lib = FakeAlbumLibrary()
    home(lib, LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 16)), LocalDayRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3)))
    oslo(lib, LocalDate.of(2026, 3, 14), 3, hidden = true)
    val visible = oslo(lib, LocalDate.of(2026, 6, 1), 3)
    lib.hide(visible.first())
    val found = trips(lib, today, albumsFor(lib), TripDismissals()).detected()
    val trip = found.proposals.single { it.kind == TripKind.TRIP }
    assertThat(trip.days.from).isEqualTo(LocalDate.of(2026, 6, 1))
    assertThat(trip.count).isEqualTo(visible.size - 1)
    assertThat(trip.cover).isNotEqualTo(visible.first())
  }

  /** D20: "Non mi interessa" vale su ogni dispositivo dell'account; "Crea album" fa un album smart una volta sola. */
  @Test
  fun scartareEAccettareUnaVoltaSola() = runBlocking<Unit> {
    val lib = FakeAlbumLibrary()
    home(lib, LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 16)), LocalDayRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
    oslo(lib, LocalDate.of(2026, 3, 14), 3)
    oslo(lib, LocalDate.of(2026, 7, 1), 2)
    val dismissals = TripDismissals()
    val albums = albumsFor(lib)
    val phone = trips(lib, today, albums, dismissals)
    val tablet = trips(lib, today, albums, dismissals)
    val first = phone.detected().proposals.filter { it.kind == TripKind.TRIP }
    assertThat(first).hasSize(2)
    phone.dismiss(first.first().id)
    assertThat(tablet.detected().proposals.map { it.id }).doesNotContain(first.first().id)
    val accepted = phone.accept(first.last().id) as TripAcceptOutcome.Created
    assertThat(phone.accept(first.last().id)).isEqualTo(TripAcceptOutcome.AlreadyAlbum(accepted.album))
    val album = albums.all.first { it.byId(accepted.album) != null }.byId(accepted.album) as Album.Smart
    assertThat((album.rules.period as SmartPeriod.Ranges).ranges).containsExactly(first.last().days)
    assertThat(phone.proposals.value.proposals.map { it.id }).doesNotContain(first.last().id)
  }

  /** D18: le foto a casa non sono un viaggio, anche tante in un giorno. */
  @Test
  fun aCasaNonCeViaggio() = runBlocking<Unit> {
    val lib = FakeAlbumLibrary()
    home(lib)
    val found = trips(lib, today, albumsFor(lib), TripDismissals()).detected()
    assertThat(found.proposals.filter { it.kind == TripKind.TRIP }).isEmpty()
  }
}
