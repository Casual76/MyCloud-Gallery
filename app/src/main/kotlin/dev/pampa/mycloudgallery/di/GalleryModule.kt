package dev.pampa.mycloudgallery.di

import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenLibrary
import dev.pampa.mycloudgallery.feature.library.library.LibraryStateSource
import dev.pampa.mycloudgallery.feature.library.trash.TrashHiddenMask
import dev.pampa.mycloudgallery.gallery.AppLibraryStates
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.LibraryDiagnostics
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.nas.ConvergingPreviewQuality
import dev.pampa.mycloudgallery.nas.NasLibraryDiagnostics
import dev.pampa.mycloudgallery.nas.NasOpsDiagnostics
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasFolders
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasOperations
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasSpace
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasTrash
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import dev.pampa.mycloudgallery.backup.BackupDiagnostics
import dev.pampa.mycloudgallery.backup.BackupInputs
import dev.pampa.mycloudgallery.backup.NoBackupDiagnostics
import dev.pampa.mycloudgallery.work.JobSchedulerBackupJobs
import dev.pampa.mycloudgallery.work.JobSchedulerNasOpsJobs
import dev.pampa.mycloudgallery.work.NasOpsJobs
import dev.pampa.mycloudgallery.work.NasWorkScheduling
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualitySettings
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.index.sync.IndexDiagnostics
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * I contratti della **Fase 3** (la galleria del telefono) legati alle implementazioni vere di P2-P10, tutte dentro un solo
 * [GalleryGraph] per processo (docs/fase3-progetto.md §1.4). I `@HiltViewModel` di `feature-library` e `feature-viewer`
 * vedono solo i contratti.
 *
 * Le varianti di misura (`benchmarkRelease` e `nonMinifiedRelease`, P9) legano [MeasurementHooks]: una sincronizzazione
 * ferma, che si preferisce alla vera, e il database del loro dataset sintetico (docs/bench/README.md §6). Nelle altre
 * varianti l'`Optional` e' vuoto: la sincronizzazione vera e `gallery.db`.
 */
@Module
@InstallIn(SingletonComponent::class)
object GalleryModule {

  @Provides
  @Singleton
  fun gallery(
    @ApplicationContext context: Context,
    graph: AppGraph,
    setup: FamilySetupRepository,
    hooks: @JvmSuppressWildcards Optional<MeasurementHooks>,
  ): GalleryGraph {
    val measurement = hooks.orElse(null)
    val parts = AvvioTrace.section("MCG:galleria:parti") { galleryParts(context, graph, measurement) }
    return AvvioTrace.section("MCG:galleria:grafo") { galleryGraph(context, graph, setup, parts) }
  }

  private fun galleryParts(context: Context, graph: AppGraph, measurement: MeasurementHooks?): GalleryParts =
    GalleryParts(
      databaseName = databaseName(measurement),
      replicaDir = SnapshotStore.directory(context),
      preferencesFile = DataStoreLibraryPreferences.file(context),
      access = AndroidPhoneAccessReader(context),
      intents = AndroidPhoneMediaIntents(context),
      recentlyDeleted = AndroidRecentlyDeletedSource(context),
      phone = PhoneLibrary::android,
      syntheticThumbnails = DeveloperTools.syntheticThumbnails(),
      openPlaces = { PlacesDataset.openResult(context) },
      benchSync = measurement?.phoneSync,
      // Fase 4A (P11): il NAS nella Libreria; mai nelle varianti di misura (il loro dataset e' sintetico, P10).
      nas = graph.nasInputs.takeIf { measurement == null },
      // P10, docs/bench/README.md §6-bis: le miniature dei media solo-NAS del dataset vengono dalla replica sintetica.
      benchReplica = measurement?.replicaBlobs,
      // Fase 4B (P16): il lavoro delle operazioni lunghe (UIDT) e la verifica del NAS dopo un'operazione finita.
      nasJobs = if (measurement == null) JobSchedulerNasOpsJobs(context) else NasOpsJobs.NONE,
      onNasOperationsClosed = { if (measurement == null) NasWorkScheduling.scanNow(context) },
      // Fase 5A (P7): il backup; mai nelle varianti di misura. La recinzione di D13 e' di `DeveloperTools`: in release vale null.
      backup = if (measurement == null) backupInputs(context, graph) else null,
      // Fase 6 (P11): le teste `ai` e il buffer del calcolo, legati da `AiModule` prima che il NAS parta.
      ai = { graph.aiHooks },
      // Avvio istantaneo: NAS, backup e lettura del telefono dopo il primo disegno utile (o al piu' 1,5 s dall'avvio del processo).
      afterFirstDraw = { AfterFirstDraw.await() },
      // CA-4 (revisione funzionale della Fase 10): con la versione fermata dal manifest la lettura del telefono in primo piano non parte.
      releaseStopped = { graph.release?.stoppedAfterLoad() == true },
    )

