package dev.pampa.mycloudgallery.feature.backup.received

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// "Ricevuti in coda" (correzioni 5B, IN-7): l'elenco dei file che un'altra app ha condiviso e che aspettano di salire sul NAS. Il ViewModel **non
// decide niente**: legge l'elenco dalla porta (`ReceivedQueue.items`) e inoltra i due tocchi. "Riprova" parte subito (rimette in coda e chiede il lavoro,
// con i cancelli di "Salva sul NAS": li guarda `:app`); "Scarta" chiede prima una conferma, perche' toglie la copia sul telefono. Dopo ogni tocco una
// frase dice com'e' andata: mai un tasto che non risponde.

/** La frase dopo un tocco su "Riprova" o "Scarta". */
@Immutable
internal sealed interface ReceivedNotice {
  data object Requeued : ReceivedNotice

  data class Blocked(val by: ReceivedStatus) : ReceivedNotice

  data object NoCopy : ReceivedNotice

  data object AlreadySaved : ReceivedNotice

  data object Discarded : ReceivedNotice

  data object Uploading : ReceivedNotice

  data object Unknown : ReceivedNotice
}

/** Tutto cio' che l'elenco mostra. [discarding] e' il file di cui si chiede la conferma di "Scarta" (null = nessuna conferma aperta). */
@Immutable
internal data class ReceivedUi(
  val items: List<ReceivedItem>,
  val discarding: ReceivedItem? = null,
  val notice: ReceivedNotice? = null,
)

@HiltViewModel
class ReceivedViewModel @Inject constructor(
  queue: Optional<ReceivedQueue>,
) : ViewModel() {

  private val port: ReceivedQueue? = queue.orElse(null)
  private val source: StateFlow<List<ReceivedItem>> = port?.items ?: MutableStateFlow(emptyList())
  private val asking = MutableStateFlow<ReceivedId?>(null)
  private val said = MutableStateFlow<ReceivedNotice?>(null)

  internal val state: StateFlow<ReceivedUi> = combine(source, asking, said) { items, askedId, notice ->
    // Un file che se n'e' andato (caricato, scartato altrove) non resta in una conferma aperta.
    ReceivedUi(items = items, discarding = items.firstOrNull { it.id == askedId }, notice = notice)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
    initialValue = ReceivedUi(source.value),
  )

  /** "Riprova": la porta guarda i cancelli; qui si dice com'e' andata. */
  fun retry(item: ReceivedItem) {
    val queue = port ?: return
    said.value = null
    viewModelScope.launch {
      said.value = when (val outcome = queue.retry(item.id)) {
        ReceivedRetry.Queued -> ReceivedNotice.Requeued
        is ReceivedRetry.Blocked -> ReceivedNotice.Blocked(outcome.by)
        ReceivedRetry.NoCopy -> ReceivedNotice.NoCopy
        ReceivedRetry.AlreadySaved -> ReceivedNotice.AlreadySaved
        ReceivedRetry.Unknown -> ReceivedNotice.Unknown
      }
    }
  }

  /** "Scarta": apre la conferma, non toglie niente. */
  fun askDiscard(item: ReceivedItem) {
    said.value = null
    asking.value = item.id
  }

  fun cancelDiscard() {
    asking.value = null
  }

  /** "Scarta" confermato: la copia sul telefono se ne va, il NAS non si tocca. */
  fun confirmDiscard() {
    val id = asking.value ?: return
    asking.value = null
    val queue = port ?: return
    viewModelScope.launch {
      said.value = when (queue.discard(id)) {
        ReceivedDiscard.Discarded -> ReceivedNotice.Discarded
        ReceivedDiscard.AlreadySaved -> ReceivedNotice.AlreadySaved
        ReceivedDiscard.Uploading -> ReceivedNotice.Uploading
        ReceivedDiscard.Unknown -> ReceivedNotice.Unknown
      }
    }
  }

  fun dismissNotice() {
    said.value = null
  }

  /** La miniatura dalla copia locale; null se non c'e'. */
  suspend fun thumbnail(item: ReceivedItem, sidePx: Int): ImageBitmap? = port?.thumbnail(item.id, sidePx)

  private companion object {
    /** Il flusso resta vivo qualche secondo senza chi lo guarda: una rotazione dello schermo non lo rifa'. */
    const val StopTimeoutMillis = 5_000L
  }
}
