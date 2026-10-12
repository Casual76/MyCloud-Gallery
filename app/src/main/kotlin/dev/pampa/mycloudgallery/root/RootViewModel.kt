package dev.pampa.mycloudgallery.root

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.di.LockSettings
import dev.pampa.mycloudgallery.status.GlobalStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * La prima schermata dell'app, decisa dallo stato della famiglia (docs/fase2-progetto.md §1.2, "Cosa resta
 * in :app"). Una per ogni [SetupState] che si vede.
 */
sealed interface RootDestination {
  /** Stato non ancora letto: niente da mostrare (nessun lampo di onboarding). */
  data object Loading : RootDestination

  /** Benvenuto o ripresa dell'onboarding. */
  data object Onboarding : RootDestination

  /** In attesa dell'approvazione dell'admin, sola lettura (E-S14). */
  data class AwaitingApproval(val code: String) : RootDestination

  /** La galleria: schede, indicatore, Impostazioni. */
  data object Main : RootDestination

  /** Dispositivo azzerato (E-S2). */
  data object Wiped : RootDestination

  /** Fermo per [reason]. */
  data class Blocked(val reason: BlockReason) : RootDestination

  companion object {
    fun from(state: SetupState): RootDestination = when (state) {
      SetupState.Loading -> Loading
      SetupState.NotConfigured, is SetupState.Onboarding -> Onboarding
      is SetupState.AwaitingApproval -> AwaitingApproval(state.code)
      is SetupState.Ready -> Main
      is SetupState.Wiped -> Wiped
      is SetupState.Blocked -> Blocked(state.reason)
    }
  }
}

/**
 * Il ViewModel della radice: sceglie la destinazione ([destination]) da `SetupState`, calcola l'indicatore
 * globale ([status]) da rete e NAS, e consegna al controller del blocco app le sue impostazioni (il gate e'
 * alla radice, sopra la navigazione).
 *
 * Con i null-object di P0 la destinazione e' sempre l'onboarding; con i fake (debug e test) si percorrono
 * tutte. P9 lega le implementazioni vere senza toccare questa classe.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
  setup: FamilySetupRepository,
  network: NetworkEnvironment,
  session: NasSessionManager,
  private val vpn: VpnController,
  lockController: AppLockController,
  @LockSettings lockSettings: Flow<@JvmSuppressWildcards AppLockSettings>,
) : ViewModel() {

  val destination: StateFlow<RootDestination> = setup.state
    .map { RootDestination.from(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, RootDestination.from(setup.state.value))

  val status: StateFlow<GlobalStatus> = combine(network.snapshot, session.state) { snapshot, nas -> GlobalStatus.from(snapshot, nas) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalStatus.from(network.snapshot.value, session.state.value))

  init {
    viewModelScope.launch { lockController.follow(lockSettings) }
  }

  /** L'intent "Apri WireGuard", o null se non e' installato. */
  fun wireGuardIntent(): Intent? = vpn.openWireGuardIntent()
}
