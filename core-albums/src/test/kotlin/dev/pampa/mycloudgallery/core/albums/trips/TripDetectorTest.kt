package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Test

/**
 * Le REGOLE di D19 una per una (docs/fase7-progetto.md §2.3), sul rilevatore puro: giorni lontani (mediana oltre max(50 km, 3 volte il raggio
 * di casa)), serie con al piu' due giorni in mezzo, almeno due giorni oppure uno con 20 foto oltre 100 km; eventi di almeno 30 media in al piu'
 * 4 ore (ritmo 5 volte la media), fuori dai viaggi; i luoghi mai quelli di casa. Le storie intere sono in `TripStoriesTest`.
 */
class TripDetectorTest {

  private val today = LocalDate.of(2026, 10, 22)
  private val home = Places.FIRENZE

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  /** La rilevazione su una piccola storia: la vita di tutti i giorni a Firenze piu' quanto dice [block]. */
  private fun detect(seed: Long = 1, block: StoryBuilder.() -> Unit): Detection {
    val story = StoryBuilder("prova", seed, today).apply {
      block()
      residence(first, today, Habits(home, outings = listOf(home.moved(4.0, 3.0, 7_100_001, "Vicino"), home.moved(-9.0, 12.0, 7_100_002, "Vicino 2"))))
    }.build()
    return TripDetector.detect(story.shots.filter { it.mine && !it.screen }, today)
  }

  private fun Detection.trips(): List<LocalDayRange> = items.filter { it.kind == TripKind.TRIP }.map { it.range }

  private fun Detection.events(): List<LocalDate> = items.filter { it.kind == TripKind.EVENT }.map { it.range.from }

  // --- giorni lontani e serie ---------------------------------------------------------------------------------------------------------------

  @Test
  fun dueGiorniLontaniSonoUnViaggioUnoSoloNo() {
    assertThat(detect { trip(d(2026, 3, 10), 2, Places.ROMA, expected = false) }.trips()).containsExactly(LocalDayRange(d(2026, 3, 10), d(2026, 3, 11)))
    assertThat(detect { trip(d(2026, 3, 10), 1, Places.ROMA, perDay = 8..8, expected = false) }.trips()).isEmpty()
  }

  @Test
  fun unGiornoConVentiFotoOltre100kmEUnViaggioConDiciannoveNo() {
    val far = home.moved(150.0, 0.0, 7_000_001, "Lontano")
    assertThat(detect { dayOut(d(2026, 3, 10), far, 20) }.trips()).containsExactly(LocalDayRange(d(2026, 3, 10), d(2026, 3, 10)))
    assertThat(detect { dayOut(d(2026, 3, 10), far, 19) }.trips()).isEmpty()
    // A 90 km (lontano ma sotto i 100) nemmeno con cento foto.
    assertThat(detect { dayOut(d(2026, 3, 10), home.moved(90.0, 0.0, 7_000_002, "Quasi"), 100) }.trips()).isEmpty()
  }

  @Test
  fun laSogliaDei50kmSiVedeSullaMediana() {
    assertThat(detect { trip(d(2026, 3, 10), 3, home.moved(40.0, 0.0, 7_000_003, "A 40 km"), expected = false) }.trips()).isEmpty()
    assertThat(detect { trip(d(2026, 3, 10), 3, home.moved(62.0, 0.0, 7_000_004, "A 62 km"), expected = false) }.trips()).hasSize(1)
  }

  @Test
  fun unaFotoConIlGpsAltroveNonFaUnGiornoLontano() {
    // Tre foto a casa e una a Tokyo lo stesso giorno: la mediana e' a casa. Ripetuto per due giorni di fila: nessun viaggio.
    val d = detect {
      for (day in listOf(d(2026, 3, 10), d(2026, 3, 11))) {
        dayOut(day, home, 3)
        outlier(day, 21, Places.TOKYO)
      }
    }
    assertThat(d.trips()).isEmpty()
  }

