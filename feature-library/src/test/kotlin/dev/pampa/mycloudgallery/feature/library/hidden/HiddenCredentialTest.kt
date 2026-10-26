package dev.pampa.mycloudgallery.feature.library.hidden

import androidx.biometric.BiometricManager
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockState
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 7 (P9, D23, E-H3): **lo sblocco dei Nascosti con la credenziale del telefono quando la biometria cambia**. Il cancello usa lo stesso
 * autenticatore del blocco dell'app, ma e' un cancello a parte: non si apre con lo sblocco dell'app (F74).
 *
 * Perche' la biometria cambiata non chiude fuori l'utente: lo sblocco **non e' legato a nessuna chiave** (nessun `CryptoObject`), quindi una nuova
 * impronta o un volto riaddestrato non lo rompono; e fra gli autenticatori ammessi c'e' sempre la credenziale di sistema (PIN, sequenza,
 * password), che il sistema offre da solo quando l'impronta o il volto non vanno. Qui si provano le tre cose: la composizione degli autenticatori
 * (e l'assenza di chiavi), il cancello con l'esito che il sistema da' in quei casi, e l'indipendenza dal blocco dell'app. Sul telefono vero: F74.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenCredentialTest {

  private val clock = TestClock()
  private val prompt = AuthPromptText("Sblocca", "prova")

  /**
   * Il telefono dopo che l'impronta e' cambiata: la schermata di sistema non accetta piu' la biometria che l'app si ricordava e chiede la
   * credenziale. Finche' l'utente non la scrive la schermata si chiude senza esito ([AuthResult.Cancelled]); quando la scrive, [AuthResult.Success].
   * La credenziale c'e' sempre: [availability] resta [AuthAvailability.AVAILABLE] anche se la biometria non serve a niente.
   */
  private class PhoneAfterBiometricChange : SystemCredentialAuthenticator {
    var credentialTyped = false
    var prompts = 0

    override fun availability(): AuthAvailability = AuthAvailability.AVAILABLE

    override suspend fun authenticate(text: AuthPromptText): AuthResult {
      prompts++
      return if (credentialTyped) AuthResult.Success else AuthResult.Cancelled
    }
  }

  @Test
  fun gliAutenticatoriAmmessiHannoSempreLaCredenzialeDelTelefono() {
    val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val weakBiometric = BiometricManager.Authenticators.BIOMETRIC_WEAK
    assertThat(BiometricPromptAuthenticator.AUTHENTICATORS and credential).isEqualTo(credential)
    assertThat(BiometricPromptAuthenticator.AUTHENTICATORS and weakBiometric).isEqualTo(weakBiometric)
    // Mai un autenticatore forte da solo: il volto dei Samsung (classe 2) e la credenziale devono restare ammessi.
    assertThat(BiometricPromptAuthenticator.AUTHENTICATORS).isEqualTo(weakBiometric or credential)
  }

  @Test
  fun loSbloccoNonELegatoAUnaChiaveDellaBiometria() {
    // Con una chiave che si invalida a ogni nuova impronta (`CryptoObject`, `setInvalidatedByBiometricEnrollment`) cambiare l'impronta
    // romperebbe lo sblocco. Il file dell'autenticatore e quelli del cancello non devono usarne.
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val files = listOf(
      "core-security/src/main/kotlin/dev/pampa/mycloudgallery/core/security/lock/BiometricPromptAuthenticator.kt",
      "feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/hidden/HiddenAccessGate.kt",
      "feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/hidden/HiddenAuthenticator.kt",
    )
    for (path in files) {
      val code = File(root, path).readText().lines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") && !it.trimStart().startsWith("/*") }.joinToString("\n")
      assertThat(code).doesNotContain("Crypto" + "Object")
      assertThat(code).doesNotContain("setInvalidatedBy" + "BiometricEnrollment")
    }
  }

  @Test
  fun dopoUnCambioDiBiometriaLaCredenzialeApreIlCancelloAlTentativoDopo() = runTest(UnconfinedTestDispatcher()) {
    val gate = HiddenAccessGate(clock, 60_000L)
    val phone = PhoneAfterBiometricChange()

    // Primo tentativo: l'impronta vecchia non serve piu', l'utente chiude la schermata. Restano chiusi, con il motivo.
    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Denied(HiddenDenial.CANCELLED))
    assertThat(gate.isUnlocked).isFalse()

    // "Sblocca" riapre la schermata di sistema: stavolta l'utente scrive il PIN.
    phone.credentialTyped = true
    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Unlocked)
    assertThat(gate.isUnlocked).isTrue()
    assertThat(phone.prompts).isEqualTo(2)
  }

  @Test
  fun anchePiuTentativiFallitiDellaBiometriaNonChiudonoFuoriPerSempre() = runTest(UnconfinedTestDispatcher()) {
    val gate = HiddenAccessGate(clock, 60_000L)
    // I tentativi li conta e li limita il sistema (E-S4): l'app non ne tiene il conto e dopo ogni rifiuto "Sblocca" riprova.
    val outcomes = ArrayDeque(listOf<AuthResult>(AuthResult.LockedOut(permanent = false), AuthResult.Error(code = 1), AuthResult.Cancelled, AuthResult.Success))
    val phone = object : SystemCredentialAuthenticator {
      override fun availability() = AuthAvailability.AVAILABLE

      override suspend fun authenticate(text: AuthPromptText): AuthResult = outcomes.removeFirst()
    }

    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Denied(HiddenDenial.LOCKED_OUT))
    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Denied(HiddenDenial.ERROR))
    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Denied(HiddenDenial.CANCELLED))
    assertThat(gate.unlock(phone, prompt)).isEqualTo(HiddenAccess.Unlocked)
  }

  @Test
  fun senzaNessunBloccoSchermoNonSiApreComunque() = runTest(UnconfinedTestDispatcher()) {
    val gate = HiddenAccessGate(clock, 60_000L)
    val none = PhoneAfterBiometricChange().also { it.credentialTyped = true }
    val noLock = object : SystemCredentialAuthenticator by none {
      override fun availability() = AuthAvailability.NO_SCREEN_LOCK
    }

    assertThat(gate.unlock(noLock, prompt)).isEqualTo(HiddenAccess.Denied(HiddenDenial.NO_SCREEN_LOCK))
    assertThat(none.prompts).isEqualTo(0)
  }

  /** F74: "app sbloccata non apre i Nascosti". Il blocco dell'app e il cancello dei Nascosti sono due cose: uno sblocco non apre l'altro. */
  @Test
  fun loSbloccoDellAppNonApreINascosti() = runTest(UnconfinedTestDispatcher()) {
    val world = FakeCollectionsWorld()
    val lock = AppLockController(clock)
    lock.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.IMMEDIATELY))
    assertThat(lock.state.value).isEqualTo(AppLockState.LOCKED)

    // L'utente sblocca l'app con la sua credenziale.
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endAuthentication(success = true)
    assertThat(lock.state.value).isEqualTo(AppLockState.UNLOCKED)

    // I Nascosti sono ancora chiusi: nessuna lettura finche' non si sblocca il cancello.
    val gate = HiddenAccessGate(clock, 60_000L)
    val controller = HiddenController(gate, world.hiddenLibrary, world.actions, backgroundScope)
    controller.shown()
    assertThat(controller.state.value).isEqualTo(HiddenUiState.Locked)
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)

    // Servono la credenziale e un gesto proprio.
    val phone = PhoneAfterBiometricChange().also { it.credentialTyped = true }
    controller.unlock(phone, prompt)
    assertThat(controller.state.value).isInstanceOf(HiddenUiState.Content::class.java)
    assertThat(phone.prompts).isEqualTo(1)
  }

  /** Un nuovo processo riparte sempre chiuso: lo sblocco non sopravvive al riavvio, qualunque cosa sia successa alla biometria. */
  @Test
  fun unNuovoCancelloRiparteChiusoQualunqueSiaLaBiometria() {
    val first = HiddenAccessGate(clock, 60_000L)
    val second = HiddenAccessGate(clock, 60_000L)

    assertThat(first.state.value).isEqualTo(HiddenAccess.Locked)
    assertThat(second.state.value).isEqualTo(HiddenAccess.Locked)
    assertThat(second.isUnlocked).isFalse()
  }
}
