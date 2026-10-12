package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.feature.diagnostics.R

/**
 * Dai tipi dei contratti (errori del NAS, motivi di attesa delle regole) ai codici del catalogo e ai testi.
 * E' la tabella del §3.4 di docs/fase2-progetto.md: «Stato SMB → NasError → Messaggio».
 *
 * Le `when` sui tipi che i moduli in parallelo possono ampliare ([NasError], [DenyReason]) hanno un ripiego
 * (`NAS_UNEXPECTED`, `NAS_NOT_ALLOWED_HERE`): un caso nuovo non rompe questo modulo, finisce nel codice
 * generico finche' non lo si aggiunge qui. [WaitReason] e' di `core-rules` e non cambia in parallelo, quindi la
 * sua `when` e' esaustiva (il compilatore la controlla).
 */
internal object DiagMapping {

  // --- Errori del NAS ---------------------------------------------------------------------------------

  fun code(error: NasError): DiagCode = when (error) {
    is NasError.Auth -> authCode(error.reason)
    is NasError.AuthBlocked -> DiagCodes.NAS_AUTH_BLOCKED
    is NasError.InsecureSession -> when (error.kind) {
      InsecureKind.GUEST, InsecureKind.ANONYMOUS -> DiagCodes.NAS_GUEST_REFUSED
      InsecureKind.NO_ENCRYPTION -> DiagCodes.NAS_NOT_ENCRYPTED
      InsecureKind.DIALECT -> DiagCodes.NAS_DIALECT_REFUSED
      InsecureKind.SIGNING_NOT_REQUIRED -> DiagCodes.NAS_SIGNING_MISSING
    }
    NasError.IdentityMismatch -> DiagCodes.NAS_IDENTITY_MISMATCH
    NasError.PermissionDenied -> DiagCodes.NAS_PERMISSION_DENIED
    is NasError.ShareMissing -> DiagCodes.NAS_SHARE_MISSING
    NasError.QuotaExceeded -> DiagCodes.NAS_QUOTA_EXCEEDED
    NasError.NasFull -> DiagCodes.NAS_FULL
    NasError.FileBusy -> DiagCodes.NAS_FILE_BUSY
    NasError.NotFound -> DiagCodes.NAS_NOT_FOUND
    NasError.SessionExpired -> DiagCodes.NAS_SESSION_EXPIRED
    NasError.ServiceDown -> DiagCodes.NAS_SERVICE_DOWN
    NasError.Timeout -> DiagCodes.NAS_TIMEOUT
    // Il motivo del divieto dice di piu' che «percorso negato»: la voce e' quella della rete.
    is NasError.NotAllowedHere -> code(error.reason)
    NasError.LocalNetworkBlocked -> DiagCodes.NET_LOCAL_NETWORK_DENIED
    NasError.Stalled -> DiagCodes.NAS_STALLED
    NasError.CredentialsMissing -> DiagCodes.NAS_CREDENTIALS_MISSING
    else -> DiagCodes.NAS_UNEXPECTED // anche `Exists`: non e' un errore, qui non dovrebbe arrivare
  }

  @Suppress("REDUNDANT_ELSE_IN_WHEN") // il ripiego e' per i motivi che P2 aggiungesse domani
  fun code(reason: DenyReason): DiagCode = when (reason) {
    DenyReason.OFFLINE -> DiagCodes.NET_OFFLINE
    DenyReason.CAPTIVE_PORTAL -> DiagCodes.NET_CAPTIVE_PORTAL
    DenyReason.UNTRUSTED_NETWORK -> DiagCodes.NET_UNTRUSTED
    DenyReason.VPN_OFF_AWAY -> DiagCodes.NET_VPN_OFF_AWAY
    DenyReason.FOREIGN_VPN -> DiagCodes.NET_FOREIGN_VPN
    DenyReason.LOCAL_NETWORK_DENIED -> DiagCodes.NET_LOCAL_NETWORK_DENIED
    DenyReason.NOT_CONFIGURED -> DiagCodes.NET_NOT_CONFIGURED
    else -> DiagCodes.NAS_NOT_ALLOWED_HERE
  }

  fun authCode(reason: AuthBlockReason): DiagCode = when (reason) {
    AuthBlockReason.AUTH_FAILED -> DiagCodes.NAS_AUTH_FAILED
    AuthBlockReason.ACCOUNT_LOCKED -> DiagCodes.NAS_ACCOUNT_LOCKED
    AuthBlockReason.ACCOUNT_DISABLED -> DiagCodes.NAS_ACCOUNT_DISABLED
    AuthBlockReason.PASSWORD_EXPIRED -> DiagCodes.NAS_PASSWORD_EXPIRED
    AuthBlockReason.ACCOUNT_RESTRICTED -> DiagCodes.NAS_ACCOUNT_RESTRICTED
  }

