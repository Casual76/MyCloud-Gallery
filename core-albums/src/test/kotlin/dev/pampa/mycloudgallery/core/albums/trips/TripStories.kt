package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import java.time.LocalDate

// LE 20 STORIE DI DUE ANNI (docs/fase7-progetto.md §5.3, riga P7 del §4.4): "tutti i viaggi trovati, nessun falso viaggio a casa". Ogni storia
// dice da sola cosa deve uscire (`Expect`); le date sono scelte a mano (nessuna casualita' nelle verita'), la vita di tutti i giorni e'
// casuale ma ripetibile (il seme e' il numero della storia). Il generatore di P12 (`tools/fixtures/trips/`) fara' lo stesso per il banco
// condiviso con P6; questo e' quello che P7 porta nei propri test.

internal object TripStories {

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  /** Quattro posti attorno a [home], a 5, 15, 27 e 41 km: le uscite di tutti i giorni (mai oltre i 50 km, mai un viaggio). */
  private fun nearby(home: Spot): List<Spot> = listOf(
    home.moved(4.0, 3.0, 7_100_001, "Vicino 1"),
    home.moved(-9.0, 12.0, 7_100_002, "Vicino 2"),
    home.moved(25.0, -10.0, 7_100_003, "Vicino 3"),
    home.moved(-30.0, 28.0, 7_100_004, "Vicino 4"),
  )

  private fun lives(b: StoryBuilder, home: Spot, from: LocalDate = b.first, to: LocalDate = b.today, tune: (Habits) -> Habits = { it }) {
    b.residence(from, to, tune(Habits(home, outings = nearby(home))))
  }

  private fun story(name: String, seed: Long, today: LocalDate = d(2026, 10, 22), block: StoryBuilder.() -> Unit): Story =
    StoryBuilder(name, seed, today).apply(block).build()

  fun all(): List<Story> = listOf(
    baseline(), move(), seaHouse(), noGps(), newPhone(), timeZones(), newYear(), weekends(), gpsFreeDays(), family(),
    screens(), eventSizes(), prolific(), sparse(), habitualPlace(), closeToHome(), hiddenMedia(), badGps(), frequentTraveller(), everything(),
  )

  /** 1. Firenze: quattro viaggi, due feste, due giornate fuori sopra i 50 km ma sotto i 100 (nessun viaggio). */
  private fun baseline() = story("Firenze, quattro viaggi e due feste", 1) {
    val home = Places.FIRENZE
    trip(d(2025, 3, 14), 5, Places.OSLO)
    trip(d(2025, 9, 5), 3, Places.ROMA)
    trip(d(2025, 12, 27), 8, Places.PARIGI)
    trip(d(2026, 6, 10), 4, Places.LONDRA)
    dayOut(d(2025, 5, 10), home.moved(-40.0, 55.0, 7_200_001, "Siena"), 22)
    dayOut(d(2026, 1, 17), home.moved(60.0, 75.0, 7_200_002, "Bologna"), 25)
    event(d(2025, 6, 21), 19, 0, 40, 150, home)
    event(d(2026, 4, 4), 17, 30, 55, 200, home)
    lives(this, home)
    expect.home = home
  }

  /** 2. Trasloco (E-AL15): Firenze fino al 30/6/2025, poi Milano. La visita alla vecchia casa, a oltre un anno e mezzo, e' un viaggio. */
  private fun move() = story("Trasloco da Firenze a Milano", 2) {
    lives(this, Places.FIRENZE, to = d(2025, 6, 30))
    lives(this, Places.MILANO, from = d(2025, 7, 1))
    trip(d(2024, 12, 20), 7, Places.PARIGI)
    trip(d(2025, 4, 10), 4, Places.LISBONA)
    trip(d(2025, 10, 2), 4, Places.LONDRA)
    trip(d(2026, 3, 20), 5, Places.OSLO)
    trip(d(2026, 8, 14), 3, Places.FIRENZE)
    expect.home = Places.MILANO
  }

