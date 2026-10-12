package dev.pampa.mycloudgallery.core.security.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tutti i codici di androidx.biometric tradotti nei tipi del modulo (E-S4, E-H3). */
class AuthMappingTest {

  @Test
  fun disponibilita_ogniCodiceHaUnaRisposta() {
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_SUCCESS)).isEqualTo(AuthAvailability.AVAILABLE)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)).isEqualTo(AuthAvailability.NO_SCREEN_LOCK)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)).isEqualTo(AuthAvailability.UNSUPPORTED)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED)).isEqualTo(AuthAvailability.UNSUPPORTED)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)).isEqualTo(AuthAvailability.TEMPORARILY_UNAVAILABLE)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)).isEqualTo(AuthAvailability.TEMPORARILY_UNAVAILABLE)
    assertThat(AuthMapping.availability(BiometricManager.BIOMETRIC_STATUS_UNKNOWN)).isEqualTo(AuthAvailability.TEMPORARILY_UNAVAILABLE)
    // un codice che non conosciamo non puo' sbloccare niente
    assertThat(AuthMapping.availability(12345)).isEqualTo(AuthAvailability.TEMPORARILY_UNAVAILABLE)
  }

  @Test
  fun lUtenteChiudeLaSchermata_eAnnullato_nonEErrore() {
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_USER_CANCELED)).isEqualTo(AuthResult.Cancelled)
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_NEGATIVE_BUTTON)).isEqualTo(AuthResult.Cancelled)
  }

  @Test
  fun ilSistemaChiudeLaSchermata_eInterrotto_siRiprovaAlRitorno() {
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_CANCELED)).isEqualTo(AuthResult.Interrupted)
  }

  @Test
  fun troppiTentativi_ilBloccoELoImponeIlSistema() {
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_LOCKOUT)).isEqualTo(AuthResult.LockedOut(permanent = false))
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)).isEqualTo(AuthResult.LockedOut(permanent = true))
  }

  @Test
  fun nessunBloccoSchermo_nonSiPuoSbloccare() {
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
      .isEqualTo(AuthResult.NotAvailable(AuthAvailability.NO_SCREEN_LOCK))
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_NO_BIOMETRICS))
      .isEqualTo(AuthResult.NotAvailable(AuthAvailability.NO_SCREEN_LOCK))
    assertThat(AuthMapping.result(BiometricPrompt.ERROR_HW_NOT_PRESENT))
      .isEqualTo(AuthResult.NotAvailable(AuthAvailability.UNSUPPORTED))
  }

  @Test
  fun gliAltriErrori_conservanoIlCodicePerLaDiagnostica() {
    for (code in listOf(
      BiometricPrompt.ERROR_HW_UNAVAILABLE,
      BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
      BiometricPrompt.ERROR_TIMEOUT,
      BiometricPrompt.ERROR_NO_SPACE,
      BiometricPrompt.ERROR_VENDOR,
      BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
      9999,
    )) {
      assertThat(AuthMapping.result(code)).isEqualTo(AuthResult.Error(code))
    }
  }

  @Test
  fun nessunCodiceDiErroreVieneMaiPresoPerSuccesso() {
    for (code in -5..40) {
      assertThat(AuthMapping.result(code)).isNotEqualTo(AuthResult.Success)
    }
  }

  @Test
  fun gliAutenticatoriAmmessiSonoDebole_piuCredenzialeDiSistema() {
    val expected = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    assertThat(BiometricPromptAuthenticator.AUTHENTICATORS).isEqualTo(expected)
    // la credenziale di sistema e' sempre ammessa: E-H3, la biometria cambiata non chiude fuori l'utente
    assertThat(BiometricPromptAuthenticator.AUTHENTICATORS and BiometricManager.Authenticators.DEVICE_CREDENTIAL).isNotEqualTo(0)
  }
}
