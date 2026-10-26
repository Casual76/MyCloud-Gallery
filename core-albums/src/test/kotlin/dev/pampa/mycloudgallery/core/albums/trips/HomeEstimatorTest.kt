package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * LA "CASA" SU STORIE SINTETICHE (docs/fase7-progetto.md D18, §2.3, E-AL10, E-AL15, E-AL16): trasloco, casa al mare, nessun GPS, fusi orari,
 * e i numeri della regola (celle di 1 km, giorni con foto serali, 15% e 10%, almeno 5 giorni, raggio di almeno 2 km).
 */
class HomeEstimatorTest {

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  private fun story(prefix: String) = TripStories.all().first { it.name.startsWith(prefix) }

  private fun index(shots: List<AlbumCandidate>, today: LocalDate): GeoIndex =
    GeoIndex(shots.filter { it.mine && !it.screen && it.gps != null && !it.day.isAfter(today) })

  private fun estimate(shots: List<AlbumCandidate>, today: LocalDate): Anchors? {
    val g = index(shots, today)
    return HomeEstimator.estimate(g, today.minusMonths(12).plusDays(1).toEpochDay().toInt(), today.toEpochDay().toInt())
  }

  private fun near(spot: Spot, anchor: Anchor, km: Double = 3.0): Boolean = TripGeometry.km(spot.lat, spot.lon, anchor.lat, anchor.lon) <= km

  @Test
  fun laCasaEDoveSiFotografaLaSera() {
    val s = story("Firenze")
    val anchors = estimate(s.shots, s.today)!!
    assertThat(near(Places.FIRENZE, anchors.home)).isTrue()
    assertThat(anchors.home.radiusKm).isAtLeast(2.0)
    assertThat(anchors.habitual).isEmpty()
    // Il peso e' in giorni distinti, non in foto: una festa con 300 foto non sposta la casa.
    assertThat(anchors.home.days).isAtMost(anchors.gpsDays)
  }

  @Test
  fun unaFestaConTrecentoFotoNonSpostaLaCasa() {
    val b = StoryBuilder("festa", 31, d(2026, 10, 22))
    val friends = Places.FIRENZE.moved(12.0, 9.0, 7_900_001, "Casa di amici")
    b.event(d(2026, 6, 6), 19, 0, 300, 180, friends, expected = false)
    b.residence(b.first, b.today, Habits(Places.FIRENZE))
    val s = b.build()
    val anchors = estimate(s.shots, s.today)!!
    assertThat(near(Places.FIRENZE, anchors.home)).isTrue()
    assertThat(anchors.habitual).isEmpty()
  }

  /** E-AL15: la casa cambia quando la nuova supera la vecchia, sulle stesse foto viste in giorni diversi. */
  @Test
  fun dopoUnTraslocoLaCasaCambiaQuandoLaNuovaSuperaLaVecchia() {
    val s = story("Trasloco")
    // Quattro mesi dopo (22/10/2025): Firenze ha ancora otto mesi su dodici, Milano sette settimane... Milano e' gia' un luogo abituale.
    val early = estimate(s.shots, d(2025, 10, 22))!!
    assertThat(near(Places.FIRENZE, early.home)).isTrue()
    assertThat(early.habitual.any { near(Places.MILANO, it) }).isTrue()
    // Sei mesi dopo (22/1/2026): Milano ha sorpassato Firenze.
    val later = estimate(s.shots, d(2026, 1, 22))!!
    assertThat(near(Places.MILANO, later.home)).isTrue()
    assertThat(later.habitual.any { near(Places.FIRENZE, it) }).isTrue()
    // Oggi, a oltre un anno dal trasloco, la vecchia casa non c'e' piu'.
    val now = estimate(s.shots, s.today)!!
    assertThat(near(Places.MILANO, now.home)).isTrue()
    assertThat(now.habitual.none { near(Places.FIRENZE, it) }).isTrue()
  }

  /** I viaggi gia' proposti non cambiano quando cambia la casa (E-AL15): stesse foto, "oggi" diverso. */
  @Test
  fun iViaggiGiaPropostiNonCambianoColTrasloco() = runBlocking<Unit> {
    val s = story("Trasloco")
    fun tripsAt(today: LocalDate): Map<String, String> {
      val visible = s.shots.filter { it.mine && !it.screen && !it.day.isAfter(today) }
      return TripDetector.detect(visible, today).items.filter { it.kind == TripKind.TRIP }.associate { it.id.value to it.range.from.toString() }
    }
    val before = tripsAt(d(2025, 12, 31))
    val after = tripsAt(s.today)
    assertThat(before).isNotEmpty()
    for ((id, from) in before) assertThat(after[id]).isEqualTo(from)
    // E dopo il trasloco non si e' inventato nessun viaggio a casa: Parigi, Lisbona, Londra prima di fine 2025.
    assertThat(before.values).containsExactly("2024-12-20", "2025-04-10", "2025-10-02")
  }

