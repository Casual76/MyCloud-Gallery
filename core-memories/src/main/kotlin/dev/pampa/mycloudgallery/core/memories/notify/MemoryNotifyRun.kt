package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryNotifyDecisions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.NotifyCandidate
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.contract.RefreshBudgets
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.SlotDecision
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.IOException
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Com'e' andata una corsa del lavoro delle notifiche (solo codici e booleani: D33). */
sealed interface NotifyOutcome {
  /** Una notifica e' partita ([special]: era un anniversario; [late]: oltre H+1 h). */
  data class Posted(val special: Boolean, val late: Boolean) : NotifyOutcome

  /** Nessuna notifica, per questo motivo (la Diagnostica lo conta). */
  data class Skipped(val reason: NotifySkip) : NotifyOutcome

  /** Il lavoro e' partito qualche minuto prima dell'orario: si riprova all'orario, senza dire che e' un giorno saltato. */
  data object NotYet : NotifyOutcome
}

/**
 * **Il giorno di una notifica** (docs/fase9-progetto.md §2.6, D15, D19, D20; E-NT1-E-NT7, E-NT14, E-NT15): quello che fa `MemoryNotifyWorker` di
 * :app a ogni corsa, tutto qui in `core-memories` perche' si provi sulla JVM con il tempo virtuale. Pianificazione, partenza, condizioni, passo
 * leggero, scelta, ricontrollo dei nascosti, registro e pubblicazione, nell'ordine della tabella del §2.6:
 *
 * 1. **Finestra**: da H a H+3 h; oltre, quel giorno si salta (E-NT3).
 * 2. **Condizioni**: notifiche accese qui, permesso concesso e canale acceso (`MemoryNotifyAccess`, **mai una richiesta**: E-NT1, E-NT2),
 *    nessuna notifica gia' oggi (registro), i Ricordi non aperti oggi. Altrimenti nulla e un codice.
 * 3. **Passo leggero**: `refresh(LIGHT, 5 s)` se la notte non ha generato i ricordi di oggi (D4).
 * 4. **Scelta** (`MemoryNotifyDecisions.choose`) fra i ricordi **sullo scaffale** pubblicati oggi, con la copertina e gli elementi **ricontrollati
 *    contro i nascosti** (`MemoryVisibility`, D19, D31): un guasto della lettura non fa uscire niente (R17).
 * 5. **Registro prima della notifica** (E-NT5): il giorno si scrive **prima** di pubblicare, e tutto sotto un lucchetto solo. Se il processo muore
 *    fra le due cose quel giorno resta senza notifica, mai con due; due corse nello stesso giorno ne fanno al piu' una.
 * 6. **Giorno** = il giorno locale di adesso (anche per una consegna dopo mezzanotte, con H tardi): al piu' una notifica per giorno **di
 *    calendario**, in qualunque frequenza.
 *
 * La corsa **si ripianifica sempre** (D20: a ogni esecuzione, a ogni cambio di impostazioni, a ogni avvio): [replan] e [watch]. Nessun orologio
 * di sistema: l'ora arriva da [clock] (`MemoryClock`, legata da :app a `Today`), il tempo del passo leggero dal budget del motore.
 */
