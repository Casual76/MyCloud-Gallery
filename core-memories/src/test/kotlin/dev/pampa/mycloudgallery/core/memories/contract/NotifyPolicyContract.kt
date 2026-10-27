package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.testing.ReferenceNotifyPolicy
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Random
import java.util.UUID
import org.junit.Test

/**
 * La suite di contratto delle decisioni delle notifiche (Fase 9, P0; `docs/fase9-progetto.md` D15, D16, D20, E-NT3-E-NT6, E-NT13; **risposte Q4
 * e Q5 (a) del 27/10**). Gira sul riferimento (`ReferenceNotifyPolicy`) e, da P10, su `MemoryNotifyPolicy`.
 */
abstract class NotifyPolicyContract {

  protected abstract fun policy(): MemoryNotifyDecisions

  private val today = LocalDate.of(2026, 11, 3)
  private val todayDay = NotifyLedger.dayOf(today)
  private var n = 0L

  private fun memory(key: MemoryKey, yearsAgo: Int? = 1, members: Int = 10, publishedDay: Int = todayDay, places: List<Int> = listOf(3_100_000)): Memory {
    n++
    val ids = List(members) { MediaId(UUID(n, it.toLong()).toString()) }
    val month = (key as? MemoryKey.Month)?.month
    val person = (key as? MemoryKey.Person)?.person
    val label = if (key is MemoryKey.Trip || key is MemoryKey.Event) "Viaggio sintetico" else null
    val title = MemoryTitle(yearsAgo, DayRange.of(LocalDate.of(2025, 11, 3)), places, label, person, month)
    return Memory(MemoryIds.of(key), key, title, ids, ids.first(), publishedDay)
  }

