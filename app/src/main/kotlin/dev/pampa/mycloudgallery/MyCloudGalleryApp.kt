package dev.pampa.mycloudgallery

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Il punto d'ingresso del processo: Hilt costruisce da qui il grafo delle dipendenze. */
@HiltAndroidApp
class MyCloudGalleryApp : Application() {

  override fun onCreate() {
    super.onCreate()
    // Nella build release questa funzione e' vuota: vedi src/release/.../AppLogging.kt.
    installLogging()
  }
}
