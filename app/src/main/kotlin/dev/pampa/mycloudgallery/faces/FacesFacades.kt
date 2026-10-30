package dev.pampa.mycloudgallery.faces

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FaceChip
import dev.pampa.mycloudgallery.core.faces.contract.FaceTokens
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.MediaFaces
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.OthersView
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.PersonName
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFace
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceAction
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPersonChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

// LE FACCIATE DELLE PERSONE (Fase 8, P13; la stessa idea di `AppAlbums` per gli album): le schermate di feature-people, feature-settings e feature-viewer
// vedono solo contratti (porte opzionali di Hilt) e parlano con queste facciate, che rispondono come i null-object finche' le persone non sono montate
// (famiglia non pronta) e poi con quelle vere. Nessuna copia di stato: ogni vista segue `FacesGraph.live`. Mai nomi, id o conteggi per persona nei log (D37).

/** Le persone di adesso: le viste e le azioni di [People], [PersonFaces], [HiddenPeople] e [MediaFaces] sopra le persone montate; vuote e "non attive" prima. */
@OptIn(ExperimentalCoroutinesApi::class)
class FacesPeopleFacade(private val graph: FacesGraph, scope: CoroutineScope) : People, PeopleIndex, PersonFaces, HiddenPeople, MediaFaces {

  private val live = graph.live

  private fun now() = graph.live.value?.people

  override val list: StateFlow<PeopleList> = live.flatMapLatest { it?.people?.list ?: flowOf(PeopleList.EMPTY) }.stateIn(scope, SharingStarted.Eagerly, PeopleList.EMPTY)

  /**
   * AT-2 (correzioni B): prima del montaggio una persona non e' "non trovata" ma "non ancora letta": il flusso **non emette niente** e la pagina resta
   * `Loading` (come dopo la morte del processo, con una pagina persona ripristinata prima che la galleria sia partita). Dalle persone montate `null` vuol dire
   * davvero "non c'e'" (o "non c'e' piu'").
   */
  override fun person(id: PersonId): Flow<PersonView?> = live.flatMapLatest { it?.people?.person(id) ?: emptyFlow() }

  override fun others(): Flow<OthersView> = live.flatMapLatest { it?.people?.others() ?: flowOf(OthersView(emptyList())) }

  override suspend fun facesOf(media: MediaId): List<FaceChip> = now()?.facesOf(media).orEmpty()

  override suspend fun rename(id: PersonId, name: String?): EditOutcome = now()?.rename(id, name) ?: EditOutcome.NotActive

  override suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome = now()?.merge(ids, keepNameOf) ?: EditOutcome.NotActive

  override suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome = now()?.split(id, faces) ?: SplitOutcome.Failed(EditOutcome.NotActive)

  override suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome = now()?.assign(face, to) ?: EditOutcome.NotActive

  override suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome = now()?.reject(face, from) ?: EditOutcome.NotActive

  override suspend fun ignore(face: FaceLocator): EditOutcome = now()?.ignore(face) ?: EditOutcome.NotActive

  override suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome = now()?.setHidden(id, hidden) ?: EditOutcome.NotActive

  override suspend fun forget(id: PersonId): EditOutcome = now()?.forget(id) ?: EditOutcome.NotActive

  override suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome = now()?.setCover(id, face) ?: EditOutcome.NotActive

  override suspend fun proposals(): List<MergeProposal> = now()?.proposals().orEmpty()

  override suspend fun answer(proposal: MergeProposal, same: Boolean): EditOutcome = now()?.answer(proposal, same) ?: EditOutcome.NotActive

  override suspend fun knows(id: PersonId): Boolean = now()?.knows(id) ?: false

  override fun hidden(): Flow<List<PersonCard>> = live.flatMapLatest { it?.people?.hidden() ?: flowOf(emptyList()) }

  override suspend fun reviewFaces(person: PersonId): List<PersonFace> = now()?.reviewFaces(person).orEmpty()

  override suspend fun coverMedia(persons: Collection<PersonId>): Map<PersonId, MediaId> = now()?.coverMedia(persons).orEmpty()

  override fun faces(media: MediaId): Flow<List<FaceChip>> = live.flatMapLatest { it?.people?.faces(media) ?: flowOf(emptyList()) }

  override suspend fun choices(): List<PersonCard> = now()?.choices().orEmpty()

  override suspend fun belongsTo(media: MediaId, face: FaceLocator): Boolean = now()?.belongsTo(media, face) ?: false

  override val available: StateFlow<Boolean> = live.flatMapLatest { it?.people?.available ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)

  override suspend fun dictionary(): List<PersonName> = now()?.dictionary().orEmpty()

  /** R10: le persone non montate sono "non so", non "nessuna foto": lancia (Cerca non da' risultati, gli album smart non scrivono niente). */
  override suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel> =
    (now() ?: throw IllegalStateException("Persone non montate.")).contentWith(persons, mode, level)

  override fun resolve(id: PersonId): PersonId? = now()?.resolve(id)

  override fun toString(): String = "FacesPeopleFacade"
}

/** I ritagli dei volti di adesso (copertine, avatar): solo dalle persone montate; null (un segnaposto) prima. Mai su disco. */
class FacesCropsFacade(private val graph: FacesGraph) : PeopleCrops {
  override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? = graph.live.value?.crops?.crop(face, sizePx)

  override fun toString(): String = "FacesCropsFacade"
}

