package dev.pampa.mycloudgallery

import dev.pampa.mycloudgallery.work.quietly

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.CutoutShareEnv
import dev.pampa.mycloudgallery.ai.TextReadingStartup
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.common.perf.InstantShapes
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShapeMemory
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashLogHandler
import dev.pampa.mycloudgallery.work.AiWorkScheduling
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.LibraryInstant
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import java.util.Optional
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.snapshotOrNull
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

  // Avvio istantaneo (28/10): i grafi arrivano pigri e si costruiscono qui sotto, uno per sezione di traccia, nello stesso ordine
  // dell'iniezione di prima: cosi' la traccia dice quanto costa ognuno.
  @Inject
  lateinit var graphLazy: dagger.Lazy<AppGraph>

  @Inject
  lateinit var galleryLazy: dagger.Lazy<GalleryGraph>

  @Inject
  lateinit var notificationsLazy: dagger.Lazy<AppNotifications>

  /** Fase 5B (P14): le porte del selettore, del provider dei documenti e della ricezione, installate prima di ogni richiesta. */
  @Inject
  lateinit var integrationsLazy: dagger.Lazy<IntegrationsGraph>

  /** Fase 5B (P14): le schermate della 5B e le voci del backup nel viewer (solo con il backup). */
  @Inject
  lateinit var fiveBLazy: dagger.Lazy<Optional<FiveBUi>>

  /** Fase 6 (P11): modelli, lettura del testo, calcolo AI e motore di ricerca (`AiModule`). */
  @Inject
  lateinit var aiLazy: dagger.Lazy<AiStack>

  /** Fase 7 (P13): gli album (`AlbumsModule`): nascono con il processo e si montano da soli quando la famiglia e' pronta. */
  @Inject
  lateinit var albumsLazy: dagger.Lazy<dev.pampa.mycloudgallery.albums.AlbumsGraph>

  /**
   * Fase 8 (P13): le persone (`FacesModule`): nascono con il processo, **prima** di qualunque apertura dei dati dei volti, e si montano da sole quando la famiglia
   * e' pronta. Nasce qui anche l'interruttore della cancellazione: un diario di "Disattiva e cancella" rimasto a meta' chiude il recinto appena le persone si montano.
   */
  @Inject
  lateinit var facesLazy: dagger.Lazy<dev.pampa.mycloudgallery.faces.FacesGraph>

  /**
   * Fase 9 (P13): i ricordi (`MemoriesModule`) e la mappa (`MapModule`). Nascono con il processo **senza I/O**: i ricordi si montano da soli dopo il primo
   * disegno utile con la famiglia pronta (lo scaffale si legge allora, fuori dal thread principale); la mappa non fa niente finche' qualcuno non la guarda.
   */
  @Inject
  lateinit var memoriesLazy: dagger.Lazy<dev.pampa.mycloudgallery.memories.MemoriesGraph>

  @Inject
  lateinit var mapLazy: dagger.Lazy<dev.pampa.mycloudgallery.map.MapGraph>

  /**
   * Fase 10 (P13): il widget (`Phase10Module`: porte vere, fornitore in `WidgetHost`, ridisegno), il descrittore dei media (legato in `MediaDescribers` alla nascita) e cio'
   * che serve per legarli (blocco dell'app, scelte del widget, finestre). Nessun I/O: si montano qui, dopo i ricordi.
   */
  @Inject
  lateinit var widgetLazy: dagger.Lazy<dev.pampa.mycloudgallery.widget.WidgetGraph>

  @Inject
  lateinit var describerLazy: dagger.Lazy<dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber>

  @Inject
  lateinit var lockController: dev.pampa.mycloudgallery.core.security.lock.AppLockController

  @Inject
  lateinit var widgetSettingsLazy: dagger.Lazy<dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettings>

  @Inject
  lateinit var windowRegistry: dev.pampa.mycloudgallery.window.WindowRegistry

  private lateinit var graph: AppGraph
  private lateinit var gallery: GalleryGraph
  private lateinit var notifications: AppNotifications
  private lateinit var integrations: IntegrationsGraph
  private lateinit var fiveB: Optional<FiveBUi>
  private lateinit var ai: AiStack
  private lateinit var albums: dev.pampa.mycloudgallery.albums.AlbumsGraph
  private lateinit var faces: dev.pampa.mycloudgallery.faces.FacesGraph
  private lateinit var memories: dev.pampa.mycloudgallery.memories.MemoriesGraph
  private lateinit var map: dev.pampa.mycloudgallery.map.MapGraph
  private lateinit var widget: dev.pampa.mycloudgallery.widget.WidgetGraph
  private lateinit var describer: dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      // Fase 4B (P16): il JobService delle operazioni sul NAS usa due id fissi di JobScheduler; WorkManager resta sotto.
      .setJobSchedulerJobIdRange(JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS.first, JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS.last)
      .build()

  override fun onCreate() {
    // OA-2 / OB-7 (C472): il registro locale dei crash, PRIMA di tutto (anche di Hilt e della lettura del testo): un'eccezione non gestita lascia nel telefono dove si e' rotta
    // (classi e cornici nostre, mai un messaggio), e la Diagnostica la mostra e la esporta. Incatenato al gestore di sistema, non fa I/O finche' non cade qualcosa.
    CrashLogHandler.install(this, BuildConfig.VERSION_CODE.toLong())
    // Fase 6 (D41): PRIMA di ogni altra cosa, anche dell'iniezione di Hilt: con la lettura del testo non attiva qui si annullano i lavori
    // di DataTransport e se ne tolgono coda e identificativo. Nessuna classe di ML Kit si carica (MlKitOffAtStartupDeviceTest).
    AvvioTrace.section("MCG:app:letturaTesto") { TextReadingStartup.run(this) }
    AvvioTrace.section("MCG:app:hilt") { super.onCreate() }
    graph = AvvioTrace.section("MCG:app:grafo") { graphLazy.get() }
    // Avvio istantaneo: il cancello "dopo il primo disegno" si apre comunque 1,5 s dopo l'avvio del processo (lavori senza schermate).
    AfterFirstDraw.arm(graph.scope, android.os.Process.getStartElapsedRealtime())
    gallery = AvvioTrace.section("MCG:app:galleria") { galleryLazy.get() }
    // Avvio istantaneo: le forme delle pagine principali (solo numeri) per gli scheletri del primo ingresso, lette fuori dal thread principale.
    InstantShapes.install(java.io.File(SnapshotStore.directory(this), InstantShapes.FILE_NAME))
    InstantShapeMemory.install(InstantShapes::get, InstantShapes::put)
    graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) { InstantShapes.load() }
    // Avvio istantaneo: la Libreria dell'ultima volta (istantanea, punto, layout) si prepara subito, fuori dal thread principale. Solo se il
    // processo nasce per una schermata: un processo nato per un lavoro in background non paga il layout (l'istantanea la legge comunque la galleria).
    if (startedForUi()) {
      gallery.warmLibrary(LibraryInstant.Screen(resources.displayMetrics.widthPixels, resources.displayMetrics.density, resources.configuration.fontScale))
    }
    notifications = AvvioTrace.section("MCG:app:notificheGrafo") { notificationsLazy.get() }
    integrations = AvvioTrace.section("MCG:app:integrazioni") { integrationsLazy.get() }
    fiveB = AvvioTrace.section("MCG:app:5b") { fiveBLazy.get() }
    ai = AvvioTrace.section("MCG:app:ai") { aiLazy.get() }
    albums = AvvioTrace.section("MCG:app:album") { albumsLazy.get() }
    faces = AvvioTrace.section("MCG:app:persone") { facesLazy.get() }
    memories = AvvioTrace.section("MCG:app:ricordi") { memoriesLazy.get() }
    map = AvvioTrace.section("MCG:app:mappa") { mapLazy.get() }
    AvvioTrace.section("MCG:app:fase10") { installPhase10() }
    // Nella build release questa funzione e' vuota: vedi src/release/.../AppLogging.kt.
    installLogging()
    AvvioTrace.section("MCG:app:notifiche") { notifications.createChannels() }
    // Fase 7 (P13): "Aggiungi ad album" nella ricezione, installato con le altre porte della ricezione.
    integrations.incomingAlbums = albums.incoming
    // Fase 5B (P14): prima di qualunque componente creato dal sistema (selettore, provider, ricezione) e di qualunque schermata.
    AvvioTrace.section("MCG:app:installaIntegrazioni") { integrations.install() }
    // Correzioni 5B (R4, MC-2): "Condividi" con l'impostazione per il viewer (anche senza backup) e per la selezione della Libreria.
    gallery.shares = integrations.shares
    fiveB.ifPresent { ui ->
      gallery.fiveB = GalleryGraph.FiveBHooks(
        mediaActions = ui.mediaActions,
        freeSpace = { ids -> ui.openFreeSpace(ids) },
        // 5B I3 (G2): "Elimina dal telefono" della selezione della Libreria passa dal foglio "Prima mettilo al sicuro" quando il backup e' attivo.
        backupActive = { ui.backupActive },
        deleteFromPhoneSafely = ui::deleteFromPhoneSafely,
        // FAB-7: la selezione della Libreria si chiude quando l'utente va avanti dal foglio, non quando lo apre.
        phoneSheetProceeded = ui.phoneSheetProceeded,
      )
    }
    // Fase 6 (P11): testo dal vivo, "Trova simili" e foto in movimento nel viewer, prima di qualunque schermata.
    gallery.searchViewer = AvvioTrace.section("MCG:app:viewerAi") {
      ai.viewerHooks(graph, gallery, CutoutShareEnv(integrations.shareCache, integrations.uris, integrations.shareHolder, integrations.revoker))
    }
    // Fase 6 (P9): lo strumento di valutazione della ricerca, solo in debug (in release la funzione e' vuota).
    dev.pampa.mycloudgallery.debugtools.DeveloperTools.installSearch(ai, gallery, this)
    AvvioTrace.section("MCG:app:processo") {
      ProcessWatcher(
        graph = graph,
        notifications = notifications,
        ensureDaily = { (graph.scheduler as? WorkManagerTrustSyncScheduler)?.ensureDaily() },
        gallery = galleryLifecycle(),
        afterFirstDraw = { AfterFirstDraw.await() },
      ).start(this)
    }
  }

  /**
   * Fase 10 (P13, docs/fase10-progetto.md §1.4): il descrittore (prima di qualunque schermata), il widget (il fornitore in `WidgetHost` prima che un ricevitore o un lavoro lo
   * chiedano, poi le iscrizioni), e i ganci dell'azzeramento (D42: widget per primi, altre finestre chiuse). Il manifest e' gia' nato con il grafo e legge il suo stato.
   */
  private fun installPhase10() {
    describer = describerLazy.get()
    widget = widgetLazy.get()
    dev.pampa.mycloudgallery.widget.WidgetWiring.install(this, widget, gallery, memories, lockController, { widgetSettingsLazy.get() }, graph.scope)
    graph.widgetStopper = dev.pampa.mycloudgallery.graph.GalleryStopper { widget.stop() }
    graph.windowsCloser = {
      dev.pampa.mycloudgallery.window.WindowLauncher.closeOtherWindows(this, windowRegistry.primary ?: NO_TASK)
    }
    // D22 (P7, "Cosa deve fare P13" punto 5): al blocco dell'app la cache dei nomi e del testo letto si vuota (anche in secondo piano: `onBackground` qui sotto).
    graph.scope.launch {
      lockController.state.collect { if (it == dev.pampa.mycloudgallery.core.security.lock.AppLockState.LOCKED) describer.forget() }
    }
  }

  /** Il processo nasce per mostrare un'activity (il sistema lo tiene in primo piano mentre la lancia), non per un lavoro in background. */
  private fun startedForUi(): Boolean {
    val info = android.app.ActivityManager.RunningAppProcessInfo()
    android.app.ActivityManager.getMyMemoryState(info)
    return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }

  private companion object {
    /** Nessuna finestra principale conosciuta: si chiudono tutte le altre (il task -1 non esiste). */
    const val NO_TASK = -1

    /** R2: quanto aspetta la galleria partita (famiglia pronta) la lettura del manifest all'apertura; dopo, si rinuncia fino alla prossima apertura. */
    const val START_WAIT_MS = 20_000L
  }

  /** `onTrimMemory` verso la cache delle miniature (nota di P6) e l'istantanea della timeline. */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (::gallery.isInitialized) gallery.onTrimMemory(level)
    // Fase 6 (D35, P13): il motore delle query e il ritagliatore del soggetto si rilasciano a memoria bassa (si ricaricano all'uso).
    if (::ai.isInitialized) ai.onTrimMemory(level)
    // Fase 8 (P13, §2.2 "Rilascio"): rilevatore ed embedder si rilasciano a memoria bassa (si riaprono da soli), e le copertine in memoria si buttano.
    if (::faces.isInitialized) faces.onTrimMemory(level)
    // Fase 9 (P13, §1.4 "Memoria"): la tabella a colonne dei ricordi e le mini-mappe in memoria si lasciano (si rifanno all'uso).
    if (::memories.isInitialized) memories.onTrimMemory(level)
    if (::map.isInitialized) map.onTrimMemory(level)
  }

  private fun galleryLifecycle(): GalleryLifecycle = object : GalleryLifecycle {
    override fun start() {
      gallery.startWhenReady {
        // WG-1: la galleria parte (anche con una configurazione nuova, nello stesso processo dopo un azzeramento): il widget fermo dall'azzeramento torna a rispondere.
        if (::widget.isInitialized) widget.resume()
        // D15: accendere Indicizzatore chiede subito il modello del calcolo (lavoro accelerato). Iscritti subito: un cambio non va perso.
        graph.scope.launch {
          dev.pampa.mycloudgallery.ai.watchIndexerModels(ai.ai.indexer, { gallery.backgroundWorkAllowed }, { trigger ->
            AfterFirstDraw.await()
            graph.quietly { ai.localModels.refreshOnce() }
            ai.localModels.needsRequest(dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id, trigger)
          }) { trigger ->
            AfterFirstDraw.launchAfter(graph.scope) {
              AiWorkScheduling.fetchNow(this@MyCloudGalleryApp, trigger)
            }
          }
        }
        // Avvio istantaneo (28/10): il resto non serve a disegnare la Libreria e parte dopo il primo disegno utile (al piu' 1,5 s
        // dall'avvio del processo). I lavori pianificati sono gli stessi di prima, con le stesse regole: cambia solo il momento.
        AfterFirstDraw.launchAfter(graph.scope) { startDeferred() }
      }
    }

    /** Cio' che parte con la galleria ma dopo il primo disegno utile: pianificazione dei lavori, indice di ricerca, modelli, ritagli. */
    private fun startDeferred() {
      // Lavori in background solo con la famiglia pronta, e mai nella variante di misura (dataset sintetico).
      if (gallery.backgroundWorkAllowed) {
        AvvioTrace.section("MCG:app:pianificaLavori") {
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
      }
      // Fase 6 (P11): l'indice di ricerca si apre con il catalogo (vive anche senza NAS), i modelli presenti si verificano (P3).
      graph.scope.launch {
        AvvioTrace.asyncSection("MCG:ai:ricercaApri") { runCatching { ai.search.open() } }
        AvvioTrace.asyncSection("MCG:ai:modelli") { runCatching { ai.localModels.refreshOnce() } }
        // P13: una riga in attesa lasciata da un "Salva come nuova foto" interrotto dalla morte del processo si toglie (IS_PENDING).
        AvvioTrace.asyncSection("MCG:ai:ritagli") { runCatching { ai.cutoutPhotos.purgeOrphans() } }
      }
      // Fase 10 (P13, nota di P3): i widget che ci sono si ridisegnano con le porte appena montate (e compongono in anticipo lo svuotamento di D5).
      if (::widget.isInitialized) dev.pampa.mycloudgallery.widget.WidgetWiring.publishAtStart(widget)
      // P13 (D47): le installazioni di prima dei ritagli ricevono, una volta sola, la sorgente "Ritagli" del backup (con la recinzione di D13 in debug).
      graph.scope.launch {
        runCatching {
          val user = graph.accountStore.observe().first()?.user
          graph.backupSettingsStorage.offerCutoutsOnce(ai.cutoutFence.sourceFor(user), ai.cutoutFence::isCutouts)
        }
      }
    }

    override fun onForeground() {
      gallery.onForeground()
      if (::widget.isInitialized) widget.appForegrounded()
      // All'apertura: una verifica del NAS se l'ultima ha piu' di 15 minuti (§1.4), poi la sincronizzazione dell'indice. Dopo il primo disegno.
      AfterFirstDraw.launchAfter(graph.scope) {
        if (gallery.backgroundWorkAllowed && gallery.nas?.verifyDue() == true) NasWorkScheduling.scanNow(this@MyCloudGalleryApp)
      }
      // R2 (revisione di sicurezza della Fase 10, D29): il manifest anche all'apertura, se l'ultima lettura ha piu' di 24 ore, dopo il primo disegno (avvio istantaneo),
      // con la famiglia pronta e l'area comune nota; non aspetta il giro dell'indice, che puo' non finire mai.
      AfterFirstDraw.launchAfter(graph.scope) {
        val started = gallery.backgroundWorkAllowed && kotlinx.coroutines.withTimeoutOrNull(START_WAIT_MS) { gallery.started.first { it } } == true
        val ready = started && graph.onboardingStore.snapshotOrNull()?.commonShare != null
        dev.pampa.mycloudgallery.release.ReleaseWiring.reads(graph).atAppStart(ready)
      }
    }

    override fun onBackground() {
      gallery.onBackground()
      // Fase 10 (P13): l'ultima finestra si e' fermata. Il widget fa un giro dopo la calma (la Home torna visibile, §2.1); il descrittore dimentica nomi e testo (D22).
      if (::widget.isInitialized) widget.appBackgrounded()
      if (::describer.isInitialized) describer.forget()
    }

    override fun onResume() = gallery.refreshAccess()
  }
}
