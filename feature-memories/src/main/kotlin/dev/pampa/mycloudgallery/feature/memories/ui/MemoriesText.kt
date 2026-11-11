package dev.pampa.mycloudgallery.feature.memories.ui

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.feature.memories.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * I testi che piu' schermate dicono allo stesso modo, da funzioni pure (si provano senza comporre): il titolo di un ricordo ("Un anno fa", "Oslo,
 * marzo 2025", "Con Anna", "Il meglio di settembre 2026"), il sottotitolo (il periodo e i luoghi), il conto e la frase che TalkBack legge per
 * una voce (docs/fase9-progetto.md §2.4, §2.5, D13). Il titolo salvato e' fatto di **dati**: qui si compone; il nome di una persona e i nomi dei
 * luoghi arrivano gia' letti e mai si salvano (D12). Mai la parola "casa" (§4.3).
 */
internal object MemoriesText {

  /** "18 foto", "1 foto" (con il separatore delle migliaia). */
  fun photos(resources: Resources, count: Int): String = resources.getQuantityString(R.plurals.memorie_photos, count, GalleryNumbers.format(count))

  /** "Un anno fa", "3 anni fa". */
  fun yearsAgo(resources: Resources, years: Int): String = resources.getQuantityString(R.plurals.memorie_years_ago, years, years)

  /**
   * Il titolo grande. [personName] null = nessun nome (Persone spenta o persona sparita): "Una persona". Un ricordo di una persona **di un anno intero**
   * dice "Anna nel 2024", gli altri "Con Anna" (D3).
   */
  fun title(resources: Resources, kind: MemoryKind, title: MemoryTitle, personName: String?, placeNames: List<String>): String = when (kind) {
    MemoryKind.ON_THIS_DAY -> title.yearsAgo?.let { yearsAgo(resources, it) } ?: resources.getString(R.string.memorie_title_day_fallback)
    MemoryKind.TRIP, MemoryKind.EVENT -> title.tripLabel
      ?: placeNames.firstOrNull()?.let { place -> resources.getString(R.string.memorie_title_trip_fallback) + ", " + place }
      ?: resources.getString(if (kind == MemoryKind.TRIP) R.string.memorie_title_trip_fallback else R.string.memorie_title_event_fallback)
    MemoryKind.PERSON -> when {
      personName == null -> resources.getString(R.string.memorie_title_person_unnamed)
      isWholeYear(title.days) -> resources.getString(R.string.memorie_title_person_year, personName, title.days.from.year)
      else -> resources.getString(R.string.memorie_title_person_with, personName)
    }
    MemoryKind.MONTH -> resources.getString(
      R.string.memorie_title_month,
      title.month?.let { GalleryDates.monthYear(it).lowercase(Locale.ROOT) } ?: GalleryDates.monthYear(java.time.YearMonth.from(title.days.from)).lowercase(Locale.ROOT),
    )
  }

  /** Un anno di calendario intero (dal 1 gennaio al 31 dicembre): la finestra "Anna nel 2024". */
  fun isWholeYear(days: DayRange): Boolean =
    days.from.year == days.to.year && days.from.dayOfYear == 1 && days.to == LocalDate.of(days.to.year, 12, 31)

  /** Il periodo: un giorno ("12 marzo 2025"), un intervallo ("1 marzo 2024 – 6 marzo 2024") o un anno intero ("2024"). */
  fun period(resources: Resources, days: DayRange): String = when {
    days.from == days.to -> GalleryDates.date(days.from)
    isWholeYear(days) -> days.from.year.toString()
    else -> resources.getString(R.string.memorie_period_range, GalleryDates.date(days.from), GalleryDates.date(days.to))
  }

  /** Il sottotitolo: il periodo e i luoghi (al piu' tre), separati da un punto mediano. */
  fun subtitle(resources: Resources, title: MemoryTitle, placeNames: List<String>): String =
    (listOf(period(resources, title.days)) + placeNames.take(MemoryTitle.MAX_PLACES)).joinToString(" · ")

  /**
   * I1 (fase I): la riga piccola sotto il titolo di una scheda grande: il mese e l'anno ("agosto 2025"). Niente quando il titolo li dice gia' (il
   * meglio di un mese, un viaggio con il mese nel nome, una persona in un anno intero).
   */
  fun caption(kind: MemoryKind, title: MemoryTitle, titleText: String): String? {
    if (kind == MemoryKind.MONTH || (kind == MemoryKind.PERSON && isWholeYear(title.days))) return null
    val month = GalleryDates.monthYear(java.time.YearMonth.from(title.days.from)).lowercase(Locale.ROOT)
    return month.takeUnless { titleText.lowercase(Locale.ROOT).contains(it) }
  }

  /** La frase di una voce per TalkBack: "Nuovo. Un anno fa, 18 foto" o "Oslo, marzo 2025, 36 foto". Mai solo l'immagine. */
  fun spoken(resources: Resources, card: MemoryCard, titleText: String): String {
    val base = resources.getString(R.string.memorie_spoken_card, titleText, photos(resources, card.count))
    return if (card.isNew) resources.getString(R.string.memorie_spoken_new, base) else base
  }

  /** L'ora di una preferenza ("19:00"). */
  fun time(time: LocalTime): String = time.format(HOUR_FORMAT)

  private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
}
