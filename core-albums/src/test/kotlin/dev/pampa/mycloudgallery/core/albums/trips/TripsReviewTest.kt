package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Le correzioni dei viaggi dopo la revisione di fine Fase 7: VG-1 (la ricerca c'e' stata o no, e si ricorda), VG-2 ("Crea album" non si spezza se la pagina
 * si chiude; un viaggio scartato resta scartato anche se, essendo in corso, si allunga), R16 (titolo e luoghi di una proposta seguono i media visibili;
 * un viaggio il cui cerchio comprende casa ha solo il periodo) e R17 (un guasto di lettura non svuota le proposte).
 */
class TripsReviewTest {

  private val today = LocalDate.of(2026, 10, 22)

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  private fun story(name: String, seed: Long = 11, block: StoryBuilder.() -> Unit): Story = StoryBuilder(name, seed, today).apply(block).build()

  private fun StoryBuilder.livesAt(home: Spot) = residence(first, today, Habits(home))

  private fun harness(story: Story, customize: StoryLibrary.() -> Unit = {}): TripsHarness {
    val library = StoryLibrary(story.shots).also { it.hidden += story.hidden }.apply(customize)
    return TripsHarness(library, story.today)
  }

  // --- VG-1 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun primaDellaPrimaRicercaNonSiSaSeCeUnaCasa() = runBlocking<Unit> {
    harness(TripStories.all().first { it.name.startsWith("Nessun GPS") }).use { h ->
      h.trips.load()
      // Mai cercati: «nessuna casa» non vuol dire niente (prima la sezione diceva «Servono foto con la posizione» a chi aveva migliaia di foto con il GPS).
      assertThat(h.trips.proposals.value.searched).isFalse()
      assertThat(h.trips.proposals.value.homeKnown).isFalse()

      h.trips.detect(1.minutes)
      // Cercati e senza una casa: adesso si puo' dire che servono le posizioni.
      assertThat(h.trips.proposals.value.searched).isTrue()
      assertThat(h.trips.proposals.value.homeKnown).isFalse()
    }
  }

  @Test
  fun cheLaRicercaCeStataSiRicordaAnchePerUnaLibreriaSenzaCasa() = runBlocking<Unit> {
    harness(TripStories.all().first { it.name.startsWith("Nessun GPS") }).use { h ->
      h.trips.detect(1.minutes)
      assertThat(h.store.wasSearched).isTrue()

      // Il processo muore e rinasce sulla stessa memoria locale: la sezione non torna a «non ancora cercati».
      val reborn = TripsHarness(h.library, h.today, h.store, h.dismissals, h.albums, h.clocks)
      reborn.trips.load()
      assertThat(reborn.trips.proposals.value.searched).isTrue()
      assertThat(reborn.trips.proposals.value.homeKnown).isFalse()
      reborn.close()
    }
  }

  @Test
  fun unaLibreriaSenzaUnoScattoProprioNonEUnaRicerca() = runBlocking<Unit> {
    harness(story("vuota") { }).use { h ->
      val report = h.trips.detect(1.minutes)
      assertThat(report.incomplete).isTrue()
      assertThat(h.trips.proposals.value.searched).isFalse()
      assertThat(h.store.wasSearched).isFalse()
    }
  }

  // --- VG-2 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun unViaggioScartatoRestaScartatoSeEssendoInCorsoSiAllunga() = runBlocking<Unit> {
    val short = story("in corso") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 10, 12), 3, Places.OSLO)
    }
    val longer = story("allungato") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 10, 12), 5, Places.OSLO)
    }
    harness(short).use { h ->
      h.trips.detect(1.minutes)
      val running = h.trips.proposals.value.proposals.single { it.kind == TripKind.TRIP && it.days.from == d(2026, 10, 12) }
      h.trips.dismiss(running.id)
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(running.id)

      // Passano due giorni, arrivano le foto: la fine cambia, e con lei l'impronta della proposta.
      val later = TripsHarness(StoryLibrary(longer.shots), today, h.store, h.dismissals, h.albums, h.clocks)
      later.trips.detect(1.minutes)
      val extended = later.trips.proposals.value.proposals.filter { it.kind == TripKind.TRIP && it.days.from == d(2026, 10, 12) }
      // Prima tornava con un'impronta nuova, come una proposta mai vista.
      assertThat(extended).isEmpty()
      assertThat(later.trips.accept(running.id)).isEqualTo(TripAcceptOutcome.NotFound)
      later.close()
    }
  }

  @Test
  fun unAltroViaggioCheNonCominciaLoStessoGiornoNonSiScartaPerSbaglio() = runBlocking<Unit> {
    val both = story("due viaggi") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 3, 10), 4, Places.OSLO)
      trip(d(2026, 6, 10), 4, Places.ROMA)
    }
    harness(both).use { h ->
      h.trips.detect(1.minutes)
      val trips = h.trips.proposals.value.proposals.filter { it.kind == TripKind.TRIP }
      assertThat(trips).hasSize(2)
      h.trips.dismiss(trips.first { it.days.from == d(2026, 3, 10) }.id)
      assertThat(h.trips.proposals.value.proposals.filter { it.kind == TripKind.TRIP }.map { it.days.from }).containsExactly(d(2026, 6, 10))
    }
  }

  @Test
  fun creaAlbumNonSiSpezzaSeLaPaginaSiChiudeFraLAlbumEIlSegno() = runBlocking<Unit> {
    harness(TripStories.all().first { it.name.startsWith("Firenze") }).use { h ->
      h.trips.detect(1.minutes)
      val oslo = h.trips.proposals.value.proposals.first { it.kind == TripKind.TRIP && Places.OSLO.placeId in it.placeIds }
      val gate = CompletableDeferred<Unit>()
      h.store.markGate = gate

      val job = launch { h.trips.accept(oslo.id) }
      h.store.markEntered.await()
      // L'album e' nato; la pagina si chiude prima che la proposta sia segnata come album.
      assertThat(h.albums.created).hasSize(1)
      job.cancel()
      gate.complete(Unit)
      job.join()

      // Prima la proposta restava e un secondo tocco faceva un doppione.
      assertThat(h.store.saved.single { it.id == oslo.id }.album).isEqualTo(h.albums.created.single().id)
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(oslo.id)
      assertThat(h.trips.accept(oslo.id)).isEqualTo(TripAcceptOutcome.AlreadyAlbum(h.albums.created.single().id))
      assertThat(h.albums.created).hasSize(1)
    }
  }

  // --- R16 ----------------------------------------------------------------------------------------------------------------------

  private fun twoPlaces(): Story = story("due luoghi") {
    livesAt(Places.FIRENZE)
    trip(d(2026, 3, 10), 3, Places.OSLO)
    trip(d(2026, 3, 13), 3, Places.LONDRA)
  }

  @Test
  fun unLuogoDiSoliMediaNascostiDopoLaRilevazioneEsceDalTitoloEDallAlbum() = runBlocking<Unit> {
    val story = twoPlaces()
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      val both = h.trips.proposals.value.proposals.single { it.kind == TripKind.TRIP && it.days.from == d(2026, 3, 10) }
      assertThat(both.placeIds).containsExactly(Places.OSLO.placeId, Places.LONDRA.placeId)
      assertThat(both.label).contains("Oslo")
      assertThat(both.label).contains("Londra")

      // Tutte le foto di Londra vengono nascoste: fino alla notte la proposta diceva ancora «Oslo e Londra» e «Crea album» la scriveva nei dati personali.
      h.library.hidden += story.shots.filter { it.placeId == Places.LONDRA.placeId }.map { it.media }
      h.trips.refresh()
      val after = h.trips.proposals.value.proposals.single { it.id == both.id }
      assertThat(after.placeIds).containsExactly(Places.OSLO.placeId)
      assertThat(after.label).contains("Oslo")
      assertThat(after.label).doesNotContain("Londra")

      assertThat(h.trips.accept(both.id)).isInstanceOf(TripAcceptOutcome.Created::class.java)
      val created = h.albums.created.single()
      assertThat(created.title).doesNotContain("Londra")
      assertThat(created.rules!!.place!!.placeIds).containsExactly(Places.OSLO.placeId)
      assertThat(created.rules!!.place!!.label).doesNotContain("Londra")
    }
  }

  @Test
  fun unTrasolocoNonCambiaIViaggiGiaProposti() = runBlocking<Unit> {
    // E-AL15: nessun media nascosto, quindi nessun titolo rifatto, anche se oggi la casa e' un'altra.
    harness(TripStories.all().first { it.name.startsWith("Trasloco") }).use { h ->
      h.trips.detect(1.minutes)
      val before = h.trips.proposals.value.proposals
      h.trips.refresh()
      assertThat(h.trips.proposals.value.proposals.map { it.label to it.placeIds }).isEqualTo(before.map { it.label to it.placeIds })
    }
  }

  @Test
  fun unViaggioConDueMeteAiLatiOpostiDiCasaHaSoloIlPeriodo() = runBlocking<Unit> {
    val north = Places.FIRENZE.moved(130.0, 0.0, 7_900_001, "Nord")
    val south = Places.FIRENZE.moved(-130.0, 0.0, 7_900_002, "Sud")
    val story = story("lati opposti") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 3, 10), 3, north)
      trip(d(2026, 3, 13), 3, south)
    }
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      val trip = h.trips.proposals.value.proposals.single { it.kind == TripKind.TRIP && it.days.from == d(2026, 3, 10) }
      assertThat(h.trips.accept(trip.id)).isInstanceOf(TripAcceptOutcome.Created::class.java)
      // Il centro del cerchio che copre le due mete e' quasi la casa: nei dati personali non entra, resta il periodo.
      assertThat(h.albums.created.single().rules!!.place).isNull()
    }
  }

  @Test
  fun lOracoloDellaCasaRispondeSoloSiENoEPrimaDellaRicercaNonSaNiente() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    harness(story).use { h ->
      val firenze = GeoPoint(Places.FIRENZE.lat, Places.FIRENZE.lon)
      val oslo = GeoPoint(Places.OSLO.lat, Places.OSLO.lon)
      val clearance = h.trips.homeClearance()
      assertThat(clearance.closeToHome(listOf(firenze, oslo))).containsExactly(false, false).inOrder()

      h.trips.detect(1.minutes)
      // Casa a Firenze (da questo momento la conosce): il punto in casa e' troppo vicino, Oslo no, e un punto a 40 km da casa nemmeno.
      val far = GeoPoint(Places.FIRENZE.lat + 40.0 / Spot.KM_PER_DEGREE, Places.FIRENZE.lon)
      assertThat(clearance.closeToHome(listOf(firenze, oslo, far))).containsExactly(true, false, false).inOrder()
    }
  }

  // --- R17 ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun unGuastoDiLetturaNonCancellaCasaEProposte() = runBlocking<Unit> {
    harness(TripStories.all().first { it.name.startsWith("Firenze") }).use { h ->
      h.trips.detect(1.minutes)
      val shown = h.trips.proposals.value
      val saved = h.store.saved
      val home = h.store.home
      assertThat(shown.proposals).isNotEmpty()
      assertThat(home).isNotNull()

      h.library.failing = true
      val error = runCatching { h.trips.detect(1.minutes) }.exceptionOrNull()
      val refreshError = runCatching { h.trips.refresh() }.exceptionOrNull()
      assertThat(error).isInstanceOf(IllegalStateException::class.java)
      assertThat(refreshError).isInstanceOf(IllegalStateException::class.java)

      // Prima «niente e' visibile» sostituiva casa e proposte con il nulla.
      assertThat(h.store.home).isSameInstanceAs(home)
      assertThat(h.store.saved).isEqualTo(saved)
      assertThat(h.trips.proposals.value).isEqualTo(shown)

      // «Crea album» durante il guasto non riesce, ma la proposta resta.
      val first = shown.proposals.first { it.kind == TripKind.TRIP }
      assertThat(h.trips.accept(first.id)).isEqualTo(TripAcceptOutcome.NotFound)
      assertThat(h.trips.proposals.value.proposals.map { it.id }).contains(first.id)

      h.library.failing = false
      h.trips.refresh()
      assertThat(h.trips.proposals.value).isEqualTo(shown)
    }
  }
}
