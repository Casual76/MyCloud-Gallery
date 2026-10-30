package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.notify.MemoriesOpened
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyAccess
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.notify.NotifyEvents
import dev.pampa.mycloudgallery.core.memories.notify.NotifyNotice
import dev.pampa.mycloudgallery.core.memories.notify.NotifyPublisher
import dev.pampa.mycloudgallery.core.memories.notify.NotifyScheduler
import dev.pampa.mycloudgallery.core.memories.notify.PostedNotice
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake delle porte delle notifiche (Fase 9, P10, §5.2): per la suite del giorno di una notifica, per la revoca e per P9 e P13 (la pagina delle
// impostazioni legge `FakeNotifyAccess.state`). Fedeli alla regola delle porte: **niente testo, titolo o nome**, solo id di ricordo, giorni, modalita'
// e conteggi; nessuna richiesta al sistema ([FakeNotifyAccess] conta solo le riletture).

/** Il permesso e il canale scelti dal test: [refresh] rilegge [system], come la porta vera rilegge Android (mai una richiesta). */
class FakeNotifyAccess(initial: NotifyAccessState = NotifyAccessState.GRANTED) : MemoryNotifyAccess {
  private val flow = MutableStateFlow(initial)

  /** Cio' che "il sistema" dice adesso: il test lo cambia (l'utente toglie il permesso, spegne il canale). */
  var system: NotifyAccessState = initial

  var refreshes: Int = 0
    private set

  override val state: StateFlow<NotifyAccessState> = flow

  override fun refresh() {
    refreshes++
    flow.value = system
  }
}

/**
 * La notifica finta (canale `memories`, id 1009): [published] sono le notifiche **partite**, nell'ordine; [current] quella ancora in tendina.
 * [dieAtNext] simula la morte del processo **dopo** il registro e **prima** della notifica (un `Error`, che il lavoro non intercetta).
 */
class FakeNotifyPublisher : NotifyPublisher {
  val published: MutableList<NotifyNotice> = ArrayList()
  var current: NotifyNotice? = null
    private set
  var cancels: Int = 0
    private set

  /** Se vero, la prossima [publish] muore (come il processo fra il registro e la notifica). */
  var dieAtNext: Boolean = false

  /** Se vero, il sistema rifiuta la notifica (permesso tolto un attimo fa). */
  var refuse: Boolean = false

  /** Quante notifiche sono partite nel giorno [day] (`aaaammgg`). */
  fun publishedOn(day: Int): Int = published.count { it.day == day }

  override suspend fun publish(notice: NotifyNotice): Boolean {
    if (dieAtNext) {
      dieAtNext = false
      throw ProcessDied()
    }
    if (refuse) return false
    published += notice
    current = notice
    return true
  }

  override fun cancel() {
    cancels++
    current = null
  }

  override suspend fun posted(): PostedNotice? = current?.let { PostedNotice(it.memory, it.day, it.mode, it.content.image) }

  /** Il ricordo della notifica ancora in tendina, per le prove. */
  fun currentMemory(): MemoryId? = current?.memory

  fun currentMode(): LockScreenMode? = current?.mode
}

/** La morte del processo in un punto preciso: un `Error`, non un'eccezione, cosi' nessun `catch (Exception)` del lavoro la nasconde. */
class ProcessDied : Error("processo morto (simulato)")

/** Il lavoro unico finto: l'ultimo orario chiesto (in ora locale) e le volte che e' stato tolto. */
class RecordingNotifyScheduler : NotifyScheduler {
  var lastNow: LocalDateTime? = null
    private set
  var lastNext: LocalDateTime? = null
    private set
  var scheduled: Int = 0
    private set
  var cancelled: Int = 0
    private set

  override fun scheduleAt(now: LocalDateTime, next: LocalDateTime) {
    scheduled++
    lastNow = now
    lastNext = next
  }

  override fun cancel() {
    cancelled++
    lastNext = null
  }
}

/** Gli eventi della Diagnostica registrati: solo codici e numeri. */
class RecordingNotifyEvents : NotifyEvents {
  val skips: MutableList<NotifySkip> = ArrayList()
  var delivered: Int = 0
    private set
  var late: Int = 0
    private set
  var revoked: Int = 0
    private set

  override fun skipped(reason: NotifySkip) {
    skips += reason
  }

  override fun delivered(late: Boolean) {
    delivered++
    if (late) this.late++
  }

  override fun revoked() {
    revoked++
  }
}

/** "Aperti oggi" deciso dal test: i giorni in cui i Ricordi sono stati aperti. */
class FakeMemoriesOpened(val days: MutableSet<LocalDate> = HashSet()) : MemoriesOpened {
  override suspend fun openedOn(day: LocalDate): Boolean = day in days
}
