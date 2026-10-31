package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.PeopleBridge
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryStatus
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.memories.AndroidMusicPlayback
import dev.pampa.mycloudgallery.memories.AppMemoriesEntryStatus
import dev.pampa.mycloudgallery.memories.AppMusicLibrary
import dev.pampa.mycloudgallery.memories.AppNotificationAccess
import dev.pampa.mycloudgallery.memories.MemoriesDiagnostics
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import dev.pampa.mycloudgallery.memories.MemoriesWiring
import dev.pampa.mycloudgallery.memories.MemoryPeopleFacade
import dev.pampa.mycloudgallery.memories.MusicPermissions
import dev.pampa.mycloudgallery.memories.memoriesSectionProvider
import dev.pampa.mycloudgallery.utility.SearchShotVectors
import dev.pampa.mycloudgallery.work.MemoryNotifySlot
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * I ricordi e le loro notifiche della **Fase 9** (docs/fase9-progetto.md §1.4, P13) legati alle implementazioni vere dentro un solo [MemoriesGraph] per
 * processo. I `@HiltViewModel` di feature-memories e feature-settings vedono solo i contratti (porte opzionali di `MemoriesPortsModule` e
 * `MemoriesEntryOptionalModule`, legate qui): la facciata risponde "sto preparando i ricordi" finche' non sono montati (famiglia non pronta, primo disegno),
 * poi parla con il motore vero. Il montaggio e' **dopo il primo disegno utile**: niente sul percorso dell'avvio.
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoriesModule {

  @Provides
  @Singleton
  fun memoriesGraph(
    @ApplicationContext context: Context,
    graph: AppGraph,
    gallery: GalleryGraph,
    albums: AlbumsGraph,
    faces: FacesGraph,
    bridge: PeopleBridge,
    ai: AiStack,
    search: Provider<SearchData>,
    today: Today,
    slot: MemoryNotifySlot,
    hooks: @JvmSuppressWildcards Optional<MeasurementHooks>,
  ): MemoriesGraph = MemoriesWiring.build(
    context = context,
    scope = graph.scope,
    gallery = gallery,
    albums = albums,
    faces = faces,
    lookup = bridge,
    vectors = SearchShotVectors(data = { search.get() }),
    today = today,
    cutoutsFolder = ai.cutoutFence::folder,
    slot = slot,
    afterFirstDraw = { AfterFirstDraw.await() },
    measuredStore = hooks.orElse(null)?.memoriesPersonalStore,
  ).also { memories ->
    // L'azzeramento ferma i ricordi per primi (§1.4, D37): niente si monta piu', il lavoro perde il suo giorno; poi lavoro, notifica e `memories/`.
    graph.memoriesStopper = GalleryStopper {
      memories.stop()
      if (memories.notifierBuilt) memories.notifier.cancel()
      // I4: i permessi di lettura delle canzoni scelte (l'app non ne ha altri) si rilasciano tutti: il sistema li ricorderebbe anche dopo l'azzeramento.
      MusicPermissions.releaseAll(context)
    }
    // P6 e P10: un "Nascondi" fatto qui ripulisce lo scaffale e rivaluta la notifica in tendina, subito.
    gallery.hiddenListeners += { memories.onHiddenChanged() }
    // P11: lo strumento di valutazione dei ricordi (F125), solo debug e in sola lettura (in release la funzione e' vuota).
    DeveloperTools.installMemories(memories)
    memories.start()
    // P12: solo una variante di misura (`MeasurementHooks`): il pilota della generazione dei ricordi per il benchmark. Niente altrove.
    hooks.orElse(null)?.onMemoriesGraph(memories)
  }

  @Provides fun memories(graph: MemoriesGraph): Memories = graph.memories

  @Provides
  @Singleton
  fun memoryPeople(graph: MemoriesGraph): MemoryPeople = MemoryPeopleFacade(graph)

  /** I nomi dei luoghi di un ricordo (id GeoNames): quelli della Libreria, gia' scritti. */
  @Provides
  fun memoryPlaceNames(gallery: GalleryGraph): MemoryPlaceNames = MemoryPlaceNames { ids -> ids.mapNotNull { gallery.places.labelOfId(it) } }

  @Provides fun notifySettings(graph: MemoriesGraph): NotifySettings = graph.notifySettings

  @Provides fun musicSettings(graph: MemoriesGraph): MusicSettings = graph.musicSettings

  @Provides
  @Singleton
  fun musicLibrary(@ApplicationContext context: Context): MusicLibrary = AppMusicLibrary(AppMusicLibrary.persistedOf(context))

  /** Un lettore per presentazione (si ferma e lascia il focus all'uscita). */
  @Provides
  fun musicPlayback(@ApplicationContext context: Context): MusicPlayback = AndroidMusicPlayback(context)

  @Provides
  @Singleton
  fun appNotificationAccess(@ApplicationContext context: Context, graph: MemoriesGraph, app: AppGraph): AppNotificationAccess =
    AppNotificationAccess(context, { graph.notifier }, app.scope)

  @Provides fun notificationAccess(access: AppNotificationAccess): NotificationAccess = access

  /** La voce "Ricordi e notifiche" di Impostazioni: frequenza scelta e, con un pallino, permesso o canale che bloccano. */
  @Provides
  @Singleton
  fun memoriesEntryStatus(graph: MemoriesGraph, access: AppNotificationAccess, app: AppGraph): MemoriesEntryStatus =
    AppMemoriesEntryStatus({ graph.notifySettings }, access, app.scope)

  /** La sezione Ricordi delle Raccolte (codice `memories`): dall'ultimo scaffale, poi con il filtro finale dei Nascosti. Una per processo. */
  @Provides
  @IntoSet
  @Singleton
  fun memoriesSection(@ApplicationContext context: Context, graph: MemoriesGraph, people: MemoryPeople, places: MemoryPlaceNames, gallery: GalleryGraph, app: AppGraph): CollectionsSectionProvider =
    memoriesSectionProvider(context.applicationContext.resources, graph.memories, people, places, gallery.hiddenMask, app.scope)

  /** La sezione "Ricordi" della Diagnostica (§2.12): stato e un elemento per codice, solo numeri. */
  @Provides
  @IntoSet
  fun memoriesDiagnostics(graph: MemoriesGraph, today: Today): DiagnosticsContributor = MemoriesDiagnostics(graph) { today.date() }
}
