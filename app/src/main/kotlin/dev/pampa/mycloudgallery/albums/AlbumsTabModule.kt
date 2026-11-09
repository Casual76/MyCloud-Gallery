package dev.pampa.mycloudgallery.albums

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.setup.SetupDataStores
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbums
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.graph.AppGraph
import javax.inject.Singleton

// LA SCHEDA ALBUM NEL GRAFO DELL'APP (fase grafica, G2b): le due porte opzionali di `AlbumsTabDependencies`. Le voci speciali sono le viste delle
// raccolte-filtro (la stessa `StateFlow` della loro griglia, sopra l'indice della Libreria visibile: mai i nascosti, l'accesso parziale rispettato);
// l'ultima apertura e' un DataStore del profilo.

@Module
@InstallIn(SingletonComponent::class)
object AlbumsTabModule {

  @Provides
  fun specialAlbums(items: CollectionItemsSource): SpecialAlbums = SpecialAlbums { kind -> items.items(kind.filter).view }

  @Provides
  @Singleton
  fun albumOpenings(@ApplicationContext context: Context, graph: AppGraph): AlbumOpenings {
    // Come i DataStore di AppGraph: `noBackupFilesDir/datastore`, un file rovinato vale vuoto.
    val store = PreferenceDataStoreFactory.create(
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
      scope = graph.scope,
      produceFile = { SetupDataStores.fileOf(context, DataStoreAlbumOpenings.FILE_NAME) },
    )
    return DataStoreAlbumOpenings(store, graph.scope)
  }
}