  /** 3. Casa al mare: i fine settimana d'estate e tutto agosto 2025 a 90 km non sono viaggi; quattro viaggi veri, uno a Tokyo (fuso +9). */
  private fun seaHouse() = story("Roma con la casa al mare", 3) {
    val home = Places.ROMA
    val sea = home.moved(-70.0, 60.0, 7_300_001, "Mare")
    for (year in listOf(2025, 2026)) {
      var friday = d(year, 6, 6).let { it.plusDays(((5 - it.dayOfWeek.value + 7) % 7).toLong()) }
      while (friday.isBefore(d(year, 9, 29))) {
        stay(friday, friday.plusDays(2), sea)
        friday = friday.plusDays(7)
      }
    }
    stay(d(2025, 8, 1), d(2025, 8, 31), sea)
    trip(d(2025, 2, 12), 5, Places.PARIGI)
    trip(d(2025, 11, 4), 4, Places.LISBONA)
    trip(d(2026, 3, 20), 9, Places.TOKYO, tz = 540)
    trip(d(2026, 9, 15), 3, Places.LONDRA)
    lives(this, home)
    expect.home = home
  }

  /** 4. Nessun GPS (E-AL16): niente casa e niente viaggi, solo eventi. Anche una settimana "di viaggio" senza GPS non si puo' trovare. */
  private fun noGps() = story("Nessun GPS", 4) {
    event(d(2025, 2, 14), 19, 0, 35, 120, null)
    event(d(2025, 11, 8), 18, 0, 80, 180, null)
    event(d(2026, 5, 1), 20, 0, 45, 150, null)
    trip(d(2025, 7, 3), 5, Places.OSLO, gpsFree = setOf(0, 1, 2, 3, 4), expected = false)
    lives(this, Places.FIRENZE) { it.copyWith(gpsMissing = 1.0) }
    expect.home = null
  }

  /** 5. Telefono nuovo: il GPS c'e' solo da settembre 2025; il viaggio di prima non si trova, i due di dopo si'. */
  private fun newPhone() = story("Telefono nuovo con il GPS", 5) {
    val home = Places.FIRENZE
    lives(this, home, to = d(2025, 8, 31)) { it.copyWith(gpsMissing = 1.0) }
    lives(this, home, from = d(2025, 9, 1))
    trip(d(2025, 3, 14), 5, Places.OSLO, gpsFree = setOf(0, 1, 2, 3, 4), expected = false)
    trip(d(2025, 11, 20), 4, Places.MONACO)
    trip(d(2026, 5, 15), 5, Places.LISBONA)
    expect.home = home
  }

  /** 6. Fusi orari (E-AL10): New York, Tokyo, Los Angeles e Sydney. Uno scatto a mezzanotte e venti del primo giorno e uno a mezzanotte meno dieci dell'ultimo. */
  private fun timeZones() = story("Viaggi in altri fusi", 6) {
    trip(d(2025, 5, 8), 7, Places.NEW_YORK, tz = -240, edges = true)
    trip(d(2025, 10, 3), 9, Places.TOKYO, tz = 540, edges = true)
    trip(d(2026, 3, 2), 6, Places.LOS_ANGELES, tz = -420, edges = true)
    trip(d(2026, 8, 6), 8, Places.SYDNEY, tz = 600, edges = true)
    lives(this, Places.FIRENZE)
    expect.home = Places.FIRENZE
  }

  /** 7. A cavallo d'anno (E-AL1): due viaggi che attraversano il 31 dicembre restano uno ciascuno; una festa; la vacanza di un mese e' un viaggio. */
  private fun newYear() = story("Viaggi a cavallo d'anno", 7) {
    trip(d(2024, 12, 28), 9, Places.PARIGI)
    trip(d(2025, 8, 10), 4, Places.LISBONA)
    trip(d(2026, 7, 5), 31, Places.ROMA, perDay = 3..8)
    trip(d(2025, 12, 30), 6, Places.MONACO)
    event(d(2026, 2, 14), 19, 30, 33, 110, Places.FIRENZE)
    lives(this, Places.FIRENZE)
    expect.home = Places.FIRENZE
  }

