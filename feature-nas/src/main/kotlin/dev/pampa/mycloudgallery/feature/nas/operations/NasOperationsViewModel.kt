package dev.pampa.mycloudgallery.feature.nas.operations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpRules
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Operazioni in corso (docs/fase4-progetto.md §1.5 "NasOperationState", §2.7): l'avanzamento di cio' che il diario sta facendo e
// "Ferma". Il ViewModel non decide niente: mostra gli stati di `NasOperations.operations` e inoltra "Ferma". "Ferma" non vuole
// una conferma perche' non distrugge: ferma prima dell'elemento successivo, toglie i propri temporanei e lascia fatto cio' che e'
// fatto (annullare e' ammesso solo prima che una copia venga pubblicata, `OpStateMachine.cancellable`).

/** Lo stato della pagina: le operazioni non chiuse e, sotto, le ultime chiuse (la piu' recente prima). */
@Immutable
internal data class OperationsUi(
  val active: List<NasOperationState> = emptyList(),
  val closed: List<NasOperationState> = emptyList(),
  /** NA-11: di quanto e' saltata l'ora del NAS (ms); null = nessun salto che sospende le scritture. */
  val clockJumpMs: Long? = null,
  /** NA-11: il foglio "L'ora del NAS e' giusta?" e' aperto. */
  val askingClock: Boolean = false,
) {
  val isEmpty: Boolean get() = active.isEmpty() && closed.isEmpty()
}

/** Divide l'elenco del diario (nell'ordine di creazione) nelle due sezioni della pagina. Pura: la provano i test su JVM. */
internal fun operationsUi(all: List<NasOperationState>): OperationsUi = OperationsUi(
  active = all.filter { it.active },
  closed = all.filterNot { it.active }.asReversed().take(OpRules.CLOSED_OPS_SHOWN),
)

@HiltViewModel
class NasOperationsViewModel @Inject constructor(
  private val operations: NasOperations,
  private val areas: NasAreas,
) : ViewModel() {

  private val asking = MutableStateFlow(false)

  internal val state: StateFlow<OperationsUi> = combine(operations.operations, areas.clockJumpMs, asking) { all, jump, ask ->
    operationsUi(all).copy(clockJumpMs = jump, askingClock = ask && jump != null)
  }.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    operationsUi(operations.operations.value).copy(clockJumpMs = areas.clockJumpMs.value),
  )

  /** "Ferma" su [id]. */
  internal fun stop(id: OpId) {
    viewModelScope.launch { operations.cancel(id) }
  }

  /** NA-11: l'utente tocca "L'ora del NAS e' giusta, continua": prima il foglio che chiede di controllarla davvero. */
  internal fun askClock() {
    asking.value = true
  }

  internal fun dismissClock() {
    asking.value = false
  }

  /** NA-11: confermato dal foglio. La sospensione finisce subito (un gesto dell'utente, mai un dato del NAS). */
  internal fun acceptClock() {
    asking.value = false
    areas.acceptClockJump()
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
