package dev.pampa.mycloudgallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.CutoutShareEnv
import dev.pampa.mycloudgallery.ai.TextReadingStartup
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.work.AiWorkScheduling
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import java.util.Optional
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.process.GalleryLifecycle
import dev.pampa.mycloudgallery.process.ProcessWatcher
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import dev.pampa.mycloudgallery.work.GalleryWorkScheduling
import dev.pampa.mycloudgallery.work.JobSchedulerNasOpsJobs
import dev.pampa.mycloudgallery.work.NasWorkScheduling
import dev.pampa.mycloudgallery.work.WorkManagerTrustSyncScheduler
import javax.inject.Inject

/**
 * Il punto d'ingresso del processo: Hilt costruisce da qui il grafo delle dipendenze ([AppGraph], con le
 * implementazioni vere di P1-P8 della Fase 2, e [GalleryGraph], la galleria del telefono della Fase 3).
 *
 * All'avvio: i canali delle notifiche, poi [ProcessWatcher], che vive quanto il processo (primo piano e profilo per le
 * regole, automazione della VPN, sessione NAS chiusa in background, controllo del registro all'apertura e all'arrivo a
 * casa, controllo quotidiano pianificato; dalla Fase 3 la galleria: repository della timeline a livello di processo,
 * lettura del telefono agganciata al primo piano, lavori a trigger di contenuto e manutenzione in carica) e che legge lo
 * stato salvato prima della prima schermata (`AppGraph.start`).
 *
 * WorkManager si inizializza su richiesta con questa configurazione (l'inizializzatore automatico e' tolto
 * nel manifest): cosi' i `@HiltWorker` (`TrustSyncWorker`, `PhoneSyncWorker`, `LibraryMaintenanceWorker`) ricevono le
 * dipendenze da Hilt.
 */
@HiltAndroidApp
class MyCloudGalleryApp : Application(), Configuration.Provider {

  @Inject
  lateinit var workerFactory: HiltWorkerFactory

  @Inject
  lateinit var graph: AppGraph

  @Inject
  lateinit var gallery: GalleryGraph

  @Inject
  lateinit var notifications: AppNotifications

  /** Fase 5B (P14): le porte del selettore, del provider dei documenti e della ricezione, installate prima di ogni richiesta. */
  @Inject
  lateinit var integrations: IntegrationsGraph

  /** Fase 5B (P14): le schermate della 5B e le voci del backup nel viewer (solo con il backup). */
  @Inject
  lateinit var fiveB: Optional<FiveBUi>

  /** Fase 6 (P11): modelli, lettura del testo, calcolo AI e motore di ricerca (`AiModule`). */
  @Inject
  lateinit var ai: AiStack

  /** Fase 7 (P13): gli album (`AlbumsModule`): nascono con il processo e si montano da soli quando la famiglia e' pronta. */
  @Inject
  lateinit var albums: dev.pampa.mycloudgallery.albums.AlbumsGraph

  /**
   * Fase 8 (P13): le persone (`FacesModule`): nascono con il processo, **prima** di qualunque apertura dei dati dei volti, e si montano da sole quando la famiglia
   * e' pronta. Nasce qui anche l'interruttore della cancellazione: un diario di "Disattiva e cancella" rimasto a meta' chiude il recinto appena le persone si montano.
   */
  @Inject
  lateinit var faces: dev.pampa.mycloudgallery.faces.FacesGraph

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      // Fase 4B (P16): il JobService delle operazioni sul NAS usa due id fissi di JobScheduler; WorkManager resta sotto.
      .setJobSchedulerJobIdRange(JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS.first, JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS.last)
      .build()

  override fun onCreate() {
    // Fase 6 (D41): PRIMA di ogni altra cosa, anche dell'iniezione di Hilt: con la lettura del testo non attiva qui si annullano i lavori
    // di DataTransport e se ne tolgono coda e identificativo. Nessuna classe di ML Kit si carica (MlKitOffAtStartupDeviceTest).
    TextReadingStartup.run(this)
    super.onCreate()
    // Nella build release questa funzione e' vuota: vedi src/release/.../AppLogging.kt.
    installLogging()
    notifications.createChannels()
    // Fase 7 (P13): "Aggiungi ad album" nella ricezione, installato con le altre porte della ricezione.
    integrations.incomingAlbums = albums.incoming
    // Fase 5B (P14): prima di qualunque componente creato dal sistema (selettore, provider, ricezione) e di qualunque schermata.
    integrations.install()
    // Correzioni 5B (R4, MC-2): "Condividi" con l'impostazione per il viewer (anche senza backup) e per la selezione della Libreria.
    gallery.shares = integrations.shares
    fiveB.ifPresent { ui -> gallery.fiveB = GalleryGraph.FiveBHooks(ui.mediaActions) { ids -> ui.openFreeSpace(ids) } }
    // Fase 6 (P11): testo dal vivo, "Trova simili" e foto in movimento nel viewer, prima di qualunque schermata.
    gallery.searchViewer = ai.viewerHooks(graph, gallery, CutoutShareEnv(integrations.shareCache, integrations.uris, integrations.shareHolder, integrations.revoker))
    // Fase 6 (P9): lo strumento di valutazione della ricerca, solo in debug (in release la funzione e' vuota).
    dev.pampa.mycloudgallery.debugtools.DeveloperTools.installSearch(ai, gallery, this)
    ProcessWatcher(
      graph = graph,
      notifications = notifications,
      ensureDaily = { (graph.scheduler as? WorkManagerTrustSyncScheduler)?.ensureDaily() },
      gallery = galleryLifecycle(),
    ).start(this)
  }

