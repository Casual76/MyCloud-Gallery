package dev.pampa.mycloudgallery.core.memories.notify

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
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Le decisioni delle notifiche dei ricordi, quelle vere (Fase 9, P10, docs/fase9-progetto.md §2.6, D15, D16, D20; risposte Q4, Q5 (a), Q9 del
 * 27/10): **pure**, senza orologio (l'ora arriva dal chiamante, dalla porta `MemoryClock`), senza Android e senza stato. La suite di contratto
 * `NotifyPolicyContract` di P0 gira anche su questa classe e il riferimento (`ReferenceNotifyPolicy`) le da' le stesse risposte
 * (`MemoryNotifyPolicyParityTest`).
 *
 * - **Orario** (D20, E-NT3, E-NT4): il prossimo H dopo adesso; all'esecuzione si consegna se l'ultimo H e' da al piu' 3 ore (in ritardo oltre
 *   H+1 h), altrimenti quel giorno si salta. Si ragiona in ora **locale** (`LocalDateTime`): un cambio di fuso o d'ora legale si vede alla corsa
 *   dopo, e [delayUntil] porta l'ora locale al ritardo vero (con le lacune e le ore doppie dell'ora legale).
 * - **Scelta** (D15, Q4): mai due nello stesso giorno, mai se i Ricordi sono gia' stati aperti oggi; solo un ricordo pubblicato oggi, non visto,
 *   non scartato, con almeno 8 elementi visibili, mai `PERSON` con "Mostra meno"; con `FEW_PER_WEEK` un anniversario sempre e gli altri al piu'
 *   3 in 7 giorni e mai il giorno dopo un'altra notifica; `ANNIVERSARIES` solo anniversari; `DAILY` qualunque.
 * - **Contenuto** (D16, Q5 (a), Q9): la modalita' generica non porta **niente** del ricordo; "Titolo e foto" ha il titolo come id di stringa e
 *   la copertina, **mai un nome** (un ricordo `PERSON` ha il titolo generico); la versione pubblica e' sempre generica.
 */
class MemoryNotifyPolicy : MemoryNotifyDecisions {

  override fun nextSlot(now: LocalDateTime, prefs: NotifyPrefs): LocalDateTime? {
    if (!prefs.enabled) return null
    val today = now.toLocalDate().atTime(prefs.hour)
    return if (now.isBefore(today)) today else today.plusDays(1)
  }

  override fun slot(now: LocalDateTime, prefs: NotifyPrefs): SlotDecision {
    val todaySlot = now.toLocalDate().atTime(prefs.hour)
    val upcoming = if (now.isBefore(todaySlot)) todaySlot else todaySlot.plusDays(1)
    if (!prefs.enabled) return SlotDecision.Wait(upcoming, skipped = false)
    // Qualche minuto in anticipo (un ritardo arrotondato, un orologio rimesso a posto): non e' un giorno saltato, si riprova fra poco.
    if (now.isBefore(todaySlot) && Duration.between(now, todaySlot).toMinutes() < EARLY_TOLERANCE_MINUTES) return SlotDecision.Wait(todaySlot, skipped = false)
    val last = if (now.isBefore(todaySlot)) todaySlot.minusDays(1) else todaySlot
    val minutes = Duration.between(last, now).toMinutes()
    return if (minutes <= NotifyRules.SKIP_AFTER_MINUTES) {
      SlotDecision.Deliver(last.toLocalDate(), late = minutes > NotifyRules.LATE_AFTER_MINUTES)
    } else {
      SlotDecision.Wait(upcoming, skipped = true)
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
    val eligible = candidates.filter { isEligible(it, todayDay) }
    val special = eligible.filter { NotifyRules.isSpecial(it.memory) }
    return when (prefs.frequency) {
      NotifyFrequency.DAILY -> best(eligible)
      NotifyFrequency.ANNIVERSARIES -> best(special)
      NotifyFrequency.FEW_PER_WEEK -> best(special) ?: if (ordinaryAllowed(ledger, today)) best(eligible.filter { !NotifyRules.isSpecial(it.memory) }) else null
    }
  }

  /** Pubblicato oggi, non visto, non scartato, abbastanza elementi visibili, mai una persona con "Mostra meno" o "Mai" (D15, D11). */
  private fun isEligible(c: NotifyCandidate, todayDay: Int): Boolean =
    c.memory.publishedDay == todayDay && !c.seen && !c.dismissed && c.visibleMembers >= MemoryRules.NOTIFY_MIN_MEMBERS &&
      !(c.memory.kind == MemoryKind.PERSON && c.lessPerson)

  /** Q4: un ricordo non speciale al piu' [NotifyRules.WEEKLY_CAP] volte in 7 giorni (contando tutte le notifiche) e mai il giorno dopo un'altra. */
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
    // La versione pubblica e la modalita' generica sono le stesse parole, fisse: niente del ricordo puo' arrivarci.
    if (mode == LockScreenMode.GENERIC) return NotifyContent(genericTitle, genericText, null, genericTitle, genericText)
    val title = titleOf(memory) ?: genericTitle
    return NotifyContent(title, TextRef(NotifyTexts.TITLED_TEXT), memory.cover, genericTitle, genericText)
  }

  /**
   * Il titolo di "Titolo e foto" (D16): anni, luogo, titolo del viaggio della Fase 7, mese. **Mai un nome**: un ricordo `PERSON` non ha titolo
   * (resta "Un ricordo per te") e una persona non entra in nessun argomento.
   */
  private fun titleOf(memory: Memory): TextRef? {
    val t = memory.title
    if (t.person != null) return null
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

  companion object {
    /** Sotto questi minuti prima dell'orario, una corsa e' "in anticipo", non un giorno saltato. */
    const val EARLY_TOLERANCE_MINUTES: Long = 5

    /**
     * Il ritardo **vero** da [now] all'orario [next], tutti e due in ora locale, nel fuso [zone] (E-NT4): con l'ora legale un'ora locale puo' non
     * esistere (la lacuna di primavera: vale l'ora dopo) o esistere due volte (l'ora doppia d'autunno: vale la prima). Mai negativo.
     */
    fun delayUntil(now: LocalDateTime, next: LocalDateTime, zone: ZoneId): Duration {
      val delay = Duration.between(ZonedDateTime.of(now, zone), ZonedDateTime.of(next, zone))
      return if (delay.isNegative) Duration.ZERO else delay
    }
  }
}
