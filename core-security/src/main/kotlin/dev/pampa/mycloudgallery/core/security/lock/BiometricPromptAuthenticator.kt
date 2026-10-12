package dev.pampa.mycloudgallery.core.security.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Lo sblocco con la credenziale di sistema tramite `BiometricPrompt` (androidx.biometric).
 *
 * - **Autenticatori**: `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`. Impronta o volto, e in ogni momento il PIN, la
 *   sequenza o la password del telefono: mai un PIN dell'app (docs/sicurezza.md §8). "Debole" e non
 *   "forte" perche' il volto dei Samsung e' di classe 2 e il piano lo ammette (impronta, volto o
 *   credenziale); inoltre `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` non e' supportata su Android 9 e 10.
 * - **Biometria cambiata (E-H3)**: lo sblocco non e' legato a nessuna chiave (nessun `CryptoObject`),
 *   quindi una nuova impronta o un volto riaddestrato non lo rompono, e la credenziale di sistema e' sempre
 *   disponibile come ripiego.
 * - **Tentativi falliti (E-S4)**: li conta e li limita il sistema (blocchi temporanei e permanenti, che
 *   arrivano come [AuthResult.LockedOut]). L'app non conta niente e non cancella niente.
 *
 * `BiometricPrompt` vuole una `FragmentActivity`: la `MainActivity` dovra' estendere `FragmentActivity`
 * (che estende `ComponentActivity`, quindi `setContent` continua a funzionare).
 */
class BiometricPromptAuthenticator(private val activity: FragmentActivity) : SystemCredentialAuthenticator {

  override fun availability(): AuthAvailability =
    AuthMapping.availability(BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS))

  override suspend fun authenticate(text: AuthPromptText): AuthResult {
    val availability = availability()
    if (availability != AuthAvailability.AVAILABLE) return AuthResult.NotAvailable(availability)

    val executor = ContextCompat.getMainExecutor(activity)
    return suspendCancellableCoroutine { continuation ->
      val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          if (continuation.isActive) continuation.resume(AuthResult.Success)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (continuation.isActive) continuation.resume(AuthMapping.result(errorCode))
        }
        // onAuthenticationFailed: un tentativo sbagliato, la schermata di sistema resta aperta. Niente da fare.
      }
      val prompt = BiometricPrompt(activity, executor, callback)
      val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(text.title)
        .apply { text.subtitle?.let { setSubtitle(it) } }
        .setAllowedAuthenticators(AUTHENTICATORS)
        // Il volto non chiede un secondo "conferma": lo sblocco e' gia' un gesto volontario.
        .setConfirmationRequired(false)
        .build()
      continuation.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
      prompt.authenticate(info)
    }
  }

  companion object {
    /** Gli autenticatori ammessi. Senza tasto negativo: con `DEVICE_CREDENTIAL` il sistema non lo ammette. */
    const val AUTHENTICATORS: Int = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
  }
}
