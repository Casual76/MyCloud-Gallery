package dev.pampa.mycloudgallery.memories

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * I conti della Diagnostica dei ricordi e delle notifiche **che sopravvivono al processo** (DG-1 e DG-2, 31/10). Il lavoro delle notifiche e la generazione notturna girano
 * in processi che muoiono subito dopo: contare in memoria voleva dire che, quando l'utente apriva la Diagnostica, la sezione non aveva quasi mai niente da dire sulle notifiche
 * saltate e sugli esclusi, e `delivered` (di questo processo) stava accanto a `late` (degli ultimi 14 giorni).
 *
 * **Solo numeri.** Un file (`memories/diag-counts`, in `noBackupFilesDir`, quindi tolto dall'azzeramento con tutta la cartella) con una riga per giorno e per codice:
 * `aaaammgg codice quanti`. Il codice e' una costante (`skip.permission`, `dropped.screenshot`, `delivered`...): mai un titolo, un luogo, una data di un ricordo, un id o un nome;
 * una riga con un codice che non e' fatto solo di lettere minuscole, cifre, `.` e `_` non si scrive e non si legge. Si tengono gli ultimi [NotifyRules.LEDGER_DAYS] giorni, gli
 * stessi di `notify-late` ([LateDeliveries]): il resto si butta a ogni scrittura. **Scrittura atomica** ([StateFiles]); un file illeggibile vale zero, una scrittura che non
 * riesce perde il numero (solo la Diagnostica ne risente) e non fa mai fallire chi conta. Senza file ([file] nullo) i conti stanno solo in memoria (le prove).
 */
class DayTally(private val file: File?, private val today: () -> LocalDate) {

  private class Row(val day: Int, val key: String, val count: Int)

  private var inMemory: List<Row> = emptyList()

  /** Aggiunge [n] a [key] per oggi. */
  fun add(key: String, n: Int = 1) = addAll(mapOf(key to n))

  /** Aggiunge i conti di [counts] per oggi, con una sola scrittura. Chi ha zero o meno non conta. */
  @Synchronized
  fun addAll(counts: Map<String, Int>) {
    val wanted = counts.filter { (key, n) -> n > 0 && validKey(key) }
    if (wanted.isEmpty()) return
    val now = dayOf(today())
    val rows = LinkedHashMap<Pair<Int, String>, Int>()
    for (row in load()) rows[row.day to row.key] = row.count
    for ((key, n) in wanted) rows.merge(now to key, n, Int::plus)
    save(rows.entries.map { (k, n) -> Row(k.first, k.second, n) })
  }

  /** Quanti conti di [key] negli ultimi [NotifyRules.LEDGER_DAYS] giorni. */
  @Synchronized
  fun count(key: String): Int = load().filter { it.key == key }.sumOf { it.count }

  /** I conti di ogni codice che comincia per [prefix], senza il prefisso, negli ultimi [NotifyRules.LEDGER_DAYS] giorni; solo quelli oltre zero. */
  @Synchronized
  fun counts(prefix: String): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    for (row in load()) if (row.key.startsWith(prefix)) out.merge(row.key.removePrefix(prefix), row.count, Int::plus)
    return out.filterValues { it > 0 }
  }

  private fun oldest(): Int = dayOf(today().minusDays(NotifyRules.LEDGER_DAYS - 1L))

  private fun load(): List<Row> {
    val oldest = oldest()
    val rows = if (file == null) inMemory else readFile(file)
    return rows.filter { it.day >= oldest }
  }

  private fun readFile(file: File): List<Row> = try {
    StateFiles.readLines(file, maxBytes = MAX_BYTES).orEmpty().mapNotNull(::parse)
  } catch (e: IOException) {
    emptyList()
  }

  private fun save(rows: List<Row>) {
    val kept = rows.filter { it.day >= oldest() }
    if (file == null) {
      inMemory = kept
      return
    }
    try {
      StateFiles.writeText(file, kept.joinToString("\n") { "${it.day} ${it.key} ${it.count}" })
    } catch (e: IOException) {
      // Un conto perso: solo la Diagnostica ne risente (l'errore e' nel log di `StateFiles`, senza percorso).
    }
  }

  private fun parse(line: String): Row? {
    val parts = line.trim().split(' ')
    if (parts.size != 3) return null
    val day = parts[0].takeIf { it.length == 8 }?.toIntOrNull() ?: return null
    val count = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return if (validKey(parts[1])) Row(day, parts[1], count) else null
  }

  companion object {
    const val FILE: String = "diag-counts"

    /** Un file di conti non e' mai grande: oltre non si legge (un file rovinato o gonfiato non pesa sulla Diagnostica). */
    private const val MAX_BYTES: Long = 64 * 1024

    fun dayOf(day: LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth

    /** Il codice di un conto: solo lettere minuscole, cifre, `.` e `_` (mai un dato dell'utente). */
    fun validKey(key: String): Boolean = key.isNotEmpty() && key.length <= 64 && key.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
  }
}
