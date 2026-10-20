package dev.pampa.mycloudgallery.core.family.onboarding

import android.content.Context
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.setup.NasAccountStore
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.security.credentials.CredentialVault
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.device.ScreenLockCheck
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.security.SecureRandom

/**
 * Salva (e toglie) le credenziali del NAS. `CredentialVault` e' una classe chiusa con un solo punto d'ingresso
 * (`getInstance`): questa e' la porta che l'onboarding vede, cosi' si prova senza Keystore. Nel telefono vero e'
 * [VaultCredentialSaver].
 */
interface CredentialSaver {
  suspend fun save(slot: String, credentials: NasCredentials): SaveResult

  suspend fun remove(slot: String)
}

class VaultCredentialSaver(private val vault: CredentialVault) : CredentialSaver {
  override suspend fun save(slot: String, credentials: NasCredentials): SaveResult = vault.save(slot, credentials)

  override suspend fun remove(slot: String) = vault.remove(slot)
}

/** Il blocco schermo del telefono (E-S3): in produzione e' `ScreenLockCheck`, che vuole un `Context`. */
fun interface ScreenLockSource {
  fun status(): ScreenLockStatus
}

class AndroidScreenLockSource(private val context: Context) : ScreenLockSource {
  override fun status(): ScreenLockStatus = ScreenLockCheck.status(context)
}

/**
 * Come si presenta questo dispositivo nel registro di famiglia.
 *
 * @property model il modello (`Build.MODEL`), un testo da mostrare.
 * @property defaultName il nome proposto ("Telefono di Mario" o "Tablet - profilo ..."), che l'utente puo' cambiare
 *   dove gli si chiede (ingresso con invito); il fondatore lo prende cosi' com'e'.
 */
data class DeviceDescription(val model: String, val defaultName: String)

fun interface DeviceInfoSource {
  fun describe(nasUser: String): DeviceDescription
}

/**
 * Dove l'onboarding consegna le scelte che appartengono ad altri moduli o ad altre fasi (sorgenti del backup,
 * ruoli, controllo di WireGuard): le ha scelte l'utente, le applica chi le conosce. Si chiama dopo ogni passo
 * che le cambia e alla fine, con tutte le scelte: chi implementa deve essere idempotente. Il preset e la
 * qualita' invece li applica l'onboarding stesso a `RuleSettingsStore`.
 */
fun interface OnboardingChoicesSink {
  suspend fun apply(choices: OnboardingChoices)
}

/** Nessuno applica le scelte: restano salvate con l'avanzamento (le legge chi le usa). */
object IgnoreOnboardingChoices : OnboardingChoicesSink {
  override suspend fun apply(choices: OnboardingChoices) = Unit
}

/**
 * I dati locali di un'altra persona (R6 della revisione 5B): dopo "Configura di nuovo" con un account o un NAS diverso, alla
 * fine dell'onboarding e prima che la Libreria si apra, il telefono toglie cio' che era del vecchio account (catalogo dei
 * solo-NAS, dati personali, miniature e anteprime, diari, cache). Mai le foto del telefono, mai il NAS. Lo implementa `:app`
 * con l'azzeramento (`LocalDataEraser`) senza le fasi che toccano l'onboarding in corso.
 */
fun interface PreviousAccountData {
  /** Vero se tutto e' stato tolto; falso = la fine dell'onboarding si ferma e si riprova. */
  suspend fun erase(): Boolean
}

/** Nessuno toglie niente (i test che non lo provano): i dati restano. */
object KeepPreviousAccountData : PreviousAccountData {
  override suspend fun erase(): Boolean = true
}

/**
 * Tutto cio' che [DefaultOnboardingCoordinator] e [dev.pampa.mycloudgallery.core.family.invite.DefaultJoinFlow]
 * usano degli altri moduli, in un posto solo (`:app` lo costruisce una volta, i test con i fake).
 *
 * @property nasClock l'ora del NAS (`SessionNasClock`): data e firme del registro, scadenza degli inviti.
 * @property deviceNow l'ora del dispositivo (ms): solo per misurare i tempi dell'onboarding, mai per decidere.
 * @property allowUserRetry riapre l'interruttore di un account preciso dopo una password nuova scritta dall'utente
 *   ([reopenBreakerFor]); lo collega `:app` (P9) al registro degli interruttori di `core-nas`.
 * @property previousData toglie i dati locali del vecchio account quando chi rientra e' un altro (R6, [PreviousAccountData]).
 * @property afterStartOver si chiama alla fine di "Configura di nuovo", a tutto salvato: `:app` ci riavvia il processo se la
 *   galleria era gia' partita (non riparte nello stesso processo, come per l'azzeramento, ON-11) e il catalogo ha ancora il
 *   proprietario di prima. Nei test non fa niente.
 */
class OnboardingPorts(
  val homeCapture: HomeCapture,
  val homeFingerprints: HomeFingerprintStore,
  val discovery: NasDiscovery,
  val network: NetworkEnvironment,
  val localNetwork: LocalNetworkPermission,
  val tester: ConnectionTester,
  val shareProbe: ShareProbe,
  val sessions: NasSessionManager,
  val breaker: AuthCircuitBreaker,
  val credentials: CredentialSaver,
  val accounts: NasAccountStore,
  val trustState: TrustStateStore,
  val registry: RegistryService,
  val deviceKeys: KeyProvider,
  val familyKeys: FamilyKeyVault,
  val rules: RuleSettingsStore,
  val health: BackgroundHealthReader,
  val screenLock: ScreenLockSource,
  val device: DeviceInfoSource,
  val choices: OnboardingChoicesSink = IgnoreOnboardingChoices,
  val nasClock: NasClock,
  val deviceNow: () -> Long = System::currentTimeMillis,
  val random: SecureRandom = SecureRandom(),
  val allowUserRetry: (suspend (NasAccount) -> Unit)? = null,
  val previousData: PreviousAccountData = KeepPreviousAccountData,
  val afterStartOver: () -> Unit = {},
  /**
   * La recinzione delle prove (D33, `ShareFence` in debug, null in release): le stesse regole della sessione. Al passo 4 serve solo a dire
   * "esclusa per l'account di prova" invece di "accesso negato" (la sessione la applica comunque prima di qualunque richiesta).
   */
  val shareGuard: ShareGuard? = null,
) {

  /**
   * L'utente ha scritto una password nuova per [account] (passo 3 o ingresso): l'interruttore di sicurezza **di
   * quell'account** (la chiave e' `(GUID del NAS, utente)`, la stessa che usa il test di connessione), se aperto da un
   * tentativo precedente, si richiude per un solo tentativo (E-R8: "Riprova" solo su azione dell'utente).
   *
   * [breaker] da solo non basta: e' l'interruttore dell'account **salvato**, che al passo 3 non c'e' ancora (o e' un
   * altro). Senza [allowUserRetry] (i test con il fake) si usa [breaker] come prima.
   */
  internal suspend fun reopenBreakerFor(account: NasAccount) {
    val retry = allowUserRetry
    if (retry != null) retry(account) else if (breaker.state.value is BreakerState.Open) breaker.onNewPasswordSaved()
  }
}
