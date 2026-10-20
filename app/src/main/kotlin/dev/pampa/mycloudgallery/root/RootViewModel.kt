package dev.pampa.mycloudgallery.root

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

  /**
   * "Riprova" della pagina "Keystore non disponibile" (R7): lo stato si ricalcola come all'avvio (`AppGraph.start`), chiedendo di
   * nuovo la chiave al Keystore. Vero se adesso il Keystore risponde.
   */
  suspend fun recheckKeys(): Boolean

  /** Il dispositivo tiene (o potrebbe tenere) la chiave di famiglia: il foglio di "Configura di nuovo" lo dice (R7). Nel dubbio, vero. */
  suspend fun holdsFamilyKey(): Boolean

  /**
   * "Configura di nuovo" dalla pagina "Keystore non disponibile", **solo dopo la conferma del foglio**: lo stesso `startOver` della
   * pagina "fuori dalla famiglia", che non tocca niente se il Keystore non risponde (`KEYS_NOT_REMOVED`).
   */
  suspend fun startOver(): StepResult

  object None : RootPorts {
    override val probe: StateFlow<ProbeResult?> = MutableStateFlow(null)
    override val breaker: StateFlow<BreakerState> = MutableStateFlow(BreakerState.Closed(1L))

    override fun deviceKeyQr(): String? = null

    override fun watchApproval(): Flow<TrustStatus> = emptyFlow()

    override suspend fun reconfigureAfterWipe() = Unit

    override suspend fun recheckKeys(): Boolean = false

    override suspend fun holdsFamilyKey(): Boolean = true

    override suspend fun startOver(): StepResult = StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
  }
}

/**
 * [RootPorts] sopra il grafo vero (P9). [restart] riavvia il processo dopo "Configura di nuovo" (ON-11): in produzione
 * [AndroidProcessRestart], nei test un contatore.
 */
class GraphRootPorts(private val graph: AppGraph, private val restart: ProcessRestart = ProcessRestart {}) : RootPorts {
  override val probe: StateFlow<ProbeResult?> = graph.reachability.lastResult
  override val breaker: StateFlow<BreakerState> = graph.breaker.state

  override fun deviceKeyQr(): String? = graph.deviceKeyQr()

  override fun watchApproval(): Flow<TrustStatus> = graph.approvalWatcher.watch()

  override suspend fun reconfigureAfterWipe() {
    // L'azzeramento ha fermato galleria e NAS di questo processo per sempre: si riparte da un processo nuovo (ON-11).
    if (graph.forgetCompletedWipe()) restart.restart()
  }

  override suspend fun recheckKeys(): Boolean = withContext(Dispatchers.Default) {
    // Come all'avvio (ProcessWatcher), fuori dal thread principale: Keystore e verifica dello stato salvato.
    graph.start()
    graph.registry.status.value != TrustStatus.KeystoreUnavailable
  }

  override suspend fun holdsFamilyKey(): Boolean = graph.coordinator.holdsFamilyKey()

  override suspend fun startOver(): StepResult = graph.coordinator.startOver()
}

/**
 * La pagina "Keystore non disponibile" (R7 della revisione 5B, `BlockReason.KEYSTORE_UNAVAILABLE`): "Riprova" e l'uscita secondaria
 * "Configura di nuovo", che passa sempre dal foglio di conferma.
 *
 * @property retrying "Riprova" in corso.
 * @property stillUnavailable l'ultimo "Riprova" e' finito e il Keystore non risponde ancora: la pagina lo dice.
 * @property confirming il foglio di conferma di "Configura di nuovo" e' aperto.
 * @property holdsKey il foglio dice che si perde la chiave di famiglia (nel dubbio si').
 * @property busy "Configura di nuovo" confermato e in corso.
 * @property problem "Configura di nuovo" non e' riuscito.
 */
data class KeystoreUi(
  val retrying: Boolean = false,
  val stillUnavailable: Boolean = false,
  val confirming: Boolean = false,
  val holdsKey: Boolean = true,
  val busy: Boolean = false,
  val problem: KeystoreProblem? = null,
)

/** Perche' "Configura di nuovo" dalla pagina "Keystore non disponibile" non e' andato. */
enum class KeystoreProblem {
  /** Il Keystore non ha risposto (niente si e' toccato) o le chiavi non si sono tolte (R19: account, password e stato restano). */
  KEYS_NOT_REMOVED,

  /** Un altro problema: si riprova. */
  NOT_DONE,
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

  private val keystoreState = MutableStateFlow(KeystoreUi())

  /** La pagina "Keystore non disponibile" (R7). */
  val keystore: StateFlow<KeystoreUi> = keystoreState.asStateFlow()

  init {
    viewModelScope.launch { lockController.follow(lockSettings) }
    viewModelScope.launch {
      // Fuori dalla pagina, la pagina riparte pulita la prossima volta (tranne mentre "Configura di nuovo" sta finendo).
      destination.collect { d -> if (d != KEYSTORE_PAGE) keystoreState.update { if (it.busy) it else KeystoreUi() } }
    }
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

  /**
   * "Riprova" della pagina "Keystore non disponibile" (R7): lo stato si ricalcola. Se il Keystore risponde la radice va dove deve (la
   * Libreria, l'attesa...); se no la pagina resta e lo dice.
   */
  fun retryKeystore() {
    val current = keystoreState.value
    if (current.retrying || current.busy) return
    keystoreState.update { it.copy(retrying = true, stillUnavailable = false, problem = null) }
    viewModelScope.launch {
      val responds = guard { ports.recheckKeys() } ?: false
      keystoreState.update { it.copy(retrying = false, stillUnavailable = !responds) }
    }
  }

  /** Il tasto "Configura di nuovo" della pagina "Keystore non disponibile" apre il foglio di conferma e basta (R7). */
  fun askKeystoreStartOver() {
    val current = keystoreState.value
    if (current.busy || current.confirming || current.retrying) return
    viewModelScope.launch {
      val holds = guard { ports.holdsFamilyKey() } ?: true // nel dubbio, l'avviso in piu'
      keystoreState.update { if (it.busy) it else it.copy(confirming = true, holdsKey = holds, problem = null) }
    }
  }

  /** Il foglio si chiude senza confermare: non succede niente. */
  fun cancelKeystoreStartOver() {
    keystoreState.update { if (it.busy) it else it.copy(confirming = false) }
  }

  /** La conferma del foglio: solo da qui parte "Configura di nuovo". */
  fun confirmKeystoreStartOver() {
    val current = keystoreState.value
    if (!current.confirming || current.busy) return
    keystoreState.update { it.copy(busy = true, confirming = false, problem = null) }
    viewModelScope.launch {
      val result = guard { ports.startOver() } ?: StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
      keystoreState.update {
        when (result) {
          // La radice va al benvenuto (lo stato e' "non configurato"): la pagina riparte pulita.
          is StepResult.Advanced -> KeystoreUi()
          is StepResult.Problem -> it.copy(
            busy = false,
            problem = if (result.problem == OnboardingProblem.KEYS_NOT_REMOVED) KeystoreProblem.KEYS_NOT_REMOVED else KeystoreProblem.NOT_DONE,
          )
        }
      }
    }
  }

  /** Un'eccezione di una porta diventa "non riuscito", mai un crash della radice. */
  private suspend fun <T> guard(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  private companion object {
    val KEYSTORE_PAGE: RootDestination = RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE)
  }
}
