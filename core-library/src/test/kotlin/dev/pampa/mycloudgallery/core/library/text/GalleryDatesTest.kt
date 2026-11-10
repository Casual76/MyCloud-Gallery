package dev.pampa.mycloudgallery.core.library.text

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * P11: l'app e' solo in italiano. **Con il telefono in inglese** (la lingua di default della JVM qui) le date della
 * galleria restano italiane: griglia, intestazioni, scrubber, viewer e Raccolte passano tutti da [GalleryDates].
 */
class GalleryDatesTest {

  private lateinit var previous: Locale

  @Before
  fun englishDevice() {
    previous = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun restore() {
    Locale.setDefault(previous)
  }

  @Test
  fun conIlTelefonoInIngleseLeDateRestanoInItaliano() {
    assertThat(GalleryDates.monthYear(YearMonth.of(2024, 3))).isEqualTo("Marzo 2024")
    assertThat(GalleryDates.weekdayTitle(LocalDate.of(2024, 3, 12), withYear = false)).isEqualTo("Martedì 12 marzo")
    assertThat(GalleryDates.weekdayTitle(LocalDate.of(2024, 3, 12), withYear = true)).isEqualTo("Martedì 12 marzo 2024")
    assertThat(GalleryDates.date(LocalDate.of(2024, 3, 12))).isEqualTo("12 marzo 2024")
    val time = LocalDateTime.of(2024, 3, 12, 18, 41)
    assertThat(GalleryDates.date(time)).isEqualTo("12 marzo 2024")
    assertThat(GalleryDates.weekdayDate(time)).isEqualTo("martedì 12 marzo 2024")
    // Sempre 24 ore, mai "6:41 PM".
    assertThat(GalleryDates.time(time)).isEqualTo("18:41")
  }

  /**
   * GF-2 (review della beta): le forme corte per l'etichetta in alto, dalla piu' lunga alla piu' stretta. Ogni forma corta e' davvero piu' corta
   * della precedente, per ogni giorno di un anno (i mesi e i giorni lunghi e corti, i giorni a una e a due cifre).
   */
  @Test
  fun leFormeCorteDellaData() {
    val day = LocalDate.of(2024, 11, 20)
    assertThat(GalleryDates.weekdayTitleShort(day, withYear = true)).isEqualTo("Mer 20 nov 2024")
    assertThat(GalleryDates.weekdayTitleShort(day, withYear = false)).isEqualTo("Mer 20 nov")
    assertThat(GalleryDates.dayMonthShort(day, withYear = true)).isEqualTo("20 nov 2024")
    assertThat(GalleryDates.dayMonthShort(day, withYear = false)).isEqualTo("20 nov")
    assertThat(GalleryDates.dayMonthShortYear(day)).isEqualTo("20 nov 24")
    assertThat(GalleryDates.numericDate(day)).isEqualTo("20/11/24")
    assertThat(GalleryDates.numericDate(LocalDate.of(2005, 3, 9))).isEqualTo("9/3/05")
    assertThat(GalleryDates.monthYearShort(YearMonth.of(2024, 9))).isEqualTo("Set 2024")
    // Lunedi' e domenica, gennaio e dicembre: gli estremi delle due tabelle.
    assertThat(GalleryDates.weekdayTitleShort(LocalDate.of(2024, 1, 1), withYear = false)).isEqualTo("Lun 1 gen")
    assertThat(GalleryDates.weekdayTitleShort(LocalDate.of(2023, 12, 31), withYear = true)).isEqualTo("Dom 31 dic 2023")

    var d = LocalDate.of(2024, 1, 1)
    while (d.year == 2024) {
      val forms = listOf(
        GalleryDates.weekdayTitle(d, withYear = true),
        GalleryDates.weekdayTitleShort(d, withYear = true),
        GalleryDates.dayMonthShort(d, withYear = true),
        GalleryDates.dayMonthShortYear(d),
        GalleryDates.numericDate(d),
      )
      assertThat(forms.zipWithNext().all { (longer, shorter) -> shorter.length < longer.length }).isTrue()
      // Il giorno della settimana abbreviato e' l'inizio di quello intero: la forma corta non contraddice la lunga.
      assertThat(forms[0].take(3)).isEqualTo(forms[1].take(3))
      d = d.plusDays(1)
    }
  }

  @Test
  fun laLinguaDellaGalleriaEItaliano() {
    assertThat(GalleryDates.LOCALE.language).isEqualTo("it")
  }
}