  private fun galleryGraph(context: Context, graph: AppGraph, setup: FamilySetupRepository, parts: GalleryParts): GalleryGraph =
    GalleryGraph(
      context = context,
      scope = graph.scope,
      parts = parts,
      setup = setup,
      ownerAccount = { graph.accountStore.observe().first()?.user?.let(AccountId::of) },
      // L'orologio di riferimento (piano §1): quello del NAS se noto, altrimenti quello del dispositivo.
      clock = { graph.nasClock.nowMillisOrNull() ?: System.currentTimeMillis() },
    ).also { gallery ->
      // L'azzeramento (fase 5) ferma la galleria viva prima di cancellare i file (R7).
      graph.galleryStopper = GalleryStopper { gallery.shutdown() }
      graph.idleGalleryStopper = GalleryStopper { gallery.shutdown(closeUnusedDatabase = true) }
    }

  /**
   * Cio' che il backup riceve dall'app (Fase 5A, P7): impostazioni del dispositivo, regole (D34), sonda delle share (E-I19), nome del
   * dispositivo (D7), **recinzione del telefono di D13** (`DeveloperTools.backupFence()`: null in release, dove la classe non esiste) e
   * lavori avviati dall'utente (UIDT).
   */
  internal fun backupInputs(context: Context, graph: AppGraph): BackupInputs = BackupInputs(
    settingsStorage = graph.backupSettingsStorage,
    ruleSettings = graph.ruleSettings,
    shareProbe = graph.shareProbe,
    deviceName = {
      val id = runCatching { graph.engine.myDeviceId() }.getOrNull()
      id?.let { runCatching { graph.engine.state?.registry?.device(it)?.name }.getOrNull() } ?: graph.profiles.load()?.deviceName
    },
    fence = DeveloperTools.backupFence(),
    jobs = JobSchedulerBackupJobs(context),
  )

  /**
   * Il catalogo: `gallery.db`; con le [MeasurementHooks] (benchmarkRelease **e** nonMinifiedRelease, I3) il dataset sintetico di
   * P9, che sta in un file a parte: la scelta segue il fatto che le hooks ci siano, non il nome del tipo di build.
   */
  internal fun databaseName(hooks: MeasurementHooks?): String = hooks?.databaseName ?: GalleryDatabase.NAME

  @Provides fun thumbnailLoader(gallery: GalleryGraph): ThumbnailLoader = gallery.thumbnails

  @Provides fun placeholderCache(gallery: GalleryGraph): PlaceholderCache = gallery.placeholders

  @Provides fun placeNames(gallery: GalleryGraph): PlaceNames = gallery.places

  @Provides fun gridLayoutEngine(gallery: GalleryGraph): GridLayoutEngine = gallery.layoutEngine

  @Provides fun libraryPreferences(gallery: GalleryGraph): LibraryPreferences = gallery.preferences

  @Provides fun localMediaActions(gallery: GalleryGraph): LocalMediaActions = gallery.actions

  @Provides fun phoneMediaIntents(gallery: GalleryGraph): PhoneMediaIntents = gallery.intents

  @Provides fun recentlyDeletedSource(gallery: GalleryGraph): RecentlyDeletedSource = gallery.recentlyDeleted

  @Provides fun collectionItemsSource(gallery: GalleryGraph): CollectionItemsSource = gallery.collectionItems

  @Provides fun hiddenLibrary(gallery: GalleryGraph): HiddenLibrary = gallery.hiddenLibrary

  @Provides fun trashHiddenMask(gallery: GalleryGraph): TrashHiddenMask = gallery.trashMask

  /**
   * Il filtro finale dei Nascosti (D31) per le schermate dei ricordi e della mappa, che lo chiedono **senza `Optional`** (R3, 31/10): se questo legame manca, `:app` non
   * compila. Sta qui, con il resto della galleria, e non in un modulo degli album: prima stava in `AlbumsModule` dietro tre `@BindsOptionalOf`, che compilano anche senza
   * nessun legame e lasciavano il filtro finale muto senza che niente se ne accorgesse. Lo prova `HiddenMaskWiringTest`.
   */
  @Provides fun hiddenMask(gallery: GalleryGraph): HiddenMask = gallery.hiddenMask

  @Provides fun libraryStateSource(states: AppLibraryStates): LibraryStateSource = states

