package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FaceChip
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.OthersView
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonName
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'ESECUTORE DI RIFERIMENTO DELLE PERSONE (docs/fase8-progetto.md §5.2 `InMemoryPeople`, §2.6, §2.9, D24-D28, Q2): [People] e
 * [PeopleIndex] sopra un'istantanea del raggruppamento ([load]) e le voci `people` scritte qui ([edits]), con la visibilita' della Libreria
 * ([visible], filtro finale [hidden]). Le stesse regole del contratto, scritte nel modo piu' semplice; le persone vere di P9 passano la stessa
 * suite (`PeopleContract`). In src/main/testing per i test di P9, P10, P13; mai nella release.
 *
 * [mediaOf] dice quali celle (membri del gruppo compresi) contengono un contenuto; [keysOf] le chiavi di contenuto di un media; [nasNow]
 * l'ora del NAS (null = le voci restano "da datare" e valgono subito lo stesso); [namedOnly] e' la risposta Q2: vero = (b), solo chi ha un
 * nome in primo piano e gli altri in "Altri volti"; falso = (a), tutti in Persone.
 */
class InMemoryPeople(
  private val visible: VisibleMedia,
  private val hidden: HiddenMask,
  private val mediaOf: (ContentKey) -> Set<MediaId>,
  private val keysOf: (MediaId) -> Set<ContentKey>,
  private val author: String,
  private val nasNow: () -> Long? = { null },
  private val ids: PersonIdSource = PersonIdSource.RANDOM,
  private val namedOnly: Boolean = true,
) : People, PeopleIndex {

  private val lock = Mutex()
  private val revision = MutableStateFlow(0L)

  @Volatile private var state: GroupingState = GroupingState.EMPTY
  @Volatile private var faces: List<GroupFace> = emptyList()
  @Volatile private var noneFaces: List<FaceLocator> = emptyList()
  @Volatile private var suggestions: List<MergeSuggestion> = emptyList()
  @Volatile private var likely: Map<PersonId, Set<ContentKey>> = emptyMap()
  @Volatile private var records: Map<PeopleKey, PeopleRecord> = emptyMap()

  /** Le voci scritte da questo dispositivo, nell'ordine (le prove guardano voce per voce, §2.6). */
  val edits: MutableList<PeopleEdit> = java.util.Collections.synchronizedList(mutableListOf())

  override val available = MutableStateFlow(true)

  private val listState = MutableStateFlow(PeopleList.EMPTY)
  override val list: StateFlow<PeopleList> = listState

  /** Il primo calcolo in corso (percentuale) o null. */
  @Volatile var progressPercent: Int? = null

  /**
   * Carica l'istantanea [state] con i volti correnti [faces] (CORE e WEAK), i volti non riconoscibili [none], le proposte [suggestions], i
   * contenuti nella fascia "forse" per persona [likely] e le voci `people` gia' unite arrivate dagli altri dispositivi [remote].
   */
  suspend fun load(
    state: GroupingState,
    faces: List<GroupFace> = emptyList(),
    none: List<FaceLocator> = emptyList(),
    suggestions: List<MergeSuggestion> = emptyList(),
    likely: Map<PersonId, Set<ContentKey>> = emptyMap(),
    remote: Collection<PeopleRecord> = emptyList(),
  ) {
    lock.withLock {
      this.state = state
      this.faces = faces
      this.noneFaces = none
      this.suggestions = suggestions
      this.likely = likely
      this.records = PeopleRecord.merge(records, remote)
    }
    refresh()
  }

  /** Ricalcola le viste (dopo un cambio della visibilita' della Libreria, come fa la vera). */
  suspend fun refresh() {
    listState.value = computeList()
    revision.value = revision.value + 1
  }

  /** Lo stato delle voci `people` unite (per gli altri dispositivi finti e per le prove). */
  fun records(): Collection<PeopleRecord> = records.values

  private fun overlay() = PeopleOverlay(state, PeopleState.of(records.values))

  // --- Viste -------------------------------------------------------------------------------------------------------------------

  private class Shown(val id: PersonId, val name: String?, val cells: List<MediaId>, val anyFace: Boolean, val cover: FaceLocator?)

  /** Le celle **visibili** di una persona (filtro finale compreso) e la copertina: solo da un media mostrabile (D25, D28). */
  private suspend fun shown(o: PeopleOverlay, id: PersonId): Shown {
    val locators = o.facesOf(id)
    val byFace = locators.associateWith { mediaOf(it.contentKey) }
    val all = byFace.values.flatten().toSet()
    val seen = visible.visible(all)
    val masked = hidden.hiddenAmong(seen)
    val cells = (seen - masked).sortedBy { it.value }
    val chosen = o.people.covers[id]?.takeIf { cover -> byFace.entries.any { it.key.matches(cover) && it.value.any { m -> m in cells } } }
    val cover = chosen ?: locators.firstOrNull { loc -> byFace.getValue(loc).any { it in cells } }
    return Shown(id, o.people.names[id], cells, locators.isNotEmpty(), cover)
  }

  /** D25, E-F38: una persona compare con un nome (o "0 foto" se non ha piu' volti) o con almeno 3 foto visibili; mai con soli nascosti. */
  private fun showable(s: Shown): Boolean = if (s.name != null) s.cells.isNotEmpty() || !s.anyFace else s.cells.size >= MIN_UNNAMED_PHOTOS

  private fun card(s: Shown) = dev.pampa.mycloudgallery.core.faces.contract.PersonCard(s.id, s.name, s.cells.size, s.cover)

  private val byPhotos = compareByDescending<Shown> { it.cells.size }.thenBy { it.id }

  private suspend fun computeList(): PeopleList {
    if (!available.value) return PeopleList.EMPTY
    val o = overlay()
    val all = o.persons.map { shown(o, it) }.filter { showable(it) }
    val (hiddenPeople, rest) = all.partition { it.id in o.people.hidden }
    val named = rest.filter { it.name != null }.sortedWith(byPhotos).map(::card)
    val unnamed = rest.filter { it.name == null }.sortedWith(byPhotos).map(::card)
    return if (namedOnly) {
      PeopleList(named, emptyList(), unnamed.size, hiddenPeople.size, progressPercent)
    } else {
      PeopleList(named, unnamed, 0, hiddenPeople.size, progressPercent)
    }
  }

  override fun person(id: PersonId): Flow<PersonView?> = revision.map { personNow(id) }

  private suspend fun personNow(id: PersonId): PersonView? {
    if (!available.value) return null
    val o = overlay()
    val root = o.resolve(id) ?: return null
    val s = shown(o, root)
    if (!s.anyFace && s.name == null) return null
    val snapshot = visible.snapshot.value ?: dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
    val positions = s.cells.map { snapshot.indexOf(it) }.filter { it >= 0 }.sorted().toIntArray()
    val view = dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView(snapshot, positions)
    val maybe = proposalsNow(o).filter { root == it.first.id || root == it.second.id }
      .map { if (it.first.id == root) it.second else it.first }
    return PersonView(card(s), view, root in o.people.hidden, maybe)
  }

  override fun others(): Flow<OthersView> = revision.map {
    if (!namedOnly || !available.value) return@map OthersView(emptyList())
    val o = overlay()
    OthersView(
      o.persons.filter { it !in o.people.hidden && o.people.names[it] == null }.map { shown(o, it) }
        .filter { showable(it) }.sortedWith(byPhotos).map(::card),
    )
  }

  override suspend fun facesOf(media: MediaId): List<FaceChip> {
    if (!available.value) return emptyList()
    val o = overlay()
    val keys = keysOf(media)
    val chips = ArrayList<FaceChip>()
    for (face in faces.filter { it.ref.contentKey in keys }.sortedBy { it.ref }) {
      if (o.people.isIgnored(face.locator)) continue
      val owner = o.faces.entries.firstOrNull { it.key.matches(face.locator) }?.value
      if (owner != null && owner in o.people.hidden) continue
      chips += FaceChip(face.locator, owner, owner?.let { o.people.names[it] }, face.tier)
    }
    for (none in noneFaces.filter { it.contentKey in keys }.sortedBy { it.encode() }) chips += FaceChip(none, null, null, FaceTier.NONE)
    return chips
  }

  private suspend fun proposalsNow(o: PeopleOverlay): List<MergeProposal> {
    val out = LinkedHashMap<Pair<PersonId, PersonId>, MergeProposal>()
    for (s in suggestions.sortedByDescending { it.link }) {
      val a = o.resolve(s.pair.first) ?: continue
      val b = o.resolve(s.pair.second) ?: continue
      if (a == b || a in o.people.hidden || b in o.people.hidden) continue
      if (o.people.apart.contains(dev.pampa.mycloudgallery.core.faces.contract.PersonPair.of(a, b))) continue
      val sa = shown(o, a)
      val sb = shown(o, b)
      if (sa.cells.isEmpty() || sb.cells.isEmpty()) continue
      val key = if (a < b) a to b else b to a
      out.getOrPut(key) { MergeProposal(card(sa), card(sb)) }
    }
    return out.values.toList()
  }

  override suspend fun proposals(): List<MergeProposal> = if (available.value) proposalsNow(overlay()) else emptyList()

  // --- Azioni (voci `people`) ---------------------------------------------------------------------------------------------------

  private suspend fun write(vararg new: PeopleEdit): EditOutcome {
    lock.withLock {
      val at = nasNow()
      val fresh = new.map { PeopleRecord(it, at, author) }
      // Una voce da datare e' la scelta appena fatta qui: sostituisce la voce della stessa chiave (anche un'altra da datare, che
      // [PeopleRecord.winner] non sa ordinare); con l'ora del NAS vale la regola dell'ultima scrittura.
      records = if (at == null) records + fresh.associateBy { it.key } else PeopleRecord.merge(records, fresh)
      edits += new
    }
    refresh()
    return EditOutcome.Done
  }

  private fun guard(): EditOutcome? = if (available.value) null else EditOutcome.NotActive

  override suspend fun rename(id: PersonId, name: String?): EditOutcome {
    guard()?.let { return it }
    val root = overlay().resolve(id) ?: return EditOutcome.NotFound
    if (name == null) return write(PeopleEdit.Name(root, null))
    val clean = PersonNames.normalize(name) ?: return EditOutcome.InvalidName
    return write(PeopleEdit.Name(root, clean))
  }

  override suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome {
    guard()?.let { return it }
    val o = overlay()
    if (ids.any { o.resolve(it) == null }) return EditOutcome.NotFound
    val roots = ids.mapNotNull { o.resolve(it) }.distinct()
    if (roots.size < 2) return EditOutcome.Invalid
    val named = roots.filter { o.people.names[it] != null }
    val keep = keepNameOf?.let { o.resolve(it) }
    if (keepNameOf != null && keep !in roots) return EditOutcome.Invalid
    if (named.size >= 2 && keep == null) return EditOutcome.NeedsNameChoice
    val survivor = keep ?: named.singleOrNull() ?: roots.map { shown(o, it) }.sortedWith(byPhotos).first().id
    return write(*roots.filter { it != survivor }.map { PeopleEdit.Alias(it, survivor) }.toTypedArray())
  }

  override suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome {
    guard()?.let { return SplitOutcome.Failed(it) }
    val o = overlay()
    val root = o.resolve(id) ?: return SplitOutcome.Failed(EditOutcome.NotFound)
    val mine = o.facesOf(root)
    if (faces.isEmpty() || faces.any { f -> mine.none { it.matches(f) } }) return SplitOutcome.Failed(EditOutcome.Invalid)
    val fresh = ids.next()
    write(PeopleEdit.Born(fresh, root), *faces.map { PeopleEdit.Is(it, fresh) }.toTypedArray(), PeopleEdit.Apart(fresh, root, true))
    return SplitOutcome.Split(fresh)
  }

  override suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome {
    guard()?.let { return it }
    if (faces.none { it.locator.matches(face) }) return EditOutcome.NotFound
    return when (to) {
      is PersonTarget.Existing -> write(PeopleEdit.Is(face, overlay().resolve(to.id) ?: return EditOutcome.NotFound))
      is PersonTarget.New -> {
        val clean = PersonNames.normalize(to.name) ?: return EditOutcome.InvalidName
        val fresh = ids.next()
        write(PeopleEdit.Is(face, fresh), PeopleEdit.Name(fresh, clean))
      }
    }
  }

  override suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome {
    guard()?.let { return it }
    val root = overlay().resolve(from) ?: return EditOutcome.NotFound
    return write(PeopleEdit.Not(face, root, true))
  }

  override suspend fun ignore(face: FaceLocator): EditOutcome {
    guard()?.let { return it }
    return write(PeopleEdit.Ignore(face, true))
  }

  override suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome {
    guard()?.let { return it }
    val root = overlay().resolve(id) ?: return EditOutcome.NotFound
    return write(PeopleEdit.Hidden(root, hidden))
  }

  override suspend fun forget(id: PersonId): EditOutcome {
    guard()?.let { return it }
    val root = overlay().resolve(id) ?: return EditOutcome.NotFound
    return write(PeopleEdit.Forget(root), PeopleEdit.Name(root, null))
  }

  override suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome {
    guard()?.let { return it }
    val o = overlay()
    val root = o.resolve(id) ?: return EditOutcome.NotFound
    if (face != null && o.facesOf(root).none { it.matches(face) }) return EditOutcome.Invalid
    return write(PeopleEdit.Cover(root, face))
  }

  override suspend fun answer(proposal: MergeProposal, same: Boolean): EditOutcome {
    guard()?.let { return it }
    val o = overlay()
    val a = o.resolve(proposal.first.id) ?: return EditOutcome.NotFound
    val b = o.resolve(proposal.second.id) ?: return EditOutcome.NotFound
    if (a == b) return EditOutcome.Invalid
    if (!same) return write(PeopleEdit.Apart(a, b, true))
    return merge(listOf(a, b), keepNameOf = listOf(a, b).firstOrNull { o.people.names[it] != null }.takeIf { o.people.names[a] == null || o.people.names[b] == null })
  }

  override suspend fun knows(id: PersonId): Boolean = available.value && overlay().resolve(id) != null

  // --- Indice per la ricerca e gli album --------------------------------------------------------------------------------------

  override suspend fun dictionary(): List<PersonName> {
    if (!available.value) return emptyList()
    val o = overlay()
    return o.persons.filter { it !in o.people.hidden && o.people.names[it] != null }
      .map { shown(o, it) }.filter { it.cells.isNotEmpty() }
      .map { PersonName(it.id, it.name!!, it.cells.size) }
  }

  override suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel> {
    if (persons.isEmpty()) return emptyMap()
    // R10: come ArchivePeople, "i volti non ci sono adesso" e' "non so", non "nessuna foto".
    check(available.value) { "Persone non attiva qui." }
    val o = overlay()
    val roots = persons.map { o.resolve(it) ?: return emptyMap() }.distinct()
    val sure = roots.associateWith { r -> o.facesOf(r).map { it.contentKey }.toSet() }
    val maybe = roots.associateWith { r -> if (level == MatchLevel.LIKELY) likely[r].orEmpty() else emptySet() }
    val keys = (sure.values.flatten() + maybe.values.flatten()).toSortedSet(compareBy { it.hex })
    val out = LinkedHashMap<ContentKey, MatchLevel>()
    for (key in keys) {
      val levels = roots.map { r -> if (key in sure.getValue(r)) MatchLevel.SURE else if (key in maybe.getValue(r)) MatchLevel.LIKELY else null }
      val result = when (mode) {
        PeopleMode.ALL -> if (levels.any { it == null }) null else if (levels.all { it == MatchLevel.SURE }) MatchLevel.SURE else MatchLevel.LIKELY
        PeopleMode.ANY -> if (levels.any { it == MatchLevel.SURE }) MatchLevel.SURE else if (levels.any { it != null }) MatchLevel.LIKELY else null
      }
      if (result != null) out[key] = result
    }
    return out
  }

  override fun resolve(id: PersonId): PersonId? = overlay().resolve(id)

  private companion object {
    /** D25: una persona senza nome compare con almeno 3 foto visibili. */
    const val MIN_UNNAMED_PHOTOS = 3
  }
}