  /** La casa al mare e' un luogo abituale (oltre il 10%), non un viaggio e non la casa. */
  @Test
  fun laCasaAlMareEUnLuogoAbituale() {
    val s = story("Roma")
    val anchors = estimate(s.shots, s.today)!!
    assertThat(near(Places.ROMA, anchors.home)).isTrue()
    val sea = Places.ROMA.moved(-70.0, 60.0)
    assertThat(anchors.habitual.any { near(sea, it) }).isTrue()
    assertThat(anchors.habitual.size).isEqualTo(1)
  }

  /** E-AL16: nessuna foto con GPS, nessuna casa; e `AwayFromHome` non sa dire niente. */
  @Test
  fun senzaGpsNonCeCasa() = runBlocking<Unit> {
    val s = story("Nessun GPS")
    assertThat(estimate(s.shots, s.today)).isNull()
    TripsHarness(StoryLibrary(s.shots), s.today).use { h ->
      val report = h.trips.detect(1.minutes)
      assertThat(report.homeKnown).isFalse()
      assertThat(h.store.home).isNull()
      assertThat(h.trips.isAway(d(2025, 7, 3))).isNull()
      assertThat(h.trips.distanceKm(d(2025, 7, 3))).isNull()
      // La sezione Viaggi lo dice (homeKnown falso) e propone gli eventi.
      assertThat(h.trips.proposals.value.homeKnown).isFalse()
      assertThat(h.trips.proposals.value.proposals.map { it.kind }.toSet()).containsExactly(TripKind.EVENT)
    }
  }

  /** E-AL10: nei fusi lontani il giorno e' quello locale dello scatto, e la casa non si confonde. */
  @Test
  fun neiFusiLontaniIGiorniSonoQuelliLocali() = runBlocking<Unit> {
    val s = story("Viaggi in altri fusi")
    TripsHarness(StoryLibrary(s.shots), s.today).use { h ->
      h.trips.detect(1.minutes)
      val tokyo = h.trips.proposals.value.proposals.first { it.placeIds.contains(Places.TOKYO.placeId) }
      assertThat(tokyo.days.from).isEqualTo(d(2025, 10, 3))
      assertThat(tokyo.days.to).isEqualTo(d(2025, 10, 11))
      // Lo scatto di mezzanotte e venti del primo giorno (le 15:20 UTC del giorno prima) e quello di mezzanotte meno dieci dell'ultimo sono del viaggio.
      val edge = s.shots.filter { it.gps != null && it.placeId == Places.TOKYO.placeId && (it.localTime?.hour == 0 || it.localTime?.hour == 23) }
      assertThat(edge.map { it.day }).containsAtLeast(d(2025, 10, 3), d(2025, 10, 11))
      val newYork = h.trips.proposals.value.proposals.first { it.placeIds.contains(Places.NEW_YORK.placeId) }
      assertThat(newYork.days.to).isEqualTo(d(2025, 5, 14))
      val members = s.shots.count { it.day in newYork.days }
      assertThat(newYork.count).isEqualTo(members)
      // Il giorno non e' quello che si leggerebbe in UTC: uno scatto della sera locale a New York e' nel giorno dopo, per l'UTC.
      assertThat(s.shots.any { it.day == d(2025, 5, 14) && java.time.Instant.ofEpochMilli(it.takenAtUtcMs).atZone(java.time.ZoneOffset.UTC).toLocalDate() == d(2025, 5, 15) }).isTrue()
      // La distanza da casa nei fusi lontani: giorno locale, arrotondata a 10 km.
      val km = h.trips.distanceKm(d(2025, 10, 3))!!
      assertThat(km % 10).isEqualTo(0)
      assertThat(km).isGreaterThan(9_000)
      assertThat(h.trips.isAway(d(2025, 10, 3))).isTrue()
    }
  }

  // --- la regola, con numeri piccoli -----------------------------------------------------------------------------------------------------

