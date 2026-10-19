package dev.pampa.mycloudgallery.feature.integrations.picker

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17; E-H1, E-X1, E-X4, E-X6, E-X7): lo stato del selettore. Decide cosa si vede e quando
// qualcosa puo' uscire; non sa niente di Compose ne' di Android a parte gli URI.
//
// Il cammino di un media verso l'altra app:
//   1. il **cancello** (`EntryGate`): pronta e sbloccata, bloccata (prima lo sblocco, E-X7: finche' non c'e' non esiste nemmeno la griglia, ne' la
//      selezione), non pronta;
//   2. la **vista** della Libreria di adesso, senza i nascosti (E-H1): la griglia ne e' la copia; se cambia, la selezione perde cio' che non c'e' piu';
//   3. un **tocco**: scegliere e poi "Scegli", oppure il tocco su un solo elemento. Mai niente da solo (C17);
//   4. la **consegna** ([PickerDeliverer]): ricontrolla la vista e il cancello, i tetti, il NAS; solo allora nascono gli id dei solo-NAS;
//   5. il cancello **un'ultima volta**: se il blocco si e' richiuso durante le attese, niente esce e gli id si revocano.

/** Che cosa mostra il selettore. */
enum class PickerScreenState {
  /** Non ancora deciso: non si mostra niente. */
  CHECKING,

  /** L'app e' bloccata (E-X7): lo sblocco, e basta. Nessun media in composizione. */
  LOCKED,

  /** L'app non e' pronta (onboarding, famiglia, dispositivo): non si offre niente. */
  NOT_READY,

  /** La griglia. */
  GRID,

  /** La consegna e' in corso (prove del NAS, creazione degli id): niente si tocca. */
  DELIVERING,
}

/** Lo stato del selettore. [items] e [selected] sono vuoti finche' non si e' nella griglia, e si svuotano appena l'app si blocca. */
class PickerUi(
  val screen: PickerScreenState = PickerScreenState.CHECKING,
  val items: PickerItems = PickerItems.Empty,
  val selected: Set<MediaId> = emptySet(),
  val notice: PickerNotice? = null,
  val unlocking: Boolean = false,
  val unlockFailed: Boolean = false,
) {
  fun copy(
    screen: PickerScreenState = this.screen,
    items: PickerItems = this.items,
    selected: Set<MediaId> = this.selected,
    notice: PickerNotice? = this.notice,
    unlocking: Boolean = this.unlocking,
    unlockFailed: Boolean = this.unlockFailed,
  ): PickerUi = PickerUi(screen, items, selected, notice, unlocking, unlockFailed)

  override fun toString(): String = "PickerUi($screen, ${selected.size} scelti)"
}

/** Come finisce il selettore. */
sealed interface PickerOutcome {

  /** Gli URI scelti. [skipped]: quanti elementi non erano piu' disponibili. */
  class Deliver(val uris: List<Uri>, val skipped: Int) : PickerOutcome

  /** L'utente ha annullato (o l'app non e' pronta / e' rimasta bloccata): `RESULT_CANCELED`. */
  data object Cancel : PickerOutcome
}