  /** 8. Fine settimana e giornate lunghe: sei fine settimana (2 giorni) e una giornata a 134 km con 26 foto sono viaggi; 90 km e 117 km con poche foto no. */
  private fun weekends() = story("Fine settimana e giornate lunghe", 8) {
    val home = Places.FIRENZE
    val dests = listOf(home.moved(120.0, 0.0, 7_500_001, "A"), home.moved(0.0, 150.0, 7_500_002, "B"), home.moved(-130.0, 0.0, 7_500_003, "C"),
      home.moved(0.0, -100.0, 7_500_004, "D"), home.moved(180.0, 50.0, 7_500_005, "E"), home.moved(-150.0, -50.0, 7_500_006, "F"))
    val saturdays = listOf(d(2025, 2, 8), d(2025, 4, 12), d(2025, 6, 14), d(2025, 9, 20), d(2026, 2, 14), d(2026, 5, 9))
    saturdays.forEachIndexed { i, day -> trip(day, 2, dests[i], perDay = 8..14) }
    dayOut(d(2025, 11, 15), home.moved(120.0, 60.0, 7_500_007, "G"), 26)
    expect.trips += LocalDayRange(d(2025, 11, 15), d(2025, 11, 15))
    dayOut(d(2026, 3, 7), home.moved(90.0, 0.0, 7_500_008, "H"), 30)
    dayOut(d(2026, 7, 4), home.moved(110.0, 40.0, 7_500_009, "I"), 15)
    lives(this, home)
    expect.home = home
  }

  /** 9. Giorni senza GPS dentro il viaggio (E-AL11): fino a due giorni di fila il viaggio resta uno. */
  private fun gpsFreeDays() = story("Viaggi con giorni senza GPS", 9) {
    trip(d(2025, 2, 1), 6, Places.OSLO, gpsFree = setOf(2))
    trip(d(2025, 6, 12), 6, Places.ROMA, gpsFree = setOf(3))
    trip(d(2025, 10, 4), 6, Places.PARIGI, gpsFree = setOf(2, 3))
    trip(d(2026, 3, 14), 6, Places.LONDRA, gpsFree = setOf(1, 4))
    lives(this, Places.FIRENZE)
    expect.home = Places.FIRENZE
  }

  /** 10. Foto dei familiari (E-AL17): il viaggio a Berlino di un'altra persona e le sue foto nei posti dei miei viaggi non sono viaggi miei. */
  private fun family() = story("Foto dei familiari", 10) {
    trip(d(2025, 3, 14), 5, Places.OSLO)
    trip(d(2025, 9, 5), 3, Places.ROMA)
    trip(d(2025, 12, 27), 8, Places.PARIGI)
    family(d(2025, 4, 2), d(2025, 4, 8), Places.BERLINO, perDay = 6)
    family(d(2025, 3, 14), d(2025, 3, 18), Places.OSLO)
    family(first, today, Places.FIRENZE.moved(1.0, 1.0), perDay = 2)
    lives(this, Places.FIRENZE)
    expect.home = Places.FIRENZE
  }

  /** 11. Screenshot e video (E-AL4): 40 screenshot in un'ora non sono un evento; i video dei viaggi contano, gli screenshot no. */
  private fun screens() = story("Screenshot e video", 11) {
    val home = Places.FIRENZE
    screenshots(d(2025, 5, 5), 14, 40, 60, home)
    screenshots(d(2026, 1, 12), 20, 35, 50)
    trip(d(2025, 3, 14), 5, Places.OSLO, videos = true, screenshotsPerDay = 8)
    trip(d(2025, 9, 5), 3, Places.ROMA, videos = true, screenshotsPerDay = 8)
    trip(d(2026, 5, 20), 4, Places.MONACO, videos = true, screenshotsPerDay = 8)
    event(d(2025, 12, 6), 19, 0, 42, 180, home, videos = true)
    lives(this, home)
    expect.home = home
  }

  /** 12. Eventi di ogni misura: 29 foto no, 30 si', 35 in sei ore e mezza no, una festa che attraversa la mezzanotte si', 90 in sei ore (una sola festa). */
  private fun eventSizes() = story("Eventi di ogni misura", 12) {
    val home = Places.FIRENZE
    event(d(2025, 1, 25), 19, 0, 29, 120, home, expected = false)
    event(d(2025, 2, 22), 19, 0, 30, 120, home)
    event(d(2025, 5, 31), 18, 0, 60, 210, home)
    event(d(2025, 8, 16), 14, 0, 35, 390, home, expected = false)
    event(d(2025, 12, 20), 22, 30, 40, 180, home)
    event(d(2026, 4, 18), 15, 0, 90, 360, home)
    lives(this, home)
    expect.home = home
  }

