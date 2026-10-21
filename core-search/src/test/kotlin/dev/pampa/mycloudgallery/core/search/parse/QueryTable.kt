package dev.pampa.mycloudgallery.core.search.parse

import java.time.LocalDate

/** Una riga della tabella pubblica: la query, "oggi" e le cinque colonne attese (chip, contenuto, parole, testo esatto, correzioni). */
internal data class QueryRow(val file: String, val line: Int, val today: LocalDate, val query: String, val expected: List<String>) {
  override fun toString(): String = "$file:$line «${query.take(60)}»"
}

/**
 * La tabella pubblica delle query italiane (P7): i file `resources/parse/query-*.txt`. Una riga = `query => chip => contenuto =>
 * parole => testo esatto => correzioni`; "-" = vuoto; `<vuota>` e `<spazio>` sono query vuote; `#` apre un commento;
 * `@oggi aaaa-mm-gg` cambia la data di oggi per le righe dopo (di partenza 2026-10-21, come `FixedToday`).
 */
internal object QueryTable {
  val FILES = listOf(
    "query-relativi.txt", "query-date.txt", "query-stagioni.txt", "query-feste.txt", "query-luoghi.txt", "query-tipi.txt",
    "query-refusi.txt", "query-limiti.txt",
  )

  val DEFAULT_TODAY: LocalDate = LocalDate.of(2026, 10, 21)

  fun load(): List<QueryRow> = FILES.flatMap { load(it) }

  fun load(name: String): List<QueryRow> {
    val stream = QueryTable::class.java.getResourceAsStream("/parse/$name") ?: error("Manca la tabella parse/$name")
    var today = DEFAULT_TODAY
    val rows = ArrayList<QueryRow>()
    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
      lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        when {
          line.isEmpty() || line.startsWith("#") -> Unit
          line.startsWith("@oggi ") -> today = LocalDate.parse(line.removePrefix("@oggi ").trim())
          else -> {
            val parts = line.split(" => ").map { it.trim() }
            val query = when (parts[0]) {
              "<vuota>" -> ""
              "<spazio>" -> " "
              else -> parts[0]
            }
            val expected = (1..5).map { parts.getOrNull(it) ?: "-" }
            rows += QueryRow(name, index + 1, today, query, expected)
          }
        }
      }
    }
    return rows
  }
}