  // --- Diagnostica: la libreria del telefono (core-mediastore) e indice, luoghi e miniature (:app) ----------------
  @Provides
  @IntoSet
  fun phoneLibraryDiagnostics(gallery: GalleryGraph): DiagnosticsContributor = gallery.phoneDiagnostics

  @Provides
  @IntoSet
  fun libraryDiagnostics(gallery: GalleryGraph): DiagnosticsContributor = LibraryDiagnostics(gallery)

  // --- Diagnostica: il NAS nella Libreria (Fase 4A, P11): stato, scansione, preparazione (:app) e indice (core-index) --
  @Provides
  @IntoSet
  fun nasLibraryDiagnostics(gallery: GalleryGraph): DiagnosticsContributor =
    gallery.nas?.let { NasLibraryDiagnostics(it) } ?: NoNasDiagnostics

  // --- Impostazioni: "Anteprime del NAS" (Fase 4A, P11). Senza NAS la pagina dice che non sono attive. ---
  @Provides
  fun previewQualitySettings(gallery: GalleryGraph, graph: AppGraph, @ApplicationContext context: Context): PreviewQualitySettings =
    gallery.nas?.let { nas ->
      // F12: un cambio di qualita' accende o spegne HQ_PREVIEWS e accoda subito la convergenza (con le regole delle attivita').
      ConvergingPreviewQuality(nas.previewQuality, graph.ruleSettings, started = { nas.runtime != null }) { level ->
        runCatching { NasWorkScheduling.convergeNow(context, level) }
      }
    } ?: NoPreviewQuality

  @Provides
  @IntoSet
  fun indexDiagnostics(gallery: GalleryGraph): DiagnosticsContributor =
    gallery.nas?.let { IndexDiagnostics(it.counters, it.qualityControl, DiagCatalog::titleOf) } ?: NoNasDiagnostics

  // --- Fase 4B (P16): organizzare il NAS. I ViewModel di feature-nas vedono solo i contratti; senza NAS i null-object (falliscono
  // chiusi: niente si pianifica, niente parte). -----------------------------------------------------------------------------
  @Provides fun nasOperations(gallery: GalleryGraph): NasOperations = gallery.nas?.organizer?.operations ?: NoNasOperations

  @Provides fun nasTrash(gallery: GalleryGraph): NasTrash = gallery.nas?.organizer?.trash ?: NoNasTrash

  @Provides fun nasSpace(gallery: GalleryGraph): NasSpace = gallery.nas?.organizer?.space ?: NoNasSpace

  @Provides fun nasFolders(gallery: GalleryGraph): NasFolders = gallery.nas?.organizer?.folders ?: NoNasFolders

  @Provides fun opClocks(gallery: GalleryGraph): OpClocks = gallery.nas?.organizer?.clocks ?: MonotonicOnlyClocks(gallery.parts.monotonicMs)

  @Provides fun nasAreas(gallery: GalleryGraph): NasAreas = gallery.nas?.organizer?.areas ?: NoNasAreas

  @Provides
  @IntoSet
  fun nasOpsDiagnostics(gallery: GalleryGraph): DiagnosticsContributor =
    gallery.nas?.let { NasOpsDiagnostics(it) } ?: NoNasDiagnostics

  // --- Fase 5A (P7): il backup in Diagnostica (stato, un elemento per problema, ultimo giro, copie simili) ---
  @Provides
  @IntoSet
  fun backupDiagnostics(gallery: GalleryGraph): DiagnosticsContributor =
    gallery.backup?.let { BackupDiagnostics(it, gallery.similarCopies) } ?: NoBackupDiagnostics
}

/** Senza NAS: l'orologio monotono (le conferme), nessuna ora del NAS (niente si scrive). */
private class MonotonicOnlyClocks(private val monotonic: () -> Long) : OpClocks {
  override fun monotonicMs(): Long = monotonic()

  override fun nasNowMs(): Long? = null
}

/** Senza NAS: nessuna area. */
private object NoNasAreas : NasAreas {
  override val areas: StateFlow<List<dev.pampa.mycloudgallery.feature.nas.contract.NasArea>> = MutableStateFlow(emptyList())
}

/** Senza il NAS nella Libreria (varianti di misura): la qualita' non c'e'. */
private object NoPreviewQuality : PreviewQualitySettings {
  override val state: StateFlow<PreviewQualityState?> = MutableStateFlow(null)

  override suspend fun choose(level: PreviewLevel) = Unit

  override suspend fun showAlbumCovers(show: Boolean) = Unit
}

/** Senza il NAS nella Libreria (varianti di misura): nessuna voce. */
private object NoNasDiagnostics : DiagnosticsContributor {
  override val section: DiagSection = DiagSection.NAS_LIBRARY

  override suspend fun collect(): List<DiagItem> = emptyList()
}