  /** 13. Chi fotografa tanto: 12 foto al giorno; un evento deve avere 5 volte il ritmo (circa 60 in 4 ore). 45 foto non bastano. */
  private fun prolific() = story("Fotografo prolifico", 13) {
    val home = Places.FIRENZE
    trip(d(2025, 3, 20), 4, Places.LONDRA, perDay = 25..40)
    trip(d(2025, 9, 4), 5, Places.PARIGI, perDay = 25..40)
    trip(d(2026, 4, 2), 3, Places.MONACO, perDay = 25..40)
    event(d(2025, 6, 14), 19, 0, 80, 180, home)
    event(d(2025, 12, 13), 17, 0, 150, 300, home)
    event(d(2026, 3, 14), 19, 0, 45, 120, home, expected = false)
    lives(this, home) { it.copyWith(dayProbability = 0.9, perDay = 8..16) }
    expect.home = home
  }

  /** 14. Chi fotografa di rado: una cinquantina di giorni con foto in due anni; la casa si stima lo stesso. */
  private fun sparse() = story("Fotografo di rado", 14) {
    val home = Places.FIRENZE
    trip(d(2025, 4, 24), 4, Places.MONACO, perDay = 6..10)
    trip(d(2026, 2, 5), 4, Places.LISBONA, perDay = 6..10)
    lives(this, home) { it.copyWith(dayProbability = 0.07, perDay = 1..3, eveningShare = 0.65, gpsMissing = 0.0) }
    expect.home = home
  }

  /** 15. Luogo abituale: i fine settimana dai suoceri (60 km) sono il 40% dei fine settimana: non sono viaggi. */
  private fun habitualPlace() = story("Il paese dei suoceri", 15) {
    val home = Places.FIRENZE
    val inLaws = home.moved(45.0, 40.0, 7_400_001, "Suoceri")
    var saturday = first.plusDays(((6 - first.dayOfWeek.value + 7) % 7).toLong())
    while (saturday.plusDays(1).isBefore(today)) {
      if (rnd.nextInt(100) < 40) stay(saturday, saturday.plusDays(1), inLaws)
      saturday = saturday.plusDays(7)
    }
    trip(d(2025, 3, 6), 5, Places.PARIGI)
    trip(d(2025, 10, 9), 4, Places.LONDRA)
    trip(d(2026, 5, 21), 4, Places.MONACO)
    lives(this, home)
    expect.home = home
  }

  /** 16. Vicino a casa: gite e un fine settimana a 44 km non sono viaggi; due viaggi veri. */
  private fun closeToHome() = story("Vicino a casa", 16) {
    val home = Places.FIRENZE
    dayOut(d(2025, 4, 5), home.moved(30.0, -15.0, 7_600_001, "Pistoia"), 18)
    dayOut(d(2025, 7, 19), home.moved(25.0, -5.0, 7_600_002, "Prato"), 14)
    dayOut(d(2026, 2, 28), home.moved(-20.0, 30.0, 7_600_003, "Arezzo"), 22)
    trip(d(2025, 5, 17), 2, home.moved(-35.0, 26.0, 7_600_004, "Chianti"), perDay = 8..14, expected = false)
    trip(d(2026, 6, 6), 3, home.moved(-36.0, 27.0, 7_600_005, "Val d'Orcia"), perDay = 8..14, expected = false)
    trip(d(2025, 9, 5), 3, Places.ROMA)
    trip(d(2026, 3, 14), 5, Places.OSLO)
    lives(this, home)
    expect.home = home
  }

  /** 17. Nascosti (E-H7): il 15% degli scatti e' nascosto e un viaggio intero e' fatto di soli nascosti: non si propone. */
  private fun hiddenMedia() = story("Con molti nascosti", 17) {
    trip(d(2025, 3, 14), 5, Places.OSLO, perDay = 8..14)
    trip(d(2025, 7, 1), 4, Places.LISBONA, perDay = 8..14, expected = false, hide = true)
    trip(d(2025, 12, 27), 6, Places.PARIGI, perDay = 8..14)
    trip(d(2026, 6, 10), 4, Places.LONDRA, perDay = 8..14)
    lives(this, Places.FIRENZE)
    hidePercent = 15
    expect.home = Places.FIRENZE
  }

