package dev.pampa.mycloudgallery.core.widget.testing

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 10 (P0, §5.2): i fake delle porte del widget, fedeli al contratto (tabelle di presenza e di miniature). La suite di contratto girera'
// anche sugli adattatori veri (miniature di core-imaging, presenza dall'istantanea) in Robolectric (P13). Nessun thread: le modifiche valgono
// alla chiamata dopo.

/** Lo scaffale finto: [pick] e' il ricordo di oggi; [failing] fa rispondere null come un errore di lettura (D5, punto 5). */
class FakeWidgetMemories(var pick: WidgetMemoryPick? = null, var failing: Boolean = false) : WidgetMemories {
  var calls: Int = 0
    private set

  override suspend fun today(): WidgetMemoryPick? {
    calls++
    return if (failing) null else pick
  }
}

/** Le miniature locali finte: [local] i media con la miniatura sul telefono; [delayMs] ritarda (budget); [failing] lancia (errore di lettura). */
class FakeWidgetThumbs(
  local: Set<MediaId> = emptySet(),
  var delayMs: Long = 0,
  var failing: Boolean = false,
) : WidgetThumbs {
  val local: MutableSet<MediaId> = local.toMutableSet()

  override suspend fun local(ids: Collection<MediaId>): Set<MediaId> {
    if (delayMs > 0) delay(delayMs)
    if (failing) throw IOException("miniature non leggibili")
    return ids.filterTo(LinkedHashSet()) { it in local }
  }
}

/** Il blocco finto: [locked] e i media [onPhone] (anche sul telefono). */
class FakeWidgetLock(locked: Boolean = false, onPhone: Set<MediaId> = emptySet()) : WidgetLock {
  override val appLocked: MutableStateFlow<Boolean> = MutableStateFlow(locked)
  val onPhone: MutableSet<MediaId> = onPhone.toMutableSet()

  override suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId> = ids.filterTo(LinkedHashSet()) { it in onPhone }
}

/** Le scelte finte, in memoria. */
class FakeWidgetPrefs(choice: WidgetChoice = WidgetChoice.DEFAULT, period: WidgetPeriod = WidgetPeriod.DEFAULT) : WidgetPrefs {
  override val choice: MutableStateFlow<WidgetChoice> = MutableStateFlow(choice)
  override val period: MutableStateFlow<WidgetPeriod> = MutableStateFlow(period)

  override suspend fun setChoice(choice: WidgetChoice) {
    this.choice.value = choice
  }

  override suspend fun setPeriod(period: WidgetPeriod) {
    this.period.value = period
  }
}

/** L'orologio finto: [now] l'ora locale, [monotonic] i millisecondi monotoni; si spostano a mano. */
class FakeWidgetClock(var now: LocalDateTime = LocalDateTime.of(2026, 10, 31, 12, 0), var monotonic: Long = 0) : WidgetClock {
  override fun nowLocal(): LocalDateTime = now

  override fun monotonicMs(): Long = monotonic

  fun advanceMinutes(minutes: Long) {
    now = now.plusMinutes(minutes)
    monotonic += minutes * 60_000
  }
}

/** Il ridisegno finto: registra le chiamate, in ordine (un test controlla "prima svuota, poi cancella", D42). */
class RecordingWidgetRefresher : WidgetRefresher {
  val calls: MutableList<String> = mutableListOf()
  val hidden: MutableList<Set<MediaId>> = mutableListOf()
  val dropped: MutableList<MemoryId> = mutableListOf()
  val blanked: MutableList<WidgetEmpty> = mutableListOf()

  override fun hiddenChanged(ids: Set<MediaId>) {
    calls += "hiddenChanged"
    hidden += ids
  }

  override fun memoryDropped(id: MemoryId) {
    calls += "memoryDropped"
    dropped += id
  }

  override fun shelfChanged() {
    calls += "shelfChanged"
  }

  override fun appearanceChanged() {
    calls += "appearanceChanged"
  }

  override fun appBackgrounded() {
    calls += "appBackgrounded"
  }

  override fun lockChanged() {
    calls += "lockChanged"
  }

  override suspend fun blankAll(reason: WidgetEmpty) {
    calls += "blankAll"
    blanked += reason
  }
}
