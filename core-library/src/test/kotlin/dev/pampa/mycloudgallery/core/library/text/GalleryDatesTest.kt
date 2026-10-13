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

  @Test
  fun laLinguaDellaGalleriaEItaliano() {
    assertThat(GalleryDates.LOCALE.language).isEqualTo("it")
  }
}
