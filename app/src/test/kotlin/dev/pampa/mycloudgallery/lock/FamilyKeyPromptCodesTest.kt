package dev.pampa.mycloudgallery.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import org.junit.Test

/**
 * Fase 10, M3: il foglio della chiave di famiglia ([FamilyKeyPrompt]) con i codici di androidx.biometric. Ogni chiusura del foglio e' un
 * rifiuto (niente firmato) con il suo motivo; gli autenticatori sono quelli che si legano a una chiave per ogni uso.
 */
class FamilyKeyPromptCodesTest {

  @Test
  fun autenticatori_daAndroid11ImprontaForteOCredenziale_suAndroid10SoloImprontaForte() {
    for (sdk in listOf(30, 35, 37)) assertThat(FamilyKeyPromptCodes.authenticators(sdk)).isEqualTo(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
    assertThat(FamilyKeyPromptCodes.authenticators(29)).isEqualTo(BIOMETRIC_STRONG)
    // Mai la biometria debole (il volto di classe 2): una chiave per ogni uso vuole la classe 3.
    for (sdk in 29..37) assertThat(FamilyKeyPromptCodes.authenticators(sdk) and BIOMETRIC_WEAK and BIOMETRIC_STRONG.inv()).isEqualTo(0)
  }

  @Test
  fun chiudereIlFoglio_eUnAnnullamento() {
    for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)) {
      assertThat(FamilyKeyPromptCodes.refusal(code)).isEqualTo(VaultRefusal.CANCELLED)
    }
  }

  @Test
  fun troppiTentativi_bloccatoDalSistema() {
    assertThat(FamilyKeyPromptCodes.refusal(BiometricPrompt.ERROR_LOCKOUT)).isEqualTo(VaultRefusal.LOCKED_OUT)
    assertThat(FamilyKeyPromptCodes.refusal(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)).isEqualTo(VaultRefusal.LOCKED_OUT)
  }

  @Test
  fun senzaBloccoSchermoOImpronta_eAltriErrori() {
    for (code in listOf(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT)) {
      assertThat(FamilyKeyPromptCodes.refusal(code)).isEqualTo(VaultRefusal.NO_SECURE_LOCK)
    }
    for (code in listOf(BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_TIMEOUT, BiometricPrompt.ERROR_VENDOR, 9999)) {
      assertThat(FamilyKeyPromptCodes.refusal(code)).isEqualTo(VaultRefusal.UNAVAILABLE)
    }
  }

  @Test
  fun disponibilita() {
    assertThat(FamilyKeyPromptCodes.availability(BiometricManager.BIOMETRIC_SUCCESS)).isNull()
    assertThat(FamilyKeyPromptCodes.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)).isEqualTo(VaultRefusal.NO_SECURE_LOCK)
    assertThat(FamilyKeyPromptCodes.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)).isEqualTo(VaultRefusal.NO_SECURE_LOCK)
    assertThat(FamilyKeyPromptCodes.availability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)).isEqualTo(VaultRefusal.UNAVAILABLE)
    assertThat(FamilyKeyPromptCodes.availability(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)).isEqualTo(VaultRefusal.UNAVAILABLE)
  }
}
