package dev.pampa.mycloudgallery.feature.nas.operations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpRules
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
) : ViewModel() {

  internal val state: StateFlow<OperationsUi> = operations.operations
    .map(::operationsUi)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), operationsUi(operations.operations.value))

  /** "Ferma" su [id]. */
  internal fun stop(id: OpId) {
    viewModelScope.launch { operations.cancel(id) }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
