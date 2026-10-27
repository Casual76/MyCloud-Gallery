package dev.pampa.mycloudgallery.feature.albums.nav

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartChoices
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.noop.NoTrips
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.NoPeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import java.time.LocalDate
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLE SCHERMATE DEGLI ALBUM PER DAGGER (Fase 7, P10; D7 della Fase 2: @HiltViewModel). Tutte **opzionali**: `:app` (P13) lega i contratti veri
// (`Albums`, `SharedAlbums`, `ShareRunner`, `SmartNameInterpreter`, `SmartAlbumEngine`, `Trips`, `VisibleMedia`, e le porte di feature-albums);
// finche' non lo fa le schermate partono lo stesso con i null-object, che **falliscono chiusi** (nessun album, nessuna proposta, nessun permesso,
// nessuna conferma) e il grafo di `:app` compila.

/** I contratti con cui parlano le schermate degli album, gia' risolti sui null-object quando `:app` non li ha legati. */
class AlbumDependencies @Inject constructor(
  albums: Optional<Albums>,
  shared: Optional<SharedAlbums>,
  choices: Optional<SmartChoices>,
  runner: Optional<ShareRunner>,
  interpreter: Optional<SmartNameInterpreter>,
  engine: Optional<SmartAlbumEngine>,
  trips: Optional<Trips>,
  people: Optional<AlbumPeople>,
  members: Optional<AlbumMembers>,
  pending: Optional<AlbumPending>,
  clock: Optional<AlbumScreensClock>,
  visible: Optional<VisibleMedia>,
  /** Fase 8 (P10): le persone che si scelgono per un album smart; senza, l'editor dice che le persone non sono attive. */
  peopleChoices: Optional<PeopleChoices>,
) {
  val albums: Albums = albums.orElse(NoAlbums)
  val shared: SharedAlbums = shared.orElse(NoSharedAlbums)
  val choices: SmartChoices = choices.orElse(NoSmartChoices)
  val runner: ShareRunner = runner.orElse(NoShareRunner)
  val interpreter: SmartNameInterpreter = interpreter.orElse(NoSmartNameInterpreter)
  val engine: SmartAlbumEngine = engine.orElse(NoSmartAlbumEngine)
  val trips: Trips = trips.orElse(NoTrips)
  val people: AlbumPeople = people.orElse(AccountNamesAsIs)
  val members: AlbumMembers = members.orElse(NoAlbumMembers)
  val pending: AlbumPending = pending.orElse(NoAlbumPending)

  /** Senza orologio vero nessun foglio conferma mai (l'orologio fermo non fa passare i 600 ms): fallisce chiuso. */
  val clock: AlbumScreensClock = clock.orElse(FixedAlbumScreensClock(LocalDate.of(1970, 1, 1)))

  /** Senza la porta della visibilita' "Aggiungi ad album" non offre nessun media (D22): fallisce chiuso. */
  val visible: VisibleMedia? = visible.orElse(null)

  /** Senza la porta delle persone nessuna persona si offre e l'editor lo dice (E-F37): fallisce chiuso. */
  val peopleChoices: PeopleChoices = peopleChoices.orElse(NoPeopleChoices)
}

/** Le porte delle schermate degli album, opzionali per Dagger: `:app` le lega ai contratti di core-albums, core-library e alle porte di questo modulo. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlbumPortsModule {

  @BindsOptionalOf
  abstract fun albums(): Albums

  @BindsOptionalOf
  abstract fun sharedAlbums(): SharedAlbums

  @BindsOptionalOf
  abstract fun smartChoices(): SmartChoices

  @BindsOptionalOf
  abstract fun shareRunner(): ShareRunner

  @BindsOptionalOf
  abstract fun smartNameInterpreter(): SmartNameInterpreter

  @BindsOptionalOf
  abstract fun smartAlbumEngine(): SmartAlbumEngine

  @BindsOptionalOf
  abstract fun trips(): Trips

  @BindsOptionalOf
  abstract fun albumPeople(): AlbumPeople

  @BindsOptionalOf
  abstract fun albumMembers(): AlbumMembers

  @BindsOptionalOf
  abstract fun albumPending(): AlbumPending

  @BindsOptionalOf
  abstract fun albumScreensClock(): AlbumScreensClock

  @BindsOptionalOf
  abstract fun visibleMedia(): VisibleMedia

  @BindsOptionalOf
  abstract fun peopleChoices(): PeopleChoices
}
