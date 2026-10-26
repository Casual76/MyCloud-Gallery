package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.LocalDate
import org.junit.Test

/** I titoli delle proposte (D19): "Oslo, marzo 2026", "Toscana e altri 2 luoghi, agosto 2025", "Sabato 12 aprile 2026". */
class TripTitlesTest {

  private val titles = TripTitles(FakePlaces())
  private val oslo = PlaceStats(intArrayOf(FakePlaceData.OSLO.place.id.value), 1, listOf(FakePlaceData.OSLO.place.id.value))

  private fun range(a: LocalDate, b: LocalDate = a) = LocalDayRange(a, b)

  @Test
  fun unViaggioHaIlLuogoEIlMese() {
    assertThat(titles.trip(range(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18)), oslo)).isEqualTo("Oslo, marzo 2026")
  }

  @Test
  fun unViaggioSuPiuMesiEPiuAnni() {
    assertThat(titles.trip(range(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 4, 3)), oslo)).isEqualTo("Oslo, marzo–aprile 2026")
    assertThat(titles.trip(range(LocalDate.of(2025, 12, 27), LocalDate.of(2026, 1, 3)), oslo)).isEqualTo("Oslo, dicembre 2025–gennaio 2026")
  }

  @Test
  fun unViaggioDiUnGiornoHaLaData() {
    assertThat(titles.trip(range(LocalDate.of(2025, 11, 15)), oslo)).isEqualTo("Oslo, 15 novembre 2025")
  }

  @Test
  fun piuLuoghiSiDiconoConIlPrimoEQuantiSonoGliAltri() {
    val ids = listOf(FakePlaceData.FIRENZE, FakePlaceData.FIESOLE, FakePlaceData.ROMA).map { it.place.id.value }
    val three = PlaceStats(ids.toIntArray(), 3, ids.sorted())
    assertThat(titles.trip(range(LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 20)), three)).isEqualTo("Firenze e altri 2 luoghi, agosto 2025")
    val two = PlaceStats(ids.take(2).toIntArray(), 2, ids.take(2).sorted())
    assertThat(titles.trip(range(LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 20)), two)).isEqualTo("Firenze e Fiesole, agosto 2025")
  }

  @Test
  fun senzaLuogoNotoIlViaggioSiChiamaViaggio() {
    assertThat(titles.trip(range(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18)), PlaceStats.NONE)).isEqualTo("Viaggio, marzo 2026")
    val unknown = PlaceStats(intArrayOf(99), 1, listOf(99))
    assertThat(titles.trip(range(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18)), unknown)).isEqualTo("Viaggio, marzo 2026")
  }

  @Test
  fun unEventoHaIlGiornoDellaSettimanaEIlLuogoSeCe() {
    val day = LocalDate.of(2026, 4, 11)
    assertThat(titles.event(range(day), PlaceStats.NONE)).isEqualTo("Sabato 11 aprile 2026")
    assertThat(titles.event(range(day), oslo)).isEqualTo("Sabato 11 aprile 2026, Oslo")
    // Una festa che attraversa la mezzanotte si intitola con il giorno in cui comincia.
    assertThat(titles.event(range(day, day.plusDays(1)), PlaceStats.NONE)).isEqualTo("Sabato 11 aprile 2026")
  }

  @Test
  fun tuttiIGiorniDellaSettimanaEIMesiHannoUnNome() {
    val names = (0 until 7).map { titles.event(range(LocalDate.of(2026, 4, 6).plusDays(it.toLong())), PlaceStats.NONE).substringBefore(' ') }
    assertThat(names).containsExactly("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica").inOrder()
    val months = (1..12).map { titles.trip(range(LocalDate.of(2026, it, 10), LocalDate.of(2026, it, 12)), PlaceStats.NONE).substringAfter(", ").substringBefore(' ') }
    assertThat(months).containsExactly("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre").inOrder()
  }

  @Test
  fun unTitoloNonSuperaMaiIl64CaratteriENonHaCaratteriVietati() {
    val long = object : PlaceNames by FakePlaces() {
      override fun summary(top: IntArray, distinct: Int): String = "Un luogo con un nome davvero molto ma molto lungo, più lungo di ogni limite ragionevole" + String(Character.toChars(0x200E))
    }
    val t = TripTitles(long)
    val stats = PlaceStats(intArrayOf(1), 1, listOf(1))
    val trip = t.trip(range(LocalDate.of(2025, 12, 27), LocalDate.of(2026, 1, 3)), stats)
    assertThat(trip.length).isAtMost(AlbumTitles.MAX_CHARS)
    assertThat(trip).endsWith("dicembre 2025–gennaio 2026")
    assertThat(trip).contains("…")
    assertThat(AlbumTitles.problem(trip)).isNull()
    val event = t.event(range(LocalDate.of(2026, 4, 11)), stats)
    assertThat(event.length).isAtMost(AlbumTitles.MAX_CHARS)
    assertThat(event).startsWith("Sabato 11 aprile 2026, ")
    assertThat(AlbumTitles.problem(event)).isNull()
  }

  /** Un nome di luogo con un carattere che il codec rifiuta (un non carattere Unicode) non rende la proposta inaccettabile: si ripiega sul titolo senza luogo. */
  @Test
  fun unNomeDiLuogoChePassaIlCodecNonRendeLaPropostaInaccettabile() {
    val weird = object : PlaceNames by FakePlaces() {
      override fun summary(top: IntArray, distinct: Int): String = "Luogo" + String(Character.toChars(0xFFFE))
    }
    val t = TripTitles(weird)
    val stats = PlaceStats(intArrayOf(1), 1, listOf(1))
    assertThat(t.trip(range(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18)), stats)).isEqualTo("Viaggio, marzo 2026")
    assertThat(t.event(range(LocalDate.of(2026, 4, 11)), stats)).isEqualTo("Sabato 11 aprile 2026")
  }

  /** Tutti i titoli possibili sono titoli che il codec degli album accetta (non fanno rifiutare la definizione a chi legge). */
  @Test
  fun iTitoliPassanoLeRegoleDeiTitoliDegliAlbum() {
    var day = LocalDate.of(2025, 1, 1)
    while (day.isBefore(LocalDate.of(2026, 12, 31))) {
      for (span in listOf(0L, 1L, 6L, 40L)) {
        val r = range(day, day.plusDays(span))
        assertThat(AlbumTitles.problem(titles.trip(r, oslo))).isNull()
        assertThat(AlbumTitles.problem(titles.event(r, oslo))).isNull()
      }
      day = day.plusDays(9)
    }
    assertThat(AlbumId.random().value).isNotEmpty()
  }
}
