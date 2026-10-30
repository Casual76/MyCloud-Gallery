package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripDigest
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripMemories
import dev.pampa.mycloudgallery.core.albums.contract.TripMemoriesContract
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryTripMemories
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.util.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I viaggi e gli eventi per i ricordi, sul `CatalogTrips` vero (docs/fase9-progetto.md D12, E-MEM13, E-H7, E-H8, R16, R17; P8): la suite di
 * contratto di P0 sulla porta vera, e le garanzie una per una: scartati mai (per impronta e per giorno d'inizio), accettati compresi (con
 * l'album), solo media propri e visibili, titolo e luoghi senza i luoghi dei media nascosti, il budget, un guasto che si propaga, e la parita'
 * con il riferimento `InMemoryTripMemories` sulle storie sintetiche della Fase 7.
 */
class PastTripsTest {

  private val today = LocalDate.of(2026, 10, 22)

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  private fun story(name: String, seed: Long = 11, block: StoryBuilder.() -> Unit): Story = StoryBuilder(name, seed, today).apply(block).build()

  private fun StoryBuilder.livesAt(home: Spot) = residence(first, today, Habits(home))

  private fun harness(story: Story, customize: StoryLibrary.() -> Unit = {}): TripsHarness {
    val library = StoryLibrary(story.shots).also { it.hidden += story.hidden }.apply(customize)
    return TripsHarness(library, story.today)
  }

  /** Quattro viaggi, due feste e una giornata fuori: la vita di Firenze. */
  private fun firenze(): Story = story("firenze") {
    livesAt(Places.FIRENZE)
    trip(d(2025, 3, 14), 5, Places.OSLO)
    trip(d(2025, 9, 5), 3, Places.ROMA)
    trip(d(2025, 12, 27), 8, Places.PARIGI)
    trip(d(2026, 6, 10), 4, Places.LONDRA)
    event(d(2025, 6, 21), 19, 0, 40, 150, Places.FIRENZE)
    event(d(2026, 4, 4), 17, 30, 55, 200, Places.FIRENZE)
  }

  private suspend fun TripsHarness.past(until: LocalDate = today, budget: kotlin.time.Duration = 1.minutes): List<TripDigest> = trips.past(until, budget)

  // --- E-MEM13: scartati mai -----------------------------------------------------------------------------------------------------------

  @Test
  fun gliScartatiNonEntranoMaiNeConImprontaNeConGiornoDInizio() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      val all = h.past()
      val trips = all.filter { it.kind == TripKind.TRIP }
      val events = all.filter { it.kind == TripKind.EVENT }
      assertThat(trips).hasSize(4)
      assertThat(events).hasSize(2)
      // Solo l'impronta (un altro dispositivo che non ha scritto il giorno d'inizio): fuori.
      h.dismissals.dismiss(trips[0].id)
      // Solo il giorno d'inizio (la proposta e' stata allungata, l'impronta e' un'altra): fuori.
      h.dismissals.dismiss(TripId.startKey(TripKind.TRIP, trips[1].days.from))
      // Un evento si scarta per impronta; il giorno d'inizio non vale per gli eventi (una festa dello stesso giorno e' un'altra).
      h.dismissals.dismiss(events[0].id)
      h.dismissals.dismiss(TripId.startKey(TripKind.EVENT, events[1].days.from))
      val after = h.past()
      assertThat(after.map { it.id }).containsNoneOf(trips[0].id, trips[1].id, events[0].id)
      assertThat(after.map { it.id }).containsAtLeast(trips[2].id, trips[3].id, events[1].id)
      assertThat(after).hasSize(all.size - 3)
      // Anche dopo il giro del tempo e di una nuova rilevazione.
      h.trips.detect(1.minutes)
      assertThat(h.past().map { it.id }).containsNoneOf(trips[0].id, trips[1].id, events[0].id)
    }
  }

  /** Un viaggio gia' diventato album e poi scartato non e' un ricordo: "Non mi interessa" vince. */
  @Test
  fun unViaggioAccettatoESpoiScartatoNonEntra() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      val oslo = h.trips.proposals.value.proposals.first { it.kind == TripKind.TRIP && it.days.from == d(2025, 3, 14) }
      assertThat(h.trips.accept(oslo.id)).isInstanceOf(TripAcceptOutcome.Created::class.java)
      assertThat(h.past().single { it.id == oslo.id }.album).isNotNull()
      h.trips.dismiss(oslo.id)
      assertThat(h.past().map { it.id }).doesNotContain(oslo.id)
    }
  }

  /** Accettati compresi: l'album c'e' solo dove la proposta e' diventata album (E-MEM13: "Apri l'album"). */
  @Test
  fun gliAccettatiEntranoConLAlbum() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      assertThat(h.past().mapNotNull { it.album }).isEmpty()
      val parigi = h.trips.proposals.value.proposals.first { it.kind == TripKind.TRIP && it.days.from == d(2025, 12, 27) }
      val created = h.trips.accept(parigi.id) as TripAcceptOutcome.Created
      val after = h.past()
      assertThat(after.single { it.album != null }.id).isEqualTo(parigi.id)
      assertThat(after.single { it.album != null }.album).isEqualTo(created.album)
      // La sezione Viaggi non mostra piu' una proposta diventata album; i ricordi si'.
      assertThat(h.trips.proposals.value.proposals.map { it.id }).doesNotContain(parigi.id)
    }
  }

  // --- E-H8: solo media propri e visibili ---------------------------------------------------------------------------------------------

  @Test
  fun soloMediaPropriEVisibili() = runBlocking<Unit> {
    val story = story("misti") {
      livesAt(Places.FIRENZE)
      trip(d(2025, 3, 14), 5, Places.OSLO, screenshotsPerDay = 3)
      family(d(2025, 3, 14), d(2025, 3, 18), Places.OSLO, perDay = 3)
    }
    val library = StoryLibrary(story.shots)
    TripsHarness(library, today).use { h ->
      h.trips.detect(1.minutes)
      val before = h.past().single { it.kind == TripKind.TRIP }
      val mine = story.shots.filter { it.mine && !it.screen && it.day in before.days.from..before.days.to }.map { it.media }.toSet()
      assertThat(before.members.toSet()).isEqualTo(mine)
      assertThat(story.shots.any { it.source == SmartSource.FAMILY && it.day in before.days.from..before.days.to }).isTrue()
      // Nascosti e membri di un gruppo nascosto (il "contagio": la query li da', la Libreria no) escono anche subito dopo.
      library.hidden += before.members.take(3)
      library.contagion += before.members.drop(3).take(2)
      val after = h.past().single { it.id == before.id }
      assertThat(after.members).containsNoneIn(before.members.take(5))
      assertThat(after.members).hasSize(before.members.size - 5)
      // Un viaggio i cui media sono tutti nascosti non c'e' piu' (E-H7).
      library.hidden += before.members
      assertThat(h.past().map { it.id }).doesNotContain(before.id)
    }
  }

  /** D31, E-H8: su storie diverse, con nascosti e contagio a caso, **mai** un media nascosto, altrui o screenshot in un viaggio dei ricordi. */
  @Test
  fun unNascostoNonEntraMaiProprieta() = runBlocking<Unit> {
    val random = Random(5)
    var checked = 0
    for (story in TripStories.all().filterIndexed { i, _ -> i % 2 == 0 }) {
      harness(story).use { h ->
        val byId = h.library.all.associateBy { it.media }
        h.trips.detect(1.minutes)
        val first = h.past(today.plusDays(1))
        val ids = first.flatMap { it.members }
        repeat(3) {
          h.library.hidden += ids.filter { random.nextInt(100) < 7 }
          h.library.contagion += ids.filter { random.nextInt(100) < 3 }
          val digests = h.past(today.plusDays(1))
          for (digest in digests) for (id in digest.members) {
            assertWithMessage("${story.name}: ${digest}").that(id in h.library.hidden || id in h.library.contagion).isFalse()
            val candidate: AlbumCandidate = byId.getValue(id)
            assertThat(candidate.mine).isTrue()
            assertThat(candidate.screen).isFalse()
          }
          checked += digests.sumOf { it.members.size }
        }
      }
    }
    assertThat(checked).isGreaterThan(1_000)
  }

  // --- Cosa e' un viaggio passato ------------------------------------------------------------------------------------------------------

  @Test
  fun soloIFinitiPrimaDelGiornoDalPiuRecenteEUnVecchioNonSparisce() = runBlocking<Unit> {
    val story = story("in corso") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 3, 10), 4, Places.OSLO)
      trip(d(2026, 10, 19), 4, Places.ROMA)
    }
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      val oslo = h.past().single()
      assertThat(oslo.days.from).isEqualTo(d(2026, 3, 10))
      // "Finiti prima di" oggi: il giorno stesso della fine non basta, il giorno dopo si'.
      assertThat(h.past(until = oslo.days.to).map { it.id }).doesNotContain(oslo.id)
      assertThat(h.past(until = oslo.days.to.plusDays(1)).map { it.id }).contains(oslo.id)
      assertThat(h.past(until = d(2026, 11, 1)).map { it.days.from }).containsExactly(d(2026, 10, 19), d(2026, 3, 10)).inOrder()
    }
  }

  /** Un evento ha il quarto d'ora del suo primo scatto e un viaggio no (la chiave `event:<giorno>:<quarto d'ora>` del ricordo, D9). */
  @Test
  fun unEventoHaIlQuartoDOraDiInizioEUnViaggioNo() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      val all = h.past()
      for (trip in all.filter { it.kind == TripKind.TRIP }) assertThat(trip.startQuarter).isNull()
      val events = all.filter { it.kind == TripKind.EVENT }.associateBy { it.days.from }
      assertThat(events[d(2025, 6, 21)]!!.startQuarter).isEqualTo(19 * 4)
      assertThat(events[d(2026, 4, 4)]!!.startQuarter).isEqualTo(17 * 4 + 2)
    }
  }

  /** L'inizio di un evento non si sposta se arriva una foto dopo (da un altro dispositivo) prima dell'evento: l'id del ricordo resta (D9). */
  @Test
  fun unaFotoSperdutaDelMattinoNonSpostaLInizioDellEvento() = runBlocking<Unit> {
    val plain = story("festa") {
      livesAt(Places.FIRENZE)
      event(d(2026, 4, 4), 20, 0, 40, 150, Places.FIRENZE)
    }
    val withStray = story("festa e una foto") {
      livesAt(Places.FIRENZE)
      event(d(2026, 4, 4), 20, 0, 40, 150, Places.FIRENZE)
      shot(d(2026, 4, 4).atTime(8, 5), 60, Places.FIRENZE)
    }
    val quarters = listOf(plain, withStray).map { s ->
      harness(s).use { h ->
        h.trips.detect(1.minutes)
        h.past().single { it.kind == TripKind.EVENT }.startQuarter
      }
    }
    assertThat(quarters).containsExactly(20 * 4, 20 * 4)
  }

  // --- R16, R17, budget ----------------------------------------------------------------------------------------------------------------

  /** R16: i luoghi di soli media nascosti dopo la rilevazione non restano nel titolo ne' fra i luoghi di un ricordo. */
  @Test
  fun unLuogoDiSoliMediaNascostiEsceDalTitoloEDaiLuoghi() = runBlocking<Unit> {
    val story = story("due luoghi") {
      livesAt(Places.FIRENZE)
      trip(d(2026, 3, 10), 3, Places.OSLO)
      trip(d(2026, 3, 13), 3, Places.LONDRA)
    }
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      val both = h.past().single { it.kind == TripKind.TRIP }
      assertThat(both.placeIds).containsExactly(Places.OSLO.placeId, Places.LONDRA.placeId)
      assertThat(both.label).contains("Londra")
      h.library.hidden += story.shots.filter { it.placeId == Places.LONDRA.placeId }.map { it.media }
      val after = h.past().single { it.id == both.id }
      assertThat(after.placeIds).containsExactly(Places.OSLO.placeId)
      assertThat(after.label).contains("Oslo")
      assertThat(after.label).doesNotContain("Londra")
    }
  }

  /** R17: un guasto della lettura dei visibili non e' "nessun viaggio": si propaga. */
  @Test
  fun unGuastoDellaLetturaSiPropaga() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      assertThat(h.past()).isNotEmpty()
      h.library.failing = true
      val failure = runCatching { h.past() }
      assertThat(failure.isFailure).isTrue()
      h.library.failing = false
      assertThat(h.past()).isNotEmpty()
    }
  }

  /** Il budget: finito il tempo si risponde con quelli fatti (i piu' recenti), senza errore e senza una proposta a meta'. */
  @Test
  fun aBudgetFinitoRispondeConQuelliFatti() = runBlocking<Unit> {
    val story = firenze()
    val full = harness(story).use { h ->
      h.trips.detect(1.minutes)
      h.past()
    }
    val library = StoryLibrary(story.shots)
    val ticking = TickingClocks(stepMs = 100)
    val partial = TripsHarness(library, today, clocks = ticking).use { h ->
      h.trips.detect(1.minutes)
      h.trips.past(today, 700.milliseconds)
    }
    assertThat(partial).isNotEmpty()
    assertThat(partial.size).isLessThan(full.size)
    assertThat(full.map { it.id }.take(partial.size)).isEqualTo(partial.map { it.id })
    for (digest in partial) assertThat(digest.members).isEqualTo(full.single { it.id == digest.id }.members)
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      assertThat(h.trips.past(today, kotlin.time.Duration.ZERO)).isEmpty()
    }
  }

  /** Stessa risposta ogni volta (puro e deterministico), e il `toString()` non mostra niente. */
  @Test
  fun deterministicoESenzaTracceNelToString() = runBlocking<Unit> {
    harness(firenze()).use { h ->
      h.trips.detect(1.minutes)
      val first = h.past()
      val second = h.past()
      assertThat(second.map { it.id to it.members }).isEqualTo(first.map { it.id to it.members })
      for (digest in first) {
        val text = digest.toString()
        assertThat(text).doesNotContain(digest.label)
        assertThat(text).doesNotContain(digest.days.from.year.toString())
        assertThat(text).doesNotContain(digest.members.first().value)
      }
    }
  }

  // --- Parita' con il riferimento ------------------------------------------------------------------------------------------------------

  /** Sulle storie sintetiche la porta vera da' le stesse digest del riferimento `InMemoryTripMemories`, con nascosti e scarti a caso. */
  @Test
  fun stesseDigestDelRiferimentoConNascostiEScarti() = runBlocking<Unit> {
    val random = Random(11)
    var compared = 0
    for (story in TripStories.all().filterIndexed { i, _ -> i % 3 == 0 }) {
      harness(story).use { h ->
        h.trips.detect(1.minutes)
        val everything = h.past(today.plusYears(1))
        if (everything.isEmpty()) return@use
        val reference = InMemoryTripMemories(everything, h.library)
        repeat(3) {
          val ids = everything.flatMap { it.members }
          h.library.hidden += ids.filter { random.nextInt(100) < 5 }
          if (random.nextBoolean()) everything.random(kotlin.random.Random(random.nextLong())).let { trip ->
            reference.dismiss(trip)
            h.dismissals.dismiss(trip.id)
            if (trip.kind == TripKind.TRIP) h.dismissals.dismiss(TripId.startKey(trip.kind, trip.days.from))
          }
          val expected = reference.past(today.plusYears(1), 1.minutes)
          val actual = h.past(today.plusYears(1))
          assertWithMessage(story.name).that(actual.map { it.id }).isEqualTo(expected.map { it.id })
          assertThat(actual.map { it.members }).isEqualTo(expected.map { it.members })
          assertThat(actual.map { it.startQuarter }).isEqualTo(expected.map { it.startQuarter })
          assertThat(actual.map { it.album }).isEqualTo(expected.map { it.album })
          compared += actual.size
        }
      }
    }
    assertThat(compared).isGreaterThan(20)
  }

  @Test
  fun iPrimiDueSecondiBastanoSuUnaStoriaGrande() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Tutto insieme") }
    harness(story).use { h ->
      h.trips.detect(1.minutes)
      val started = System.nanoTime()
      val digests = h.past(today.plusDays(1), 10.seconds)
      assertThat(digests).isNotEmpty()
      assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000)
    }
  }
}

