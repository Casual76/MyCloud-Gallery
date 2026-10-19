package dev.pampa.mycloudgallery.graph

import dev.pampa.mycloudgallery.core.family.onboarding.DeviceInfoSource
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingChoices
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingChoicesSink
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.setup.NasAccountStore
import dev.pampa.mycloudgallery.core.family.trust.LocalDeviceProfile
import dev.pampa.mycloudgallery.core.family.trust.LocalDeviceProfileSource
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerRecord
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NasSessionActivity
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.rules.DeviceRoles
import dev.pampa.mycloudgallery.core.rules.DeviceRolesSignal
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInput
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Gli adattatori fra i contratti dei moduli (P1-P8) che nessun modulo puo' scrivere da solo, perche' ognuno vede
// solo i propri vicini: li scrive :app, che li vede tutti (docs/fase2-progetto.md §1.2, "Cosa resta in :app").

/**
 * L'interruttore di sicurezza (§3.3) dell'account NAS **di questo dispositivo**, per chi lo guarda senza conoscere
 * l'account (Impostazioni, Diagnostica): segue l'account salvato dall'onboarding. Senza account e' chiuso.
 *
 * [onNewPasswordSaved] legge l'account dal file ([NasAccountStore.current]) e non dalla copia in memoria: l'onboarding
 * lo chiama subito dopo aver salvato un account nuovo, e deve toccare quello.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountAuthBreaker(
  account: StateFlow<NasAccount?>,
  private val accounts: NasAccountStore,
  private val breakers: AuthBreakerRegistry,
  scope: CoroutineScope,
) : AuthCircuitBreaker {

  override val state: StateFlow<BreakerState> = account
    .flatMapLatest { current -> if (current == null) flowOf(CLOSED) else breakers.forAccount(current).state }
    .stateIn(scope, SharingStarted.Eagerly, CLOSED)

  override suspend fun onNewPasswordSaved() {
    val current = accounts.current() ?: return
    breakers.forAccount(current).onNewPasswordSaved()
  }

  /**
   * L'utente ha scritto una password nuova per [account] (passo 3 o ingresso): l'interruttore di quell'account, se e'
   * aperto, si richiude per **un** tentativo (E-R8: "Riprova" solo su azione dell'utente). Si legge lo stato dal file,
   * non dalla copia in memoria, che per un interruttore appena creato non e' ancora arrivata.
   */
  suspend fun allowUserRetry(account: NasAccount) {
    val breaker = breakers.forAccount(account)
    if (breaker.current() is BreakerState.Open) breaker.onNewPasswordSaved()
  }

  private companion object {
    val CLOSED: BreakerState = BreakerState.Closed(BreakerRecord.FIRST_GENERATION)
  }
}

/**
 * La sonda TCP 445 di `core-network` con l'ultimo esito ricordato: l'indicatore globale (§3.5) distingue "NAS non
 * raggiungibile" (la sonda e' fallita) da "non ancora provato". L'esito si dimentica a ogni cambio di percorso.
 */
class ObservedReachabilityProbe(
  private val delegate: ReachabilityProbe,
  gate: NasRouteGate,
  scope: CoroutineScope,
  /**
   * La sessione NAS (verifica funzionale, RE-6): quando il NAS risponde con una sessione (pronta, o in risveglio) una sonda fallita di prima
   * non vale piu'. Prima l'esito si dimenticava solo al cambio di percorso, e a sessione inattiva l'indicatore restava «NAS non
   * raggiungibile» anche col NAS tornato.
   */
  sessionState: Flow<NasConnState> = emptyFlow(),
) : ReachabilityProbe {

  private val last = MutableStateFlow<ProbeResult?>(null)

  /** L'ultimo esito sul percorso corrente, o null se non c'e' stata ancora una sonda. */
  val lastResult: StateFlow<ProbeResult?> = last.asStateFlow()

  init {
    scope.launch { gate.routes.collect { last.value = null } }
    scope.launch {
      sessionState.collect { state ->
        if (state is NasConnState.Ready || state is NasConnState.WakingUp) last.value = null
      }
    }
  }

  override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult =
    delegate.tcp445(route, nas, timeout).also { last.value = it }
}

/** La sessione NAS sta lavorando? (P2: l'automazione della VPN aspetta che sia libera prima di spegnere il tunnel). */
class SessionBusyActivity(session: SmbNasSessionManager, scope: CoroutineScope) : NasSessionActivity {
  override val isBusy: StateFlow<Boolean> =
    session.activeOperations.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
}

