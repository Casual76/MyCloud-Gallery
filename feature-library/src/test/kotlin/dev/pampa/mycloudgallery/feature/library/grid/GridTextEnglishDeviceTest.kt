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
    duration = "durata %1\$s", group = "%1\$s scatti", phoneOnly = "solo sul telefono", nasOnly = "solo sul NAS",
    both = "sul telefono e sul NAS", safe = "al sicuro sul NAS", toVerify = "copia sul NAS da verificare",
    backupProblem = "problema con il backup", similarCopies = "%1\$s copie simili",
    open = "Apri", select = "Seleziona", deselect = "Deseleziona",
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

  /**
   * GF-2 (review della beta): le forme piu' corte della data per l'etichetta in alto, dalla piu' lunga alla piu' stretta. Anche loro in
   * italiano con il telefono in inglese. "Oggi", "Ieri" e gli anni non ne hanno: entrano sempre.
   */
  @Test
  fun leFormeCorteDelleIntestazioni() {
    val today = LocalDate.of(2026, 10, 13)
    assertThat(GridText.sectionShorterTitles(section(GridLevel.DAYS, 20241120), today))
      .containsExactly("Mer 20 nov 2024", "20 nov 2024", "20 nov 24", "20/11/24").inOrder()
    // Nell'anno in corso l'intestazione non ha l'anno, e nemmeno le sue forme corte.
    assertThat(GridText.sectionShorterTitles(section(GridLevel.DAYS, 20261010), today)).containsExactly("Sab 10 ott", "10 ott").inOrder()
    assertThat(GridText.sectionShorterTitles(section(GridLevel.DAYS, 20261013), today)).isEmpty()
    assertThat(GridText.sectionShorterTitles(section(GridLevel.DAYS, 20261012), today)).isEmpty()
    assertThat(GridText.sectionShorterTitles(section(GridLevel.MONTHS, 20240912), today)).containsExactly("Set 2024")
    assertThat(GridText.sectionShorterTitles(section(GridLevel.ALL, 20240912), today)).containsExactly("Set 2024")
    assertThat(GridText.shorterMonths(YearMonth.of(2024, 9))).containsExactly("Set 2024")
    assertThat(GridText.sectionShorterTitles(section(GridLevel.YEARS, 20240912), today)).isEmpty()
  }
}
