package dev.pampa.mycloudgallery.core.nas.contract

import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import kotlinx.coroutines.flow.StateFlow

/**
 * Un account del NAS usato da questo dispositivo (o profilo del tablet).
 *
 * @property credentialSlot la voce del `CredentialVault` dove sta la password (mai la password qui).
 * @property pinnedGuid il GUID del NAS letto alla prima NEGOTIATE (D3). Null solo prima del passo 2.
 */
data class NasAccount(
  val address: NasAddress,
  val user: String,
  val credentialSlot: String,
  val pinnedGuid: String?,
) {
  override fun toString(): String = "NasAccount(${address})"
}

/** Cosa ha trovato la sorgente delle credenziali (adattata da :app al `CredentialVault`). */
sealed interface CredentialLoad {
  /** Le credenziali: chi le riceve le chiude con `use { }` appena ha autenticato. */
  class Found(val credentials: NasCredentials) : CredentialLoad {
    override fun toString(): String = "Found(credenziali nascoste)"
  }

  data object Absent : CredentialLoad

  /** Chiave persa o invalidata: serve di nuovo la password (E-S9). */
  data object NeedsPassword : CredentialLoad

  /** Guasto passeggero (telefono appena riavviato e non sbloccato): riprovare piu' tardi. */
  data object Unavailable : CredentialLoad
}

/** Da dove `core-nas` prende la password: `core-nas` non conosce il Keystore, :app fa da ponte. */
fun interface NasCredentialSource {
  suspend fun load(slot: String): CredentialLoad
}

/** Per chi si apre una sessione: decide i tempi (§3.1, "Annullamento") e se si puo' svegliare il NAS. */
enum class Purpose {
  /** L'utente aspetta: 15 s di timeout. */
  INTERACTIVE,

  /** Lavoro automatico: 60 s, e niente risvegli dei dischi senza motivo (E-R15). */
  BACKGROUND,

  /** Onboarding: percorso dedicato (Wi-Fi da cui si e' letto il candidato), un solo tentativo di password. */
  ONBOARDING,
}

/** Perche' si chiude la sessione. */
enum class InvalidateReason {
  NETWORK_CHANGED,
  ROUTE_DENIED,
  IDLE_TIMEOUT,
  PROCESS_STOPPED,
  CREDENTIALS_CHANGED,
  SUSPECT_CONNECTION,
  WIPE,
  USER_REQUEST,
}

/** I dialetti SMB che la Diagnostica sa nominare. Solo [SMB_3_1_1] e' accettato (C3). */
enum class SmbDialect { SMB_3_1_1, SMB_3_0_2, SMB_3_0, SMB_2_1, SMB_2_0_2, UNKNOWN }

/**
 * Cosa si sa della sessione aperta, per la Diagnostica ("cifrata: si'/no") e per l'orologio del NAS.
 *
 * @property serverGuid il GUID della NEGOTIATE, gia' confrontato con quello memorizzato.
 * @property nasTimeOffsetMs l'ora del NAS meno quella del dispositivo, letta alla NEGOTIATE.
 */
data class SessionInfo(
  val dialect: SmbDialect,
  val encrypted: Boolean,
  val cipher: String?,
  val signingRequired: Boolean,
  val serverGuid: String,
  val nasTimeOffsetMs: Long,
  val via: Via,
)

/** Lo stato della connessione al NAS, per l'indicatore globale (§3.5) e la Diagnostica. */
sealed interface NasConnState {
  /** Nessuna sessione aperta (anche: chiusa per inattivita'). */
  data object Idle : NasConnState

  data object Connecting : NasConnState

  /** TCP rapida ma i dischi non rispondono ancora: "Risveglio del NAS…" (E-R1). [since] = orologio monotono. */
  data class WakingUp(val since: Long) : NasConnState

  data class Ready(val info: SessionInfo) : NasConnState

  /** Percorso consentito ma il NAS non risponde, o un altro errore non d'autenticazione. */
  data class Unavailable(val error: NasError) : NasConnState

  /** L'interruttore e' aperto: "Serve la password del NAS". */
  data class AuthBlocked(val reason: AuthBlockReason) : NasConnState
}

/**
 * Cio' che un lavoro riceve dentro [NasSessionManager.withShare]: la share gia' connessa. Il lavoro NON
 * chiude mai share o sessione (lo fa solo il gestore, §3.1 "Pool").
 */
interface NasShareScope {
  val shareName: String

  /** Le operazioni di core-trust (lettura difensiva, pubblicazione esclusiva). Bloccanti: si e' gia' su I/O. */
  val fileSystem: NasFileSystem

  val browser: NasBrowser
}

/**
 * La sessione verso il NAS (P1, §3). Una `Connection` e una `Session` per account, riusate; un solo connect
 * alla volta; mai due `authenticate` in parallelo; controlli di sicurezza nell'ordine del §3.1.
 */
interface NasSessionManager {
  val state: StateFlow<NasConnState>

  /** L'ultima volta (orologio monotono) che un'operazione ha toccato i dischi: i lavori leggeri ne approfittano. */
  val lastDiskTouchAt: StateFlow<Long?>

  /**
   * Esegue [block] con la share [share] connessa. Se il percorso e' negato, l'interruttore aperto o la
   * sessione insicura, [block] non parte e il risultato e' l'errore.
   */
  suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T>

  /** Il `NasFileSystem` di una share per `core-trust` (gli errori arrivano come [NasIoException]). */
  fun fileSystem(share: String): NasFileSystem

  fun browser(share: String): NasBrowser

  /**
   * La lettura dei media del NAS (Fase 4, D2) sopra questa sessione: stessi controlli del §3.1, stesso limitatore, stessa
   * recinzione D33. Aggiunta di P1 con un default (nessun NAS, nessuna lettura) perche' le sessioni finte gia' scritte
   * dagli altri moduli restino valide; l'implementazione SMB la ridefinisce.
   */
  fun mediaReader(): NasMediaReader = dev.pampa.mycloudgallery.core.nas.noop.NoNasMediaReader

  suspend fun invalidate(reason: InvalidateReason)
}

/** Lo stato dell'interruttore di sicurezza (§3.3), persistente, con la generazione della credenziale. */
sealed interface BreakerState {
  val generation: Long

  data class Closed(override val generation: Long) : BreakerState

  /** [at] = ora del dispositivo (ms) in cui si e' aperto. */
  data class Open(val reason: AuthBlockReason, val at: Long, override val generation: Long) : BreakerState
}

/**
 * L'interruttore di sicurezza sull'autenticazione (E-R8, E-S12, C5): si apre al primo errore
 * d'autenticazione e si chiude solo quando l'utente salva una password nuova ([onNewPasswordSaved], che
 * aumenta la generazione). Test obbligatorio di P1: 10 coroutine e 2 lavori con la password sbagliata
 * producono esattamente 1 `authenticate`, anche dopo il riavvio del processo.
 */
interface AuthCircuitBreaker {
  val state: StateFlow<BreakerState>

  suspend fun onNewPasswordSaved()
}