  @Test
  fun inBuchiDiAlPiuDueGiorniNonSpezzanoIlViaggioTreSi() {
    val merged = detect {
      trip(d(2026, 5, 2), 3, Places.ROMA, expected = false)
      quiet(d(2026, 5, 5), d(2026, 5, 6))
      trip(d(2026, 5, 7), 4, Places.ROMA, expected = false)
    }
    assertThat(merged.trips()).containsExactly(LocalDayRange(d(2026, 5, 2), d(2026, 5, 10)))
    val split = detect {
      trip(d(2026, 5, 2), 3, Places.ROMA, expected = false)
      quiet(d(2026, 5, 5), d(2026, 5, 7))
      trip(d(2026, 5, 8), 4, Places.ROMA, expected = false)
    }
    assertThat(split.trips()).containsExactly(LocalDayRange(d(2026, 5, 8), d(2026, 5, 11)), LocalDayRange(d(2026, 5, 2), d(2026, 5, 4)))
  }

  /** La vacanza piu' lunga dell'anno (un mese di fila) e' un viaggio: da sola supera il 10% dei giorni, ma e' un solo soggiorno. */
  @Test
  fun unaVacanzaDiUnMeseEUnViaggio() {
    // Foto serali tutti i giorni (come fa chi dorme li'): il mese pesa piu' del 10% dei giorni, ma e' un solo soggiorno.
    val d = detect { stay(d(2026, 7, 1), d(2026, 7, 31), Places.LISBONA) }
    assertThat(d.trips()).containsExactly(LocalDayRange(d(2026, 7, 1), d(2026, 7, 31)))
    // Due soggiorni nello stesso posto nello stesso anno, invece, sono un posto dove si torna: non e' un viaggio (la casa al mare, i suoceri).
    val twice = detect {
      stay(d(2026, 3, 1), d(2026, 3, 14), Places.LISBONA)
      stay(d(2026, 7, 1), d(2026, 7, 14), Places.LISBONA)
    }
    assertThat(twice.trips()).isEmpty()
  }

  @Test
  fun unGiornoGeolocalizzatoVicinoACasaSpezzaIlViaggio() {
    val d = detect {
      trip(d(2026, 5, 2), 3, Places.ROMA, expected = false)
      dayOut(d(2026, 5, 5), home, 4)
      trip(d(2026, 5, 6), 3, Places.ROMA, expected = false)
    }
    assertThat(d.trips()).containsExactly(LocalDayRange(d(2026, 5, 6), d(2026, 5, 8)), LocalDayRange(d(2026, 5, 2), d(2026, 5, 4)))
  }

  @Test
  fun iGiorniSenzaGpsDentroIlViaggioNonLoSpezzano() {
    val d = detect { trip(d(2026, 5, 2), 6, Places.OSLO, gpsFree = setOf(2, 3), expected = false) }
    assertThat(d.trips()).containsExactly(LocalDayRange(d(2026, 5, 2), d(2026, 5, 7)))
    // E contano nel viaggio: i media di quei giorni ci sono (E-AL11: l'album li suggerira').
    assertThat(d.items.single { it.kind == TripKind.TRIP }.members.count { it.gps == null && it.day in d.trips().single() }).isAtLeast(2)
  }

  @Test
  fun unGpsAZeroZeroNonEUnGiornoLontano() {
    val d = detect {
      for (day in 1..4) dayOut(d(2026, 5, day), home, 3)
      quiet(d(2026, 5, 5), d(2026, 5, 8))
      for (day in 5..8) nullIsland(d(2026, 5, day))
    }
    assertThat(d.trips()).isEmpty()
  }

  /** Otto foto con il GPS esatto a (0, 0): la posizione che un telefono scrive quando non ne ha letta nessuna. */
  private fun StoryBuilder.nullIsland(day: LocalDate) {
    repeat(8) { shot(LocalDateTime.of(day, java.time.LocalTime.of(10 + it, 0)), 60, Spot("Nessun luogo", 0.0, 0.0, 0), jitterKm = 0.0) }
  }

  @Test
  fun leFotoDelFuturoNonFannoViaggi() {
    val d = detect { trip(today.plusDays(3), 4, Places.ROMA, expected = false) }
    assertThat(d.trips()).isEmpty()
  }

