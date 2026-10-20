package dev.pampa.mycloudgallery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.state.LocalPhoneTrash
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
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
 *
 * **L'intent d'ingresso non porta niente** (revisione 2, F5): l'activity e' esportata (launcher), quindi qualsiasi app
 * puo' avviarla con extra scelti da lei, e Navigation Compose li leggerebbe come deep link interni
 * (`android-support-nav:controller:deepLinkIds`/`deepLinkArgs`): per esempio l'attesa dell'approvazione con un codice
 * falso. L'app non ha deep link: prima di `setContent`, e a ogni `onNewIntent`, l'intent si sostituisce con uno pulito.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  /** Unico per processo: lo stesso che RootViewModel alimenta con le impostazioni del blocco. */
  @Inject
  lateinit var lockController: AppLockController

  override fun onCreate(savedInstanceState: Bundle?) {
    intent = launcherIntent(this)
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    GalleryLog.d(TAG) { "MainActivity pronta" }
    val authenticator = BiometricPromptAuthenticator(this)
    setContent {
      MyCloudGalleryTheme {
        // D-3 (prove sul campo): su One UI il cestino del telefono e' quello di Galleria; le schermate lo dicono con le parole giuste.
        CompositionLocalProvider(LocalPhoneTrash provides PhoneTrash.of(Build.MANUFACTURER)) {
          // I tag di test come id delle risorse (docs/bench/README.md §6-ter): i selettori del collaudo li trovano per tag
          // (`g1-chrome-*`) invece che per testo. Solo semantica: niente cambia nell'aspetto.
          Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            AppRoot(lockController = lockController, authenticator = authenticator)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    val clean = launcherIntent(this)
    super.onNewIntent(clean)
    setIntent(clean)
  }

  internal companion object {
    private const val TAG = "MainActivity"

    /** L'intent con cui l'app parte dal launcher, e nient'altro: niente extra, dati, flag o componenti di altri. */
    fun launcherIntent(context: Context): Intent =
      Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
  }
}
