package dev.pampa.mycloudgallery.feature.nas.trash

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import dev.pampa.mycloudgallery.feature.nas.ui.UiNotice
import dev.pampa.mycloudgallery.feature.nas.ui.noticeFor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Eliminati di recente del NAS (docs/fase4-progetto.md §1.5 "NasTrashState", §2.7, D25, Q8): le voci del cestino del NAS, il
// ripristino e "Elimina subito". Il ViewModel mostra lo stato di `NasTrash` e per ogni gesto chiede il piano a `NasOperations`; non
// elimina mai da solo e non costruisce mai una conferma: quello lo fa il foglio, dopo il tocco (due per "Elimina subito").
//
// **Niente di cio' che si legge qui e' un ingresso di una cancellazione.** Le voci arrivano da record firmati letti dal NAS (e dal diario
// locale per le proprie): servono a *mostrare*. "Elimina subito" passa solo l'id di una voce **propria** (`OwnTrashRef`): quello che si
// elimina davvero lo ricava core-nasops dal diario locale, e un id che li' non c'e' non elimina niente.

/** La chiave stabile di una voce nella selezione. */
internal fun NasTrashItem.key(): String = "$ownerDeviceId/$trashId"

/** Lo stato della pagina. */
@Immutable
internal data class TrashUi(
  val areas: List<NasArea> = emptyList(),
  val list: NasTrashState = NasTrashState.Loading,
  val selecting: Boolean = false,
  val selection: Set<String> = emptySet(),
  val confirm: OpPlan? = null,
  val notice: UiNotice? = null,
) {
  /** Le voci da mostrare: tutte se il NAS risponde, solo le proprie dell'ultima lettura se no. */
  val items: List<NasTrashItem>
    get() = when (list) {
      is NasTrashState.Ready -> list.items
      is NasTrashState.Offline -> list.cachedOwn
      else -> emptyList()
    }

  /** Il NAS risponde e almeno una share e' scrivibile: ripristino ed "Elimina subito" possibili. */
  val canModify: Boolean get() = (list as? NasTrashState.Ready)?.writable == true

  val offline: Boolean get() = list is NasTrashState.Offline

  val selectedItems: List<NasTrashItem> get() = items.filter { it.key() in selection }

  /** Si ripristina quello che ha un record valido e una share scrivibile. */
  val restorable: List<NasTrashItem> get() = selectedItems.filter { it.restorable && it.originalPath != null }

  /** "Elimina subito" solo se **tutto** quello che e' scelto e' proprio (Q8, D25). */
  val canPurge: Boolean get() = canModify && selectedItems.isNotEmpty() && selectedItems.all { it.deletableNow }

  val canRestore: Boolean get() = canModify && restorable.isNotEmpty()
}

@HiltViewModel
class NasTrashViewModel @Inject constructor(
  private val trash: NasTrash,
  private val operations: NasOperations,
  areas: NasAreas,
  private val clocks: OpClocks,
) : ViewModel() {

  private val mutable = MutableStateFlow(TrashUi())
  internal val state: StateFlow<TrashUi> = mutable

  init {
    viewModelScope.launch { areas.areas.collect { list -> mutable.update { it.copy(areas = list) } } }
    viewModelScope.launch {
      trash.items.collect { list ->
        mutable.update { current ->
          val present = ((list as? NasTrashState.Ready)?.items ?: (list as? NasTrashState.Offline)?.cachedOwn)?.map { it.key() }?.toSet()
          current.copy(list = list, selection = if (present == null) current.selection else current.selection.intersect(present))
        }
      }
    }
    viewModelScope.launch { trash.refresh() }
  }

  /** L'orologio monotono con cui si data una conferma: lo stesso con cui `NasOperations.start` la controlla. */
  fun monotonicMs(): Long = clocks.monotonicMs()

  internal fun refresh() {
    viewModelScope.launch { trash.refresh() }
  }

  // --- Selezione ------------------------------------------------------------------------------------------------------------

  internal fun startSelecting() = mutable.update { if (it.canModify) it.copy(selecting = true) else it }

  internal fun toggle(item: NasTrashItem) = mutable.update {
    val key = item.key()
    it.copy(selecting = true, selection = if (key in it.selection) it.selection - key else it.selection + key)
  }

  internal fun selectAll() = mutable.update { it.copy(selecting = true, selection = it.items.map { item -> item.key() }.toSet()) }

  internal fun clearSelection() = mutable.update { it.copy(selecting = false, selection = emptySet()) }

  // --- Ripristina ed "Elimina subito": ogni gesto e' un piano da confermare -------------------------------------------------

  internal fun requestRestore() {
    val entries = mutable.value.takeIf { it.canModify }?.restorable.orEmpty().mapNotNull { item ->
      item.originalPath?.let { TrashRestoreRef(item.share, item.ownerDeviceId, item.trashId, it, item.isFolder, item.size) }
    }
    if (entries.isEmpty()) return
    plan(OpRequest.Restore(entries))
  }

  internal fun requestPurge() {
    val current = mutable.value
    if (!current.canPurge) return
    plan(OpRequest.PurgeNow(current.selectedItems.map { OwnTrashRef(it.trashId) }))
  }

  private fun plan(request: OpRequest) {
    viewModelScope.launch {
      when (val outcome = operations.plan(request)) {
        is PlanOutcome.Ready -> mutable.update { it.copy(confirm = outcome.plan, notice = null) }
        is PlanOutcome.Rejected -> mutable.update { it.copy(notice = noticeFor(outcome)) }
      }
    }
  }

  internal fun dismissConfirm() = mutable.update { it.copy(confirm = null) }

  /** L'utente ha confermato [plan] con il numero di tocchi che chiede (due per "Elimina subito"): parte. */
  internal fun confirmed(plan: OpPlan, confirmation: UserConfirmation) {
    mutable.update { it.copy(confirm = null) }
    viewModelScope.launch {
      val outcome = operations.start(plan, confirmation)
      mutable.update { it.copy(notice = noticeFor(outcome), selecting = false, selection = emptySet()) }
      trash.refresh()
    }
  }

  internal fun dismissNotice() = mutable.update { it.copy(notice = null) }
}
