package dev.pampa.mycloudgallery.lock

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.VaultAuth
import dev.pampa.mycloudgallery.core.trust.crypto.VaultAuthenticator
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Il foglio di autenticazione di sistema per la chiave di famiglia (Fase 10, correzione 1.4 M3, decisione 2(a) dell'utente del 31/10):
 * `BiometricPrompt` con un `CryptoObject` sul `Cipher` della cassaforte, quindi il Keystore usa la chiave **solo** per l'operazione che
 * l'admin ha appena confermato.
 *
 * - **Autenticatori**: impronta forte o credenziale del telefono (PIN, sequenza, password) da Android 11; su Android 10 solo l'impronta
 *   forte con "Annulla" (la credenziale non si lega a un `CryptoObject` prima di Android 11). Il volto dei Samsung e' di classe 2: qui non
 *   vale, a differenza del blocco app (`BiometricPromptAuthenticator`), perche' una chiave per ogni uso vuole la classe 3.
 * - **Solo davanti all'admin**: il foglio si mostra sopra l'activity dell'app in primo piano (`onResume`), tenuta con un riferimento
 *   debole. Senza una schermata in primo piano (un lavoro in background, l'app in secondo piano) non si mostra niente e la cassaforte
 *   risponde [VaultRefusal.UNAVAILABLE]: la chiave di famiglia non si apre mai senza l'admin davanti.
 * - **Esiti** ([FamilyKeyPromptCodes]): chiuso dall'utente o dal sistema → annullato; troppi tentativi → bloccato dal sistema (E-S4,
 *   l'app non conta niente); niente blocco schermo o niente impronta → serve un blocco schermo; il resto → non disponibile, si riprova.
 *
 * Tocca solo `:app`: il blocco app di `core-security/.../lock/` non cambia.
 */
class FamilyKeyPrompt(application: Application) : VaultAuthenticator {

  /** Le activity dell'app in primo piano, dall'ultima ripresa (due finestre possono essere riprese insieme, D16). Solo dal thread principale. */
  private val resumed = ArrayList<WeakReference<FragmentActivity>>()

  init {
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
          if (activity !is FragmentActivity) return
          resumed.removeAll { it.get() == null || it.get() === activity }
          resumed += WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
          resumed.removeAll { it.get() == null || it.get() === activity }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
          resumed.removeAll { it.get() == null || it.get() === activity }
        }
      },
    )
  }

  override suspend fun authenticate(cipher: Cipher, use: FamilyKeyUse): VaultAuth = withContext(Dispatchers.Main.immediate) {
    val activity = resumed.lastOrNull { it.get()?.isFinishing == false }?.get()
    if (activity == null) {
      GalleryLog.w(TAG) { "nessuna schermata in primo piano: il foglio della chiave di famiglia non si mostra" }
      return@withContext VaultAuth.Refused(VaultRefusal.UNAVAILABLE)
    }
    val authenticators = FamilyKeyPromptCodes.authenticators(Build.VERSION.SDK_INT)
    FamilyKeyPromptCodes.availability(BiometricManager.from(activity).canAuthenticate(authenticators))?.let {
      return@withContext VaultAuth.Refused(it)
    }
    val executor = ContextCompat.getMainExecutor(activity)
    suspendCancellableCoroutine<VaultAuth> { continuation ->
      val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          val unlocked = result.cryptoObject?.cipher ?: cipher
          if (continuation.isActive) continuation.resume(VaultAuth.Authenticated(unlocked))
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (continuation.isActive) continuation.resume(VaultAuth.Refused(FamilyKeyPromptCodes.refusal(errorCode)))
        }
        // onAuthenticationFailed: un tentativo sbagliato, il foglio resta aperto. Niente da fare.
      }
      val prompt = BiometricPrompt(activity, executor, callback)
      val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.family_key_prompt_title))
        .setSubtitle(activity.getString(if (use == FamilyKeyUse.SAVE) R.string.family_key_prompt_save else R.string.family_key_prompt_sign))
        .setDescription(activity.getString(R.string.family_key_prompt_detail))
        .setAllowedAuthenticators(authenticators)
        .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText(activity.getString(R.string.family_key_prompt_cancel)) }
        .build()
      continuation.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
      prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
  }

  private companion object {
    const val TAG = "FamilyKeyPrompt"
  }
}

/** I codici di androidx.biometric tradotti per la cassaforte. Puri: li prova `FamilyKeyPromptCodesTest` con tutti i codici. */
internal object FamilyKeyPromptCodes {

  /** Da Android 11 impronta forte o credenziale; su Android 10 solo l'impronta forte (nessun `CryptoObject` con la credenziale prima). */
  fun authenticators(sdk: Int): Int = if (sdk >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

  /** La risposta di `canAuthenticate`: null se il foglio si puo' mostrare, altrimenti perche' no. */
  fun availability(code: Int): VaultRefusal? = when (code) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    // Con la credenziale ammessa "nulla di registrato" vuol dire niente blocco schermo; su Android 10, nessuna impronta.
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
    -> VaultRefusal.NO_SECURE_LOCK
    else -> VaultRefusal.UNAVAILABLE
  }

  /** Il codice di `onAuthenticationError`. Un tentativo sbagliato non e' un errore: il foglio resta aperto. */
  fun refusal(errorCode: Int): VaultRefusal = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    -> VaultRefusal.CANCELLED
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> VaultRefusal.LOCKED_OUT
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    -> VaultRefusal.NO_SECURE_LOCK
    else -> VaultRefusal.UNAVAILABLE
  }
}