  /** `onTrimMemory` verso la cache delle miniature (nota di P6) e l'istantanea della timeline. */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (::gallery.isInitialized) gallery.onTrimMemory(level)
    // Fase 6 (D35, P13): il motore delle query e il ritagliatore del soggetto si rilasciano a memoria bassa (si ricaricano all'uso).
    if (::ai.isInitialized) ai.onTrimMemory(level)
    // Fase 8 (P13, §2.2 "Rilascio"): rilevatore ed embedder si rilasciano a memoria bassa (si riaprono da soli), e le copertine in memoria si buttano.
    if (::faces.isInitialized) faces.onTrimMemory(level)
  }

  private fun galleryLifecycle(): GalleryLifecycle = object : GalleryLifecycle {
    override fun start() {
      gallery.startWhenReady {
        // Lavori in background solo con la famiglia pronta, e mai nella variante di misura (dataset sintetico).
        if (gallery.backgroundWorkAllowed) {
          GalleryWorkScheduling.ensure(this@MyCloudGalleryApp)
          // Fase 4A (P11): i lavori del NAS nella Libreria, solo se il NAS c'e' (FeatureFlags.nasLibrary).
          if (gallery.nas != null) {
            NasWorkScheduling.ensure(this@MyCloudGalleryApp)
            NasWorkScheduling.scanNow(this@MyCloudGalleryApp)
          }
          // Fase 5A (P7): i lavori del backup (foto, video, verifiche). Le regole decidono quando; i trigger sono la lettura del telefono.
          if (gallery.backup != null) BackupWorkScheduling.ensure(this@MyCloudGalleryApp)
          // Fase 6 (P11): modelli e calcolo AI (le regole di AI_COMPUTE decidono quando; senza il ruolo il calcolo finisce subito).
          AiWorkScheduling.ensure(this@MyCloudGalleryApp)
          // Fase 8 (P13): il calcolo dei volti (le regole di AI_COMPUTE decidono quando; senza Persone attiva qui la tappa finisce subito).
          dev.pampa.mycloudgallery.work.FacesWorkScheduling.ensure(this@MyCloudGalleryApp)
        }
        // Fase 6 (P11): l'indice di ricerca si apre con il catalogo (vive anche senza NAS), i modelli presenti si verificano (P3).
        graph.scope.launch {
          runCatching { ai.search.open() }
          runCatching { ai.localModels.refreshOnce() }
          // P13: una riga in attesa lasciata da un "Salva come nuova foto" interrotto dalla morte del processo si toglie (IS_PENDING).
          runCatching { ai.cutoutPhotos.purgeOrphans() }
        }
        // P13 (D47): le installazioni di prima dei ritagli ricevono, una volta sola, la sorgente "Ritagli" del backup (con la recinzione di D13 in debug).
        graph.scope.launch {
          runCatching {
            val user = graph.accountStore.observe().first()?.user
            graph.backupSettingsStorage.offerCutoutsOnce(ai.cutoutFence.sourceFor(user), ai.cutoutFence::isCutouts)
          }
        }
        // D15: accendere Indicizzatore chiede subito il modello del calcolo (lavoro accelerato).
        graph.scope.launch {
          ai.ai.indexer.drop(1).collect { on ->
            if (on && gallery.backgroundWorkAllowed) AiWorkScheduling.fetchNow(this@MyCloudGalleryApp, ModelTrigger.ROLE_CHANGED)
          }
        }
      }
    }

    override fun onForeground() {
      gallery.onForeground()
      // All'apertura: una verifica del NAS se l'ultima ha piu' di 15 minuti (§1.4), poi la sincronizzazione dell'indice.
      if (gallery.backgroundWorkAllowed && gallery.nas?.verifyDue() == true) NasWorkScheduling.scanNow(this@MyCloudGalleryApp)
    }

    override fun onBackground() = gallery.onBackground()

    override fun onResume() = gallery.refreshAccess()
  }
}
