package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// L'AVVISO SUGLI ALTRI DISPOSITIVI (docs/fase8-progetto.md §2.1, D4, E-F7): l'account ha attivato Persone da un altro dispositivo, o il testo e' cambiato;
// qui non si scarica, calcola o mostra niente finche' l'utente non sceglie "Va bene" o "Non su questo dispositivo". L'avviso c'e' solo mentre
// l'interruttore dice `AwaitingNotice`: scelto, sparisce da solo (una tantum).

class FacesNoticeController(
  private val switch: FacesSwitch,
  private val activatedBy: FacesActivatedBy?,
  private val scope: CoroutineScope,
) {
  /** Il nome del dispositivo che ha attivato, se si sa; letto una volta, quando serve. */
  private val by = MutableStateFlow<String?>(null)
  private var asked = false

  val state: StateFlow<FacesNoticeState> = combine(switch.state, by) { current, label ->
    when (current) {
      is FacesState.AwaitingNotice -> FacesNoticeState.Show(current.textVersion, label)
      else -> FacesNoticeState.Hidden
    }
  }.stateIn(scope, SharingStarted.Eagerly, initial())

  private fun initial(): FacesNoticeState =
    (switch.currentState() as? FacesState.AwaitingNotice)?.let { FacesNoticeState.Show(it.textVersion, null) } ?: FacesNoticeState.Hidden

  /** Legge il nome del dispositivo che ha attivato (una volta): un errore non toglie l'avviso, resta senza nome. */
  fun load() {
    val source = activatedBy ?: return
    if (asked) return
    asked = true
    scope.launch {
      by.value = try {
        source.label()?.takeIf { it.isNotBlank() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
    }
  }

  /** "Va bene": questo dispositivo ha visto l'avviso. */
  fun accept() {
    scope.launch {
      val awaiting = switch.currentState() as? FacesState.AwaitingNotice ?: return@launch
      switch.acceptNotice(awaiting.textVersion)
    }
  }

  /** "Non su questo dispositivo": esclusione locale (niente calcolo, niente copia dei dati, dati locali cancellati). */
  fun excludeHere() {
    scope.launch { switch.excludeThisDevice() }
  }
}
