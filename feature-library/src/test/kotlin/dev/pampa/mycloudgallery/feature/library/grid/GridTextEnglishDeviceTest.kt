package dev.pampa.mycloudgallery.feature.library.grid

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * P11: con il telefono in inglese le intestazioni della griglia e lo scrubber restano in italiano (le date passano da
 * `GalleryDates`, l'unico punto di formattazione della galleria).
 */
class GridTextEnglishDeviceTest {

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

  private val strings = GridStrings(
    today = "Oggi", yesterday = "Ieri", kindPhoto = "Foto", kindVideo = "Video", kindMotion = "Foto in movimento",
    kindRaw = "Foto RAW", kindAnimated = "Immagine animata", favoriteFeminine = "preferita", favoriteMasculine = "preferito",
    editedFeminine = "modificata", editedMasculine = "modificato", unreadable = "non leggibile", estimated = "data stimata",
    duration = "durata %1\$s", group = "%1\$d scatti", open = "Apri", select = "Seleziona", deselect = "Deseleziona",
    scrubber = "Scorri la timeline",
  )

  private fun section(level: GridLevel, day: Int) =
    SectionInfo(level = level, firstDay = day, lastDay = day, count = 1, topPlaces = IntArray(0), distinctPlaces = 0)

  @Test
  fun leIntestazioniRestanoInItaliano() {
    val today = LocalDate.of(2026, 10, 13)
    assertThat(GridText.month(YearMonth.of(2024, 3))).isEqualTo("Marzo 2024")
    assertThat(GridText.sectionTitle(section(GridLevel.MONTHS, 20240312), today, strings)).isEqualTo("Marzo 2024")
    assertThat(GridText.sectionTitle(section(GridLevel.DAYS, 20240312), today, strings)).isEqualTo("Martedì 12 marzo 2024")
    assertThat(GridText.sectionTitle(section(GridLevel.DAYS, 20261010), today, strings)).isEqualTo("Sabato 10 ottobre")
    assertThat(GridText.sectionTitle(section(GridLevel.DAYS, 20261012), today, strings)).isEqualTo("Ieri")
  }
}