  /** Una casa ha almeno 5 giorni serali: con 4 non c'e' casa (poche foto di una vacanza non fanno una casa). */
  @Test
  fun serveUnMinimoDiGiorni() {
    val few = StoryBuilder("poche", 41, d(2026, 10, 22)).apply {
      for (day in 1..4) shot(d(2026, 9, day).atTime(21, 0), 60, Places.FIRENZE)
    }.build()
    assertThat(estimate(few.shots, few.today)).isNull()
    val five = StoryBuilder("cinque", 41, d(2026, 10, 22)).apply {
      for (day in 1..5) shot(d(2026, 9, day).atTime(21, 0), 60, Places.FIRENZE)
    }.build()
    assertThat(estimate(five.shots, five.today)).isNotNull()
  }

  /** Chi non fotografa a casa la sera: ripiego su tutte le ore, altrimenti non ci sarebbero ne' casa ne' viaggi. */
  @Test
  fun senzaFotoSeraliSiRipiegaSuTutteLeOre() {
    val b = StoryBuilder("di giorno", 42, d(2026, 10, 22))
    for (n in 0 until 40) b.shot(d(2026, 6, 1).plusDays(n * 2L).atTime(12, 0), 60, Places.FIRENZE)
    val s = b.build()
    val anchors = estimate(s.shots, s.today)!!
    assertThat(near(Places.FIRENZE, anchors.home)).isTrue()
  }

  /** Un luogo abituale ha oltre il 10% dei giorni (con piu' visite): con 12 giorni su 112 si', con 10 su 110 no. */
  @Test
  fun leQuoteDeiLuoghiAbituali() {
    fun anchorsWith(awayDays: Int): Anchors? {
      val b = StoryBuilder("quote", 43, d(2026, 10, 22))
      val other = Places.FIRENZE.moved(0.0, 60.0, 7_910_001, "Altrove")
      for (n in 0 until 100) b.shot(d(2026, 1, 1).plusDays(n * 2L).atTime(21, 0), 60, Places.FIRENZE)
      // Una visita ogni venti giorni: visite separate, non un solo soggiorno.
      for (n in 0 until awayDays) b.shot(d(2026, 1, 2).plusDays(n * 20L).atTime(21, 0), 60, other)
      return estimate(b.build().shots, d(2026, 10, 22))
    }
    assertThat(anchorsWith(12)!!.habitual).hasSize(1)
    assertThat(anchorsWith(10)!!.habitual).isEmpty()
  }

  /**
   * Un solo soggiorno, anche di un mese, non e' un luogo abituale ma una vacanza (altrimenti il viaggio piu' lungo dell'anno, che da solo supera
   * il 10% dei giorni, non sarebbe mai proposto); due soggiorni, o oltre 45 giorni di fila (un'altra casa), si'.
   */
  @Test
  fun unSoloSoggiornoEUnaVacanzaDueSoggiorniOUnaltraCasaSonoUnLuogoAbituale() {
    fun anchorsWith(vararg stays: IntRange): Anchors? {
      val b = StoryBuilder("soggiorni", 44, d(2026, 10, 22))
      val other = Places.FIRENZE.moved(0.0, 60.0, 7_910_002, "Altrove")
      for (n in 0 until 100) b.shot(d(2026, 1, 1).plusDays(n * 2L).atTime(21, 0), 60, Places.FIRENZE)
      for (stay in stays) for (n in stay) b.shot(d(2026, 1, 1).plusDays(n.toLong()).atTime(21, 0), 60, other)
      return estimate(b.build().shots, d(2026, 10, 22))
    }
    // 25 giorni di fila su 125 (20%): una vacanza.
    assertThat(anchorsWith(220..244)!!.habitual).isEmpty()
    // Gli stessi 25 giorni in due soggiorni (12 e 13) lontani nell'anno: un luogo dove si torna.
    assertThat(anchorsWith(120..131, 250..262)!!.habitual).hasSize(1)
    // 50 giorni di fila (oltre i 45): un'altra casa, anche se e' una volta sola.
    assertThat(anchorsWith(220..269)!!.habitual).hasSize(1)
    // 44 giorni di fila: ancora una vacanza (lunga).
    assertThat(anchorsWith(220..263)!!.habitual).isEmpty()
  }

  @Test
  fun ilRaggioDiCasaEAlmenoDueChilometriEIlLimiteDeiViaggiNonScendeSotto50() {
    val s = story("Firenze")
    val anchors = estimate(s.shots, s.today)!!
    assertThat(anchors.home.radiusKm).isAtLeast(2.0)
    assertThat(anchors.farKm).isAtLeast(50.0)
  }
}
