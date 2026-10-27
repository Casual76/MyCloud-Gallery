package dev.pampa.mycloudgallery.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayoutStore
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.navigation.AppCollectionsLayoutStore
import dev.pampa.mycloudgallery.navigation.AppCollectionsSections
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le Raccolte della Fase 7 (P11, D33) legate in `:app` (P13): il deposito dell'ordine e della visibilita' delle sezioni sopra i dati personali e un
 * fornitore per sezione (`@IntoSet`): Album, Album smart e Viaggi (P10), Cartelle NAS (la 4B, prima "Sul NAS" passato come `extraGroups`), Utility
 * (P8, con "Libera spazio" della 5B) e "Da tenere al sicuro" senza voci (gli Eliminati di recente sono nell'Utility, una volta sola: D26).
 */
@Module
@InstallIn(SingletonComponent::class)
object CollectionsModule {

  @Provides
  @Singleton
  fun collectionsLayoutStore(gallery: GalleryGraph, graph: AppGraph): CollectionsLayoutStore {
    val parts = gallery.nas?.organizer?.partsState
    val stores: StateFlow<PersonalStore?> = parts?.map { it?.personalStore }?.stateIn(graph.scope, SharingStarted.Eagerly, parts.value?.personalStore)
      ?: MutableStateFlow(null)
    return AppCollectionsLayoutStore(stores, graph.scope)
  }

  @Provides
  @Singleton
  fun appCollectionsSections(
    @ApplicationContext context: Context,
    gallery: GalleryGraph,
    graph: AppGraph,
    albums: Albums,
    trips: Trips,
    people: Optional<AlbumPeople>,
    clock: AlbumScreensClock,
    items: CollectionItemsSource,
  ): AppCollectionsSections {
    val organizer = gallery.nas?.organizer
    val configured = organizer?.partsState?.map { it != null } ?: flowOf(false)
    val active = organizer?.operations?.operations?.map { list -> list.count { it.active } } ?: AppCollectionsSections.NO_OPERATIONS
    return AppCollectionsSections(
      resources = context.applicationContext.resources,
      albums = albums,
      trips = trips,
      people = people.orElse(AccountNamesAsIs),
      clock = clock,
      items = items,
      nasConfigured = configured,
      operations = active,
      // "Libera spazio" c'e' con il backup e i dialoghi del sistema (come la voce della selezione della Libreria).
      freeSpace = gallery.fiveB != null && gallery.intents.canTrash,
      // "Eliminati di recente": il cestino di Android o quello del NAS (D26).
      canTrash = gallery.intents.canTrash || organizer != null,
      phoneTrash = PhoneTrash.of(Build.MANUFACTURER),
      scope = graph.scope,
    )
  }

  @Provides
  @IntoSet
  fun albumsSection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.albums

  @Provides
  @IntoSet
  fun smartSection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.smart

  @Provides
  @IntoSet
  fun tripsSection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.trips

  @Provides
  @IntoSet
  fun nasSection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.nas

  @Provides
  @IntoSet
  fun utilitySection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.utility

  @Provides
  @IntoSet
  fun safeSection(sections: AppCollectionsSections): CollectionsSectionProvider = sections.safe
}