class PickerViewModel(
  private val request: PickerRequest,
  private val gate: EntryGate,
  val library: IntegrationLibrary,
  private val issuer: DocumentIssuer?,
  authority: String,
  scope: CoroutineScope? = null,
  nasProbeDeadlineMs: Long = DocumentPolicy.NAS_PROBE_DEADLINE_MS,
) : ViewModel() {

  private val scope: CoroutineScope = scope ?: viewModelScope
  private val deliverer = PickerDeliverer(library, issuer, request, authority, nasProbeDeadlineMs)

  private val _ui = MutableStateFlow(PickerUi())
  val ui: StateFlow<PickerUi> = _ui.asStateFlow()

  private val _outcome = MutableStateFlow<PickerOutcome?>(null)

  /** Quando non e' null il selettore ha finito: l'activity imposta il risultato e si chiude. */
  val outcome: StateFlow<PickerOutcome?> = _outcome.asStateFlow()

  val allowMultiple: Boolean get() = request.allowMultiple

  private var viewJob: Job? = null
  private var deliverJob: Job? = null

  // --- Il cancello (E-X7) -----------------------------------------------------------------------------------------------

  /** Rilegge il cancello. Si chiama a ogni ritorno in primo piano: il blocco puo' essersi chiuso mentre si era altrove. */
  fun refreshGate() {
    if (_outcome.value != null) return
    when (gate.decide()) {
      EntryDecision.ALLOWED -> enterGrid()
      EntryDecision.NEEDS_UNLOCK -> enter(PickerScreenState.LOCKED)
      EntryDecision.NOT_READY -> enter(PickerScreenState.NOT_READY)
    }
  }

  private fun enterGrid() {
    val screen = _ui.value.screen
    if (screen == PickerScreenState.GRID || screen == PickerScreenState.DELIVERING) return
    _ui.update { PickerUi(screen = PickerScreenState.GRID) }
    viewJob?.cancel()
    viewJob = scope.launch {
      library.items.view.collect { view ->
        val snapshot = view.snapshot
        val items = PickerItems.of(snapshot, request)
        // Un media che esce dalla vista (nascosto, sparito) esce anche dalla selezione (E-H1).
        _ui.update { current ->
          if (current.screen != PickerScreenState.GRID && current.screen != PickerScreenState.DELIVERING) {
            current
          } else {
            current.copy(items = items, selected = current.selected.filterTo(LinkedHashSet()) { snapshot.indexOf(it) >= 0 })
          }
        }
      }
    }
  }

  /** Bloccata o non pronta: **niente** della Libreria resta nello stato (ne' la griglia, ne' la selezione), e ogni consegna in corso si ferma. */
  private fun enter(screen: PickerScreenState) {
    val current = _ui.value
    if (current.screen == screen) return
    viewJob?.cancel()
    deliverJob?.cancel()
    _ui.value = PickerUi(screen = screen, unlockFailed = current.unlockFailed && screen == PickerScreenState.LOCKED)
    IntegrationCounters.add(if (screen == PickerScreenState.LOCKED) IntegrationCounter.PICKER_LOCKED else IntegrationCounter.PICKER_NOT_READY)
  }

  /** Lo sblocco sta per partire: falso se non si e' bloccati o ce n'e' gia' uno in corso. */
  fun beginUnlock(): Boolean {
    val current = _ui.value
    if (current.screen != PickerScreenState.LOCKED || current.unlocking) return false
    _ui.value = current.copy(unlocking = true, unlockFailed = false)
    return true
  }

  /** Lo sblocco e' finito. Dopo un `true` si rilegge il cancello: deve rispondere `ALLOWED`, altrimenti lo sblocco non vale. */
  fun endUnlock(success: Boolean) {
    _ui.update { it.copy(unlocking = false) }
    if (success) refreshGate()
    if (!success || _ui.value.screen == PickerScreenState.LOCKED) _ui.update { it.copy(unlockFailed = true) }
  }

  // --- La scelta --------------------------------------------------------------------------------------------------------

  /** Un tocco su un elemento. Senza `EXTRA_ALLOW_MULTIPLE` sceglie e consegna; altrimenti aggiunge o toglie dalla selezione. */
  fun onTap(id: MediaId) {
    val state = _ui.value
    if (state.screen != PickerScreenState.GRID) return
    if (!request.allowMultiple) {
      deliver(setOf(id))
      return
    }
    if (id in state.selected) {
      _ui.update { it.copy(selected = it.selected - id, notice = null) }
      return
    }
    if (state.selected.size >= DocumentPolicy.MAX_PICKED_ITEMS) {
      IntegrationCounters.add(IntegrationCounter.PICKER_TOO_MANY)
      _ui.update { it.copy(notice = PickerNotice.TooMany(DocumentPolicy.MAX_PICKED_ITEMS)) }
      return
    }
    if (isNasOnly(state, id)) {
      val count = state.selected.count { isNasOnly(state, it) }
      if (count >= DocumentPolicy.MAX_NAS_ONLY_ITEMS) {
        IntegrationCounters.add(IntegrationCounter.PICKER_TOO_MANY)
        _ui.update { it.copy(notice = PickerNotice.TooManyNasOnly(count + 1, 0L)) }
        return
      }
    }
    _ui.update { it.copy(selected = it.selected + id, notice = null) }
  }

  /** "Scegli": consegna la selezione. */
  fun confirm() {
    val state = _ui.value
    if (state.screen != PickerScreenState.GRID || state.selected.isEmpty()) return
    deliver(state.selected)
  }

  fun dismissNotice() {
    _ui.update { it.copy(notice = null) }
  }

  /** L'utente annulla (chiude, indietro): una consegna in corso si ferma e non lascia id (E-X4). */
  fun cancel() {
    deliverJob?.cancel()
    viewJob?.cancel()
    if (_outcome.value == null) {
      IntegrationCounters.add(IntegrationCounter.PICKER_CANCELLED)
      _outcome.value = PickerOutcome.Cancel
    }
  }

  // --- La consegna ------------------------------------------------------------------------------------------------------

  private fun deliver(ids: Set<MediaId>) {
    if (deliverJob?.isActive == true || _outcome.value != null) return
    // Niente esce senza lo sblocco: il cancello si rilegge al momento del tocco.
    if (gate.decide() != EntryDecision.ALLOWED) {
      refreshGate()
      return
    }
    _ui.update { it.copy(screen = PickerScreenState.DELIVERING, notice = null) }
    deliverJob = scope.launch {
      val delivery = try {
        deliverer.prepare(ids)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        PickerDelivery.Refused(PickerNotice.NothingAvailable)
      }
      when (delivery) {
        is PickerDelivery.Refused -> _ui.update { it.copy(screen = PickerScreenState.GRID, notice = delivery.notice) }
        is PickerDelivery.Ready -> {
          // L'ultimo cancello: il blocco puo' essersi richiuso durante le attese. Niente esce, e gli id appena creati si revocano.
          if (gate.decide() != EntryDecision.ALLOWED) {
            revoke(delivery.minted)
            refreshGate()
          } else {
            _outcome.value = PickerOutcome.Deliver(delivery.uris, delivery.skipped)
          }
        }
      }
    }
  }

  private fun revoke(ids: List<DocumentId>) {
    if (ids.isNotEmpty()) issuer?.revoke(ids)
  }

  private fun isNasOnly(state: PickerUi, id: MediaId): Boolean {
    val snapshot = state.items.snapshot
    val index = snapshot.indexOf(id)
    return index >= 0 && !snapshot.hasFlag(index, CellFlags.ON_PHONE) && snapshot.nasRefAt(index) != null
  }

  companion object {

    /** La fabbrica che legge le porte installate (le activity le crea il sistema: non si iniettano). */
    fun factory(request: PickerRequest, authority: String): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        PickerViewModel(
          request = request,
          gate = EntryGate { IntegrationPorts.entryGate.decide() },
          library = PickerPorts.library,
          issuer = IntegrationPorts.documents as? DocumentIssuer,
          authority = authority,
        )
      }
    }
  }
}
