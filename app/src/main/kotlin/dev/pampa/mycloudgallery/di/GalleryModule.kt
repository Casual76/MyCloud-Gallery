package dev.pampa.mycloudgallery.di

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
    val parts = GalleryParts(
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
    )
    return GalleryGraph(
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
    }
  }

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

  @Provides fun libraryStateSource(states: AppLibraryStates): LibraryStateSource = states

  // --- Diagnostica: la libreria del telefono (core-mediastore) e indice, luoghi e miniature (:app) ----------------
  @Provides
  @IntoSet
  fun phoneLibraryDiagnostics(gallery: GalleryGraph): DiagnosticsContributor = gallery.phoneDiagnostics

  @Provides
  @IntoSet
  fun libraryDiagnostics(gallery: GalleryGraph): DiagnosticsContributor = LibraryDiagnostics(gallery)
}
