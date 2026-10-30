package dev.pampa.mycloudgallery.core.memories.plan.people

import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPriority
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.plan.PlanDates
import dev.pampa.mycloudgallery.core.memories.settings.MemoryQuiet
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate

/**
 * Il generatore dei ricordi con le persone (docs/fase9-progetto.md §2.1 "Persona", D3, D11, D12, E-MEM10, E-MEM11, Q9 (a), P8): un
 * [MemoryPlanner] che da' **solo** candidati di tipo [MemoryKind.PERSON], da innestare in `IndexedPlanner(people = PeoplePlanner())`. Puro e
 * deterministico: stessa Libreria, stesse scelte, stesso giorno = stesso candidato (e' lo stesso del riferimento a forza bruta
 * `ReferencePlanner`, su ogni ingresso: lo prova `PeoplePlannerParityTest`).
 *
 * **Chi entra.** Le persone arrivano da `PlanInput.people` (la porta `MemoryPeople`): solo persone **con un nome**, **non nascoste**, **non
 * dimenticate**, con gli alias gia' risolti e i soli media visibili (D12, E-H10); il generatore non vede mai un nome, solo l'id. In piu':
 *  - Persone disponibile qui e "Ricordi con le persone" acceso (Q9 (a)); altrimenti nessun ricordo;
 *  - al piu' una persona a settimana ([MemoryRules.PERSON_EVERY_DAYS]) e **una sola per giro**: la piu' fotografata, poi l'id;
 *  - una persona con "Mostra meno" o "Mai nei ricordi" non ha ricordi suoi (D11): `choices.people` e' guardato per l'id della porta;
 *  - almeno [MemoryRules.PERSON_MIN_PHOTOS] foto buone in almeno [MemoryRules.PERSON_MIN_DAYS] giorni diversi, dopo le esclusioni di D5 e i
 *    periodi "Mai" (D11, E-MEM12: `MemoryExclusions`, una sola definizione).
 *
 * **Quale finestra.** Il primo anno **passato** (dal piu' recente) con almeno [MemoryRules.PERSON_YEAR_MIN_PHOTOS] foto della persona la cui
 * chiave non sia "quieta" (`MemoryQuiet`: gia' sullo scaffale, scartata, vista negli ultimi 12 mesi); altrimenti "negli ultimi mesi" (gli ultimi
 * [MemoryRules.PERSON_RECENT_DAYS] giorni fino a ieri) con lo stesso minimo. Se la persona non ha finestre, si passa alla successiva.
 *
 * **Cosa non fa.** Non guarda i media delle altre persone nella foto (un "Mai" su una persona di gruppo lo applica la scelta delle foto, che ha le
 * persone di ogni media), non legge la Libreria al di fuori della tabella (i candidati sono posizioni della tabella: solo celle visibili, D31) e
 * non scrive niente. Il nome non e' mai un ingresso, un'uscita o un `toString()`.
 */
class PeoplePlanner : MemoryPlanner {

  override fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    if (!input.peopleAvailable || !input.choices.peopleMemories || input.people.isEmpty()) return emptyList()
    val since = PlanDates.daysSince(input.history.lastPublished[MemoryKind.PERSON], today)
    if (since != null && since < MemoryRules.PERSON_EVERY_DAYS) return emptyList()
    val table = input.cells
    val seen = HashSet<PersonId>()
    for (digest in ordered(input.people)) {
      if (!seen.add(digest.person)) continue
      if (input.choices.people[digest.person] != null) continue
      candidateFor(digest, input, table, today)?.let { return listOf(it) }
    }
    return emptyList()
  }

  /** La persona piu' fotografata per prima, poi l'id: l'ordine non dipende da quello della porta. */
  private fun ordered(people: List<PersonDigest>): List<PersonDigest> =
    people.sortedWith(compareByDescending<PersonDigest> { it.members.size }.thenBy { it.person })

  private fun candidateFor(digest: PersonDigest, input: PlanInput, table: CellTable, today: LocalDate): MemoryCandidate? {
    val cells = PlanDates.good(table, input.choices, positionsOf(digest, table), dateBound = false)
    if (cells.size < MemoryRules.PERSON_MIN_PHOTOS || daysOf(cells, table) < MemoryRules.PERSON_MIN_DAYS) return null
    for ((year, inYear) in byYear(cells, table, today.year)) {
      if (inYear.size < MemoryRules.PERSON_YEAR_MIN_PHOTOS) continue
      val key = MemoryKey.Person(digest.person, PersonWindow.Year(year))
      if (MemoryQuiet.isQuiet(MemoryIds.of(key), input.history, input.choices, today)) continue
      val days = DayRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
      return MemoryCandidate(key, MemoryPriority.PERSON, inYear, null, days, person = digest.person)
    }
    val window = DayRange(today.minusDays(MemoryRules.PERSON_RECENT_DAYS), today.minusDays(1))
    val recent = cells.filter { table.dateAt(it)!! in window }.toIntArray()
    if (recent.size < MemoryRules.PERSON_YEAR_MIN_PHOTOS) return null
    val key = MemoryKey.Person(digest.person, PersonWindow.Recent)
    if (MemoryQuiet.isQuiet(MemoryIds.of(key), input.history, input.choices, today)) return null
    return MemoryCandidate(key, MemoryPriority.PERSON, recent, null, window, person = digest.person)
  }

  /** Le posizioni nella tabella dei media della persona che ci sono (i visibili), senza doppioni, in ordine di posizione. */
  private fun positionsOf(digest: PersonDigest, table: CellTable): IntArray {
    val found = IntArray(digest.members.size)
    var count = 0
    for (id in digest.members) {
      val i = table.indexOf(id)
      if (i >= 0) found[count++] = i
    }
    val sorted = found.copyOf(count).also { it.sort() }
    var unique = 0
    for (k in sorted.indices) if (k == 0 || sorted[k] != sorted[k - 1]) sorted[unique++] = sorted[k]
    return sorted.copyOf(unique)
  }

  /** Quanti giorni diversi toccano le [cells] (hanno tutte una data: `MemoryExclusions` ha tolto le altre). */
  private fun daysOf(cells: IntArray, table: CellTable): Int {
    val days = HashSet<Int>()
    for (i in cells) days += table.dayAt(i)
    return days.size
  }

  /** Le celle per anno, dall'anno piu' recente, dei soli anni **passati** (l'anno in corso e' "negli ultimi mesi"). */
  private fun byYear(cells: IntArray, table: CellTable, thisYear: Int): List<Pair<Int, IntArray>> {
    val groups = HashMap<Int, MutableList<Int>>()
    for (i in cells) {
      val year = table.dayAt(i) / YEAR_DIVISOR
      if (year < thisYear) groups.getOrPut(year) { ArrayList() }.add(i)
    }
    return groups.keys.sortedDescending().map { it to groups.getValue(it).toIntArray() }
  }

  override fun toString(): String = "PeoplePlanner"

  private companion object {
    const val YEAR_DIVISOR = 10_000
  }
}
