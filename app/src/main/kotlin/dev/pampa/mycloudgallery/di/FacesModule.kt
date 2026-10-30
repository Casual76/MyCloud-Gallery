package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiWiring
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.faces.AppActivationPorts
import dev.pampa.mycloudgallery.faces.AppFacesClock
import dev.pampa.mycloudgallery.faces.AppFacesRequirements
import dev.pampa.mycloudgallery.faces.AppPeopleChoices
import dev.pampa.mycloudgallery.faces.AppPeopleEntryStatus
import dev.pampa.mycloudgallery.faces.AppViewerPeople
import dev.pampa.mycloudgallery.faces.FacesCropsFacade
import dev.pampa.mycloudgallery.faces.FacesEraserFacade
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.FacesPeopleFacade
import dev.pampa.mycloudgallery.faces.FacesSwitchFacade
import dev.pampa.mycloudgallery.faces.FacesWiring
import dev.pampa.mycloudgallery.faces.PeopleBridge
import dev.pampa.mycloudgallery.faces.PeopleDiagnostics
import dev.pampa.mycloudgallery.faces.PeopleOpener
import dev.pampa.mycloudgallery.faces.peopleSectionProvider
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.people.activation.ActivationPorts
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.erase.ErasePorts
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryStatus
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import javax.inject.Singleton

/**
 * Le persone della **Fase 8** (docs/fase8-progetto.md §1.4, P13) legate alle implementazioni vere dentro un solo [FacesGraph] per processo. I
 * `@HiltViewModel` di feature-people, feature-settings e feature-viewer vedono solo i contratti (porte opzionali, legate qui): le facciate rispondono come i
 * null-object finche' le persone non sono montate (famiglia non pronta), poi parlano con quelle vere. La ricerca e gli album smart ricevono le persone dal
 * ponte [PeopleBridge] (nato prima di loro, legato quando le persone si montano): finche' non sono attive qui falliscono chiusi (il nome resta testo).
 */
@Module
@InstallIn(SingletonComponent::class)
object FacesModule {

  /** Il ponte verso la ricerca e gli album: una sola istanza per processo, passata a `AiModule` e `AlbumsModule` prima che le persone esistano. */
  @Provides
  @Singleton
  fun peopleBridge(): PeopleBridge = PeopleBridge()

  @Provides
  @Singleton
  fun facesGraph(
    @ApplicationContext context: Context,
    graph: AppGraph,
    gallery: GalleryGraph,
    ai: AiStack,
    albums: AlbumsGraph,
    bridge: PeopleBridge,
    opener: PeopleOpener,
  ): FacesGraph = FacesWiring.build(context, graph, gallery, ai, albums, bridge).also { faces ->
    // L'azzeramento ferma le persone per prime (§1.4): motori rilasciati, calcolo fermo, niente si apre piu'; poi si tolgono database e chiavi.
    graph.facesStopper = GalleryStopper {
      bridge.unbind()
      faces.stop()
    }
    // La riga "Persone" nelle info del viewer: sempre legata (senza persone attive qui non compare nessun chip: fallisce chiuso).
    val people = FacesPeopleFacade(faces, graph.scope)
    gallery.viewerPeople = AppViewerPeople(faces, people, FacesCropsFacade(faces), opener)
    // Lo strumento di valutazione delle persone (F105): solo debug, in sola lettura (in release la funzione e' vuota).
    DeveloperTools.installPeople(faces, people)
    faces.start()
  }

  @Provides
  @Singleton
  fun peopleOpener(): PeopleOpener = PeopleOpener()

  @Provides
  @Singleton
  fun peopleFacade(faces: FacesGraph, graph: AppGraph): FacesPeopleFacade = FacesPeopleFacade(faces, graph.scope)

  @Provides fun people(facade: FacesPeopleFacade): People = facade

  @Provides fun personFaces(facade: FacesPeopleFacade): PersonFaces = facade

  @Provides fun hiddenPeople(facade: FacesPeopleFacade): HiddenPeople = facade

  @Provides
  @Singleton
  fun peopleCrops(faces: FacesGraph): FacesCropsFacade = FacesCropsFacade(faces)

  @Provides fun peopleCropsPort(facade: FacesCropsFacade): PeopleCrops = facade

  @Provides
  @Singleton
  fun facesSwitch(faces: FacesGraph, graph: AppGraph): FacesSwitch = FacesSwitchFacade(faces, graph.scope)

  @Provides
  @Singleton
  fun facesClock(graph: AppGraph, gallery: GalleryGraph): FacesClock = AppFacesClock(graph.nasInputs, { gallery.nas?.clockGuard?.blocked == true })

  @Provides
  @Singleton
  fun facesRequirements(graph: AppGraph, ai: AiStack): FacesRequirements = AppFacesRequirements(
    inputs = graph.nasInputs,
    flags = graph.flags,
    roles = { AiWiring.familyRoles(graph, ai.ai.indexer.value) },
    indexerHere = { ai.ai.indexer.value },
    nasNow = { graph.nasInputs.nasClock.nowMillisOrNull() },
  )

  @Provides
  @Singleton
  fun activationPorts(
    switch: FacesSwitch,
    requirements: FacesRequirements,
    faces: FacesGraph,
    graph: AppGraph,
    gallery: GalleryGraph,
    ai: AiStack,
    indexer: IndexerControl,
    people: FacesPeopleFacade,
    eraser: FacesEraserFacade,
  ): ActivationPorts = AppActivationPorts(switch, requirements, faces, graph, gallery, { ai.ai.indexer.value }, indexer, people, eraser)

  @Provides
  @Singleton
  fun eraserFacade(faces: FacesGraph, graph: AppGraph): FacesEraserFacade = FacesEraserFacade(faces, graph.scope)

  @Provides
  @Singleton
  fun erasePorts(eraser: FacesEraserFacade, clock: FacesClock): ErasePorts = object : ErasePorts {
    override val eraser = eraser
    override val clock = clock
  }

  @Provides
  @Singleton
  fun peopleEntryStatus(switch: FacesSwitch, graph: AppGraph): PeopleEntryStatus = AppPeopleEntryStatus(switch, graph.scope)

  @Provides
  fun peopleChoices(people: FacesPeopleFacade): PeopleChoices =
    AppPeopleChoices(people, people)

  /** La sezione Persone delle Raccolte (§2.1, D25): sempre c'e', anche da spenta, con "Attiva riconoscimento dei volti". */
  @Provides
  @IntoSet
  fun peopleSection(
    @ApplicationContext context: Context,
    people: FacesPeopleFacade,
    switch: FacesSwitch,
    faces: FacesGraph,
    graph: AppGraph,
  ): CollectionsSectionProvider = peopleSectionProvider(context.applicationContext.resources, people, switch.state, faces, graph.scope, switch.offElsewhere)

  /** La sezione "Persone" della Diagnostica (§2.10): stato e un elemento per codice, solo numeri. */
  @Provides
  @IntoSet
  fun peopleDiagnostics(faces: FacesGraph): DiagnosticsContributor = PeopleDiagnostics(faces)
}