  /** 18. GPS sbagliato: il (0, 0) di una posizione mai letta e, ogni tanto, una foto con il GPS a Tokyo in un giorno a casa. */
  private fun badGps() = story("GPS sbagliato", 18) {
    val home = Places.FIRENZE
    trip(d(2025, 3, 14), 5, Places.OSLO)
    trip(d(2025, 9, 5), 3, Places.ROMA)
    trip(d(2025, 12, 27), 8, Places.PARIGI)
    for (day in listOf(d(2025, 1, 20), d(2025, 5, 28), d(2025, 8, 19), d(2026, 2, 3), d(2026, 7, 22))) {
      dayOut(day, home, 4)
      outlier(day, 21, Places.TOKYO)
    }
    lives(this, home) { it.copyWith(badGps = 0.05) }
    expect.home = home
  }

  /** 19. Chi viaggia molto: un viaggio ogni due settimane circa; due viaggi con tre giorni a casa in mezzo restano due, due con due giorni vuoti in mezzo diventano uno. */
  private fun frequentTraveller() = story("Viaggiatore frequente", 19) {
    val dests = listOf(Places.OSLO, Places.ROMA, Places.PARIGI, Places.LONDRA, Places.MONACO, Places.LISBONA, Places.BERLINO, Places.NEW_YORK)
    var start = first.plusDays(8)
    var i = 0
    while (start.plusDays(30).isBefore(d(2026, 4, 1))) {
      val days = 5 + rnd.nextInt(6)
      val dest = dests[i++ % dests.size]
      trip(start, days, dest, tz = if (dest === Places.NEW_YORK) -240 else 60, perDay = 5..10)
      start = start.plusDays(days + 9L + rnd.nextInt(6))
    }
    trip(d(2026, 5, 2), 3, Places.ROMA, expected = false)
    quiet(d(2026, 5, 5), d(2026, 5, 6))
    trip(d(2026, 5, 7), 4, Places.ROMA, expected = false)
    expect.trips += LocalDayRange(d(2026, 5, 2), d(2026, 5, 10))
    lives(this, Places.FIRENZE) { it.copyWith(dayProbability = 0.7) }
    expect.home = Places.FIRENZE
  }

  /** 20. Tutto insieme: trasloco da Milano a Bologna, casa al mare, fusi, feste, screenshot, familiari, GPS sbagliato e il 8% di nascosti. */
  private fun everything() = story("Tutto insieme", 20) {
    val milano = Places.MILANO
    val bologna = Places.BOLOGNA
    val sea = milano.moved(-100.0, -60.0, 7_700_001, "Alassio")
    var friday = d(2025, 6, 6)
    while (friday.isBefore(d(2025, 9, 29))) {
      stay(friday, friday.plusDays(2), sea)
      friday = friday.plusDays(7)
    }
    trip(d(2025, 1, 15), 5, Places.OSLO)
    trip(d(2025, 5, 8), 6, Places.NEW_YORK, tz = -240, edges = true)
    trip(d(2025, 10, 3), 8, Places.TOKYO, tz = 540, edges = true)
    trip(d(2026, 3, 5), 4, Places.LONDRA)
    trip(d(2026, 7, 9), 5, Places.LISBONA)
    dayOut(d(2025, 4, 12), milano.moved(35.0, 0.0, 7_700_002, "Pavia"), 18)
    event(d(2025, 6, 21), 19, 0, 40, 150, milano)
    event(d(2026, 4, 11), 18, 0, 60, 210, bologna)
    screenshots(d(2025, 11, 5), 11, 40, 55, milano)
    family(first, today, bologna.moved(2.0, 2.0), perDay = 2)
    lives(this, milano, to = d(2025, 11, 30)) { it.copyWith(badGps = 0.03, screenshots = 0.05) }
    lives(this, bologna, from = d(2025, 12, 1)) { it.copyWith(badGps = 0.03, screenshots = 0.05) }
    hidePercent = 8
    expect.home = bologna
  }
}

internal fun Habits.copyWith(
  dayProbability: Double = this.dayProbability,
  perDay: IntRange = this.perDay,
  gpsMissing: Double = this.gpsMissing,
  badGps: Double = this.badGps,
  screenshots: Double = this.screenshots,
  eveningShare: Double = this.eveningShare,
): Habits = Habits(home, dayProbability, perDay, outingShare, outings, gpsMissing, badGps, screenshots, backupOnly, eveningShare, tz)
