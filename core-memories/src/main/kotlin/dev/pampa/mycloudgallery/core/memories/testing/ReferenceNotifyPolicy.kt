package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryNotifyDecisions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifyCandidate
import dev.pampa.mycloudgallery.core.memories.contract.NotifyContent
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.SlotDecision
import dev.pampa.mycloudgallery.core.memories.contract.TextRef
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Le decisioni delle notifiche di riferimento (Fase 9, P0, D15, D16, D20, Q4, Q5 (a)): le regole scritte una per una. `MemoryNotifyPolicy` di
 * P10 passa la stessa suite (`NotifyPolicyContract`).
 *
 * - **Orario** (D20): il prossimo H dopo adesso; all'esecuzione si consegna se l'ultimo H e' da al piu' 3 ore (in ritardo oltre H+1 h), se no
 *   si aspetta il prossimo e quel giorno si salta (E-NT3). Un cambio di fuso o d'ora legale si vede alla corsa dopo (E-NT4).
 * - **Scelta** (D15, Q4): mai due nello stesso giorno, mai se i Ricordi sono gia' stati aperti oggi; un ricordo pubblicato oggi, non visto, non
 *   scartato, con almeno 8 elementi visibili, mai una persona con "Mostra meno"; `FEW_PER_WEEK` = un anniversario sempre, gli altri al piu' 3 in
 *   7 giorni e mai il giorno dopo un'altra notifica; `ANNIVERSARIES` = solo anniversari; `DAILY` = qualunque.
 * - **Contenuto** (D16): generico senza niente del ricordo; con "Titolo e foto" il titolo come id di stringa (anni, luogo, titolo del viaggio,
 *   mese) e la copertina, **mai** un nome (un ricordo `PERSON` resta generico nel titolo); la versione pubblica sempre generica.
 */
class ReferenceNotifyPolicy : MemoryNotifyDecisions {

  override fun nextSlot(now: LocalDateTime, prefs: NotifyPrefs): LocalDateTime? {
    if (!prefs.enabled) return null
    val today = now.toLocalDate().atTime(prefs.hour)
    return if (now.isBefore(today)) today else today.plusDays(1)
  }

  override fun slot(now: LocalDateTime, prefs: NotifyPrefs): SlotDecision {
    val todaySlot = now.toLocalDate().atTime(prefs.hour)
    val last = if (now.isBefore(todaySlot)) todaySlot.minusDays(1) else todaySlot
    val minutes = Duration.between(last, now).toMinutes()
    val next = if (now.isBefore(todaySlot)) todaySlot else todaySlot.plusDays(1)
    if (!prefs.enabled) return SlotDecision.Wait(next, skipped = false)
    return if (minutes <= NotifyRules.SKIP_AFTER_MINUTES) {
      SlotDecision.Deliver(last.toLocalDate(), late = minutes > NotifyRules.LATE_AFTER_MINUTES)
    } else {
      SlotDecision.Wait(next, skipped = true)
    }
  }

  override fun choose(
    candidates: List<NotifyCandidate>,
    prefs: NotifyPrefs,
    ledger: NotifyLedger,
    today: LocalDate,
    openedToday: Boolean,
  ): MemoryId? {
    if (!prefs.enabled || openedToday || ledger.notifiedOn(today)) return null
    val todayDay = NotifyLedger.dayOf(today)
    val eligible = candidates.filter {
      it.memory.publishedDay == todayDay && !it.seen && !it.dismissed && it.visibleMembers >= MemoryRules.NOTIFY_MIN_MEMBERS &&
        !(it.memory.kind == MemoryKind.PERSON && it.lessPerson)
    }
    val special = eligible.filter { NotifyRules.isSpecial(it.memory) }
    val ordinary = eligible.filter { !NotifyRules.isSpecial(it.memory) }
    return when (prefs.frequency) {
      NotifyFrequency.DAILY -> best(eligible)
      NotifyFrequency.ANNIVERSARIES -> best(special)
      NotifyFrequency.FEW_PER_WEEK -> best(special) ?: if (ordinaryAllowed(ledger, today)) best(ordinary) else null
    }
  }

  /** Q4: un ricordo non speciale al piu' 3 volte in 7 giorni (contando tutte le notifiche) e mai il giorno dopo un'altra notifica. */
  private fun ordinaryAllowed(ledger: NotifyLedger, today: LocalDate): Boolean {
    if (ledger.notifiedOn(today.minusDays(1))) return false
    return ledger.countIn(today.minusDays(NotifyRules.WEEK_DAYS - 1), today.minusDays(1)) < NotifyRules.WEEKLY_CAP
  }

  /** La priorita' di D15: anniversario di viaggio o evento, "anni fa" (dal piu' recente), il mese, la persona, la riscoperta; poi l'id. */
  private fun best(candidates: List<NotifyCandidate>): MemoryId? = candidates.minWithOrNull(
    compareBy<NotifyCandidate> { rank(it.memory) }.thenBy { it.memory.title.yearsAgo ?: Int.MAX_VALUE }.thenBy { it.memory.id.value },
  )?.memory?.id

  private fun rank(memory: Memory): Int = when {
    NotifyRules.isSpecial(memory) -> 0
    memory.kind == MemoryKind.ON_THIS_DAY -> 1
    memory.kind == MemoryKind.MONTH -> 2
    memory.kind == MemoryKind.PERSON -> 3
    else -> 4
  }

  override fun content(memory: Memory, mode: LockScreenMode): NotifyContent {
    val genericTitle = TextRef(NotifyTexts.GENERIC_TITLE)
    val genericText = TextRef(NotifyTexts.GENERIC_TEXT)
    if (mode == LockScreenMode.GENERIC) return NotifyContent(genericTitle, genericText, null, genericTitle, genericText)
    val title = titleOf(memory) ?: genericTitle
    return NotifyContent(title, TextRef(NotifyTexts.TITLED_TEXT), memory.cover, genericTitle, genericText)
  }

  /** Il titolo di "Titolo e foto": mai un nome (un ricordo `PERSON` non ha titolo qui). */
  private fun titleOf(memory: Memory): TextRef? {
    val t = memory.title
    return when (memory.kind) {
      MemoryKind.PERSON -> null
      MemoryKind.MONTH -> t.month?.let { TextRef(NotifyTexts.TITLE_MONTH, listOf(it.year, it.monthValue)) }
      MemoryKind.TRIP, MemoryKind.EVENT -> t.tripLabel?.let { TextRef(NotifyTexts.TITLE_TRIP, listOfNotNull(t.yearsAgo), label = it) }
      MemoryKind.ON_THIS_DAY -> {
        val years = t.yearsAgo ?: return null
        val place = t.placeIds.firstOrNull()
        if (place != null) TextRef(NotifyTexts.TITLE_YEARS_PLACE, listOf(years, place)) else TextRef(NotifyTexts.TITLE_YEARS, listOf(years))
      }
    }
  }
}