/** L'onboarding finito (o a buon punto), letto senza aspettare: null se l'archivio non e' pronto. */
internal fun OnboardingStateStore.snapshotOrNull(): OnboardingSnapshot? = (state.value as? StoreState.Ready)?.snapshot

/**
 * Cio' che la fiducia (P3) deve sapere di questo dispositivo, da cio' che l'onboarding (P4) ha salvato: account, aree,
 * ruoli (passo 7), nome e modello. Null finche' account e area comune non ci sono.
 */
class SnapshotDeviceProfiles(
  private val store: OnboardingStateStore,
  private val accounts: NasAccountStore,
  private val device: DeviceInfoSource,
  private val appVersion: String,
) : LocalDeviceProfileSource {

  override suspend fun load(): LocalDeviceProfile? {
    val snapshot = (store.state.first { it !is StoreState.Loading } as? StoreState.Ready)?.snapshot ?: return null
    val account = accounts.current()
    val user = snapshot.nasUser ?: account?.user ?: return null
    val common = snapshot.commonShare ?: return null
    val description = device.describe(user)
    val roles = buildSet {
      if (snapshot.choices.indexer) add(DeviceRole.INDEXER)
      if (snapshot.choices.preparer) add(DeviceRole.PREPARER)
    }
    return LocalDeviceProfile(
      nasUser = user,
      commonShare = common,
      privateShare = snapshot.privateShare,
      deviceName = description.defaultName,
      deviceModel = description.model,
      roles = roles,
      appVersion = appVersion,
    )
  }
}

/** I ruoli di questo dispositivo (passo 7 dell'onboarding) per il motore delle regole. */
class OnboardingRolesSignal(store: OnboardingStateStore, scope: CoroutineScope) : DeviceRolesSignal {
  override val roles: StateFlow<DeviceRoles> = store.state
    .map { state ->
      val choices = (state as? StoreState.Ready)?.snapshot?.choices ?: OnboardingChoices()
      DeviceRoles(indexer = choices.indexer, preparer = choices.preparer)
    }
    .stateIn(scope, SharingStarted.Eagerly, DeviceRoles())
}

/**
 * Dove finiscono le scelte dell'onboarding che appartengono ad altri moduli: il controllo di WireGuard (passo 11) va
 * in `VpnControlConfigStore` (P2), letto da `WireGuardVpnController` e dall'automazione. Ruoli e sorgenti del backup
 * restano nell'avanzamento salvato, dove li leggono [OnboardingRolesSignal] e (Fase 5) il backup. Idempotente.
 */
class AppOnboardingChoices(private val vpnConfig: VpnControlConfigStore) : OnboardingChoicesSink {
  override suspend fun apply(choices: OnboardingChoices) {
    val wanted = if (choices.remoteControl) VpnControlConfig(enabled = true, tunnelName = choices.tunnelName) else VpnControlConfig.OFF
    // Salvare di nuovo toglie anche una sospensione dell'automazione: solo se la scelta e' davvero cambiata.
    if (vpnConfig.config.value != wanted) vpnConfig.save(wanted)
  }
}

/**
 * L'account NAS per le Impostazioni (P7): chi e', dove sta, dove e' la password. La password va nel Keystore nella
 * voce dell'account; l'indirizzo nuovo nell'archivio dell'account, con lo stesso GUID (E-R7: l'ha gia' verificato la
 * pagina, con il test di connessione).
 */
class AppNasAccountSettings(
  override val account: StateFlow<NasAccount?>,
  private val accounts: NasAccountStore,
  private val credentials: AppCredentialStore,
) : NasAccountSettings {

  override suspend fun savePassword(credentials: NasCredentials): SaveResult {
    val current = accounts.current() ?: return SaveResult.Unavailable
    return this.credentials.save(current.credentialSlot, credentials)
  }

  override suspend fun updateAddress(address: NasAddress) {
    val current = accounts.current() ?: return
    accounts.save(current.copy(address = address))
  }
}

/** Il test di connessione della Diagnostica (P8) prova l'account di questo dispositivo, sull'area comune. */
class AccountConnectionTestInput(
  private val accounts: NasAccountStore,
  private val profiles: LocalDeviceProfileSource,
) : ConnectionTestInputSource {
  override suspend fun current(): ConnectionTestInput? {
    val account = accounts.current() ?: return null
    val share = profiles.load()?.commonShare
    return ConnectionTestInput(TestTarget(account.address, account.user, share, account.pinnedGuid), account.credentialSlot)
  }
}
