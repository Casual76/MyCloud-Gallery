package dev.pampa.mycloudgallery.core.security.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

/**
 * Traduce i codici numerici di androidx.biometric nei tipi del modulo. Separato dalla classe che apre la
 * schermata di sistema perche' e' pura e si prova con tutti i codici (docs/sicurezza.md E-S4, E-H3).
 */
internal object AuthMapping {

  /** Risposta di `BiometricManager.canAuthenticate(...)`. */
  fun availability(canAuthenticateCode: Int): AuthAvailability = when (canAuthenticateCode) {
    BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.AVAILABLE
    // Con la credenziale di sistema ammessa, "nulla di registrato" vuol dire: niente PIN, sequenza o password.
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NO_SCREEN_LOCK
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
    -> AuthAvailability.UNSUPPORTED
    // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, STATUS_UNKNOWN e qualunque codice nuovo: riprovare.
    else -> AuthAvailability.TEMPORARILY_UNAVAILABLE
  }

  /** Codice di `onAuthenticationError(...)`. Un tentativo sbagliato non e' un errore: la schermata resta aperta. */
  fun result(errorCode: Int): AuthResult = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> AuthResult.Cancelled
    BiometricPrompt.ERROR_CANCELED -> AuthResult.Interrupted
    BiometricPrompt.ERROR_LOCKOUT -> AuthResult.LockedOut(permanent = false)
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthResult.LockedOut(permanent = true)
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    -> AuthResult.NotAvailable(AuthAvailability.NO_SCREEN_LOCK)
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> AuthResult.NotAvailable(AuthAvailability.UNSUPPORTED)
    else -> AuthResult.Error(errorCode)
  }
}
