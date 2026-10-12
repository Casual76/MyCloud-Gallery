package dev.pampa.mycloudgallery.core.nas.session

import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbCreditsExhaustedException
import dev.pampa.mycloudgallery.core.nas.smb.SmbGuestRejectedException
import dev.pampa.mycloudgallery.core.nas.smb.SmbNegotiationException
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTimeoutException
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** Che cosa fare dopo un errore di un'operazione SMB (§3.1, righe "Riconnessione" e "Annullamento"). */
internal enum class FailureKind {
  /** Connessione caduta o sessione scaduta: si scarta e, se l'operazione e' idempotente, si riprova. */
  RECONNECT,

  /** Nessuna risposta in tempo: la connessione e' sospetta, si chiude, nessun nuovo tentativo. */
  SUSPECT,

  /** Crediti SMB esauriti: si aspetta e si riprova sulla stessa connessione (limitatore). */
  CREDITS,

  /** Un esito definitivo del server (permesso negato, non trovato, pieno...): la connessione resta buona. */
  FINAL,
}

internal class Failure(val error: NasError, val kind: FailureKind) {
  override fun toString(): String = "Failure($error, $kind)"
}

/** Un errore gia' tradotto, lanciato dal codice delle operazioni per uscire con quel [NasError]. */
internal class NasOpException(val error: NasError, val kind: FailureKind = FailureKind.FINAL) :
  IOException("errore NAS: ${error::class.simpleName}")

/**
 * La tabella del §3.4 in un posto solo: codice NTSTATUS o eccezione del trasporto → [NasError]. I messaggi
 * per l'utente sono in `NasErrorMessages` (risorse del modulo).
 */
internal object SmbErrors {

  /** I cinque errori d'autenticazione che aprono l'interruttore; null per ogni altro codice. */
  fun authReason(status: Long): AuthBlockReason? = when (status) {
    NtStatus.LOGON_FAILURE, NtStatus.WRONG_PASSWORD -> AuthBlockReason.AUTH_FAILED
    NtStatus.ACCOUNT_LOCKED_OUT -> AuthBlockReason.ACCOUNT_LOCKED
    NtStatus.ACCOUNT_DISABLED -> AuthBlockReason.ACCOUNT_DISABLED
    NtStatus.PASSWORD_EXPIRED, NtStatus.PASSWORD_MUST_CHANGE -> AuthBlockReason.PASSWORD_EXPIRED
    NtStatus.ACCOUNT_RESTRICTION, NtStatus.INVALID_LOGON_HOURS, NtStatus.INVALID_WORKSTATION -> AuthBlockReason.ACCOUNT_RESTRICTED
    else -> null
  }

  /**
   * Un codice di stato. `DISK_FULL` diventa [NasError.NasFull] finche' non si sa altro: chi puo' chiama
   * [diskFull] con lo spazio letto dalla share (solo dopo un `DISK_FULL`, perche' tocca i dischi).
   */
  fun fromStatus(status: Long, share: String?): NasError {
    authReason(status)?.let { return NasError.Auth(it) }
    return when (status) {
      NtStatus.ACCESS_DENIED, NtStatus.MEDIA_WRITE_PROTECTED -> NasError.PermissionDenied
      NtStatus.BAD_NETWORK_NAME -> NasError.ShareMissing(share.orEmpty())
      NtStatus.DISK_FULL -> NasError.NasFull
      NtStatus.SHARING_VIOLATION, NtStatus.FILE_LOCK_CONFLICT -> NasError.FileBusy
      NtStatus.OBJECT_NAME_COLLISION -> NasError.Exists
      NtStatus.OBJECT_NAME_NOT_FOUND, NtStatus.OBJECT_PATH_NOT_FOUND, NtStatus.NO_SUCH_FILE -> NasError.NotFound
      NtStatus.USER_SESSION_DELETED, NtStatus.NETWORK_SESSION_EXPIRED -> NasError.SessionExpired
      else -> NasError.Unexpected(status)
    }
  }

  /** `0xC000007F`: quota dell'account esaurita se il volume ha ancora spazio, altrimenti NAS pieno (E-N13, E-R9). */
  fun diskFull(space: SmbSpace?): NasError =
    if (space != null && space.totalFreeBytes > 0) NasError.QuotaExceeded else NasError.NasFull

  /** Il percorso negato: "rete locale negata" ha il suo errore, controllato prima di "NAS spento" (android.md 7.3). */
  fun denied(reason: DenyReason): NasError =
    if (reason == DenyReason.LOCAL_NETWORK_DENIED) NasError.LocalNetworkBlocked else NasError.NotAllowedHere(reason)

  /**
   * Qualunque errore di un'operazione. Gli errori di programmazione (non I/O) non si nascondono: si rilanciano.
   */
  fun classify(t: Throwable, share: String?): Failure = when {
    // Android 17 (target 37) senza ACCESS_LOCAL_NETWORK: la TCP di solito scade, ma un EPERM esplicito si dice
    // per quello che e', prima di "NAS spento" (android.md 7.3). Il cancello di rete lo controlla gia' prima.
    t is IOException && t.mentionsEperm() -> Failure(NasError.LocalNetworkBlocked, FailureKind.FINAL)
    else -> classifyType(t, share)
  }

  private fun Throwable.mentionsEperm(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSES) {
      if (current.message?.contains("EPERM") == true) return true
      current = current.cause
      depth++
    }
    return false
  }

  private const val MAX_CAUSES = 8

  private fun classifyType(t: Throwable, share: String?): Failure = when (t) {
    is NasOpException -> Failure(t.error, t.kind)
    is NasIoException -> Failure(t.error, FailureKind.FINAL)
    is SmbStatusException -> when (t.status) {
      NtStatus.USER_SESSION_DELETED, NtStatus.NETWORK_SESSION_EXPIRED -> Failure(NasError.SessionExpired, FailureKind.RECONNECT)
      else -> Failure(fromStatus(t.status, share), FailureKind.FINAL)
    }
    is SmbGuestRejectedException -> Failure(NasError.InsecureSession(InsecureKind.GUEST), FailureKind.FINAL)
    is SmbNegotiationException -> Failure(NasError.InsecureSession(InsecureKind.DIALECT), FailureKind.FINAL)
    is SmbCreditsExhaustedException -> Failure(NasError.Timeout, FailureKind.CREDITS)
    is SmbTimeoutException, is SocketTimeoutException -> Failure(NasError.Timeout, FailureKind.SUSPECT)
    is ConnectException -> Failure(NasError.ServiceDown, FailureKind.FINAL)
    is NoRouteToHostException -> Failure(NasError.Timeout, FailureKind.FINAL)
    is InterruptedIOException -> Failure(NasError.Timeout, FailureKind.SUSPECT)
    is SmbConnectionLostException -> Failure(NasError.Timeout, FailureKind.RECONNECT)
    is IOException -> Failure(NasError.Timeout, FailureKind.RECONNECT)
    else -> throw t
  }
}
