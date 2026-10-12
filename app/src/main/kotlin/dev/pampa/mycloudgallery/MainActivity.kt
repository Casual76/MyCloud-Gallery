package dev.pampa.mycloudgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import javax.inject.Inject

/**
 * L'unico componente esportato dell'app (la launcher activity). Per ora mostra una schermata vuota a
 * tema Ametista; la navigazione vera arriva con la Fase 3.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  /** Iniettato da Hilt: dimostra, a ogni avvio, che il grafo delle dipendenze e' cablato. */
  @Inject
  lateinit var dispatchers: DispatcherProvider

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    GalleryLog.d(TAG) { "MainActivity pronta, dispatcher: ${dispatchers.default}" }
    setContent {
      MyCloudGalleryTheme {
        AppRoot()
      }
    }
  }

  private companion object {
    const val TAG = "MainActivity"
  }
}
