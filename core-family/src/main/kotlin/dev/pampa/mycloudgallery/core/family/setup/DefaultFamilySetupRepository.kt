package dev.pampa.mycloudgallery.core.family.setup

import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingPlans
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Dove si trova questo dispositivo nella vita della famiglia ([SetupState]), ricavato da tre fonti:
 * l'avanzamento dell'onboarding ([OnboardingStateStore], di P4), lo stato nel registro firmato
 * ([RegistryService.status], di P3) e l'azzeramento ([SelfWipe.progress], di P3). E' la prima cosa che `:app`
 * guarda per scegliere la schermata: onboarding, attesa dell'approvazione, Libreria, "dispositivo azzerato".
 *
 * La decisione e' [SetupResolver.resolve], una funzione pura con la sua tabella di casi; qui c'e' solo
 * il collegamento ai flussi e l'ora dell'azzeramento.
 *
 * @param scope la vita del processo: dove si raccolgono i flussi.
 * @param deviceNow l'ora del dispositivo (ms), per datare un azzeramento visto per la prima volta.
 */
class DefaultFamilySetupRepository(
  store: OnboardingStateStore,
  registry: RegistryService,
  wipe: SelfWipe,
  scope: CoroutineScope,
  private val deviceNow: () -> Long = System::currentTimeMillis,
) : FamilySetupRepository {

  private var wipeObservedAt: Long? = null

  override val state: StateFlow<SetupState> =
    combine(store.state, registry.status, wipe.progress) { stored, trust, phase ->
      // L'ora dell'azzeramento: la prima volta che lo si vede finito (P3 non la espone, e l'eraser puo' aver
      // cancellato anche i nostri dati). Se non e' finito o non e' mai iniziato, si dimentica.
      if (phase == WipePhase.DONE) {
        if (wipeObservedAt == null) wipeObservedAt = deviceNow()
      } else {
        wipeObservedAt = null
      }
      SetupResolver.resolve(stored, trust, phase, wipeObservedAt ?: 0L)
    }.stateIn(scope, SharingStarted.Eagerly, SetupState.Loading)
}

/**
 * La tabella delle decisioni, in ordine di precedenza:
 *
 * 1. **azzeramento a meta'** (`SelfWipe.progress` fra "iniziato" e "finito") o dispositivo segnato perso: `Blocked(WIPE_IN_PROGRESS)`;
 * 2. archivio dell'avanzamento non ancora letto: `Loading`; non leggibile (telefono appena riavviato e non
 *    sbloccato): `Blocked(STORAGE_LOCKED)`;
 * 3. **onboarding in corso** (percorso scelto, non finito): `Onboarding`. Vince su tutto il resto, anche su un
 *    registro che gia' dice "approvato" (l'admin e' approvato dal passo 12, ma i passi 13 e 14 restano) e su un
 *    azzeramento vecchio (chi ricomincia dopo un azzeramento ha scelto un percorso nuovo);
 * 4. azzeramento finito e nessun percorso nuovo: `Wiped`;
 * 5. il registro firmato: approvato → `Ready`; in attesa → `AwaitingApproval`; versione di un'app piu' nuova →
 *    `Blocked(NEEDS_APP_UPDATE)`; rami in conflitto → `Blocked(REGISTRY_FORK)`; revocato o dismesso →
 *    `NotConfigured` (per riusare il dispositivo si ricomincia da capo e si riapprova, con una chiave nuova);
 *    il registro solo "vecchio" o non riletto (`Stale` senza gravita') non cambia lo stato di chi era approvato;
 * 6. altrimenti `NotConfigured`.
 */
object SetupResolver {

  fun resolve(stored: StoreState, trust: TrustStatus, wipe: WipePhase?, wipedAt: Long): SetupState {
    if (wipe != null && wipe != WipePhase.DONE) return SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS)
    // "Perso" con l'azzeramento gia' finito non e' piu' "in corso": il motore della fiducia dice Lost finche' il diario
    // c'e' (P9: senza questa riga, nello stesso processo dell'azzeramento non si arrivava mai a Wiped).
    if (trust == TrustStatus.Lost && wipe != WipePhase.DONE) return SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS)
    val snapshot = when (stored) {
      StoreState.Loading -> return SetupState.Loading
      StoreState.Unavailable -> return SetupState.Blocked(BlockReason.STORAGE_LOCKED)
      is StoreState.Ready -> stored.snapshot
    }
    if (snapshot.path != null && !snapshot.finished) return SetupState.Onboarding(snapshot.progress)
    if (wipe == WipePhase.DONE && snapshot.path == null) return SetupState.Wiped(wipedAt)
    return fromTrust(trust, snapshot)
  }

  private fun fromTrust(trust: TrustStatus, snapshot: OnboardingSnapshot): SetupState = when (trust) {
    is TrustStatus.Approved -> SetupState.Ready(trust.admin)
    is TrustStatus.Pending -> SetupState.AwaitingApproval(trust.code, snapshot.join?.requestedAt ?: 0L)
    TrustStatus.Retired, TrustStatus.Revoked, TrustStatus.NotConfigured, TrustStatus.Lost -> SetupState.NotConfigured
    is TrustStatus.Stale -> when (trust.reason) {
      StaleReason.NEEDS_APP_UPDATE -> SetupState.Blocked(BlockReason.NEEDS_APP_UPDATE)
      StaleReason.EQUIVOCATION -> SetupState.Blocked(BlockReason.REGISTRY_FORK)
      // Registro non riletto da un po' o elenco troncato: si lavora con l'ultimo accettato. Chi ha finito
      // l'onboarding resta configurato (admin se il suo percorso lo era), gli altri non lo sono ancora.
      StaleReason.NOT_REFRESHED, StaleReason.NEWER_UNVERIFIED, StaleReason.LISTING_TRUNCATED ->
        if (snapshot.finished) SetupState.Ready(admin = OnboardingPlans.isAdminPath(snapshot.path)) else SetupState.NotConfigured
    }
  }
}
