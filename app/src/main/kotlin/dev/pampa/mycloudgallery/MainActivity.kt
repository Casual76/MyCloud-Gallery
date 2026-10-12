package dev.pampa.mycloudgallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import javax.inject.Inject

/**
 * L'unica activity dell'app e l'unico punto d'ingresso dell'utente (launcher, `singleTask` nel manifest).
 *
 * E' una `FragmentActivity` perche' il blocco app usa `BiometricPrompt`, che la richiede (estende
 * `ComponentActivity`, quindi Compose funziona come prima). Una sola activity anche perche' il blocco app
 * presume una sola activity: con due, ogni passaggio conterebbe come uscita dall'app.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  /** Unico per processo: lo stesso che RootViewModel alimenta con le impostazioni del blocco. */
  @Inject
  lateinit var lockController: AppLockController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    GalleryLog.d(TAG) { "MainActivity pronta" }
    val authenticator = BiometricPromptAuthenticator(this)
    setContent {
      MyCloudGalleryTheme {
        AppRoot(lockController = lockController, authenticator = authenticator)
      }
    }
  }

  private companion object {
    const val TAG = "MainActivity"
  }
}
