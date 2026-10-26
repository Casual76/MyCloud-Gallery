package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il `Trips` VERO (`CatalogTrips`) nei suoi comportamenti (docs/fase7-progetto.md D20, D22, E-H7): "Crea album" una volta sola e con le regole
 * giuste (mai la casa), "Non mi interessa" per sempre e su ogni dispositivo, i nascosti che non compaiono mai (anche quelli nascosti dopo la
 * rilevazione), la ripresa dopo la morte del processo, il tempo a disposizione.
 */
class CatalogTripsTest {

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  private fun story(prefix: String = "Firenze") = TripStories.all().first { it.name.startsWith(prefix) }

  private fun harness(story: Story = story(), customize: StoryLibrary.() -> Unit = {}): TripsHarness {
    val library = StoryLibrary(story.shots).also { it.hidden += story.hidden }.apply(customize)
    return TripsHarness(library, story.today)
  }

  private fun List<TripProposal>.trip(place: Spot) = first { it.kind == TripKind.TRIP && place.placeId in it.placeIds }

  // --- Crea album (D20) ----------------------------------------------------------------------------------------------------------------

  @Test
  fun creaAlbumFaUnAlbumSmartConIlPeriodoEIlLuogoDelViaggio() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val oslo = h.trips.proposals.value.proposals.trip(Places.OSLO)
      val outcome = h.trips.accept(oslo.id) as TripAcceptOutcome.Created
      val created = h.albums.created.single()
      assertThat(created.id).isEqualTo(outcome.album)
      assertThat(created.kind).isEqualTo(AlbumKind.SMART)
      assertThat(created.title).isEqualTo(oslo.label)
      val rules = created.rules!!
      assertThat((rules.period as SmartPeriod.Ranges).ranges).containsExactly(oslo.days)
      assertThat(rules.text).isNull()
      assertThat(rules.sources).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.SmartSource.ALL)
      val place = rules.place!!
      assertThat(place.placeIds).containsExactly(Places.OSLO.placeId)
      assertThat(TripGeometry.km(place.center.latitude, place.center.longitude, Places.OSLO.lat, Places.OSLO.lon)).isLessThan(6.0)
      assertThat(place.radiusKm).isAtLeast(5)
      // La proposta e' diventata un album: non si ripropone, e non si accetta due volte.
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(oslo.id)
      assertThat(h.trips.accept(oslo.id)).isEqualTo(TripAcceptOutcome.AlreadyAlbum(outcome.album))
      assertThat(h.albums.created).hasSize(1)
    }
  }

  @Test
  fun unAlbumNatoDaUnaPropostaNonSiRiproponeConLaRilevazioneDopo() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val roma = h.trips.proposals.value.proposals.trip(Places.ROMA)
      val created = h.trips.accept(roma.id) as TripAcceptOutcome.Created
      h.trips.detect(1.minutes)
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(roma.id)
      assertThat(h.store.saved.single { it.id == roma.id }.album).isEqualTo(created.album)
      // Anche dopo la morte del processo: un'altra istanza sulla stessa memoria locale.
      val reborn = TripsHarness(h.library, h.today, h.store, h.dismissals, h.albums, h.clocks)
      reborn.trips.load()
      assertThat(reborn.trips.proposals.value.proposals.map { it.id }).doesNotContain(roma.id)
      assertThat(reborn.trips.accept(roma.id)).isEqualTo(TripAcceptOutcome.AlreadyAlbum(created.album))
      reborn.close()
    }
  }

  /** Il luogo dell'album non e' la casa: un evento (che potrebbe essere a casa) ha solo il periodo, un viaggio ha il cerchio della meta. */
  @Test
  fun laCasaNonFiniscemaiInUnAlbum() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val all = h.trips.proposals.value.proposals
      assertThat(all.map { it.kind }.toSet()).containsExactly(TripKind.TRIP, TripKind.EVENT)
      val home = h.store.home!!.home
      for (p in all) {
        h.trips.accept(p.id)
        val c = h.albums.created.last()
        assertThat(c.title).isEqualTo(p.label)
        assertThat(c.title).doesNotContain("Firenze")
        if (p.kind == TripKind.EVENT) {
          assertThat(c.rules!!.place).isNull()
        } else {
          val center = c.rules!!.place!!.center
          assertThat(TripGeometry.km(center.latitude, center.longitude, home.lat, home.lon)).isGreaterThan(50.0)
        }
      }
      assertThat(h.albums.created).hasSize(all.size)
    }
  }

  @Test
  fun unaPropostaSconosciutaOScartataNonSiAccetta() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      assertThat(h.trips.accept(TripId("0".repeat(64)))).isEqualTo(TripAcceptOutcome.NotFound)
      val first = h.trips.proposals.value.proposals.first()
      h.trips.dismiss(first.id)
      assertThat(h.trips.accept(first.id)).isEqualTo(TripAcceptOutcome.NotFound)
      assertThat(h.albums.created).isEmpty()
    }
  }

  // --- Non mi interessa (D20) -------------------------------------------------------------------------------------------------------------

  @Test
  fun nonMiInteressaVaViaPerSempreESuOgniDispositivoDelloStessoAccount() = runBlocking<Unit> {
    harness().use { phone ->
      val tablet = phone.anotherDevice()
      phone.trips.detect(1.minutes)
      tablet.trips.detect(1.minutes)
      val victim = phone.trips.proposals.value.proposals.first()
      phone.trips.dismiss(victim.id)
      assertThat(phone.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim.id)
      // Sul tablet sparisce subito (la scelta e' dell'account), e non torna con una rilevazione nuova.
      assertThat(tablet.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim.id)
      tablet.trips.detect(1.minutes)
      phone.trips.detect(1.minutes)
      assertThat(tablet.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim.id)
      assertThat(phone.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim.id)
      // Le altre proposte restano.
      assertThat(phone.trips.proposals.value.proposals).isNotEmpty()
      tablet.close()
    }
  }

  // --- nascosti (D22, E-H7) -----------------------------------------------------------------------------------------------------------------

  @Test
  fun unNascostoDopoLaRilevazioneNonSiContaNeSiMostra() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val oslo = h.trips.proposals.value.proposals.trip(Places.OSLO)
      val before = oslo.count
      // L'utente nasconde la copertina: al ricontrollo la copertina e' un'altra e il conteggio cala di uno.
      h.library.hidden += oslo.cover
      h.trips.refresh()
      val after = h.trips.proposals.value.proposals.trip(Places.OSLO)
      assertThat(after.count).isEqualTo(before - 1)
      assertThat(after.cover).isNotEqualTo(oslo.cover)
      assertThat(h.library.maskCalls).isGreaterThan(0)
      // Un membro di un gruppo nascosto (il contagio che le query non hanno) non si conta.
      val victim = h.library.all.first { it.day in oslo.days && it.media !in h.library.hidden }
      h.library.contagion += victim.media
      h.trips.refresh()
      assertThat(h.trips.proposals.value.proposals.trip(Places.OSLO).count).isEqualTo(before - 2)
    }
  }

  @Test
  fun unViaggioDiSoliNascostiNonSiPropone() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val roma = h.trips.proposals.value.proposals.trip(Places.ROMA)
      h.library.all.filter { it.day in roma.days }.forEach { h.library.hidden += it.media }
      h.trips.refresh()
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(roma.id)
      // E la rilevazione dopo non lo trova piu'.
      h.trips.detect(1.minutes)
      assertThat(h.trips.proposals.value.proposals.map { it.days }).doesNotContain(roma.days)
    }
  }

  // --- memoria locale e tempo ------------------------------------------------------------------------------------------------------------

  @Test
  fun alRiavvioLeProposteSiRileggonoDallaMemoriaLocale() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val shown = h.trips.proposals.value
      assertThat(h.store.replacements).isEqualTo(1)
      val reborn = TripsHarness(h.library, h.today, h.store, h.dismissals, h.albums, h.clocks)
      reborn.trips.load()
      assertThat(reborn.trips.proposals.value).isEqualTo(shown)
      assertThat(reborn.trips.proposals.value.homeKnown).isTrue()
      reborn.close()
    }
  }

  @Test
  fun seIlTempoFinisceLeProposteRestanoQuelleDiPrima() = runBlocking<Unit> {
    val s = story()
    val library = StoryLibrary(s.shots)
    TripsHarness(library, s.today).use { h ->
      h.trips.detect(1.minutes)
      val shown = h.trips.proposals.value
      // Un secondo di orologio a ogni lettura e mezzo secondo a disposizione: la lettura delle pagine si ferma subito, senza niente di parziale.
      val slow = TripsHarness(library, s.today, h.store, h.dismissals, h.albums, TickingClocks(stepMs = 1_000))
      val report = slow.trips.detect(kotlin.time.Duration.parse("0.5s"))
      assertThat(report.incomplete).isTrue()
      assertThat(report.homeKnown).isTrue()
      assertThat(h.store.replacements).isEqualTo(1)
      assertThat(h.store.home).isNotNull()
      slow.close()
      assertThat(h.trips.proposals.value).isEqualTo(shown)
    }
  }

  @Test
  fun laCasaSiConservaSoloInLocaleESiTogliePerLaRilevazioneSenzaGps() = runBlocking<Unit> {
    val s = story("Telefono nuovo")
    harness(s).use { h ->
      assertThat(h.trips.detect(1.minutes).homeKnown).isTrue()
      assertThat(h.store.home).isNotNull()
      // Se il GPS sparisce (le foto con posizione diventano senza), la stima di prima non resta a dire dove si abita.
      val bare = StoryLibrary(s.shots.map { it.copy(gps = null, placeId = 0) })
      val h2 = TripsHarness(bare, s.today, h.store, h.dismissals, h.albums, h.clocks)
      assertThat(h2.trips.detect(1.minutes).homeKnown).isFalse()
      assertThat(h.store.home).isNull()
      h2.close()
    }
  }

  @Test
  fun iTitoliDelleProposte() = runBlocking<Unit> {
    harness().use { h ->
      h.trips.detect(1.minutes)
      val parigi = h.trips.proposals.value.proposals.trip(Places.PARIGI)
      assertThat(parigi.label).isEqualTo("Parigi, dicembre 2025–gennaio 2026")
      val oslo = h.trips.proposals.value.proposals.trip(Places.OSLO)
      assertThat(oslo.label).isEqualTo("Oslo, marzo 2025")
      val party = h.trips.proposals.value.proposals.first { it.kind == TripKind.EVENT && it.days.from == d(2025, 6, 21) }
      assertThat(party.label).isEqualTo("Sabato 21 giugno 2025")
    }
  }
}
