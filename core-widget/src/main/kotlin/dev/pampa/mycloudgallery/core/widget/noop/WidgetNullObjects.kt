package dev.pampa.mycloudgallery.core.widget.noop

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 10 (P0, D5): i null-object del widget. Falliscono **chiusi**: nessun ricordo, nessuna miniatura locale, blocco acceso senza media del
// telefono, nessun piano con foto. Un widget legato per sbaglio a un null-object mostra "Configura MyCloud Gallery", mai una foto.

/** Nessuno scaffale: nessun ricordo di oggi. */
object NoWidgetMemories : WidgetMemories {
  override suspend fun today(): WidgetMemoryPick? = null
}

/** Nessuna miniatura locale: ogni media si salta (E-W5). */
object NoWidgetThumbs : WidgetThumbs {
  override suspend fun local(ids: Collection<MediaId>): Set<MediaId> = emptySet()
}

/** Blocco sconosciuto = acceso, e nessun media "anche sul telefono" (Q3 (c), fallire chiusi). */
object ClosedWidgetLock : WidgetLock {
  override val appLocked: StateFlow<Boolean> = MutableStateFlow(true)

  override suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId> = emptySet()
}

/** Le scelte di base (Q1 (a), Q2 (a)); cambiarle non ha effetto. */
object DefaultWidgetPrefs : WidgetPrefs {
  override val choice: StateFlow<WidgetChoice> = MutableStateFlow(WidgetChoice.DEFAULT)
  override val period: StateFlow<WidgetPeriod> = MutableStateFlow(WidgetPeriod.DEFAULT)

  override suspend fun setChoice(choice: WidgetChoice) = Unit

  override suspend fun setPeriod(period: WidgetPeriod) = Unit
}

/** Nessun widget: nessun ridisegno. */
object NoWidgetRefresher : WidgetRefresher {
  override fun hiddenChanged(ids: Set<MediaId>) = Unit

  override fun memoryDropped(id: MemoryId) = Unit

  override fun shelfChanged() = Unit

  override fun appearanceChanged() = Unit

  override fun appBackgrounded() = Unit

  override fun lockChanged() = Unit

  override suspend fun blankAll(reason: WidgetEmpty) = Unit
}

/** Il pianificatore non legato: sempre "Configura MyCloud Gallery" (D42), mai una foto. */
object NotConfiguredWidgetPlanner : WidgetPlanner {
  override suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): WidgetPlan =
    WidgetPlan(slot, size, WidgetMode.Message(WidgetEmpty.NOT_CONFIGURED), emptyList(), epoch = 0)
}

/** Nessun registro: non ricorda niente, quindi ogni tocco apre la Libreria. */
object NoWidgetLedger : WidgetLedger {
  override suspend fun record(plan: WidgetPlan) = Unit

  override suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef? = null

  override suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot> = emptySet()

  override suspend fun showingMemory(id: MemoryId): Set<WidgetSlot> = emptySet()

  override suspend fun slots(): Set<WidgetSlot> = emptySet()

  override suspend fun forget(slot: WidgetSlot) = Unit

  override suspend fun clear() = Unit
}