/**
 * L'interruttore di adesso per le pagine di attivazione, dell'avviso e di Impostazioni -> Persone. Prima del montaggio Persone e' spenta e non
 * accendibile ([ActivateOutcome.Unavailable]); i gesti dell'utente non fanno niente di nascosto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacesSwitchFacade(private val graph: FacesGraph, scope: CoroutineScope) : FacesSwitch {
  private val live = graph.live

  override val state: StateFlow<FacesState> = live.flatMapLatest { it?.switch?.state ?: flowOf(FacesState.Off) }.stateIn(scope, SharingStarted.Eagerly, FacesState.Off)

  override fun currentState(): FacesState = graph.live.value?.switch?.currentState() ?: FacesState.Off

  override val fresh: StateFlow<Boolean> = live.flatMapLatest { it?.switch?.fresh ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)

  override val syncPending: StateFlow<Boolean> = live.flatMapLatest { it?.switch?.syncPending ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)

  /** CE-3: spenta da un altro dispositivo (la riga sopra "Attiva riconoscimento dei volti"). */
  override val offElsewhere: StateFlow<Boolean> = live.flatMapLatest { it?.switch?.offElsewhere ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)

  override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = graph.live.value?.switch?.activate(consent) ?: ActivateOutcome.Unavailable

  override suspend fun acceptNotice(textVersion: Int) {
    graph.live.value?.switch?.acceptNotice(textVersion)
  }

  override suspend fun excludeThisDevice() {
    graph.live.value?.switch?.excludeThisDevice()
  }

  override suspend fun includeThisDevice() {
    graph.live.value?.switch?.includeThisDevice()
  }

  override suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome =
    graph.live.value?.switch?.reconcile(setting) ?: ReconcileOutcome.Failed(FacesPrePublish.NOT_READY)

  override fun toString(): String = "FacesSwitchFacade"
}

/**
 * Le persone del media aperto nel viewer (§2.6 "Info del viewer"): i chip dai volti del media (un volto per chip, i volti non riconoscibili in un chip solo),
 * il ritaglio al momento, il foglio "Chi e'?" e le azioni. **Il viewer non vede core-faces**: il volto e' un riferimento opaco (`FaceTokens`), e un
 * riferimento che non e' di quel media non si ritaglia ne' si applica. Una persona nascosta non compare mai (le viste di core-faces la tolgono).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewerPeople(
  private val graph: FacesGraph,
  private val facade: FacesPeopleFacade,
  private val crops: PeopleCrops,
  private val opener: PeopleOpener,
) : ViewerPeople {

  override fun faces(media: MediaId): Flow<List<ViewerFaceChip>> = facade.faces(media).let { flow ->
    kotlinx.coroutines.flow.flow {
      flow.collect { chips -> emit(toChips(chips)) }
    }
  }

  private fun toChips(chips: List<FaceChip>): List<ViewerFaceChip> {
    val out = ArrayList<ViewerFaceChip>()
    var unrecognizable = 0
    for (chip in chips) {
      if (chip.tier == dev.pampa.mycloudgallery.core.model.FaceTier.NONE) {
        unrecognizable++
        continue
      }
      val ref = runCatching { ViewerFaceRef(FaceTokens.of(chip.face)) }.getOrNull() ?: continue
      val person = chip.person
      val name = chip.name
      out += if (person != null && name != null) ViewerFaceChip.Named(ref, person, name) else ViewerFaceChip.Unnamed(ref, person)
    }
    if (unrecognizable > 0) out += ViewerFaceChip.Unrecognizable(unrecognizable)
    return out
  }

  override suspend fun crop(media: MediaId, face: ViewerFaceRef, sizePx: Int): Bitmap? {
    val locator = locatorOf(media, face) ?: return null
    return crops.crop(locator, sizePx)
  }

  override suspend fun choices(media: MediaId, face: ViewerFaceRef): List<ViewerPersonChoice> {
    locatorOf(media, face) ?: return emptyList()
    return facade.choices().mapNotNull { card -> card.name?.let { ViewerPersonChoice(card.id, it) } }
  }

  override suspend fun act(media: MediaId, face: ViewerFaceRef, action: ViewerFaceAction): ViewerFaceOutcome {
    val locator = locatorOf(media, face) ?: return ViewerFaceOutcome.NOT_FOUND
    val outcome = when (action) {
      is ViewerFaceAction.Is -> facade.assign(locator, PersonTarget.Existing(action.person))
      is ViewerFaceAction.IsNew -> facade.assign(locator, PersonTarget.New(action.name))
      is ViewerFaceAction.IsNot -> facade.reject(locator, action.person)
      ViewerFaceAction.Ignore -> facade.ignore(locator)
    }
    return when (outcome) {
      EditOutcome.Done -> ViewerFaceOutcome.DONE
      EditOutcome.InvalidName -> ViewerFaceOutcome.INVALID_NAME
      EditOutcome.NotFound -> ViewerFaceOutcome.NOT_FOUND
      else -> ViewerFaceOutcome.UNAVAILABLE
    }
  }

  override fun openPerson(person: PersonId) = opener.open(person)

  /** Il volto di un riferimento, solo se sta in un contenuto di quel media (un dato ostile non si ritaglia ne' si applica). */
  private suspend fun locatorOf(media: MediaId, face: ViewerFaceRef): FaceLocator? {
    val locator = FaceTokens.parse(face.token) ?: return null
    return locator.takeIf { facade.belongsTo(media, it) }
  }

  override fun toString(): String = "AppViewerPeople"
}

/** Dove porta il tocco su una persona nel viewer: lo scrive la navigazione (chiude il viewer e apre la pagina). */
class PeopleOpener {
  @Volatile var onOpen: (PersonId) -> Unit = {}

  fun open(person: PersonId) = onOpen(person)
}
