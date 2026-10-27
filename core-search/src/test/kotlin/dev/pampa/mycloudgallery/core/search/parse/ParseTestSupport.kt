package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate

/** Il dizionario finto dei test: luoghi italiani e stranieri generici (nessun dato personale), omonimi, regioni e paesi. */
internal object TestPlaces {
  private fun entry(name: String, label: String, level: PlaceLevel, count: Int, vararg ids: Int) =
    PlaceEntry(name, PlaceOption(ids.toSet(), label, level), count)

  val DICTIONARY = PlaceDictionary(
    listOf(
      entry("firenze", "Firenze", PlaceLevel.PLACE, 50, 1),
      entry("pisa", "Pisa", PlaceLevel.PLACE, 7, 15),
      entry("lucca", "Lucca", PlaceLevel.PLACE, 4, 17),
      entry("roma", "Roma", PlaceLevel.PLACE, 30, 16),
      entry("monaco di baviera", "Monaco di Baviera", PlaceLevel.PLACE, 20, 2),
      entry("monaco", "Monaco", PlaceLevel.COUNTRY, 5, 3),
      entry("reggio emilia", "Reggio Emilia", PlaceLevel.PLACE, 15, 4),
      entry("reggio calabria", "Reggio Calabria", PlaceLevel.PLACE, 8, 5),
      entry("san marco", "San Marco (Toscana)", PlaceLevel.PLACE, 6, 10),
      entry("san marco", "San Marco (Veneto)", PlaceLevel.PLACE, 12, 11),
      entry("santa maria", "Santa Maria (Campania)", PlaceLevel.PLACE, 3, 12),
      entry("santa maria", "Santa Maria (Lazio)", PlaceLevel.PLACE, 9, 13),
      entry("santa maria", "Santa Maria (Puglia)", PlaceLevel.PLACE, 1, 14),
      entry("cortina d ampezzo", "Cortina d'Ampezzo", PlaceLevel.PLACE, 5, 18),
      entry("bianchi", "Bianchi", PlaceLevel.PLACE, 2, 19),
      entry("toscana", "Toscana", PlaceLevel.REGION, 80, 1, 10, 15, 17),
      entry("emilia romagna", "Emilia-Romagna", PlaceLevel.REGION, 30, 4),
      entry("baviera", "Baviera", PlaceLevel.REGION, 20, 2),
      entry("italia", "Italia", PlaceLevel.COUNTRY, 200, 1, 4, 5, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
      entry("germania", "Germania", PlaceLevel.COUNTRY, 20, 2),
    ),
  )
}

/** La riga di prova di un chip: la forma sta in `resources/parse/LEGGIMI.txt`. */
internal object QueryRenderer {
  fun chip(chip: SearchChip): String = when (chip) {
    is SearchChip.DateRange ->
      if (chip.everyYear) "Y ${md(chip.from)}..${md(chip.to)} ${chip.label}" else "D ${chip.from}..${chip.to} ${chip.label}"
    is SearchChip.DateSet -> chip.days.sorted().let { "E ${it.first()}..${it.last()} #${it.size} ${chip.label}" }
    is SearchChip.Place -> {
      val alt = if (chip.alternatives.isEmpty()) "" else " alt=" + chip.alternatives.joinToString(",") { "${it.label}#${ids(it.ids)}" }
      "P ${chip.option.label} #${ids(chip.option.ids)} ${chip.option.level} word=${chip.word}$alt"
    }
    is SearchChip.MediaType -> "T ${chip.type} ${chip.label}"
    is SearchChip.Source -> "S ${chip.source} ${chip.label}"
    is SearchChip.Flag -> "F ${chip.flag} ${chip.label}"
    is SearchChip.Presence -> "L ${chip.where} ${chip.label}"
    is SearchChip.Similar -> "M ${chip.media} ${chip.label}"
    is SearchChip.Word -> "W ${if (chip.exact) "exact" else "free"} ${chip.text}"
    // Fase 8 (P0): il chip "Persona" (D26). Nessuna query della tabella lo produce ancora: la forma la fissa P10 nel LEGGIMI.txt.
    is SearchChip.Person -> "N ${chip.option.label} #${chip.option.ids.size} ${chip.option.mode.code} word=${chip.word}"
  }

  private fun md(d: LocalDate): String = "%02d-%02d".format(d.monthValue, d.dayOfMonth)

  private fun ids(ids: Set<Int>): String = ids.sorted().joinToString("+")

  /** Le sei colonne di una riga: chip ; contenuto ; parole ; testo esatto ; correzioni. */
  fun columns(parsed: ParsedQuery): List<String> = listOf(
    parsed.chips.joinToString(" ; ") { chip(it) }.ifEmpty { "-" },
    parsed.semantic ?: "-",
    parsed.words.joinToString(", ").ifEmpty { "-" },
    parsed.exact.joinToString(" ; ").ifEmpty { "-" },
    parsed.corrections.joinToString(" ; ") { "${it.first}<-${it.second}" }.ifEmpty { "-" },
  )
}
