package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.NotifyCandidate
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * I ricordi di prova delle notifiche (Fase 9, P10): date relative a un "oggi" scelto dal test, id sintetici, **un titolo, un luogo e una persona
 * riconoscibili** ([LABEL], [PLACE], [PERSON]) che nessun contenuto generico deve mai portare. Nessun dato personale.
 */
internal object NotifyFixtures {
  /** Il titolo di un viaggio sintetico: se si trova in una notifica generica, la notifica ha perso. */
  const val LABEL = "Viaggio Sintetico Zeta"

  /** Un luogo (id GeoNames) sintetico, distinguibile da ogni altro numero dei test. */
  const val PLACE = 7_654_321

  val PERSON: PersonId = PersonId.fromBytes(personBytes())

  // Senza lambda nell'inizializzatore dell'oggetto (un difetto del compilatore Kotlin fa fallire la verifica del bytecode).
  private fun personBytes(): ByteArray {
    val bytes = ByteArray(16)
    bytes.fill(9)
    return bytes
  }

  private var counter = 0L

  fun dayInt(date: LocalDate): Int = NotifyLedger.dayOf(date)

  fun memory(key: MemoryKey, today: LocalDate, yearsAgo: Int? = 1, members: Int = 10, publishedDay: Int = dayInt(today), places: List<Int> = listOf(PLACE)): Memory {
    counter++
    val ids = List(members) { MediaId(UUID(0x6000_0000L + counter, it.toLong()).toString()) }
    val person = (key as? MemoryKey.Person)?.person
    val label = if (key is MemoryKey.Trip || key is MemoryKey.Event) LABEL else null
    val month = (key as? MemoryKey.Month)?.month
    val title = MemoryTitle(yearsAgo, DayRange.of(today.minusYears(1)), places, label, person, month)
    return Memory(MemoryIds.of(key), key, title, ids, ids.first(), publishedDay)
  }

  /** "N anni fa oggi" (anni e giorno d'origine diversi per ogni [years]). */
  fun otd(today: LocalDate, years: Int = 1, members: Int = 10) = memory(MemoryKey.OnThisDay(today.minusYears(years.toLong())), today, yearsAgo = years, members = members)

  /** L'anniversario di un viaggio: inizio fra -3 e +3 giorni da oggi. */
  fun tripAnniversary(today: LocalDate, members: Int = 12) =
    memory(MemoryKey.Trip(DayRange(today.minusYears(1).minusDays(2), today.minusYears(1).plusDays(1))), today, yearsAgo = 1, members = members)

  /** La riscoperta di un viaggio vecchio: non e' un anniversario, non e' "speciale". */
  fun rediscovery(today: LocalDate, members: Int = 12) =
    memory(MemoryKey.Trip(DayRange(today.minusYears(4), today.minusYears(4).plusDays(5))), today, yearsAgo = null, members = members)

  fun month(today: LocalDate, members: Int = 20) = memory(MemoryKey.Month(YearMonth.from(today).minusMonths(1)), today, yearsAgo = null, members = members)

  fun person(today: LocalDate, members: Int = 20) = memory(MemoryKey.Person(PERSON, PersonWindow.Year(today.year - 1)), today, yearsAgo = null, members = members)

  fun candidate(m: Memory, seen: Boolean = false, dismissed: Boolean = false, visible: Int = m.members.size, less: Boolean = false) =
    NotifyCandidate(m, seen, dismissed, visible, less)

  fun ledgerOf(today: LocalDate, vararg daysAgo: Long, special: Boolean = false): NotifyLedger =
    NotifyLedger(daysAgo.map { NotifyMark(dayInt(today.minusDays(it)), special) }.sortedBy { it.day })
}
