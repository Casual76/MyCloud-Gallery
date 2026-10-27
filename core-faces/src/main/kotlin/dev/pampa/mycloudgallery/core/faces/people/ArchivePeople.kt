package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FaceChip
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.MediaFaces
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.OthersView
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleArchiveSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFace
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonName
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.faces.grouping.GroupingProposals
import dev.pampa.mycloudgallery.core.faces.people.PeopleCore.Loaded
import dev.pampa.mycloudgallery.core.faces.people.PeopleCore.Shown
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LE PERSONE VERE (docs/fase8-progetto.md §1.2 "Persone" e "Indice", §2.6, §2.9, D22-D28, Q2 (b) del 27/10; riga P9 del §4.4): [People] e
 * [PeopleIndex] sopra l'**archivio** della generazione corrente ([PeopleArchiveSource]: l'istantanea del raggruppamento e le voci `people`
 * unite), con la Libreria per cio' che si puo' mostrare ([PeopleVisibility]) e le celle per i contenuti ([PeopleCells]). Passa la stessa
 * suite di contratto dell'esecutore di riferimento (`PeopleContract`).
 *
 * - **Viste** ([list], [person], [others], [hidden], [faces], [dictionary]): nascosti mai (D28) e copertine solo da un media mostrabile
 *   (D25); si rifanno da sole quando cambia la Libreria, l'opzione di Q2, la disponibilita' o arriva un dato nuovo ([triggers]: la
 *   sincronizzazione, il custode), e **subito** dopo ogni azione.
 * - **Azioni** ([PeopleEditor]): voci `people` nell'archivio (valgono subito, E-F5) e in coda alla pubblicazione.
 * - Q2 e' un'**opzione** ([variant]): (b) predefinita, (a) dietro l'opzione.
 * - Persone non attiva qui ([availability] falso) o nessun archivio: elenchi vuoti, ogni azione `NotActive`.
 *
 * Mai nomi, id o conteggi per persona nei log (D37): questa classe non logga.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ArchivePeople(
  archives: PeopleArchiveSource,
  private val cells: PeopleCells,
  private val visibility: PeopleVisibility,
  publisher: FacesPublisher,
  clock: FacesClock,
  author: String,
  scope: CoroutineScope,
  ids: PersonIdSource = PersonIdSource.RANDOM,
  private val variant: StateFlow<PeopleVariant> = constant(PeopleVariant.DEFAULT),
  private val availability: StateFlow<Boolean> = constant(true),
  private val progress: StateFlow<Int?> = constant(null),
  triggers: Flow<Unit> = emptyFlow(),
) : People, PeopleIndex, HiddenPeople, MediaFaces, PersonFaces, FaceCropGate {

  private val core = PeopleCore(archives, cells, visibility)
  private val viewLock = Mutex()
  private val tick = MutableStateFlow(0L)
  private val listState = MutableStateFlow(PeopleList.EMPTY)
  private val editor = PeopleEditor(core, publisher, clock, author, ids) { refresh(reload = false) }
  private val proposalLock = Mutex()
  private var proposalMemo: Triple<Loaded, Long, List<MergeProposal>>? = null

  /**
   * Dal piu' fotografato; a pari foto, per id. **Prima del blocco `init`**: il primo `refresh` parte sul dispositivo di `scope` (un altro thread) e usa questo ordine; una proprieta'
   * dichiarata dopo `init` puo' essere ancora nulla in quel momento.
   */
  private val byPhotos = compareByDescending<Shown> { it.cells.size }.thenBy { it.id }

  override val list: StateFlow<PeopleList> = listState.asStateFlow()

  override val available: StateFlow<Boolean> = availability

  init {
    // La prima volta subito; poi, quando la Libreria cambia (un nascosto, una foto nuova), l'opzione di Q2 o la disponibilita', poco alla volta.
    scope.launch { refresh(reload = true) }
    scope.launch {
      combine(visibility.snapshot, variant, availability) { snapshot, v, a -> Triple(snapshot?.seq, v, a) }
        .drop(1)
        .debounce(REFRESH_DEBOUNCE_MS)
        .collectLatest { refresh(reload = true) }
    }
    // Un dato nuovo da fuori (la replica ha portato voci o un'istantanea, il custode ha pubblicato): si rilegge l'archivio.
    scope.launch { triggers.collectLatest { refresh(reload = true) } }
    // L'avanzamento del primo calcolo cambia spesso: non rifa' le viste, aggiorna solo il numero.
    scope.launch { progress.collect { percent -> listState.update { it.copy(progressPercent = percent) } } }
  }

  /** Rilegge l'archivio (se [reload]) e rifa' le viste. Lo chiamano le azioni, i [triggers] e chi lega (dopo un giro di replica). */
  suspend fun refresh(reload: Boolean = true) {
    viewLock.withLock {
      listState.value = if (availability.value) {
        if (reload) core.reload()
        computeList()
      } else {
        PeopleList.EMPTY
      }
      tick.update { it + 1 }
    }
  }

  // --- Viste ------------------------------------------------------------------------------------------------------------------------

  /** D25, E-F38: con un nome si compare con almeno una foto mostrabile (o con "0 foto" se non ha piu' volti), senza con almeno 3. */
  private fun showable(s: Shown): Boolean = if (s.name != null) s.cells.isNotEmpty() || !s.anyFace else s.cells.size >= MIN_UNNAMED_PHOTOS


  private suspend fun computeList(): PeopleList {
    val l = core.current() ?: return PeopleList.EMPTY
    val all = core.shownFor(l, l.lens.persons).values.filter { showable(it) }
    val (hiddenPeople, rest) = all.partition { it.hidden }
    val named = rest.filter { it.name != null }.sortedWith(byPhotos).map { it.card() }
    val unnamed = rest.filter { it.name == null }.sortedWith(byPhotos).map { it.card() }
    return if (variant.value.namedOnly) {
      PeopleList(named, emptyList(), unnamed.size, hiddenPeople.size, progress.value)
    } else {
      PeopleList(named, unnamed, 0, hiddenPeople.size, progress.value)
    }
  }

  override fun person(id: PersonId): Flow<PersonView?> = tick.mapLatest { personNow(id) }

  private suspend fun personNow(id: PersonId): PersonView? {
    if (!availability.value) return null
    val l = core.current() ?: return null
    val root = l.lens.resolve(id) ?: return null
    val s = core.shownFor(l, listOf(root)).getValue(root)
    // Una persona senza nessuna foto mostrabile non ha pagina (D28), salvo un nome che non si ritrova piu' ("0 foto · ricontrolla").
    if (s.cells.isEmpty() && !(s.name != null && !s.anyFace)) return null
    val snapshot = visibility.snapshot.value ?: return null
    val positions = s.cells.map { snapshot.indexOf(it) }.filter { it >= 0 }.sorted().toIntArray()
    val maybe = proposalsFor(l).filter { root == it.first.id || root == it.second.id }.map { if (it.first.id == root) it.second else it.first }
    return PersonView(s.card(), IndexOrderedTimelineView(snapshot, positions), s.hidden, maybe)
  }

  override fun others(): Flow<OthersView> = tick.mapLatest {
    if (!variant.value.namedOnly || !availability.value) return@mapLatest OthersView(emptyList())
    val l = core.current() ?: return@mapLatest OthersView(emptyList())
    val unnamed = l.lens.persons.filter { l.lens.people.names[it] == null && it !in l.lens.people.hidden }
    OthersView(core.shownFor(l, unnamed).values.filter { showable(it) }.sortedWith(byPhotos).map { it.card() })
  }

  override fun hidden(): Flow<List<PersonCard>> = tick.mapLatest {
    if (!availability.value) return@mapLatest emptyList()
    val l = core.current() ?: return@mapLatest emptyList()
    val hiddenRoots = l.lens.persons.filter { it in l.lens.people.hidden }
    core.shownFor(l, hiddenRoots).values.filter { showable(it) }.sortedWith(byPhotos).map { it.card() }
  }

  // --- I volti di un media (riga "Persone" del viewer) -----------------------------------------------------------------------------

  override suspend fun facesOf(media: MediaId): List<FaceChip> {
    if (!availability.value) return emptyList()
    val l = core.current() ?: return emptyList()
    val lens = l.lens
    val keys = cells.keysOf(media)
    if (keys.isEmpty()) return emptyList()
    val bodies = l.archive.winners(keys)
    val chips = ArrayList<FaceChip>()
    for (key in keys.sortedBy { it.hex }) {
      val body = bodies[key] ?: continue
      for (entry in body.faces) {
        val place = FaceLocator(key, entry.frame, entry.box)
        // Ignorato, o di una persona che l'utente ha dimenticato: non compare (E-F27, "Ignora questo volto").
        if (lens.isIgnored(place) || lens.belongsToForgotten(place)) continue
        if (entry.tier == FaceTier.NONE) {
          chips += FaceChip(place, null, null, FaceTier.NONE)
          continue
        }
        val owner = lens.ownerOf(place)
        // Una persona nascosta non compare nella riga (D28).
        if (owner != null && owner in lens.people.hidden) continue
        chips += FaceChip(place, owner, owner?.let { lens.people.names[it] }, entry.tier)
      }
    }
    return chips
  }

  override fun faces(media: MediaId): Flow<List<FaceChip>> = tick.mapLatest { facesOf(media) }

  override suspend fun choices(): List<PersonCard> = listState.value.named

  override suspend fun belongsTo(media: MediaId, face: FaceLocator): Boolean = face.contentKey in cells.keysOf(media)

  override suspend fun reviewFaces(person: PersonId): List<PersonFace> {
    if (!availability.value) return emptyList()
    val l = core.current() ?: return emptyList()
    val root = l.lens.resolve(person) ?: return emptyList()
    val shown = core.shownFor(l, listOf(root)).getValue(root)
    val score = l.grouping.assignments.values.associate { it.locator to it.score }
    return shown.shownFaces.map { (face, media) -> PersonFace(face, media, l.lens.isConfirmed(face)) }
      .sortedWith(compareBy<PersonFace> { it.confirmed }.thenBy { score[it.face] ?: 1f }.thenBy { it.face.encode() })
  }

  override suspend fun coverMedia(persons: Collection<PersonId>): Map<PersonId, MediaId> {
    if (!availability.value || persons.isEmpty()) return emptyMap()
    val l = core.current() ?: return emptyMap()
    val roots = persons.mapNotNull { l.lens.resolve(it) }.distinct()
    val shown = core.shownFor(l, roots)
    return roots.mapNotNull { id ->
      val s = shown[id] ?: return@mapNotNull null
      val cover = s.cover ?: return@mapNotNull null
      s.shownFaces.firstOrNull { it.first == cover }?.second?.let { id to it }
    }.toMap()
  }

  override suspend fun mayShow(face: FaceLocator): Boolean =
    visibility.shown(cells.cellsWith(listOf(face.contentKey))[face.contentKey].orEmpty()).isNotEmpty()

  // --- Proposte "E' la stessa persona?" ---------------------------------------------------------------------------------------------

  override suspend fun proposals(): List<MergeProposal> {
    if (!availability.value) return emptyList()
    val l = core.current() ?: return emptyList()
    return proposalsFor(l)
  }

  private suspend fun proposalsFor(l: Loaded): List<MergeProposal> = proposalLock.withLock {
    val seq = visibility.snapshot.value?.seq ?: -1L
    proposalMemo?.let { (loaded, at, memo) -> if (loaded === l && at == seq) return@withLock memo }
    val lens = l.lens
    val suggestions = GroupingProposals.of(GroupingInput(l.grouping, l.archive.groupFaces(), lens.people.corrections()))
    val pairs = LinkedHashMap<PersonPair, Float>()
    for (s in suggestions.sortedByDescending { it.link }) {
      val a = lens.resolve(s.pair.first) ?: continue
      val b = lens.resolve(s.pair.second) ?: continue
      if (a == b || a in lens.people.hidden || b in lens.people.hidden) continue
      val pair = PersonPair.of(a, b)
      if (pair !in lens.people.apart) pairs.putIfAbsent(pair, s.link)
    }
    val shown = core.shownFor(l, pairs.keys.flatMap { listOf(it.first, it.second) }.distinct())
    pairs.keys.mapNotNull { pair ->
      val first = shown[pair.first]
      val second = shown[pair.second]
      if (first == null || second == null || first.cells.isEmpty() || second.cells.isEmpty()) null else MergeProposal(first.card(), second.card())
    }.take(MAX_PROPOSALS).also { proposalMemo = Triple(l, seq, it) }
  }

  // --- Azioni (voci `people`) ---------------------------------------------------------------------------------------------------------

  /** Persone non attiva su questo dispositivo (spenta, in attesa dell'avviso, esclusa, in cancellazione): ogni azione e' `NotActive`, niente si scrive. */
  private suspend fun act(body: suspend () -> EditOutcome): EditOutcome = if (availability.value) body() else EditOutcome.NotActive

  override suspend fun rename(id: PersonId, name: String?): EditOutcome = act { editor.rename(id, name) }

  override suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome = act { editor.merge(ids, keepNameOf) }

  override suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome =
    if (availability.value) editor.split(id, faces) else SplitOutcome.Failed(EditOutcome.NotActive)

  override suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome = act { editor.assign(face, to) }

  override suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome = act { editor.reject(face, from) }

  override suspend fun ignore(face: FaceLocator): EditOutcome = act { editor.ignore(face) }

  override suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome = act { editor.setHidden(id, hidden) }

  override suspend fun forget(id: PersonId): EditOutcome = act { editor.forget(id) }

  override suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome = act { editor.setCover(id, face) }

  override suspend fun answer(proposal: MergeProposal, same: Boolean): EditOutcome =
    act { editor.answer(proposal.first.id, proposal.second.id, same) }

  // --- Indice per la ricerca e gli album ---------------------------------------------------------------------------------------------

  override suspend fun dictionary(): List<PersonName> {
    if (!availability.value) return emptyList()
    val l = core.current() ?: return emptyList()
    val named = l.lens.persons.filter { it !in l.lens.people.hidden && l.lens.people.names[it] != null }
    return core.shownFor(l, named).values.filter { it.cells.isNotEmpty() }.sortedWith(byPhotos).map { PersonName(it.id, it.name!!, it.cells.size) }
  }

  override suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel> {
    if (!availability.value || persons.isEmpty()) return emptyMap()
    val l = core.current() ?: return emptyMap()
    val resolved = persons.map { l.lens.resolve(it) }
    // "Tutte": una persona che non c'e' piu' (dimenticata) non puo' esserci in nessuna foto; "una qualunque": le altre contano.
    if (mode == PeopleMode.ALL && resolved.any { it == null }) return emptyMap()
    val roots = resolved.filterNotNull().distinct()
    if (roots.isEmpty()) return emptyMap()
    val sure = roots.associateWith { r -> l.lens.facesOf(r).map { it.contentKey }.toSet() }
    val maybe = if (level == MatchLevel.LIKELY) PeopleLikely.contentFor(l, roots) else emptyMap()
    val keys = (sure.values.flatten() + maybe.values.flatten()).toSortedSet(compareBy { it.hex })
    val out = LinkedHashMap<ContentKey, MatchLevel>()
    for (key in keys) {
      val levels = roots.map { r -> if (key in sure.getValue(r)) MatchLevel.SURE else if (key in maybe[r].orEmpty()) MatchLevel.LIKELY else null }
      val result = when (mode) {
        PeopleMode.ALL -> if (levels.any { it == null }) null else if (levels.all { it == MatchLevel.SURE }) MatchLevel.SURE else MatchLevel.LIKELY
        PeopleMode.ANY -> if (levels.any { it == MatchLevel.SURE }) MatchLevel.SURE else if (levels.any { it != null }) MatchLevel.LIKELY else null
      }
      if (result != null) out[key] = result
    }
    return out
  }

  /** Segue gli alias con quello che l'archivio ha **adesso**; null = dimenticata, sconosciuta o nessun archivio. Sincrona: non rilegge. */
  override fun resolve(id: PersonId): PersonId? = core.peek()?.lens?.resolve(id)

  private companion object {
    /** D25: una persona senza nome compare con almeno 3 foto mostrabili. */
    const val MIN_UNNAMED_PHOTOS = 3

    /** Le proposte "E' la stessa persona?" mostrate al piu' tante alla volta. */
    const val MAX_PROPOSALS = 30

    /** La Libreria cambia spesso durante una scansione: le viste si rifanno al massimo ogni tanto. */
    const val REFRESH_DEBOUNCE_MS = 250L

    fun <T> constant(value: T): StateFlow<T> = MutableStateFlow(value).asStateFlow()
  }
}