class MemoryNotifyRun(
  private val policy: MemoryNotifyDecisions,
  private val settings: NotifySettings,
  private val ledger: NotifyLedgerStore,
  private val memories: Memories,
  private val visibility: MemoryVisibility,
  private val opened: MemoriesOpened,
  private val access: MemoryNotifyAccess,
  private val publisher: NotifyPublisher,
  private val clock: MemoryClock,
  private val scheduler: NotifyScheduler,
  private val events: NotifyEvents = NoNotifyEvents,
) {
  private val lock = Mutex()

  /** Una corsa del lavoro: decide, eventualmente pubblica, e rifa' sempre il lavoro unico per il prossimo orario. */
  suspend fun run(): NotifyOutcome {
    try {
      val outcome = lock.withLock { runLocked() }
      if (outcome is NotifyOutcome.Skipped) events.skipped(outcome.reason)
      return outcome
    } finally {
      replan()
    }
  }

  /** Rifa' il lavoro unico `memories-notify` per il prossimo orario (D20); con le notifiche spente lo toglie. */
  fun replan() {
    val prefs = settings.prefs.value
    val now = clock.nowLocal()
    val next = policy.nextSlot(now, prefs)
    if (next == null) scheduler.cancel() else scheduler.scheduleAt(now, next)
  }

  /**
   * Ripianifica **adesso** e a ogni cambio delle impostazioni (D20); con le notifiche spente toglie anche la notifica in tendina (l'utente le ha
   * spente). P13 lo chiama all'avvio dell'app: cosi' il lavoro e' rifatto a ogni avvio.
   */
  fun watch(scope: CoroutineScope): Job = scope.launch {
    settings.prefs.collect { prefs ->
      if (!prefs.enabled) publisher.cancel()
      replan()
    }
  }

  private suspend fun runLocked(): NotifyOutcome {
    val prefs = settings.prefs.value
    if (!prefs.enabled) return NotifyOutcome.Skipped(NotifySkip.DISABLED)
    val now = clock.nowLocal()
    val today = now.toLocalDate()
    val late = when (val slot = policy.slot(now, prefs)) {
      is SlotDecision.Deliver -> slot.late
      is SlotDecision.Wait -> return if (slot.skipped) NotifyOutcome.Skipped(NotifySkip.OUTSIDE_WINDOW) else NotifyOutcome.NotYet
    }
    access.refresh()
    when (access.state.value) {
      NotifyAccessState.GRANTED -> Unit
      NotifyAccessState.CHANNEL_OFF -> return NotifyOutcome.Skipped(NotifySkip.CHANNEL)
      NotifyAccessState.PERMISSION_MISSING, NotifyAccessState.APP_OFF -> return NotifyOutcome.Skipped(NotifySkip.PERMISSION)
    }
    val marks = readLedger() ?: return NotifyOutcome.Skipped(NotifySkip.ALREADY_TODAY)
    if (marks.notifiedOn(today)) return NotifyOutcome.Skipped(NotifySkip.ALREADY_TODAY)
    try {
      if (opened.openedOn(today)) return NotifyOutcome.Skipped(NotifySkip.ALREADY_OPENED)
      lightPass(today)
      return choseAndPost(prefs.lockScreen, marks, today, late)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un guasto (lettura dei visibili, scaffale) non fa uscire niente e non lascia traccia nel log: solo il tipo (R17).
      return NotifyOutcome.Skipped(NotifySkip.NOTHING)
    }
  }

  /**
   * Il registro; **null se non si legge o e' rovinato**: allora non si puo' provare "oggi niente" e quel giorno non si notifica. Il file rovinato
   * si rimette a posto (vuoto), cosi' domani si riparte.
   */
  private suspend fun readLedger(): NotifyLedger? = try {
    ledger.read()
  } catch (e: CorruptLedgerException) {
    try {
      ledger.clear()
    } catch (_: IOException) {
      // Domani si riprova a leggerlo e a rimetterlo a posto.
    }
    null
  } catch (_: IOException) {
    null
  }

  /** D4: i ricordi di oggi li prepara la notte; se non c'e' stata, un passo leggero da 5 secondi. Un guasto del passo lascia lo scaffale com'e'. */
  private suspend fun lightPass(today: LocalDate) {
    val status = memories.status.value
    if (status is MemoriesStatus.Idle && status.lastDay == NotifyLedger.dayOf(today)) return
    try {
      memories.refresh(RefreshReason.LIGHT, RefreshBudgets.LIGHT_SECONDS.seconds)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Quello che c'e' sullo scaffale resta valido (il motore non lo butta per un guasto).
    }
  }

  private suspend fun choseAndPost(mode: LockScreenMode, marks: NotifyLedger, today: LocalDate, late: Boolean): NotifyOutcome {
    val prefs = settings.prefs.value
    var candidates = candidatesOf(today, mode)
    repeat(MAX_ROUNDS) {
      val chosen = policy.choose(candidates, prefs, marks, today, openedToday = false)
      if (chosen == null) {
        // Troppo presto per la frequenza (Q4) o proprio niente di bello oggi (E-NT6)?
        val withoutLedger = policy.choose(candidates, prefs, NotifyLedger(), today, openedToday = false)
        return NotifyOutcome.Skipped(if (withoutLedger != null) NotifySkip.FREQUENCY else NotifySkip.NOTHING)
      }
      val pick = candidates.first { it.memory.id == chosen }
      // Il ricontrollo di D19 subito prima di pubblicare: lo scaffale riletto e i nascosti di adesso.
      val ready = recheck(chosen, mode)
      if (ready == null) {
        candidates = candidates.filter { it.memory.id != chosen }
        return@repeat
      }
      val special = NotifyRules.isSpecial(pick.memory)
      val notice = NotifyNotice(chosen, NotifyLedger.dayOf(today), mode, policy.content(ready, mode))
      // **Prima** il registro (E-NT5): se qui il processo muore, quel giorno non c'e' notifica; se va storto la scrittura, non parte niente.
      ledger.record(NotifyMark(notice.day, special), today)
      if (!publisher.publish(notice)) return NotifyOutcome.Skipped(NotifySkip.PERMISSION)
      events.delivered(late)
      return NotifyOutcome.Posted(special, late)
    }
    return NotifyOutcome.Skipped(NotifySkip.NOTHING)
  }

  /**
   * I ricordi di oggi **sullo scaffale**, pubblicati oggi, con gli elementi visibili contati dopo il filtro dei nascosti. In "Titolo e foto" un
   * ricordo con la copertina nascosta non e' un candidato (D19). Un ricordo che non c'e' piu' (scartato, uscito, sotto 3 elementi) non c'e'.
   */
  private suspend fun candidatesOf(today: LocalDate, mode: LockScreenMode): List<NotifyCandidate> {
    val todayDay = NotifyLedger.dayOf(today)
    val lessPeople = memories.lessChoices.value.mapNotNull { (it.target as? LessTarget.Person)?.person }.toSet()
    val out = ArrayList<NotifyCandidate>()
    for (card in memories.shelf.value.items) {
      if (card.publishedDay != todayDay) continue
      val view = memories.memory(card.id).first() ?: continue
      val showable = showableOf(view, mode) ?: continue
      val person = view.memory.title.person
      out += NotifyCandidate(view.memory, view.seen, dismissed = false, visibleMembers = showable.size, lessPerson = person != null && person in lessPeople)
    }
    return out
  }

  /** Gli elementi del ricordo che si possono mostrare **adesso** (mai un nascosto), o null se la copertina di "Titolo e foto" non c'e'. */
  private suspend fun showableOf(view: MemoryView, mode: LockScreenMode): List<MediaId>? {
    val hidden = visibility.hiddenOrThrow(view.memory.members)
    val showable = view.memory.members.filter { it !in hidden }
    if (mode == LockScreenMode.TITLE_AND_PHOTO && view.cover in hidden) return null
    return showable
  }

  /**
   * Il ricordo [id] com'e' adesso, riletto dallo scaffale, con la copertina che si puo' mostrare e almeno [MemoryRules.NOTIFY_MIN_MEMBERS]
   * elementi visibili; null se non regge piu'.
   */
  private suspend fun recheck(id: MemoryId, mode: LockScreenMode): Memory? {
    val view = memories.memory(id).first() ?: return null
    val showable = showableOf(view, mode) ?: return null
    if (showable.size < MemoryRules.NOTIFY_MIN_MEMBERS) return null
    val cover = if (view.cover in showable) view.cover else showable.first()
    return view.memory.copy(cover = cover)
  }

  private companion object {
    /** Quante volte si ripesca se la scelta non regge al ricontrollo (un ricordo alla volta): un tetto per non girare a vuoto. */
    const val MAX_ROUNDS = 6
  }
}