  private fun otd(year: Int) = memory(MemoryKey.OnThisDay(LocalDate.of(year, 11, 3)), yearsAgo = 2026 - year)
  private fun tripAnniversary() = memory(MemoryKey.Trip(DayRange(LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 4))))
  private fun rediscovery() = memory(MemoryKey.Trip(DayRange(LocalDate.of(2022, 5, 1), LocalDate.of(2022, 5, 9))), yearsAgo = null)
  private fun month() = memory(MemoryKey.Month(YearMonth.of(2026, 10)), yearsAgo = null)
  private fun personMemory() = memory(MemoryKey.Person(PersonId.fromBytes(ByteArray(16) { 2 }), PersonWindow.Year(2025)), yearsAgo = null)

  private fun candidate(m: Memory, seen: Boolean = false, dismissed: Boolean = false, visible: Int = m.members.size, less: Boolean = false) =
    NotifyCandidate(m, seen, dismissed, visible, less)

  private fun choose(
    candidates: List<NotifyCandidate>,
    prefs: NotifyPrefs = NotifyPrefs(),
    ledger: NotifyLedger = NotifyLedger(),
    day: LocalDate = today,
    opened: Boolean = false,
  ) = policy().choose(candidates, prefs, ledger, day, opened)

  private fun ledgerOf(vararg daysAgo: Long, special: Boolean = false) =
    NotifyLedger(daysAgo.map { NotifyMark(NotifyLedger.dayOf(today.minusDays(it)), special) })

  /** D20: il prossimo orario (di serie le 19:00); spente = nessun orario. */
  @Test
  fun ilProssimoOrario() {
    val prefs = NotifyPrefs()
    assertThat(prefs.hour).isEqualTo(LocalTime.of(19, 0))
    assertThat(policy().nextSlot(today.atTime(18, 0), prefs)).isEqualTo(today.atTime(19, 0))
    assertThat(policy().nextSlot(today.atTime(19, 30), prefs)).isEqualTo(today.plusDays(1).atTime(19, 0))
    assertThat(policy().nextSlot(today.atTime(18, 0), prefs.copy(enabled = false))).isNull()
  }

  /** D20, E-NT3: si consegna fino a H+3 h (in ritardo oltre H+1 h); oltre, quel giorno si salta; la finestra passa la mezzanotte. */
  @Test
  fun laFinestraDiConsegna() {
    val prefs = NotifyPrefs()
    assertThat(policy().slot(today.atTime(19, 30), prefs)).isEqualTo(SlotDecision.Deliver(today, late = false))
    assertThat(policy().slot(today.atTime(20, 30), prefs)).isEqualTo(SlotDecision.Deliver(today, late = true))
    assertThat(policy().slot(today.atTime(22, 0), prefs)).isEqualTo(SlotDecision.Deliver(today, late = true))
    assertThat(policy().slot(today.atTime(22, 1), prefs)).isEqualTo(SlotDecision.Wait(today.plusDays(1).atTime(19, 0), skipped = true))
    val late = NotifyPrefs(hour = LocalTime.of(23, 0))
    assertThat(policy().slot(today.atTime(0, 30), late)).isEqualTo(SlotDecision.Deliver(today.minusDays(1), late = true))
    assertThat(policy().slot(today.atTime(23, 40), late)).isEqualTo(SlotDecision.Deliver(today, late = false))
  }

  /** D15, E-NT6: solo un ricordo pubblicato oggi, non visto, non scartato, con almeno 8 elementi visibili; mai una persona "Mostra meno". */
  @Test
  fun qualeRicordoPuoEssereLaNotifica() {
    val m = otd(2025)
    assertThat(choose(listOf(candidate(m)))).isEqualTo(m.id)
    assertThat(choose(listOf(candidate(m, seen = true)))).isNull()
    assertThat(choose(listOf(candidate(m, dismissed = true)))).isNull()
    assertThat(choose(listOf(candidate(m, visible = 7)))).isNull()
    assertThat(choose(listOf(candidate(memory(MemoryKey.OnThisDay(LocalDate.of(2024, 11, 3)), publishedDay = todayDay - 1))))).isNull()
    val p = personMemory()
    assertThat(choose(listOf(candidate(p, less = true)), NotifyPrefs(frequency = NotifyFrequency.DAILY))).isNull()
    assertThat(choose(listOf(candidate(p)), NotifyPrefs(frequency = NotifyFrequency.DAILY))).isEqualTo(p.id)
    assertThat(choose(listOf(candidate(m)), opened = true)).isNull()
    assertThat(choose(listOf(candidate(m)), NotifyPrefs(enabled = false))).isNull()
  }

  /** D15: la priorita' (anniversario di un viaggio, "anni fa" dal piu' recente, il mese, la persona, la riscoperta). */
  @Test
  fun laPriorita() {
    val daily = NotifyPrefs(frequency = NotifyFrequency.DAILY)
    val trip = tripAnniversary()
    val recent = otd(2025)
    val old = otd(2020)
    val all = listOf(candidate(old), candidate(month()), candidate(personMemory()), candidate(rediscovery()), candidate(recent), candidate(trip))
    assertThat(choose(all, daily)).isEqualTo(trip.id)
    assertThat(choose(all.filter { it.memory != trip }, daily)).isEqualTo(recent.id)
    val m = month()
    assertThat(choose(listOf(candidate(personMemory()), candidate(m), candidate(rediscovery())), daily)).isEqualTo(m.id)
  }

  /** Q4: mai due nello stesso giorno, in nessuna frequenza. */
  @Test
  fun maiDueNelloStessoGiorno() {
    for (frequency in NotifyFrequency.entries) {
      val prefs = NotifyPrefs(frequency = frequency)
      assertWithMessage(frequency.code).that(choose(listOf(candidate(tripAnniversary())), prefs, ledgerOf(0))).isNull()
    }
  }

  /** Q4: di base al piu' 3 in 7 giorni e mai il giorno dopo un'altra; un anniversario arriva anche oltre il tetto. */
  @Test
  fun dueOTreASettimanaEGliAnniversariSempre() {
    val ordinary = listOf(candidate(otd(2025)))
    val special = listOf(candidate(tripAnniversary()))
    assertThat(choose(ordinary, ledger = ledgerOf(1))).isNull()
    assertThat(choose(ordinary, ledger = ledgerOf(2, 4, 6))).isNull()
    assertThat(choose(ordinary, ledger = ledgerOf(2, 4))).isNotNull()
    assertThat(choose(ordinary, ledger = ledgerOf(2, 4, 7))).isNotNull()
    assertThat(choose(special, ledger = ledgerOf(1, 2, 3, 4))).isNotNull()
    // Solo anniversari: un "anni fa" non arriva mai; ogni giorno: arriva anche il giorno dopo.
    assertThat(choose(ordinary, NotifyPrefs(frequency = NotifyFrequency.ANNIVERSARIES))).isNull()
    assertThat(choose(special, NotifyPrefs(frequency = NotifyFrequency.ANNIVERSARIES))).isNotNull()
    assertThat(choose(ordinary, NotifyPrefs(frequency = NotifyFrequency.DAILY), ledgerOf(1))).isNotNull()
  }

  /** Q4 come proprieta': 400 giorni a caso con la frequenza di serie. Mai due al giorno; un ricordo non speciale rispetta tetto e distanza. */
  @Test
  fun proprietaDellaFrequenzaDiSerie() {
    val random = Random(9)
    var ledger = NotifyLedger()
    val sent = ArrayList<Pair<LocalDate, Boolean>>()
    var day = today
    repeat(400) {
      val offered = ArrayList<NotifyCandidate>()
      val publishedDay = NotifyLedger.dayOf(day)
      if (random.nextInt(3) > 0) offered += candidate(memory(MemoryKey.OnThisDay(day.minusYears(1L + random.nextInt(5))), yearsAgo = 1, publishedDay = publishedDay))
      if (random.nextInt(10) == 0) offered += candidate(memory(MemoryKey.Trip(DayRange(day.minusYears(1), day.minusYears(1).plusDays(2))), publishedDay = publishedDay))
      val chosen = policy().choose(offered, NotifyPrefs(), ledger, day, openedToday = false)
      if (chosen != null) {
        val memory = offered.first { it.memory.id == chosen }.memory
        val special = NotifyRules.isSpecial(memory)
        if (!special) {
          assertThat(ledger.notifiedOn(day.minusDays(1))).isFalse()
          assertThat(ledger.countIn(day.minusDays(6), day.minusDays(1))).isLessThan(NotifyRules.WEEKLY_CAP)
        }
        assertThat(ledger.notifiedOn(day)).isFalse()
        ledger = ledger.with(NotifyMark(NotifyLedger.dayOf(day), special), day)
        sent += day to special
      }
      day = day.plusDays(1)
    }
    assertThat(sent.map { it.first }).containsNoDuplicates()
    assertThat(sent.size).isGreaterThan(100)
  }

  /** D16, Q5 (a), E-NT12: la notifica generica non ha niente del ricordo; la versione pubblica e' sempre generica. */
  @Test
  fun laNotificaGenericaNonDiceNiente() {
    for (m in listOf(otd(2025), tripAnniversary(), month(), personMemory(), rediscovery())) {
      val content = policy().content(m, LockScreenMode.GENERIC)
      assertThat(content.image).isNull()
      for (text in listOf(content.title, content.text, content.publicTitle, content.publicText)) {
        assertThat(text.key).isIn(NotifyTexts.GENERIC)
        assertThat(text.numbers).isEmpty()
        assertThat(text.label).isNull()
      }
    }
  }

  /** D16, E-NT13: "Titolo e foto" ha titolo e copertina, mai un nome (una persona resta generica nel titolo); pubblica generica. */
  @Test
  fun titoloEFotoMaiUnNome() {
    val o = otd(2025)
    val withTitle = policy().content(o, LockScreenMode.TITLE_AND_PHOTO)
    assertThat(withTitle.title).isEqualTo(TextRef(NotifyTexts.TITLE_YEARS_PLACE, listOf(1, 3_100_000)))
    assertThat(withTitle.image).isEqualTo(o.cover)
    assertThat(withTitle.publicTitle.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
    val trip = policy().content(tripAnniversary(), LockScreenMode.TITLE_AND_PHOTO)
    assertThat(trip.title.label).isEqualTo("Viaggio sintetico")
    val person = policy().content(personMemory(), LockScreenMode.TITLE_AND_PHOTO)
    assertThat(person.title.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
    assertThat(person.title.label).isNull()
  }

  /** Le preferenze: ora a passi di 15 minuti, di serie accese, 2-3 a settimana (Q4) e schermata di blocco generica (Q5 (a)). */
  @Test
  fun lePreferenzeDiSerie() {
    val prefs = NotifyPrefs()
    assertThat(prefs.enabled).isTrue()
    assertThat(prefs.frequency).isEqualTo(NotifyFrequency.FEW_PER_WEEK)
    assertThat(prefs.lockScreen).isEqualTo(LockScreenMode.GENERIC)
    assertThat(runCatching { NotifyPrefs(hour = LocalTime.of(19, 10)) }.isFailure).isTrue()
    assertThat(prefs.toString()).doesNotContain("19")
  }
}

/** La suite sul riferimento. */
class ReferenceNotifyPolicyContractTest : NotifyPolicyContract() {
  override fun policy(): MemoryNotifyDecisions = ReferenceNotifyPolicy()
}
