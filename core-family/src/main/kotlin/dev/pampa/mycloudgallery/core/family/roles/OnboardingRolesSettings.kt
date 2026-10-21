package dev.pampa.mycloudgallery.core.family.roles

import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Chi ripubblica lo stato firmato del dispositivo (formato §5.10) dopo un cambio di ruoli. `:app` lo lega a
 * `DeviceStatusBook.publishIfDue`, che pubblica quando i ruoli del profilo locale non sono quelli dell'ultimo stato. Falso = non
 * pubblicato adesso (NAS non raggiungibile, dispositivo non approvato): lo si fa al prossimo giro, e il cambio resta salvato.
 */
fun interface RolesRepublisher {
  suspend fun republish(): Boolean
}

/**
 * I ruoli di questo dispositivo, cambiabili dopo l'onboarding (Fase 6, D15, risposta Q3). Le scelte stanno dove le ha messe il
 * passo 7 dell'onboarding (`OnboardingChoices.indexer` e `preparer`): lo stesso posto, quindi `LocalDeviceProfile` (che `:app` costruisce
 * da quell'istantanea) vede subito i ruoli nuovi. Il passo 7 resta la proposta iniziale.
 *
 * - Prima della fine dell'onboarding ([OnboardingSnapshot.finished] falso) niente si salva: [RolesChange.NotConfigured] e [roles] vuoto.
 * - Stessi ruoli di prima: [RolesChange.Unchanged], niente scrittura e niente pubblicazione.
 * - Altrimenti si salva e si ripubblica subito. Se la pubblicazione fallisce o lancia (tranne l'annullamento) il cambio resta salvato e
 *   [RolesChange.Saved.republished] e' falso.
 *
 * Spegnere Indicizzatore ferma il calcolo al media successivo e rilascia le prenotazioni (E-A13): lo fa chi osserva [roles] (`AiGraph`).
 * Mai un nome, un account o una share in un log.
 */
class OnboardingRolesSettings(
  private val store: OnboardingStateStore,
  private val republisher: RolesRepublisher,
  scope: CoroutineScope,
) : RolesSettings {

  override val roles: StateFlow<Set<DeviceRole>> = store.state
    .map { rolesOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, rolesOf(store.state.value))

  override suspend fun setRoles(indexer: Boolean, preparer: Boolean): RolesChange {
    val wanted = rolesFor(indexer, preparer)
    val ready = (store.state.value as? StoreState.Ready)?.snapshot
    if (ready == null || !ready.finished) return RolesChange.NotConfigured
    if (rolesFor(ready.choices.indexer, ready.choices.preparer) == wanted) return RolesChange.Unchanged
    val saved = store.update { snapshot ->
      if (snapshot.finished) snapshot.copy(choices = snapshot.choices.copy(indexer = indexer, preparer = preparer)) else snapshot
    } ?: return RolesChange.NotConfigured
    if (!saved.finished) return RolesChange.NotConfigured
    return RolesChange.Saved(wanted, republished = republish())
  }

  private suspend fun republish(): Boolean = try {
    republisher.republish()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    false
  }

  private companion object {
    fun rolesOf(state: StoreState): Set<DeviceRole> {
      val snapshot = (state as? StoreState.Ready)?.snapshot ?: return emptySet()
      if (!snapshot.finished) return emptySet()
      return rolesFor(snapshot.choices.indexer, snapshot.choices.preparer)
    }

    fun rolesFor(indexer: Boolean, preparer: Boolean): Set<DeviceRole> = buildSet {
      if (indexer) add(DeviceRole.INDEXER)
      if (preparer) add(DeviceRole.PREPARER)
    }
  }
}
