package dev.pampa.mycloudgallery.root

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.di.LockSettings
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.status.GlobalStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
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
 * Cio' che la radice chiede al grafo dell'app oltre ai contratti: l'esito della sonda TCP e l'interruttore (per
 * l'indicatore), il QR della chiave in attesa, l'attesa dell'approvazione, la riconfigurazione dopo un azzeramento.
 * In produzione e' [GraphRootPorts]; nei test [RootPorts.None].
 */
interface RootPorts {
  val probe: StateFlow<ProbeResult?>
  val breaker: StateFlow<BreakerState>

  /** Il QR della chiave del dispositivo in attesa (`MCGK1:`), o null. */
  fun deviceKeyQr(): String?

  /** L'attesa dell'approvazione: il registro si rilegge ogni 10 s finche' qualcuno raccoglie (solo con l'app aperta). */
  fun watchApproval(): Flow<TrustStatus>

  /** "Configura di nuovo" dopo un azzeramento finito. */
  suspend fun reconfigureAfterWipe()

  object None : RootPorts {
    override val probe: StateFlow<ProbeResult?> = MutableStateFlow(null)
    override val breaker: StateFlow<BreakerState> = MutableStateFlow(BreakerState.Closed(1L))

    override fun deviceKeyQr(): String? = null

    override fun watchApproval(): Flow<TrustStatus> = emptyFlow()

    override suspend fun reconfigureAfterWipe() = Unit
  }
}

/** [RootPorts] sopra il grafo vero (P9). */
class GraphRootPorts(private val graph: AppGraph) : RootPorts {
  override val probe: StateFlow<ProbeResult?> = graph.reachability.lastResult
  override val breaker: StateFlow<BreakerState> = graph.breaker.state

  override fun deviceKeyQr(): String? = graph.deviceKeyQr()

  override fun watchApproval(): Flow<TrustStatus> = graph.approvalWatcher.watch()

  override suspend fun reconfigureAfterWipe() = graph.forgetCompletedWipe()
}

/**
 * Il ViewModel della radice: sceglie la destinazione ([destination]) da `SetupState`, calcola l'indicatore
 * globale ([status]) da rete, NAS, sonda TCP e interruttore, e consegna al controller del blocco app le sue
 * impostazioni (il gate e' alla radice, sopra la navigazione).
 *
 * Gli stati sono quelli veri del grafo (P9); nei test i fake dei contratti.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
  setup: FamilySetupRepository,
  network: NetworkEnvironment,
  session: NasSessionManager,
  private val vpn: VpnController,
  lockController: AppLockController,
  @LockSettings lockSettings: Flow<@JvmSuppressWildcards AppLockSettings>,
  private val ports: RootPorts = RootPorts.None,
) : ViewModel() {

  val destination: StateFlow<RootDestination> = setup.state
    .map { RootDestination.from(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, RootDestination.from(setup.state.value))

  val status: StateFlow<GlobalStatus> =
    combine(network.snapshot, session.state, ports.probe, ports.breaker) { snapshot, nas, probe, breaker ->
      GlobalStatus.from(snapshot, nas, probe, breaker is BreakerState.Open)
    }.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      GlobalStatus.from(network.snapshot.value, session.state.value, ports.probe.value, ports.breaker.value is BreakerState.Open),
    )

  init {
    viewModelScope.launch { lockController.follow(lockSettings) }
  }

  /** L'intent "Apri WireGuard", o null se non e' installato. */
  fun wireGuardIntent(): Intent? = vpn.openWireGuardIntent()

  /** Il QR della chiave da mostrare nell'attesa dell'approvazione. */
  fun deviceKeyQr(): String? = ports.deviceKeyQr()

  /** Da raccogliere solo mentre la pagina d'attesa e' visibile (la pagina lo fa con il suo ciclo di vita). */
  fun watchApproval(): Flow<TrustStatus> = ports.watchApproval()

  /** "Configura di nuovo" (dispositivo azzerato): si torna al benvenuto. */
  fun reconfigure() {
    viewModelScope.launch { ports.reconfigureAfterWipe() }
  }
}
