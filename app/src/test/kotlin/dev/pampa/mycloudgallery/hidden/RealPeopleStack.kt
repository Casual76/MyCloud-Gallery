package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleArchiveSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.people.ArchivePeople
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.integration.closeScope
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 8 (P9, D28, riga PEOPLE del registro): LO STACK VERO DELLE PERSONE sopra il catalogo VERO del mondo casuale. `ArchivePeople` (core-faces) con l'archivio dei volti
// (in memoria, le stesse regole del contratto), la visibilita' vera (`CatalogVisibleMedia`, `CatalogHiddenMask`) e la Libreria vera (l'istantanea del mondo). I volti sono
// SINTETICI (solo vettori, nessuna immagine): da zero a quattro per media, tutti i media compresi i nascosti, i membri dei gruppi con un nascosto e gli "ultimi nascosti" (dopo
// l'istantanea). Una persona vive solo su media che non si devono mai mostrare. Il banco passa dallo stesso codice del prodotto; l'oracolo ricava cosa deve comparire dal solo
// spec del mondo.

/**
 * Le celle del mondo: la cella di un media e' lui stesso o, se sta in un gruppo, la copertina del gruppo (la convenzione di `FacesCatalog.cellKeys` e `mediaWith`: una persona
 * compare nella cella che contiene il suo volto, D26).
 */
internal class WorldPeopleCells(private val w: BuiltWorld, private val keyOfIndex: (Int) -> ContentKey) : PeopleCells {
  private val indexOfKey: Map<ContentKey, Int> = w.ids.indices.associateBy(keyOfIndex)

  fun cellIndex(index: Int): Int = w.spec.groups.firstOrNull { index in it.members }?.cover ?: index

  override suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> =
    keys.mapNotNull { key -> indexOfKey[key]?.let { key to setOf(w.ids[cellIndex(it)]) } }.toMap()

  override suspend fun keysOf(media: MediaId): Set<ContentKey> {
    val index = w.indexOf(media)
    val members = w.spec.groups.firstOrNull { it.cover == index }?.members ?: listOf(index)
    return members.mapTo(LinkedHashSet(), keyOfIndex)
  }
}

internal class RealPeopleStack private constructor(val w: BuiltWorld, leaky: Boolean) : AutoCloseable {
  private val seed = w.spec.seed
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val identities = SyntheticIdentities(seed)
  private val personIds = GroupingStories.ids(seed * 17 + 3)
  private val generation = FaceGeneration.fromBytes(ByteArray(FaceGeneration.BYTES) { (it + seed.toInt()).toByte() })

  /** La visibilita' vera; con [leaky] il filtro e' tolto (la prova al contrario: la proprieta' deve diventare rossa). */
  val visible: VisibleMedia = if (leaky) AllVisible(MutableStateFlow(w.snapshot)) else CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
  val mask: HiddenMask = if (leaky) HiddenMask { emptySet() } else CatalogHiddenMask(w.db)

  /**
   * Le persone normali: tre con un nome, una senza nome nascosta, una con un nome nascosta, una dimenticata, due senza nome (i gruppi di "Altri volti"); e quella che vive
   * solo su media da non mostrare. I nomi sono inventati.
   */
  val persons: List<PersonId> = List(PERSONS) { personIds.next() }
  val onlyForbidden: PersonId = personIds.next()
  val named: List<Pair<PersonId, String>> = listOf(persons[0] to "Anna", persons[1] to "Marco", persons[2] to "Giulia", persons[4] to "Luca")
  val hiddenPerson: PersonId = persons[3]
  val namedHiddenPerson: PersonId = persons[4]
  val hiddenPersons: Set<PersonId> = setOf(hiddenPerson, namedHiddenPerson)
  val forgottenPerson: PersonId = persons[5]

  fun keyOf(index: Int): ContentKey = GroupingStories.contentKey(seed, index)

  val cells = WorldPeopleCells(w, ::keyOf)

  /** Tutti i volti del mondo (sui media di qualunque tipo) e di chi sono. */
  val faces: List<GroupFace>
  val ownerOf: Map<FaceRef, PersonId>
  private val mediaOfFace: Map<FaceRef, Int>
  val noneFaces: List<FaceLocator>

