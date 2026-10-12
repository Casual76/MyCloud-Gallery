package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.feature.settings.R
import kotlin.time.Duration.Companion.milliseconds

/**
 * Il codice a gruppi di quattro che l'admin digita (il codice di approvazione, il codice di recupero).
 *
 * Chi digita puo' mettere minuscole, spazi e trattini, e scambiare O/0 e I/L/1: il confronto vero lo fa
 * `ApprovalCodes.matches` in `core-trust`. Qui si sistema solo la forma, cosi' il campo e' leggibile mentre
 * si scrive: maiuscole, solo lettere e cifre, un trattino ogni quattro caratteri.
 */
internal object CodeFormat {

  /** Solo lettere e cifre ASCII, in maiuscolo, al massimo [maxChars]. */
  fun normalize(typed: String, maxChars: Int): String =
    typed.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(maxChars)

  /** Il testo da mostrare nel campo: `K7QD-2M9X-FA4T`. */
  fun format(typed: String, maxChars: Int): String = normalize(typed, maxChars).chunked(GROUP).joinToString("-")

  /** Il codice di approvazione: 16 caratteri, `AAAA-BBBB-CCCC-DDDD`. */
  const val APPROVAL_CHARS: Int = 16

  /** Il codice di recupero: il tetto e' largo apposta, il formato vero e' di `core-trust` (sette gruppi). */
  const val RECOVERY_CHARS: Int = 64

  private const val GROUP = 4
}

/** Un nome di account del NAS valido come lo vuole `core-trust` (ASCII, 1-64, solo `.`, `_`, `-` oltre a lettere e cifre). */
internal object NasUserRules {
  private const val MAX_CHARS = 64

  fun isValid(user: String): Boolean =
    user.length in 1..MAX_CHARS &&
      user[0].isAsciiAlnum() &&
      user.all { it.isAsciiAlnum() || it == '.' || it == '_' || it == '-' }

  /** L'account `admin` del NAS non si usa mai nell'app (§8): niente distinzione fra maiuscole e minuscole. */
  fun isAdminAccount(user: String): Boolean = user.equals("admin", ignoreCase = true)

  private fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/** "2 minuti fa", "3 ore fa", "ieri"... da una durata in millisecondi (mai negativa). */
@Composable
internal fun agoText(elapsedMillis: Long): String {
  val d = elapsedMillis.coerceAtLeast(0L).milliseconds
  val minutes = d.inWholeMinutes.toInt()
  val hours = d.inWholeHours.toInt()
  val days = d.inWholeDays.toInt()
  return when {
    minutes < 1 -> stringResource(R.string.ago_now)
    hours < 1 -> pluralStringResource(R.plurals.ago_minutes, minutes, minutes)
    days < 1 -> pluralStringResource(R.plurals.ago_hours, hours, hours)
    else -> pluralStringResource(R.plurals.ago_days, days, days)
  }
}

/**
 * Il messaggio umano di ogni errore del NAS: la colonna "Messaggio" della tabella del §3.4, una riga per
 * `NasError`. Niente testo libero e niente dati: il nome di una share non compare mai.
 */
@StringRes
internal fun NasError.messageRes(): Int = when (this) {
  is NasError.Auth -> reason.messageRes()
  is NasError.AuthBlocked -> reason.messageRes()
  is NasError.InsecureSession -> when (kind) {
    InsecureKind.GUEST, InsecureKind.ANONYMOUS -> R.string.nas_error_guest
    InsecureKind.NO_ENCRYPTION, InsecureKind.DIALECT, InsecureKind.SIGNING_NOT_REQUIRED -> R.string.nas_error_insecure
  }
  NasError.IdentityMismatch -> R.string.nas_error_identity
  NasError.PermissionDenied -> R.string.nas_error_permission
  is NasError.ShareMissing -> R.string.nas_error_share_missing
  NasError.QuotaExceeded -> R.string.nas_error_quota
  NasError.NasFull -> R.string.nas_error_full
  NasError.FileBusy -> R.string.nas_error_busy
  NasError.Exists, NasError.NotFound, NasError.SessionExpired -> R.string.nas_error_generic
  NasError.ServiceDown -> R.string.nas_error_service_down
  NasError.Timeout -> R.string.nas_error_timeout
  is NasError.NotAllowedHere -> reason.messageRes()
  NasError.LocalNetworkBlocked -> R.string.nas_error_local_network
  NasError.Stalled -> R.string.nas_error_stalled
  NasError.CredentialsMissing -> R.string.nas_error_credentials_missing
  is NasError.Unexpected -> R.string.nas_error_generic
}

@StringRes
internal fun AuthBlockReason.messageRes(): Int = when (this) {
  AuthBlockReason.AUTH_FAILED -> R.string.nas_error_auth_failed
  AuthBlockReason.ACCOUNT_LOCKED -> R.string.nas_error_account_locked
  AuthBlockReason.ACCOUNT_DISABLED -> R.string.nas_error_account_disabled
  AuthBlockReason.PASSWORD_EXPIRED -> R.string.nas_error_password_expired
  AuthBlockReason.ACCOUNT_RESTRICTED -> R.string.nas_error_account_restricted
}

/** Perche' il percorso verso il NAS e' negato (§3.4, riga "percorso negato"). */
@StringRes
internal fun DenyReason.messageRes(): Int = when (this) {
  DenyReason.OFFLINE -> R.string.deny_offline
  DenyReason.CAPTIVE_PORTAL -> R.string.deny_captive_portal
  DenyReason.UNTRUSTED_NETWORK -> R.string.deny_untrusted
  DenyReason.VPN_OFF_AWAY -> R.string.deny_vpn_off
  DenyReason.FOREIGN_VPN -> R.string.deny_foreign_vpn
  DenyReason.LOCAL_NETWORK_DENIED -> R.string.nas_error_local_network
  DenyReason.NOT_CONFIGURED -> R.string.deny_not_configured
}
