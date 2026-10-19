package dev.pampa.mycloudgallery.feature.backup.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `PendingTasksState`, §2.6 "Da decidere", D20, D31): le attivita' in sospeso. Il ViewModel **non
// decide niente**: legge l'elenco dal contratto (`PendingTasks.tasks`) e inoltra i due tocchi. "Tienilo" chiude l'attivita' (`dismiss`, non
// tocca il NAS); "Elimina l'originale" non e' un metodo del contratto: il backup non elimina mai niente da solo (D2) e lo fa il foglio
// "Elimina…" di feature-nas, che `:app` apre dalla porta [PendingTaskActions] e, a cancellazione avviata, chiude con `PendingTasks.complete`.

/**
 * Cio' che la lista "Da decidere" chiede a `:app` e che questo modulo non sa fare: eliminare l'originale di un video modificato. `:app` apre il
 * foglio "Elimina…" di feature-nas (cestino del NAS per 30 giorni, con la sua conferma) e, partita l'operazione, chiama `PendingTasks.complete`.
 * Senza questa porta il tasto "Elimina l'originale" non c'e' (D13 della Fase 3: solo cio' che funziona).
 */
fun interface PendingTaskActions {
  fun deleteOriginal(task: PendingTask)
}

/** Tutto cio' che la lista mostra. */
@Immutable
internal data class PendingTasksUi(
  /** Le attivita' aperte, dalla piu' vecchia. */
  val tasks: List<PendingTask>,
  /** `:app` sa eliminare l'originale: il tasto compare. */
  val canDeleteOriginal: Boolean,
)

@HiltViewModel
class PendingTasksViewModel @Inject constructor(
  tasks: Optional<PendingTasks>,
  actions: Optional<PendingTaskActions>,
) : ViewModel() {

  private val port: PendingTasks? = tasks.orElse(null)
  private val outside: PendingTaskActions? = actions.orElse(null)
  private val source: StateFlow<List<PendingTask>> = port?.tasks ?: MutableStateFlow(emptyList())

  internal val state: StateFlow<PendingTasksUi> = source
    .map { PendingTasksUi(it, canDeleteOriginal = outside != null) }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
      initialValue = PendingTasksUi(source.value, canDeleteOriginal = outside != null),
    )

  /** "Tienilo": l'originale resta come versione e la domanda non torna. */
  fun keep(task: PendingTask) {
    val tasksPort = port ?: return
    viewModelScope.launch { tasksPort.dismiss(task.id) }
  }

  /** "Elimina l'originale": apre il foglio di feature-nas (lo fa `:app`); qui non si cancella niente. */
  fun deleteOriginal(task: PendingTask) {
    outside?.deleteOriginal(task)
  }

  private companion object {
    /** Il flusso resta vivo qualche secondo senza chi lo guarda: una rotazione dello schermo non lo rifa'. */
    const val StopTimeoutMillis = 5_000L
  }
}