  val archive = InMemoryFacesArchive(generation)
  val variant = MutableStateFlow(PeopleVariant.NAMED_FIRST)
  val available = MutableStateFlow(true)
  val people: ArchivePeople

  init {
    val faceList = ArrayList<GroupFace>()
    val owners = HashMap<FaceRef, PersonId>()
    val mediaOf = HashMap<FaceRef, Int>()
    val none = ArrayList<FaceLocator>()
    for (m in w.spec.media) {
      val random = Random(seed * 131 + m.index)
      val count = random.nextInt(0, 5)
      val who = (0 until PERSONS).shuffled(random).take(count)
      for ((slot, identity) in who.withIndex()) {
        val face = GroupingStories.face(identities, seed, m.index, slot, identity)
        faceList += face
        owners[face.ref] = persons[identity]
        mediaOf[face.ref] = m.index
      }
      // Un volto non riconoscibile ogni tanto (nessun vettore): il viewer lo conta, nessuna persona lo ha.
      if (random.nextInt(7) == 0) none += FaceLocator(keyOf(m.index), 0, FaceBox(1_000 + NONE_SLOT * 8_000, 10_000, 8_000 + NONE_SLOT * 8_000, 20_000))
    }
    // Una persona che vive solo su media da non mostrare mai (nascosti, membri di gruppi nascosti, ultimi nascosti): non deve comparire da nessuna parte.
    // Prima gli "ultimi nascosti" e i loro gruppi (che l'istantanea ha ancora: se il filtro mancasse, sarebbero le celle che escono), poi gli altri.
    val late = w.oracle.liveMustNeverShow - w.oracle.mustNeverShow
    for (index in (late.sorted() + (w.oracle.liveMustNeverShow - late).sorted()).take(6)) {
      val slot = faceList.count { mediaOf[it.ref] == index }
      if (slot >= MAX_FACES_PER_MEDIA) continue
      val face = GroupingStories.face(identities, seed, index, slot, PERSONS)
      faceList += face
      owners[face.ref] = onlyForbidden
      mediaOf[face.ref] = index
    }
    faces = faceList.sortedBy { it.ref }
    ownerOf = owners
    mediaOfFace = mediaOf
    noneFaces = none

    people = ArchivePeople(
      archives = PeopleArchiveSource { archive },
      cells = cells,
      visibility = PeopleVisibility(visible, mask),
      publisher = NoFacesPublisher,
      clock = FakeFacesClock(),
      author = AUTHOR,
      scope = scope,
      ids = GroupingStories.ids(seed * 17 + 5),
      variant = variant,
      availability = available,
      progress = MutableStateFlow(null),
      triggers = MutableSharedFlow(extraBufferCapacity = 8),
    )
  }

  private suspend fun seedArchive() {
    for ((key, list) in faces.groupBy { it.ref.contentKey }) {
      val sorted = list.sortedBy { it.ref.index }
      val entries = ArrayList<FaceEntry>()
      for (face in sorted) entries += entry(face.ref.index, face.locator.frame, face.locator.box, face.tier, Float16.encode(face.vector()))
      for (extra in noneFaces.filter { it.contentKey == key }) entries += entry(entries.size, extra.frame, extra.box, FaceTier.NONE, null)
      archive.saveBody(FaceBody(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, AUTHOR, entries, sorted.first().ref.bodyDigest))
    }
    // Il contenuto che ha solo volti non riconoscibili ha il suo corpo.
    for (extra in noneFaces.filter { n -> faces.none { it.ref.contentKey == n.contentKey } }) {
      archive.saveBody(FaceBody(extra.contentKey, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, AUTHOR, listOf(entry(0, extra.frame, extra.box, FaceTier.NONE, null)), 1))
    }
    val everyone = persons + onlyForbidden
    val groups = faces.groupBy { ownerOf.getValue(it.ref) }
    val group = everyone.associateWith { id ->
      GroupPerson(id, PersonState.ACTIVE, null, 1, 1, groups[id].orEmpty().take(GroupingParams.MAX_EXEMPLARS).map { it.ref })
    }
    val assignments = faces.map { GroupAssignment(it.ref, it.locator, ownerOf.getValue(it.ref), 0.6f, AssignOrigin.AUTO) }
    archive.saveGrouping(GroupingState(1, 0, group, assignments.associateBy { it.face }))
  }

