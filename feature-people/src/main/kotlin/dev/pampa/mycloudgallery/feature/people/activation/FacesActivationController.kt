package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.ActivationRequirements
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// LA LOGICA DELLA PAGINA DI ATTIVAZIONE (docs/fase8-progetto.md §2.1, D3, D5, D34; riga P7 del §4.4). Un controllore semplice, senza Android:
// i requisiti (area privata, indicizzatori), lo stato mostrato dalla pagina e l'esito dell'attivazione. **Non costruisce il consenso**: lo
// costruisce solo la pagina, dopo un tocco su "Attiva" (`FacesActivationScreen`, Phase8ArchitectureTest); qui il consenso arriva e basta.

class FacesActivationController(
  private val switch: FacesSwitch,
  private val requirements: FacesRequirements,
  private val indexerRole: ActivationIndexerRole?,
  private val scope: CoroutineScope,
) {
  private val mutableState = MutableStateFlow<FacesActivationState?>(null)

  /** Null finche' i requisiti non sono stati letti. */
  val state: StateFlow<FacesActivationState?> = mutableState.asStateFlow()

  private val lock = Mutex()

  /** "Fallo su questo dispositivo" si offre solo se `:app` ha legato il ruolo. */
  val canOfferIndexerRole: Boolean get() = indexerRole != null

  /** Legge i requisiti e dice cosa mostrare. Non tocca una pagina gia' attivata o in corso di attivazione. */
  fun load() {
    scope.launch { lock.withLock { loadLocked() } }
  }

  private suspend fun loadLocked() {
    val current = mutableState.value
    if (current is FacesActivationState.Activating || current is FacesActivationState.Activated) return
    mutableState.value = when (val now = switch.currentState()) {
      FacesState.NeedsUpdate -> FacesActivationState.Failed(ActivationProblem.NEEDS_UPDATE)
      is FacesState.Erasing -> FacesActivationState.Failed(ActivationProblem.UNAVAILABLE)
      // Persone e' gia' attiva qui: niente da accendere.
      is FacesState.On -> if (now.activeHere) FacesActivationState.Activated(switch.syncPending.value) else explain(joining = true)
      // L'account l'ha gia' acceso altrove: la pagina serve a leggerla e a dire "Attiva" qui, senza requisiti da soddisfare (non si crea niente).
      is FacesState.AwaitingNotice -> explain(joining = true)
      FacesState.Off -> explain(joining = false)
    }
  }

  private suspend fun explain(joining: Boolean): FacesActivationState {
    val facts = read()
    if (!joining) {
      if (!facts.privateArea) return FacesActivationState.NoPrivateArea
      if (facts.indexers == 0 && !facts.thisDeviceIndexer) return FacesActivationState.NeedsIndexer(facts.canBecomeIndexer && canOfferIndexerRole)
    }
    return FacesActivationState.Explain(FacesSwitch.CURRENT_TEXT_VERSION, facts)
  }

  private suspend fun read(): ActivationRequirements = try {
    val area = requirements.privateArea()
    val indexers = requirements.indexers()
    val declared = area as? PrivateAreaState.Declared
    ActivationRequirements(
      privateArea = declared != null,
      indexers = indexers.accountIndexers,
      thisDeviceIndexer = indexers.thisDevice,
      canBecomeIndexer = indexers.canBecomeIndexer,
      // Nel dubbio la recinzione tiene (D34): la pagina dice che le persone restano su ogni dispositivo.
      localOnly = declared?.let { requirements.localOnly(it.share) } ?: true,
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    ActivationRequirements(privateArea = false, indexers = 0, thisDeviceIndexer = false, canBecomeIndexer = false, localOnly = true)
  }

  /** "Attiva": il consenso lo ha costruito la pagina. L'esito dell'interruttore decide cosa si mostra. */
  fun activate(consent: FacesActivationConsent) {
    scope.launch {
      lock.withLock {
        mutableState.value = FacesActivationState.Activating
        mutableState.value = try {
          outcomeState(switch.activate(consent))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          FacesActivationState.Failed(ActivationProblem.UNAVAILABLE)
        }
      }
    }
  }

  private fun outcomeState(outcome: ActivateOutcome): FacesActivationState = when (outcome) {
    is ActivateOutcome.Activated -> FacesActivationState.Activated(switch.syncPending.value)
    ActivateOutcome.NoPrivateArea -> FacesActivationState.NoPrivateArea
    is ActivateOutcome.NoIndexer -> FacesActivationState.NeedsIndexer(outcome.canBecomeIndexer && canOfferIndexerRole)
    ActivateOutcome.StaleConsent -> FacesActivationState.Failed(ActivationProblem.STALE_CONSENT)
    ActivateOutcome.NeedsUpdate -> FacesActivationState.Failed(ActivationProblem.NEEDS_UPDATE)
    ActivateOutcome.Unavailable -> FacesActivationState.Failed(ActivationProblem.UNAVAILABLE)
  }

  /** "Fallo su questo dispositivo": accende il ruolo Indicizzatore e rilegge i requisiti. */
  fun becomeIndexer() {
    val role = indexerRole ?: return
    scope.launch {
      lock.withLock {
        try {
          role.enable()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Il ruolo non si e' acceso: i requisiti dicono ancora la stessa cosa e la pagina resta com'e'.
        }
        mutableState.value = null
        loadLocked()
      }
    }
  }

  /** Dopo un errore: si rileggono i requisiti e si ripropone la pagina. */
  fun retry() {
    scope.launch {
      lock.withLock {
        mutableState.value = null
        loadLocked()
      }
    }
  }
}
