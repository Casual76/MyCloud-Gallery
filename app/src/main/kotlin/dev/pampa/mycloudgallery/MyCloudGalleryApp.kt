package dev.pampa.mycloudgallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.process.ProcessWatcher
import dev.pampa.mycloudgallery.work.WorkManagerTrustSyncScheduler
import javax.inject.Inject

/**
 * Il punto d'ingresso del processo: Hilt costruisce da qui il grafo delle dipendenze ([AppGraph], con le
 * implementazioni vere di P1-P8).
 *
 * All'avvio: i canali delle notifiche, poi [ProcessWatcher], che vive quanto il processo (primo piano e profilo per le
 * regole, automazione della VPN, sessione NAS chiusa in background, controllo del registro all'apertura e all'arrivo a
 * casa, controllo quotidiano pianificato) e che legge lo stato salvato prima della prima schermata (`AppGraph.start`).
 *
 * WorkManager si inizializza su richiesta con questa configurazione (l'inizializzatore automatico e' tolto
 * nel manifest): cosi' i `@HiltWorker` (`TrustSyncWorker`, poi backup e indice) ricevono le dipendenze da Hilt.
 */
@HiltAndroidApp
class MyCloudGalleryApp : Application(), Configuration.Provider {

  @Inject
  lateinit var workerFactory: HiltWorkerFactory

  @Inject
  lateinit var graph: AppGraph

  @Inject
  lateinit var notifications: AppNotifications

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

  override fun onCreate() {
    super.onCreate()
    // Nella build release questa funzione e' vuota: vedi src/release/.../AppLogging.kt.
    installLogging()
    notifications.createChannels()
    ProcessWatcher(
      graph = graph,
      notifications = notifications,
      ensureDaily = { (graph.scheduler as? WorkManagerTrustSyncScheduler)?.ensureDaily() },
    ).start(this)
  }
}
