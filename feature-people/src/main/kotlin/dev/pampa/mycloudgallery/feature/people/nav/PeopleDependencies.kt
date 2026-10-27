package dev.pampa.mycloudgallery.feature.people.nav

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.noop.NoHiddenPeople
import dev.pampa.mycloudgallery.core.faces.noop.NoPeople
import dev.pampa.mycloudgallery.core.faces.noop.NoPersonFaces
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLE SCHERMATE DELLE PERSONE PER DAGGER (Fase 8, P9; D7 della Fase 2: @HiltViewModel). Tutte **opzionali**: `:app` (P13) lega i contratti
// veri (`People` e `PersonFaces` e `HiddenPeople` sopra `ArchivePeople` di core-faces); finche' non lo fa le schermate partono lo stesso con i
// null-object, che **falliscono chiusi** (nessuna persona, nessun volto, ogni azione "Persone non e' attiva qui") e il grafo di `:app` compila.

/** I contratti con cui parlano le schermate delle persone, gia' risolti sui null-object quando `:app` non li ha legati. */
class PeopleDependencies @Inject constructor(
  people: Optional<People>,
  faces: Optional<PersonFaces>,
  hidden: Optional<HiddenPeople>,
) {
  val people: People = people.orElse(NoPeople)
  val faces: PersonFaces = faces.orElse(NoPersonFaces)
  val hidden: HiddenPeople = hidden.orElse(NoHiddenPeople)
}

/** Le porte delle persone, opzionali per Dagger: `:app` le lega ai contratti di core-faces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PeoplePortsModule {

  @BindsOptionalOf
  abstract fun people(): People

  @BindsOptionalOf
  abstract fun personFaces(): PersonFaces

  @BindsOptionalOf
  abstract fun hiddenPeople(): HiddenPeople
}