  /** Il messaggio umano (§3.4), per lo schermo. Con [DiagText] perche' il nome di una share e' un dato dell'utente. */
  fun message(error: NasError): DiagText = when (error) {
    is NasError.Auth -> DiagText(authMessage(error.reason))
    is NasError.AuthBlocked -> DiagText(R.string.diagnostics_nas_auth_blocked)
    is NasError.InsecureSession -> DiagText(
      when (error.kind) {
        InsecureKind.GUEST, InsecureKind.ANONYMOUS -> R.string.diagnostics_nas_insecure_guest
        InsecureKind.NO_ENCRYPTION, InsecureKind.DIALECT -> R.string.diagnostics_nas_insecure_encryption
        InsecureKind.SIGNING_NOT_REQUIRED -> R.string.diagnostics_nas_insecure_signing
      },
    )
    NasError.IdentityMismatch -> DiagText(R.string.diagnostics_nas_identity_mismatch)
    NasError.PermissionDenied -> DiagText(R.string.diagnostics_nas_permission_denied)
    is NasError.ShareMissing -> DiagText(R.string.diagnostics_nas_share_missing, listOf(error.share))
    NasError.QuotaExceeded -> DiagText(R.string.diagnostics_nas_quota_exceeded)
    NasError.NasFull -> DiagText(R.string.diagnostics_nas_full)
    NasError.FileBusy -> DiagText(R.string.diagnostics_nas_file_busy)
    NasError.NotFound -> DiagText(R.string.diagnostics_nas_not_found)
    NasError.SessionExpired -> DiagText(R.string.diagnostics_nas_session_expired)
    NasError.ServiceDown -> DiagText(R.string.diagnostics_nas_service_down)
    NasError.Timeout -> DiagText(R.string.diagnostics_nas_timeout)
    is NasError.NotAllowedHere -> DiagText(deniedMessage(error.reason))
    NasError.LocalNetworkBlocked -> DiagText(R.string.diagnostics_nas_local_network_blocked)
    NasError.Stalled -> DiagText(R.string.diagnostics_nas_stalled)
    NasError.CredentialsMissing -> DiagText(R.string.diagnostics_nas_credentials_missing)
    is NasError.Unexpected -> DiagText(
      R.string.diagnostics_nas_unexpected,
      listOf(error.ntStatus?.let { "0x%08X".format(it) } ?: "?"),
    )
    else -> DiagText(R.string.diagnostics_nas_unexpected, listOf("?"))
  }

  private fun authMessage(reason: AuthBlockReason): Int = when (reason) {
    AuthBlockReason.AUTH_FAILED -> R.string.diagnostics_nas_auth_failed
    AuthBlockReason.ACCOUNT_LOCKED -> R.string.diagnostics_nas_account_locked
    AuthBlockReason.ACCOUNT_DISABLED -> R.string.diagnostics_nas_account_disabled
    AuthBlockReason.PASSWORD_EXPIRED -> R.string.diagnostics_nas_password_expired
    AuthBlockReason.ACCOUNT_RESTRICTED -> R.string.diagnostics_nas_account_restricted
  }

  @Suppress("REDUNDANT_ELSE_IN_WHEN")
  private fun deniedMessage(reason: DenyReason): Int = when (reason) {
    DenyReason.OFFLINE -> R.string.diagnostics_denied_offline
    DenyReason.CAPTIVE_PORTAL -> R.string.diagnostics_denied_captive_portal
    DenyReason.UNTRUSTED_NETWORK -> R.string.diagnostics_denied_untrusted
    DenyReason.VPN_OFF_AWAY -> R.string.diagnostics_denied_vpn_off
    DenyReason.FOREIGN_VPN -> R.string.diagnostics_denied_foreign_vpn
    DenyReason.LOCAL_NETWORK_DENIED -> R.string.diagnostics_nas_local_network_blocked
    DenyReason.NOT_CONFIGURED -> R.string.diagnostics_denied_not_configured
    else -> R.string.diagnostics_denied_generic
  }

  /** Il tipo dell'errore come costante, per l'esportazione (un errore e' una classe sigillata, non un enum). */
  @Suppress("REDUNDANT_ELSE_IN_WHEN")
  fun kind(error: NasError): NasErrorKind = when (error) {
    is NasError.Auth -> NasErrorKind.AUTH
    is NasError.AuthBlocked -> NasErrorKind.AUTH_BLOCKED
    is NasError.InsecureSession -> NasErrorKind.INSECURE_SESSION
    NasError.IdentityMismatch -> NasErrorKind.IDENTITY_MISMATCH
    NasError.PermissionDenied -> NasErrorKind.PERMISSION_DENIED
    is NasError.ShareMissing -> NasErrorKind.SHARE_MISSING
    NasError.QuotaExceeded -> NasErrorKind.QUOTA_EXCEEDED
    NasError.NasFull -> NasErrorKind.NAS_FULL
    NasError.FileBusy -> NasErrorKind.FILE_BUSY
    NasError.Exists -> NasErrorKind.EXISTS
    NasError.NotFound -> NasErrorKind.NOT_FOUND
    NasError.SessionExpired -> NasErrorKind.SESSION_EXPIRED
    NasError.ServiceDown -> NasErrorKind.SERVICE_DOWN
    NasError.Timeout -> NasErrorKind.TIMEOUT
    is NasError.NotAllowedHere -> NasErrorKind.NOT_ALLOWED_HERE
    NasError.LocalNetworkBlocked -> NasErrorKind.LOCAL_NETWORK_BLOCKED
    NasError.Stalled -> NasErrorKind.STALLED
    NasError.CredentialsMissing -> NasErrorKind.CREDENTIALS_MISSING
    is NasError.Unexpected -> NasErrorKind.UNEXPECTED
    else -> NasErrorKind.OTHER
  }
}