/** Il mondo del contratto sul `CatalogTrips` vero: viaggi ed eventi rilevati su una storia sintetica, uno accettato come album e uno in corso. */
class CatalogTripMemoriesContractTest : TripMemoriesContract() {
  private val harnesses = ArrayList<TripsHarness>()

  @org.junit.After
  fun chiude() {
    harnesses.forEach { it.close() }
  }

  override fun world(today: LocalDate): TripWorld {
    val story = StoryBuilder("contratto", 77, today, spanDays = 1_300).apply {
      residence(first, today, Habits(Places.FIRENZE))
      trip(today.minusYears(1).minusDays(2), 6, Places.OSLO)
      trip(today.minusYears(3), 7, Places.PARIGI)
      event(today.minusMonths(14), 19, 0, 40, 150, Places.FIRENZE)
      trip(today.minusDays(40), 6, Places.ROMA)
      trip(today.minusDays(2), 3, Places.LONDRA)
    }.build()
    val library = StoryLibrary(story.shots)
    val h = TripsHarness(library, today)
    harnesses += h
    return runBlocking {
      h.trips.detect(1.minutes)
      val oslo = h.trips.proposals.value.proposals.first { it.kind == TripKind.TRIP && it.days.from == today.minusYears(1).minusDays(2) }
      check(h.trips.accept(oslo.id) is TripAcceptOutcome.Created)
      val all = h.trips.past(today.plusYears(10), 1.minutes)
      object : TripWorld {
        override val all: List<TripDigest> = all
        override val subject: TripMemories = h.trips

        override suspend fun hide(id: MediaId) {
          library.hidden += id
        }

        override suspend fun dismiss(trip: TripDigest) = h.trips.dismiss(trip.id)

        override suspend fun visible(id: MediaId): Boolean = id in library.visible(listOf(id))
      }
    }
  }
}
