package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.Suggestion

/**
 * I completamenti del calendario (§2.6: "completamenti di luoghi, mesi, feste, tipi e ricerche recenti"): i mesi e le feste che l'analizzatore
 * italiano delle query capisce (D26). Sono un elenco **fisso** scritto qui: **mai parole del testo letto** (D29: il vocabolario del testo contiene
 * anche quelle dei nascosti, e non entra mai fra i suggerimenti).
 */
object CalendarCompletions {

  val MONTHS: List<String> = listOf(
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno", "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
  )

  val HOLIDAYS: List<String> = listOf(
    "Capodanno", "Epifania", "San Valentino", "Pasqua", "Pasquetta", "Ferragosto", "Halloween", "Natale",
  )

  /** I mesi e le feste che cominciano con [prefix] (maiuscole e accenti non contano), al massimo [limit]. */
  fun of(prefix: String, limit: Int = 4): List<Suggestion> {
    val p = SearchText.normalize(prefix)
    if (p.length < MIN_PREFIX) return emptyList()
    return (MONTHS + HOLIDAYS).filter { SearchText.normalize(it).startsWith(p) }.take(limit).map { Suggestion.Term(it) }
  }

  private const val MIN_PREFIX = 2
}