/** Il tipo di un `NasError` come costante di un enum: l'unica forma in cui puo' entrare nell'esportazione. */
internal enum class NasErrorKind {
  AUTH, AUTH_BLOCKED, INSECURE_SESSION, IDENTITY_MISMATCH, PERMISSION_DENIED, SHARE_MISSING, QUOTA_EXCEEDED, NAS_FULL,
  FILE_BUSY, EXISTS, NOT_FOUND, SESSION_EXPIRED, SERVICE_DOWN, TIMEOUT, NOT_ALLOWED_HERE, LOCAL_NETWORK_BLOCKED,
  STALLED, CREDENTIALS_MISSING, UNEXPECTED, OTHER,
}

/**
 * Perche' un'attivita' non gira, come costante di un enum (per l'esportazione) e come codice del catalogo. Una
 * riga per ogni sottotipo di [WaitReason]: la tabella di docs/regole-attivita.md §4 «Motivi, in ordine di priorita'».
 */
internal enum class ActivityReason(val code: DiagCode) {
  PROFILE_INACTIVE(DiagCodes.ACT_PROFILE_INACTIVE),
  ROLE_DISABLED(DiagCodes.ACT_ROLE_DISABLED),
  ACTIVITY_DISABLED(DiagCodes.ACT_DISABLED),
  APP_NOT_VISIBLE(DiagCodes.ACT_APP_NOT_VISIBLE),
  ROAMING(DiagCodes.ACT_ROAMING),
  OFFLINE(DiagCodes.ACT_OFFLINE),
  NETWORK_NOT_TRUSTED(DiagCodes.ACT_NETWORK_NOT_TRUSTED),
  POOR_NETWORK(DiagCodes.ACT_POOR_NETWORK),
  OUTSIDE_WINDOW(DiagCodes.ACT_OUTSIDE_WINDOW),
  NEEDS_POWER(DiagCodes.ACT_NEEDS_POWER),
  BATTERY_LOW(DiagCodes.ACT_BATTERY_LOW),
  POWER_SAVER(DiagCodes.ACT_POWER_SAVER),
  THERMAL(DiagCodes.ACT_THERMAL),
  NETWORK_NOT_ALLOWED(DiagCodes.ACT_NETWORK_NOT_ALLOWED),
  HEAVY_NEEDS_HOME(DiagCodes.ACT_HEAVY_NEEDS_HOME),
  CAP_REACHED(DiagCodes.ACT_CAP_REACHED),
  ANDROID_LIMIT(DiagCodes.ACT_ANDROID_LIMIT),
  NAS_UNREACHABLE(DiagCodes.ACT_NAS_UNREACHABLE),
  CONFIRMATION_REQUIRED(DiagCodes.ACT_CONFIRMATION_REQUIRED),
  ;

  companion object {
    fun of(reason: WaitReason): ActivityReason = when (reason) {
      WaitReason.ProfileInactive -> PROFILE_INACTIVE
      is WaitReason.RoleDisabled -> ROLE_DISABLED
      WaitReason.ActivityDisabled -> ACTIVITY_DISABLED
      WaitReason.AppNotVisible -> APP_NOT_VISIBLE
      WaitReason.Roaming -> ROAMING
      WaitReason.Offline -> OFFLINE
      WaitReason.NetworkNotTrusted -> NETWORK_NOT_TRUSTED
      WaitReason.PoorNetwork -> POOR_NETWORK
      is WaitReason.OutsideWindow -> OUTSIDE_WINDOW
      WaitReason.NeedsPower -> NEEDS_POWER
      is WaitReason.BatteryLow -> BATTERY_LOW
      WaitReason.PowerSaver -> POWER_SAVER
      is WaitReason.Thermal -> THERMAL
      is WaitReason.NetworkNotAllowed -> NETWORK_NOT_ALLOWED
      WaitReason.HeavyNeedsHomeWifi -> HEAVY_NEEDS_HOME
      is WaitReason.CapReached -> CAP_REACHED
      is WaitReason.AndroidLimit -> ANDROID_LIMIT
      WaitReason.NasUnreachable -> NAS_UNREACHABLE
      is WaitReason.ConfirmationRequired -> CONFIRMATION_REQUIRED
    }
  }
}