  @Test
  fun senzaCasaNonCiSonoViaggi() {
    val story = StoryBuilder("poche", 3, today).apply { trip(d(2026, 5, 2), 4, Places.ROMA, expected = false) }.build()
    val d = TripDetector.detect(story.shots.filter { it.mine && !it.screen }, today)
    assertThat(d.current).isNull()
    assertThat(d.trips()).isEmpty()
  }

  // --- eventi ------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unEventoHaAlmenoTrentaMediaInAlPiuQuattroOre() {
    // Esattamente 30 in esattamente 4 ore (240 minuti fra il primo e l'ultimo): un evento.
    assertThat(detect { event(d(2026, 4, 11), 18, 0, 30, 240, home) }.events()).containsExactly(d(2026, 4, 11))
    // 29 no.
    assertThat(detect { event(d(2026, 4, 11), 18, 0, 29, 240, home, expected = false) }.events()).isEmpty()
    // 30 in 241 minuti: in una finestra di 4 ore ne stanno al piu' 29.
    assertThat(detect { event(d(2026, 4, 11), 18, 0, 30, 241, home, expected = false) }.events()).isEmpty()
  }

  @Test
  fun leFinestreCheSiSovrappongonoSonoUnaFestaSola() {
    // 70 foto in sei ore: ogni finestra di quattro ore ne ha 46, e tutte insieme sono una festa.
    val d = detect { event(d(2026, 4, 11), 15, 0, 70, 360, home) }
    assertThat(d.events()).containsExactly(d(2026, 4, 11))
    assertThat(d.items.single { it.kind == TripKind.EVENT }.coverPool).hasSize(70)
  }

  @Test
  fun unaFestaCheAttraversaLaMezzanotteHaDueGiorniEIlTitoloDelPrimo() {
    val d = detect { event(d(2026, 4, 11), 22, 30, 40, 180, home) }
    val event = d.items.single { it.kind == TripKind.EVENT }
    assertThat(event.range).isEqualTo(LocalDayRange(d(2026, 4, 11), d(2026, 4, 12)))
  }

  @Test
  fun ilRitmoContaCinqueVolteLaMediaDeiGiorniConFoto() {
    // 12 foto al giorno di media: un evento ne vuole circa 60. 45 non bastano, 80 si'.
    val habits = { b: StoryBuilder -> b.residence(b.first, today, Habits(home, dayProbability = 0.9, perDay = 8..16)) }
    val few = StoryBuilder("ritmo", 5, today).apply { habits(this); event(d(2026, 4, 11), 19, 0, 45, 120, home, expected = false) }.build()
    val many = StoryBuilder("ritmo", 5, today).apply { habits(this); event(d(2026, 4, 11), 19, 0, 80, 120, home) }.build()
    assertThat(TripDetector.detect(few.shots, today).events()).isEmpty()
    assertThat(TripDetector.detect(many.shots, today).events()).containsExactly(d(2026, 4, 11))
  }

  @Test
  fun unaFestaDuranteUnViaggioNonEUnEvento() {
    val d = detect {
      trip(d(2026, 5, 2), 4, Places.ROMA, expected = false)
      for (i in 0 until 40) shot(LocalDateTime.of(d(2026, 5, 3), java.time.LocalTime.of(20, 0)).plusMinutes(i * 3L), 60, Places.ROMA, 0.2)
    }
    assertThat(d.events()).isEmpty()
    assertThat(d.trips()).hasSize(1)
  }

  @Test
  fun unaFestaACasaNonHaLuoghiENonSiChiamaConLaCittaDoveSiAbita() {
    val d = detect { event(d(2026, 4, 11), 19, 0, 40, 150, home) }
    val event = d.items.single { it.kind == TripKind.EVENT }
    assertThat(event.places.ids).isEmpty()
  }

  @Test
  fun unaFestaLontanaDaCasaHaIlSuoLuogo() {
    val lake = home.moved(0.0, 70.0, 7_800_001, "Lago")
    val d = detect { event(d(2026, 4, 11), 19, 0, 40, 150, lake) }
    assertThat(d.items.single { it.kind == TripKind.EVENT }.places.ids).containsExactly(7_800_001)
  }

  @Test
  fun senzaCasaUnEventoNonHaLuoghi() {
    // Foto di tutti i giorni senza GPS e una festa con il GPS: un giorno solo con la posizione non fa una casa.
    val story = StoryBuilder("poche", 3, today).apply {
      event(d(2026, 4, 11), 19, 0, 40, 150, home)
      residence(first, today, Habits(home, gpsMissing = 1.0))
    }.build()
    val d = TripDetector.detect(story.shots.filter { it.mine && !it.screen }, today)
    assertThat(d.current).isNull()
    assertThat(d.events()).containsExactly(d(2026, 4, 11))
    assertThat(d.items.single { it.kind == TripKind.EVENT }.places.ids).isEmpty()
  }

  // --- luoghi, copertine, ordine ---------------------------------------------------------------------------------------------------------

  @Test
  fun iLuoghiDiUnViaggioNonSonoQuelliDiCasa() {
    val d = detect {
      trip(d(2026, 5, 2), 4, Places.ROMA, expected = false)
      // La mattina della partenza: una foto all'aeroporto, a casa.
      shot(LocalDateTime.of(d(2026, 5, 2), java.time.LocalTime.of(7, 30)), 60, home, 1.0)
    }
    val trip = d.items.single { it.kind == TripKind.TRIP }
    assertThat(trip.places.ids).containsExactly(Places.ROMA.placeId)
    assertThat(trip.places.top.toList()).containsExactly(Places.ROMA.placeId)
  }

  @Test
  fun leProposteSonoDalPiuRecenteECopertinaEUnaFotoDelViaggio() {
    val d = detect {
      trip(d(2025, 3, 10), 3, Places.OSLO, expected = false)
      trip(d(2026, 6, 10), 3, Places.ROMA, expected = false)
      event(d(2025, 9, 6), 19, 0, 40, 150, home)
    }
    assertThat(d.items.map { it.range.to }).isInOrder(Comparator.reverseOrder<LocalDate>())
    val trip = d.items.first { it.kind == TripKind.TRIP }
    val cover = dev.pampa.mycloudgallery.core.albums.trips.CoverPicker.pick(trip.coverPool)
    assertThat(trip.coverPool.map { it.media }).contains(cover.media)
    assertThat(trip.coverPool.all { it.pixels <= cover.pixels }).isTrue()
  }

  @Test
  fun ilCerchioDiUnViaggioCopreLeSueFoto() {
    val d = detect { trip(d(2026, 5, 2), 4, Places.OSLO, expected = false) }
    val item = d.items.single { it.kind == TripKind.TRIP }
    val area = TripDetector.areaOf(item.members, d.current)!!
    assertThat(TripGeometry.km(area.lat, area.lon, Places.OSLO.lat, Places.OSLO.lon)).isLessThan(5.0)
    assertThat(area.radiusKm).isIn(com.google.common.collect.Range.closed(SmartLimits.MIN_RADIUS_KM, 30))
    // Un giro in due citta' lontane: il cerchio le copre tutte e due.
    val two = detect {
      trip(d(2026, 5, 2), 3, Places.PARIGI, expected = false)
      trip(d(2026, 5, 5), 3, Places.LONDRA, expected = false)
    }
    val round = two.items.single { it.kind == TripKind.TRIP }
    val a = TripDetector.areaOf(round.members, two.current)!!
    for (spot in listOf(Places.PARIGI, Places.LONDRA)) assertThat(TripGeometry.km(a.lat, a.lon, spot.lat, spot.lon)).isAtMost(a.radiusKm.toDouble())
  }

  @Test
  fun inInputInDisordineDaLoStessoRisultato() {
    val story = StoryBuilder("ordine", 7, today).apply {
      trip(d(2026, 5, 2), 4, Places.ROMA, expected = false)
      event(d(2026, 4, 11), 19, 0, 40, 150, home)
      residence(first, today, Habits(home))
    }.build()
    val visible = story.shots.filter { it.mine && !it.screen }
    val a = TripDetector.detect(visible, today)
    val b = TripDetector.detect(visible.shuffled(kotlin.random.Random(9)), today)
    assertThat(b.items.map { it.id }).isEqualTo(a.items.map { it.id })
  }
}
