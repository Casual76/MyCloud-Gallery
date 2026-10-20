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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Dove si trova questo dispositivo nella vita della famiglia ([SetupState]), ricavato da tre fonti:
 * l'avanzamento dell'onboarding ([OnboardingStateStore], di P4), lo stato nel registro firmato
 * ([RegistryService.status], di P3) e l'azzeramento ([SelfWipe.progress], di P3). E' la prima cosa che `:app`
 * guarda per scegliere la schermata: onboarding, attesa dell'approvazione, Libreria, "dispositivo azzerato".
 *
 * La decisione e' [SetupResolver.resolve], una funzione pura con la sua tabella di casi; qui c'e' solo
 * il collegamento ai flussi e l'ora dell'azzeramento.
 *
 * Lo stato si calcola un passo dopo le fonti (le si osserva su [scope]): chi ha appena cambiato una fonte e vuole che lo stato
 * l'abbia gia' vista lo aspetta con [awaitSourcesSeen] (D-7, l'avvio di `AppGraph`).
 *
 * @param scope la vita del processo: dove si raccolgono i flussi.
 * @param deviceNow l'ora del dispositivo (ms), per datare un azzeramento visto per la prima volta.
 */
class DefaultFamilySetupRepository(
  store: OnboardingStateStore,
  private val registry: RegistryService,
  private val wipe: SelfWipe,
  scope: CoroutineScope,
  private val deviceNow: () -> Long = System::currentTimeMillis,
) : FamilySetupRepository {

  private var wipeObservedAt: Long? = null

  /** L'ultima fase dell'azzeramento vista (per riconoscere "finito, poi dimenticato"). */
  private var lastPhase: WipePhase? = null

  /** Vero dal momento in cui un azzeramento finito e' stato dimenticato finche' il registro dice ancora "perso". */
  private var forgotten = false

  private val current = MutableStateFlow<SetupState>(SetupState.Loading)

  /** Lo stato della fiducia e la fase dell'azzeramento da cui viene il valore di adesso di [state]; null prima del primo calcolo. */
  private val seen = MutableStateFlow<SeenSources?>(null)

  override val state: StateFlow<SetupState> = current.asStateFlow()

  init {
    scope.launch {
      resolved(store).collect { (setup, sources) ->
        // Prima lo stato, poi cio' che ha visto: chi aspetta [seen] trova gia' [state] aggiornato.
        current.value = setup
        seen.value = sources
      }
    }
  }

  /**
   * **D-7** (prove sul campo): ritorna quando [state] e' stato calcolato con lo stato della fiducia (`RegistryService.status`) e la
   * fase dell'azzeramento (`SelfWipe.progress`) **di adesso**, o di dopo. Il valore di [state] si calcola su [scope], un passo dopo
   * le fonti: senza questa attesa, in un processo appena nato, l'avvio dichiarava finita la lettura della fiducia mentre [state]
   * diceva ancora "non configurato" (calcolato con lo stato iniziale del motore della fiducia).
   *
   * Le fonti si confrontano con quelle che il calcolo ha visto, non con un valore passato da chi chiama: se nel frattempo cambiano
   * ancora, si aspetta anche quel cambio (mai un valore piu' vecchio di quello letto da chi chiama).
   */
  suspend fun awaitSourcesSeen() {
    combine(seen, registry.status, wipe.progress) { seenNow, trust, phase -> seenNow == SeenSources(trust, phase) }.first { it }
  }

  private fun resolved(store: OnboardingStateStore) =
    combine(store.state, registry.status, wipe.progress) { stored, trust, phase ->
      // L'ora dell'azzeramento: la prima volta che lo si vede finito (P3 non la espone, e l'eraser puo' aver
      // cancellato anche i nostri dati). Se non e' finito o non e' mai iniziato, si dimentica.
      if (phase == WipePhase.DONE) {
        if (wipeObservedAt == null) wipeObservedAt = deviceNow()
      } else {
        wipeObservedAt = null
      }
      // "Configura di nuovo" (P11): `forgetCompletedWipe` toglie il diario (la fase passa da "finito" a nessuna) un istante
      // PRIMA che il motore della fiducia ricalcoli il suo stato, che fino ad allora dice ancora "perso". Le tre fonti si
      // osservano separatamente, quindi la combinazione vedeva (perso, nessun azzeramento) = "azzeramento in corso": un
      // lampo della schermata bloccata in produzione e un test d'integrazione instabile. Dopo un azzeramento finito non
      // c'e' piu' la chiave del dispositivo, quindi "perso" non puo' essere vero: finche' il motore non si aggiorna vale
      // "non configurato". Un dispositivo segnato perso di nuovo passa da una configurazione nuova (stato diverso da
      // "perso" nel frattempo) o dall'azzeramento (fase non nulla): in entrambi i casi la correzione si spegne.
      if (lastPhase == WipePhase.DONE && phase == null) forgotten = true
      if (phase != null || trust != TrustStatus.Lost) forgotten = false
      lastPhase = phase
      val effectiveTrust = if (forgotten) TrustStatus.NotConfigured else trust
      SetupResolver.resolve(stored, effectiveTrust, phase, wipeObservedAt ?: 0L) to SeenSources(trust, phase)
    }

  /** Le fonti (quelle vere, prima delle correzioni) da cui e' venuto un valore di [state]. */
  private data class SeenSources(val trust: TrustStatus, val phase: WipePhase?)
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
 *    stato salvato con un Keystore che non risponde → `Blocked(KEYSTORE_UNAVAILABLE)` (R7: mai "non configurato", che porta
 *    alla pagina "fuori dalla famiglia" e a "Configura di nuovo");
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
    TrustStatus.KeystoreUnavailable -> SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE)
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
