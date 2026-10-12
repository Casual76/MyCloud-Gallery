package dev.pampa.mycloudgallery.core.nas

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import java.util.Locale

/**
 * Il messaggio per l'utente di ogni [NasError] (tabella del §3.4, colonna "Messaggio"), dalle risorse del modulo.
 * `Exists`, `NotFound` e `SessionExpired` non hanno messaggio: non sono errori da mostrare (null).
 */
object NasErrorMessages {

  @StringRes
  fun messageRes(error: NasError): Int? = when (error) {
    is NasError.Auth -> authRes(error.reason)
    is NasError.AuthBlocked -> authRes(error.reason)
    is NasError.InsecureSession -> when (error.kind) {
      InsecureKind.GUEST, InsecureKind.ANONYMOUS -> R.string.nas_error_insecure_guest
      InsecureKind.NO_ENCRYPTION, InsecureKind.DIALECT, InsecureKind.SIGNING_NOT_REQUIRED -> R.string.nas_error_insecure_no_encryption
    }
    NasError.IdentityMismatch -> R.string.nas_error_identity_mismatch
    NasError.PermissionDenied -> R.string.nas_error_permission_denied
    is NasError.ShareMissing -> R.string.nas_error_share_missing
    NasError.QuotaExceeded -> R.string.nas_error_quota_exceeded
    NasError.NasFull -> R.string.nas_error_nas_full
    NasError.FileBusy -> R.string.nas_error_file_busy
    NasError.ServiceDown -> R.string.nas_error_service_down
    NasError.Timeout -> R.string.nas_error_timeout
    is NasError.NotAllowedHere -> deniedRes(error.reason)
    NasError.LocalNetworkBlocked -> R.string.nas_error_local_network_blocked
    NasError.Stalled -> R.string.nas_error_stalled
    NasError.CredentialsMissing -> R.string.nas_error_credentials_missing
    is NasError.Unexpected -> if (error.ntStatus != null) R.string.nas_error_unexpected else R.string.nas_error_unexpected_no_code
    NasError.Exists, NasError.NotFound, NasError.SessionExpired -> null
  }

  /** Il testo pronto (null se l'errore non si mostra). Il nome della share compare solo qui, a schermo. */
  fun format(resources: Resources, error: NasError): String? {
    val res = messageRes(error) ?: return null
    return when (error) {
      is NasError.ShareMissing -> resources.getString(res, error.share)
      is NasError.Unexpected -> error.ntStatus?.let { resources.getString(res, "0x%08X".format(Locale.ROOT, it)) } ?: resources.getString(res)
      else -> resources.getString(res)
    }
  }

  @StringRes
  private fun authRes(reason: AuthBlockReason): Int = when (reason) {
    AuthBlockReason.AUTH_FAILED -> R.string.nas_error_auth_failed
    AuthBlockReason.ACCOUNT_LOCKED -> R.string.nas_error_account_locked
    AuthBlockReason.ACCOUNT_DISABLED -> R.string.nas_error_account_disabled
    AuthBlockReason.PASSWORD_EXPIRED -> R.string.nas_error_password_expired
    AuthBlockReason.ACCOUNT_RESTRICTED -> R.string.nas_error_account_restricted
  }

  @StringRes
  private fun deniedRes(reason: DenyReason): Int = when (reason) {
    DenyReason.VPN_OFF_AWAY -> R.string.nas_error_vpn_off_away
    DenyReason.UNTRUSTED_NETWORK -> R.string.nas_error_untrusted_network
    DenyReason.FOREIGN_VPN -> R.string.nas_error_foreign_vpn
    DenyReason.OFFLINE -> R.string.nas_error_offline
    DenyReason.CAPTIVE_PORTAL -> R.string.nas_error_captive_portal
    DenyReason.NOT_CONFIGURED -> R.string.nas_error_not_configured
    DenyReason.LOCAL_NETWORK_DENIED -> R.string.nas_error_local_network_blocked
  }
}
