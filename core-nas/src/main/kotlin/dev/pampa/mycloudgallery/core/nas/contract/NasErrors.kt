package dev.pampa.mycloudgallery.core.nas.contract

import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import java.io.IOException

/**
 * Perche' l'interruttore di sicurezza si e' aperto: i cinque errori d'autenticazione del §3.4. Al primo di
 * questi nessun chiamante arriva piu' ad `authenticate` finche' l'utente non salva una password nuova.
 */
enum class AuthBlockReason {
  /** `0xC000006D` LOGON_FAILURE, `0xC000006A`: la password non e' piu' valida (E-R8). */
  AUTH_FAILED,

  /** `0xC0000234` LOCKED_OUT: l'account va sbloccato dalla dashboard del NAS (E-S12). */
  ACCOUNT_LOCKED,

  /** `0xC0000072`: account disattivato. */
  ACCOUNT_DISABLED,

  /** `0xC0000071`, `0xC0000224`: password scaduta. */
  PASSWORD_EXPIRED,

  /** `0xC000006E/6F/70`: il NAS non permette l'accesso a questo account adesso. */
  ACCOUNT_RESTRICTED,
}

/** Che cosa rende insicura una sessione: la si chiude subito, nessun dato inviato (C3). */
enum class InsecureKind { GUEST, ANONYMOUS, NO_ENCRYPTION, DIALECT, SIGNING_NOT_REQUIRED }

/**
 * Gli errori della sessione NAS, uno per riga della tabella del §3.4. Ognuno ha il suo messaggio umano
 * (stringhe in italiano nel modulo che lo mostra). Niente testo libero dentro: il nome di una share e' un
 * dato dell'utente, quindi [ShareMissing] lo tiene per lo schermo ma `toString` non lo stampa (§4.2).
 */
sealed interface NasError {

  /** Apre l'interruttore di sicurezza (§3.3)? Vale solo per gli errori d'autenticazione. */
  val opensBreaker: Boolean get() = false

  /** Uno dei cinque errori d'autenticazione. */
  data class Auth(val reason: AuthBlockReason) : NasError {
    override val opensBreaker: Boolean get() = true
  }

  /** Guest, anonimo, senza cifratura, dialetto diverso da 3.1.1: rifiutato, nessun dato inviato. */
  data class InsecureSession(val kind: InsecureKind) : NasError

  /** Il GUID della NEGOTIATE non e' quello memorizzato: nessuna password inviata (E-R7, E-S10, D3). */
  data object IdentityMismatch : NasError

  /** `0xC0000022`: niente permesso per questa cartella (E-R10, E-N9). */
  data object PermissionDenied : NasError

  /** `0xC00000CC`: la share non esiste piu' (E-R10). */
  class ShareMissing(val share: String) : NasError {
    override fun equals(other: Any?): Boolean = other is ShareMissing && other.share == share

    override fun hashCode(): Int = share.hashCode()

    override fun toString(): String = "ShareMissing"
  }

  /** `0xC000007F` con spazio libero globale: quota dell'account esaurita (E-N13). */
  data object QuotaExceeded : NasError

  /** `0xC000007F` con NAS pieno: backup in pausa, la consultazione continua (E-R9). */
  data object NasFull : NasError

  /** `0xC0000043`, `0xC0000054`: file in uso dal PC, si riprova piu' tardi (E-N13). */
  data object FileBusy : NasError

  /** `0xC0000035`: esiste gia'. Non e' un vero errore: con gli stessi byte vale come successo (E-B3). */
  data object Exists : NasError

  /** `0xC0000034`, `0xC000003A`. */
  data object NotFound : NasError

  /** `0xC0000203`, `0xC000035C`: sessione scaduta, riconnessione silenziosa. */
  data object SessionExpired : NasError

  /** TCP rifiutata: il NAS e' acceso ma la condivisione file non risponde. */
  data object ServiceDown : NasError

  /** Nessuna risposta in tempo (forse si sta risvegliando, E-R1). */
  data object Timeout : NasError

  /** Percorso negato dal cancello di rete (E-S10, E-R3, E-S16). */
  data class NotAllowedHere(val reason: DenyReason) : NasError

  /** Android non permette all'app di usare la rete locale: si dice PRIMA di "NAS spento" (android.md 7.3). */
  data object LocalNetworkBlocked : NasError

  /** Trasferimento bloccato senza errori (watchdog, E-R14). */
  data object Stalled : NasError

  /** L'interruttore e' aperto: nessuna autenticazione tentata. */
  data class AuthBlocked(val reason: AuthBlockReason) : NasError

  /** Nessuna credenziale salvata, o va reinserita (E-S9). */
  data object CredentialsMissing : NasError

  /** Un codice di stato SMB non previsto dalla tabella (per la Diagnostica: solo il numero). */
  data class Unexpected(val ntStatus: Long?) : NasError
}

/** L'esito di un'operazione sul NAS: il valore o l'errore. Nessuna eccezione verso chi chiama. */
sealed interface NasResult<out T> {
  data class Ok<T>(val value: T) : NasResult<T>

  data class Err(val error: NasError) : NasResult<Nothing>
}

/** Il valore, o null se e' un errore. */
fun <T> NasResult<T>.okOrNull(): T? = (this as? NasResult.Ok<T>)?.value

/** L'errore, o null se e' andata bene. */
fun NasResult<*>.errorOrNull(): NasError? = (this as? NasResult.Err)?.error

/**
 * L'errore NAS dentro un'eccezione, per le API bloccanti di `core-trust` (`NasFileSystem`), che non hanno un
 * tipo di ritorno per gli errori. Il messaggio e' fisso: niente nomi o percorsi.
 */
class NasIoException(val error: NasError) : IOException("errore NAS: ${error::class.simpleName}")
