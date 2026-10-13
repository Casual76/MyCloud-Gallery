package dev.pampa.mycloudgallery.core.library.text

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **Il solo punto** in cui la galleria scrive date e ore per le persone (P11): griglia, intestazioni, scrubber, viewer,
 * pannello informazioni, Raccolte. L'app e' solo in italiano (piano §4 "Lingua"; `localeFilters = ['it']`), quindi le
 * date si scrivono **sempre** con [LOCALE], anche se il telefono e' in inglese: prima viewer e griglia seguivano la lingua
 * del dispositivo e mescolavano "March" con "Oggi".
 *
 * I formattatori si creano una volta sola (sono immutabili e si usano da piu' thread). Solo `java.time`: niente Android,
 * si prova su JVM.
 */
object GalleryDates {

  /** La lingua di tutte le date della galleria. */
  val LOCALE: Locale = Locale.ITALIAN

  private val MonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", LOCALE)
  private val WeekdayDayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", LOCALE)
  private val WeekdayDayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", LOCALE)
  private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", LOCALE)
  private val Time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", LOCALE)

  /** "Marzo 2024": l'intestazione dei mesi, lo scrubber, la barra al livello "Tutte". */
  fun monthYear(month: YearMonth): String = month.format(MonthYear).capitalized()

  /** "Martedì 12 marzo" (o con l'anno, se non e' quello in corso): l'intestazione dei giorni. */
  fun weekdayTitle(day: LocalDate, withYear: Boolean): String =
    day.format(if (withYear) WeekdayDayMonthYear else WeekdayDayMonth).capitalized()

  /** "12 marzo 2024": la data di una cella per il lettore di schermo, il titolo del viewer. */
  fun date(day: LocalDate): String = day.format(DayMonthYear)

  /** Come [date], dall'ora locale dello scatto. */
  fun date(time: LocalDateTime): String = time.format(DayMonthYear)

  /** "martedì 12 marzo 2024": la riga della data nel pannello informazioni. */
  fun weekdayDate(time: LocalDateTime): String = time.format(WeekdayDayMonthYear)

  /** "12:41": sempre 24 ore. */
  fun time(time: LocalDateTime): String = time.format(Time)

  /** La prima lettera maiuscola (nella lingua della galleria). */
  fun String.capitalized(): String = replaceFirstChar { it.titlecase(LOCALE) }
}
