package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, D36, E-L13): lo stato e il ViewModel della sezione "N copie" del pannello informazioni.
// Il viewer guarda un media solo: lo stato e' il gruppo di quel media (null = non ha copie, la sezione non c'e'), piu' cio' che l'utente
// sta facendo (il foglio di conferma di "Separa", un'azione in corso).

/**
 * Cio' che la sezione mostra.
 *
 * @property viewing la copia che il viewer sta mostrando.
 * @property group il suo gruppo, con la copertina e le copie.
 * @property confirmSeparate il foglio di conferma di "Separa" e' aperto.
 * @property busy un'azione e' in corso: le righe non rispondono (un tocco solo alla volta).
 */
@Immutable
internal data class SimilarCopiesUiState(
  val viewing: MediaId,
  val group: SimilarCopiesGroup,
  val confirmSeparate: Boolean = false,
  val busy: Boolean = false,
) {
  /** Mai le identita' dei media nei log. */
  override fun toString(): String = "SimilarCopiesUiState(${group.copies.size} copie, confirm=$confirmSeparate, busy=$busy)"
}

/**
 * Il ViewModel della sezione: sopravvive alla rotazione (E-V8), non tiene nient'altro che il gruppo che il rilevatore ha appena
 * dato. Le azioni sono del rilevatore ([SimilarCopies]): qui si chiamano e si rilegge il gruppo, che e' la verita'.
 *
 * Un solo lavoro per volta: una richiesta nuova ([show] su un altro media) annulla quella in corso.
 *
 * @param scope di serie `viewModelScope`; i test passano il proprio.
 */
internal class SimilarCopiesViewModel(
  private val source: SimilarCopies,
  private val scope: CoroutineScope? = null,
) : ViewModel() {

  private val mutable = MutableStateFlow<SimilarCopiesUiState?>(null)
  val state: StateFlow<SimilarCopiesUiState?> = mutable.asStateFlow()

  private var job: Job? = null

  private val workScope: CoroutineScope get() = scope ?: viewModelScope

  /** Mostra il gruppo di [id] (null se non ha copie). Rilegge sempre: il gruppo puo' essere cambiato (un'altra copia, un nascosto). */
  fun show(id: MediaId) {
    job?.cancel()
    job = workScope.launch {
      val loaded = load(id)
      // Una riletta (il catalogo e' cambiato) non chiude il foglio di conferma aperto sulla stessa copia.
      val confirming = mutable.value?.let { it.viewing == id && it.confirmSeparate } == true
      mutable.value = if (loaded != null && confirming) loaded.copy(confirmSeparate = true) else loaded
    }
  }

  /** "Mostra questa come principale" per la copia in vista. */
  fun makeMain() {
    val current = mutable.value ?: return
    if (current.busy) return
    act(current) { source.chooseCover(it.viewing) }
  }

  /** "Separa": apre il foglio di conferma; non separa ancora. */
  fun askSeparate() {
    mutable.update { it?.takeUnless { s -> s.busy }?.copy(confirmSeparate = true) ?: it }
  }

  fun cancelSeparate() {
    mutable.update { it?.copy(confirmSeparate = false) }
  }

  /** L'utente ha confermato: la copia in vista esce dal gruppo, per sempre. Dopo, la copia non ha piu' compagne e la sezione sparisce. */
  fun confirmSeparate() {
    val current = mutable.value ?: return
    if (current.busy || !current.confirmSeparate) return
    act(current.copy(confirmSeparate = false)) { source.separate(it.viewing) }
  }

  private fun act(current: SimilarCopiesUiState, action: suspend (SimilarCopiesUiState) -> ChoiceOutcome) {
    job?.cancel()
    mutable.value = current.copy(busy = true)
    job = workScope.launch {
      try {
        action(current)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Il rilevatore non risponde: si rilegge com'e' (la scelta non e' stata fatta, o lo e'; il gruppo lo dice).
      }
      mutable.value = load(current.viewing)
    }
  }

  private suspend fun load(id: MediaId): SimilarCopiesUiState? {
    val group = try {
      source.groupOf(id)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    } ?: return null
    return SimilarCopiesUiState(viewing = id, group = group)
  }

  companion object {
    /** La fabbrica per `viewModel(factory = ...)`: il rilevatore arriva dai servizi del viewer, non da Hilt. */
    fun factory(source: SimilarCopies): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = SimilarCopiesViewModel(source) as T
    }
  }
}
