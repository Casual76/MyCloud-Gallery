package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.testing.MemoriesSamples
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I titoli e i sottotitoli dei ricordi (Fase 9, P9; §2.4, D3, D12, D13): "Un anno fa", "3 anni fa", "Oslo, marzo 2025", "Con Anna", "Anna nel 2024", "Il meglio di settembre 2026".
 * Il titolo salvato e' fatto di dati, il testo si compone qui; il nome e i luoghi arrivano gia' letti e non si salvano. Mai la parola "casa".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesTextTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
  private val person = PersonId.fromBytes(ByteArray(16) { (it + 3).toByte() })

  private fun title(yearsAgo: Int? = null, days: DayRange, label: String? = null, who: PersonId? = null, month: YearMonth? = null) =
    MemoryTitle(yearsAgo, days, emptyList(), label, who, month)

  @Test
  fun anniFa() {
    val day = DayRange.of(LocalDate.of(2025, 3, 12))
    assertThat(MemoriesText.title(resources, MemoryKind.ON_THIS_DAY, title(1, day), null, emptyList())).isEqualTo("Un anno fa")
    assertThat(MemoriesText.title(resources, MemoryKind.ON_THIS_DAY, title(3, day), null, emptyList())).isEqualTo("3 anni fa")
    assertThat(MemoriesText.yearsAgo(resources, 12)).isEqualTo("12 anni fa")
  }

  @Test
  fun viaggiEEventi_conEtichettaOSenza() {
    val days = DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6))
    assertThat(MemoriesText.title(resources, MemoryKind.TRIP, title(days = days, label = "Oslo, marzo 2025"), null, listOf("Oslo"))).isEqualTo("Oslo, marzo 2025")
    assertThat(MemoriesText.title(resources, MemoryKind.TRIP, title(days = days), null, listOf("Oslo"))).isEqualTo("Un viaggio, Oslo")
    assertThat(MemoriesText.title(resources, MemoryKind.TRIP, title(days = days), null, emptyList())).isEqualTo("Un viaggio")
    assertThat(MemoriesText.title(resources, MemoryKind.EVENT, title(days = DayRange.of(days.from)), null, emptyList())).isEqualTo("Un evento")
  }

  @Test
  fun persone_unAnnoIntero_oUnaFinestra_oSenzaNome() {
    val year = DayRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
    val recent = DayRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 20))
    assertThat(MemoriesText.title(resources, MemoryKind.PERSON, title(days = year, who = person), "Anna", emptyList())).isEqualTo("Anna nel 2024")
    assertThat(MemoriesText.title(resources, MemoryKind.PERSON, title(days = recent, who = person), "Anna", emptyList())).isEqualTo("Con Anna")
    // Persone non e' attiva qui, o la persona e' nascosta: nessun nome (mai uno salvato).
    assertThat(MemoriesText.title(resources, MemoryKind.PERSON, title(days = year, who = person), null, emptyList())).isEqualTo("Una persona")
    assertThat(MemoriesText.isWholeYear(year)).isTrue()
    assertThat(MemoriesText.isWholeYear(recent)).isFalse()
  }

  @Test
  fun ilMeseDelMeglio() {
    val month = YearMonth.of(2026, 9)
    assertThat(MemoriesText.title(resources, MemoryKind.MONTH, title(days = DayRange(month.atDay(1), month.atEndOfMonth()), month = month), null, emptyList()))
      .isEqualTo("Il meglio di settembre 2026")
  }

  @Test
  fun periodoESottotitolo() {
    val one = DayRange.of(LocalDate.of(2025, 3, 12))
    val range = DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6))
    assertThat(MemoriesText.period(resources, one)).isEqualTo("12 marzo 2025")
    assertThat(MemoriesText.period(resources, range)).isEqualTo("1 marzo 2025 – 6 marzo 2025")
    assertThat(MemoriesText.period(resources, DayRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))).isEqualTo("2024")
    assertThat(MemoriesText.subtitle(resources, title(days = one), listOf("Firenze", "Oslo", "Roma", "Torino"))).isEqualTo("12 marzo 2025 · Firenze · Oslo · Roma")
    assertThat(MemoriesText.subtitle(resources, title(days = one), emptyList())).isEqualTo("12 marzo 2025")
  }

  @Test
  fun contoEFrasePerTalkBack() {
    assertThat(MemoriesText.photos(resources, 1)).isEqualTo("1 foto")
    assertThat(MemoriesText.photos(resources, 1_240)).isEqualTo("1.240 foto")
    val card = MemoriesSamples.otd
    assertThat(MemoriesText.spoken(resources, card, "Un anno fa")).isEqualTo("Nuovo. Un anno fa, 18 foto")
    assertThat(MemoriesText.spoken(resources, card.copy(isNew = false), "Un anno fa")).isEqualTo("Un anno fa, 18 foto")
  }

  @Test
  fun lOraSiScriveA24Ore_eNessunTestoDiceCasa() {
    assertThat(MemoriesText.time(LocalTime.of(7, 5))).isEqualTo("07:05")
    assertThat(MemoriesText.time(LocalTime.of(19, 0))).isEqualTo("19:00")
    val every = MemoryKind.entries.map { kind ->
      MemoriesText.title(resources, kind, title(days = DayRange.of(LocalDate.of(2025, 3, 12)), month = YearMonth.of(2025, 3)), null, listOf("Firenze"))
    }
    for (text in every) assertThat(text.lowercase()).doesNotContain("casa")
  }
}
