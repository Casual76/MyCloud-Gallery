package dev.pampa.mycloudgallery.di

import android.content.Context
import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.albums.AlbumRequests
import dev.pampa.mycloudgallery.albums.AlbumsDiagnostics
import dev.pampa.mycloudgallery.albums.AppIncomingAlbums
import dev.pampa.mycloudgallery.albums.FileIncomingPromises
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.albums.AppAlbumMembers
import dev.pampa.mycloudgallery.albums.AppAlbumPending
import dev.pampa.mycloudgallery.albums.AppAlbumScreensClock
import dev.pampa.mycloudgallery.albums.AppAlbums
import dev.pampa.mycloudgallery.albums.AppShareRunner
import dev.pampa.mycloudgallery.albums.AppSharedAlbums
import dev.pampa.mycloudgallery.albums.AppSmartAlbums
import dev.pampa.mycloudgallery.albums.AppTrips
import dev.pampa.mycloudgallery.albums.AppViewerAlbumActions
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.utility.UtilityDiagnostics
import dev.pampa.mycloudgallery.utility.UtilityGraph
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

/**
 * I contratti della **Fase 7** (gli album) legati alle implementazioni vere (P2-P7) dentro un solo [AlbumsGraph] per processo
 * (docs/fase7-progetto.md §1.4). I `@HiltViewModel` di feature-albums vedono solo i contratti (porte opzionali di `AlbumPortsModule`, legate qui):
 * le facciate rispondono come i null-object finche' gli album non sono montati (famiglia non pronta), poi parlano con quelli veri.
 */
@Module
@InstallIn(SingletonComponent::class)
object AlbumsModule {

  @Provides
  @Singleton
  fun albumsGraph(
    @ApplicationContext context: Context,
    graph: AppGraph,
    gallery: GalleryGraph,
    search: SearchEngine,
    today: Today,
    // Le facciate dipendono dal grafo: si prendono al primo uso (le voci ⋮ del viewer non servono prima).
    albumsFacade: Provider<Albums>,
    sharedFacade: Provider<SharedAlbums>,
    people: dev.pampa.mycloudgallery.faces.PeopleBridge,
  ): AlbumsGraph =
    AlbumsWiring.build(context, graph, gallery, search, today, people).also { albums ->
      // L'azzeramento ferma gli album per primi (§1.4): niente parte piu' e i giri in corso si aspettano, poi si cancellano i dati.
      graph.albumsStopper = GalleryStopper { albums.stop() }
      // P9: un "Nascondi" fatto qui ricalcola subito copertine e conteggi degli album condivisi.
      gallery.onHiddenChanged = { albums.hiddenChanged() }
      // P10-10: "Aggiungi ad album" nel menu ⋮ di ogni viewer (le voci di un album le aggiunge chi apre il viewer da un album).
      gallery.viewerAlbums = AppViewerAlbumActions({ albumsFacade.get() }, { sharedFacade.get() }, gallery.albumRequests, graph.scope)
      // P10-10: "Aggiungi ad album" dalla condivisione in arrivo: la promessa su disco, mantenuta quando i file sono nel catalogo.
      val incoming = AppIncomingAlbums(
        { albumsFacade.get() },
        { sharedFacade.get() },
        FileIncomingPromises(File(context.noBackupFilesDir, "${AlbumsWiring.DIR}/$INCOMING_PROMISES")),
        { stage -> AppIncomingAlbums.stageMediaOf(gallery.database, stage) },
        gallery.albumRequests,
      )
      albums.incoming = incoming
      albums.afterCatalog = { incoming.resolve() }
      albums.start()
    }

  /** Il file delle promesse della ricezione verso gli album (dentro `albums/`, che l'azzeramento toglie). */
  internal const val INCOMING_PROMISES: String = "incoming-promises"

  /** Le richieste "Aggiungi ad album" (selezione, viewer, Nascosti): una sola coda per processo, quella della galleria. */
  @Provides fun albumRequests(gallery: GalleryGraph): AlbumRequests = gallery.albumRequests

  @Provides
  @Singleton
  fun appAlbums(albums: AlbumsGraph, graph: AppGraph): AppAlbums = AppAlbums(albums, graph.scope)

  @Provides fun albums(albums: AppAlbums): Albums = albums

  @Provides fun smartChoices(albums: AppAlbums): SmartChoices = albums

  @Provides
  @Singleton
  fun sharedAlbums(albums: AlbumsGraph, graph: AppGraph): SharedAlbums = AppSharedAlbums(albums, graph.scope)

  @Provides
  @Singleton
  fun shareRunner(albums: AlbumsGraph, graph: AppGraph): ShareRunner = AppShareRunner(albums, graph.scope)

  @Provides
  @Singleton
  fun appSmartAlbums(albums: AlbumsGraph): AppSmartAlbums = AppSmartAlbums(albums)

  @Provides fun smartAlbumEngine(smart: AppSmartAlbums): SmartAlbumEngine = smart

  @Provides fun smartNameInterpreter(smart: AppSmartAlbums): SmartNameInterpreter = smart

  @Provides
  @Singleton
  fun trips(albums: AlbumsGraph, graph: AppGraph): Trips = AppTrips(albums, graph.scope)

  @Provides fun albumMembers(albums: AlbumsGraph): AlbumMembers = AppAlbumMembers(albums)

  @Provides fun albumPending(albums: AlbumsGraph): AlbumPending = AppAlbumPending(albums)

  /** "Oggi" dalla porta `Today` (D36) e l'orologio monotono: senza questo nessun foglio degli album conferma (P10-8). */
  @Provides fun albumScreensClock(today: Today): AlbumScreensClock = AppAlbumScreensClock(today) { SystemClock.elapsedRealtime() }

  /** La sezione "Album" della Diagnostica (§2.8): stato e un elemento per codice, solo numeri. */
  @Provides
  @IntoSet
  fun albumsDiagnostics(albums: AlbumsGraph): DiagnosticsContributor = AlbumsDiagnostics(albums)

  /** DG-2: la manutenzione dell'Utility (controllo notturno dei video del NAS, firme dei Doppioni simili): solo numeri, nella stessa sezione. */
  @Provides
  @IntoSet
  fun utilityDiagnostics(utility: UtilityGraph): DiagnosticsContributor = UtilityDiagnostics(utility)

  /** La vista senza nascosti della Libreria (D22): "Aggiungi ad album" offre solo questi. */
  @Provides fun visibleMedia(gallery: GalleryGraph): VisibleMedia = gallery.visibleMedia
}