  private fun entry(index: Int, frame: Int, box: FaceBox, tier: FaceTier, vector: ShortArray?) = FaceEntry(
    index = index, frame = frame, box = box, landmarks = ShortArray(10) { (1_000 + it * 97).toShort() }, score = 200, tier = tier,
    reasons = if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL, sidePx = 120, yaw = 0, sharpness = 100, vector = vector,
  )

  private suspend fun populate() {
    seedArchive()
    people.refresh()
    for ((id, name) in named) people.rename(id, name)
    for (hidden in hiddenPersons) people.setHidden(hidden, true)
    people.forget(forgottenPerson)
    people.refresh()
  }

  // --- L'oracolo ---------------------------------------------------------------------------------------------------------------------------

  /** Gli indici dei media su cui [person] ha un volto (prima di ogni filtro), da quanto e' stato seminato. */
  fun mediaIndicesOf(person: PersonId): Set<Int> = faces.filter { ownerOf.getValue(it.ref) == person }.mapTo(LinkedHashSet()) { mediaOfFace.getValue(it.ref) }

  /** I volti di [person] (prima di ogni filtro). */
  fun facesOf(person: PersonId): List<GroupFace> = faces.filter { ownerOf.getValue(it.ref) == person }

  fun indexOfFace(face: GroupFace): Int = mediaOfFace.getValue(face.ref)

  /**
   * Le celle che [person] deve avere sul mondo: per ogni suo volto su un media che si puo' mostrare **adesso** (anche dopo l'istantanea: il filtro finale), la cella che lo
   * contiene (il media, o la copertina del suo gruppo). Una persona dimenticata non ha niente.
   */
  fun expectedCells(person: PersonId): Set<MediaId> {
    if (person == forgottenPerson) return emptySet()
    return mediaIndicesOf(person).filter { it in w.oracle.liveShowable }.mapTo(LinkedHashSet()) { w.ids[cells.cellIndex(it)] }
  }

  fun nameOf(person: PersonId): String? = named.firstOrNull { it.first == person }?.second

  /** Le persone che stanno in "Tutte le persone" (Q2 b): con un nome e almeno una foto; non nascoste, non dimenticate. */
  fun expectedNamed(): List<PersonId> = named.map { it.first }.filter { it !in hiddenPersons && expectedCells(it).isNotEmpty() }

  /** Le persone senza nome con almeno tre foto mostrabili, non nascoste, non dimenticate. */
  fun expectedUnnamed(): List<PersonId> =
    (persons + onlyForbidden).filter { nameOf(it) == null && it !in hiddenPersons && it != forgottenPerson && expectedCells(it).size >= MIN_UNNAMED_PHOTOS }

  /** Le persone nascoste che avrebbero un posto fra quelle mostrate (con un nome una foto, senza nome tre). */
  fun expectedHidden(): List<PersonId> =
    hiddenPersons.filter { expectedCells(it).size >= (if (nameOf(it) != null) 1 else MIN_UNNAMED_PHOTOS) }

  /** L'indice del media che ha la chiave [key]. */
  fun indexOfKey(key: ContentKey): Int = w.ids.indices.first { keyOf(it) == key }

  /** Prima si chiude il lavoro (e si aspetta che finisca), poi l'archivio: un `refresh` ancora in volo sul thread di Default non trova l'archivio chiuso. */
  override fun close() {
    closeScope(scope)
    archive.close()
  }

  /** Una visibilita' senza filtro: tutto e' visibile (la mutazione della prova). */
  private class AllVisible(override val snapshot: kotlinx.coroutines.flow.StateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?>) : VisibleMedia {
    override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
  }

  companion object {
    private const val PERSONS = 8
    private const val MAX_FACES_PER_MEDIA = 6

    /** Il posto del riquadro di un volto non riconoscibile: lontano da quelli dei volti veri. */
    private const val NONE_SLOT = 6
    const val MIN_UNNAMED_PHOTOS = 3
    private const val AUTHOR = "00000000000000000000000000"

    /** Lo stack sopra il mondo [w], con le persone gia' calcolate; [leaky] toglie il filtro della visibilita' (la prova al contrario). */
    suspend fun build(w: BuiltWorld, leaky: Boolean = false): RealPeopleStack = RealPeopleStack(w, leaky).also { it.populate() }
  }
}
