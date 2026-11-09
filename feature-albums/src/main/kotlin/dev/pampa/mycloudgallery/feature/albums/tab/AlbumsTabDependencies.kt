package dev.pampa.mycloudgallery.feature.albums.tab

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.NoSpecialAlbums
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbums
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLA SCHEDA ALBUM PER DAGGER (fase grafica, G2b). Opzionali come quelle delle altre schermate degli album ([AlbumPortsModule]): `:app` le lega
// (`AlbumsTabModule`); senza, la scheda mostra solo gli album e i Preferiti vuoti, e nessuna voce si ricorda come aperta.

/** Le voci speciali e l'ultima apertura, gia' risolte sui null-object quando `:app` non le ha legate. */
class AlbumsTabDependencies @Inject constructor(specials: Optional<SpecialAlbums>, openings: Optional<AlbumOpenings>) {
  val specials: SpecialAlbums = specials.orElse(NoSpecialAlbums)
  val openings: AlbumOpenings = openings.orElse(NoAlbumOpenings)

  companion object {
    /** Nessuna porta: per i test che costruiscono i ViewModel a mano. */
    val NONE: AlbumsTabDependencies = AlbumsTabDependencies(Optional.empty(), Optional.empty())
  }
}

/** Le porte della scheda Album, opzionali per Dagger. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlbumsTabPortsModule {

  @BindsOptionalOf
  abstract fun specialAlbums(): SpecialAlbums

  @BindsOptionalOf
  abstract fun albumOpenings(): AlbumOpenings
}
