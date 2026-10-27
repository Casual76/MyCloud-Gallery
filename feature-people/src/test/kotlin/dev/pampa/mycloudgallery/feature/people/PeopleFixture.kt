package dev.pampa.mycloudgallery.feature.people

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.PersonFace
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryPeople
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Il mondo dei test dei controllori e delle schermate (Fase 8, P9): quattro persone (piu' [extras] se servono scene piene) gia' raggruppate dal custode (Anna, Marco, un gruppo piccolo e uno
 * che ha solo media nascosti) con [PHOTOS] foto ciascuna, un media per contenuto, la Libreria finta (nascosti compresi) e [InMemoryPeople] (il riferimento
 * del contratto) sopra. **Nessun dato vero**: solo vettori sintetici ([GroupingStories]), id deterministici, nomi inventati. Le persone vere di core-faces
 * (`ArchivePeople`) hanno le loro prove; qui si prova la logica delle schermate sul riferimento.
 */
class PeopleFixture(seed: Long = 11, val namedOnly: Boolean = true, identities: Int = 4) {
  val ids = GroupingStories.ids(seed)
  val anna: PersonId = ids.next()
  val marco: PersonId = ids.next()
  val bruno: PersonId = ids.next()
  val hiddenOnly: PersonId = ids.next()

  /** Altre persone oltre alle quattro (per le scene piene degli screenshot): ognuna con [PHOTOS] foto mostrabili. */
  val extras: List<PersonId> = List(identities - 4) { ids.next() }
  val author: String = "0".repeat(26)

  private val order = listOf(anna, marco, bruno, hiddenOnly) + extras
  private val byPerson = LinkedHashMap<PersonId, List<GroupFace>>()
  val media = LinkedHashMap<ContentKey, MediaId>()

  init {
    // Una storia sintetica con quattro identita', un volto per foto: ogni persona prende le sue prime foto. Solo vettori, nessuna immagine.
    val story = GroupingStories.story(seed, identities = identities, photos = 40 * identities, maxFacesPerPhoto = 1)
    val byTruth = story.faces.groupBy { story.truth.getValue(it.ref) }
    for ((n, person) in order.withIndex()) byPerson[person] = byTruth.getValue(n).sortedBy { it.ref }.take(PHOTOS).also { check(it.size == PHOTOS) }
    for ((i, face) in faces.withIndex()) media[face.ref.contentKey] = MediaId(UUID(0x7_0000_0000L, i.toLong()).toString())
  }

  val faces: List<GroupFace> get() = byPerson.values.flatten().sortedBy { it.ref }

  fun facesOf(person: PersonId): List<GroupFace> = byPerson.getValue(person)

  /** I media nascosti: tutti quelli di [hiddenOnly] e l'ultimo di [anna]. */
  val hidden: Set<MediaId> = (facesOf(hiddenOnly).map { media.getValue(it.ref.contentKey) } + media.getValue(facesOf(anna).last().ref.contentKey)).toSet()

  val visibility = FakeVisibility(snapshot = snapshot(media.values.filter { it !in hidden }), present = media.values.toSet(), hidden = hidden)

  val state: GroupingState = run {
    val persons = order.associateWith { id -> GroupPerson(id, PersonState.ACTIVE, null, 1, 1, facesOf(id).take(GroupingParams.MAX_EXEMPLARS).map { it.ref }) }
    val assignments = order.flatMap { id -> facesOf(id).map { GroupAssignment(it.ref, it.locator, id, 0.6f, AssignOrigin.AUTO) } }
    GroupingState(1, 0, persons, assignments.associateBy { it.face })
  }

  /** Una proposta "E' la stessa persona?" fra [anna] e [marco]. */
  val suggestion = MergeSuggestion(PersonPair.of(anna, marco), 0.45f)

  val people: InMemoryPeople = InMemoryPeople(
    visibility, visibility, { key -> setOfNotNull(media[key]) }, { id -> media.filterValues { it == id }.keys }, author,
    ids = GroupingStories.ids(seed + 1), namedOnly = namedOnly,
  ).also { runBlocking { it.load(state, faces, suggestions = listOf(suggestion)) } }

  /** Cio' che la revisione dei volti legge: i volti di una persona con la cella da cui ritagliarli (i media mostrabili). */
  val personFaces: PersonFaces = object : PersonFaces {
    override suspend fun reviewFaces(person: PersonId): List<PersonFace> {
      val root = people.resolve(person) ?: return emptyList()
      val shown = people.person(root).first() ?: return emptyList()
      val visible = (0 until shown.view.size).map { shown.view.snapshot.idAt(shown.view.snapshotIndex(it)) }.toSet()
      return facesOf(root).filter { media.getValue(it.ref.contentKey) in visible }.map { PersonFace(it.locator, media.getValue(it.ref.contentKey), confirmed = false) }
    }

    override suspend fun coverMedia(persons: Collection<PersonId>): Map<PersonId, MediaId> = persons.mapNotNull { id ->
      val first = byPerson[id]?.firstOrNull { media.getValue(it.ref.contentKey) !in hidden } ?: return@mapNotNull null
      id to media.getValue(first.ref.contentKey)
    }.toMap()
  }

  /** Lo stato delle voci `people` di adesso (per le prove che guardano cosa e' stato scritto). */
  fun peopleState(): PeopleState = PeopleState.of(people.records())

  fun records(): Collection<PeopleRecord> = people.records()

  private fun snapshot(cells: List<MediaId>): InMemoryTimelineSnapshot {
    val n = cells.size
    val uuids = cells.map { UUID.fromString(it.value) }
    return InMemoryTimelineSnapshot(
      seq = 1,
      idMsb = LongArray(n) { uuids[it].mostSignificantBits },
      idLsb = LongArray(n) { uuids[it].leastSignificantBits },
      sortTime = LongArray(n) { 1_700_000_000_000L - it * 3_600_000L },
      localDay = IntArray(n) { 20_260_301 + it % 28 },
      kinds = ByteArray(n) { MediaKind.PHOTO.ordinal.toByte() },
      sources = ByteArray(n) { MediaSource.CAMERA.ordinal.toByte() },
      flags = IntArray(n),
      aspectMilli = IntArray(n) { 1_000 },
      durationMs = LongArray(n),
      place = IntArray(n),
      groupSize = IntArray(n) { 1 },
    )
  }

  companion object {
    const val PHOTOS: Int = 5
  }
}

/**
 * [HiddenPeople] sopra il riferimento `InMemoryPeople`: le persone di [candidates] che sono nascoste adesso e hanno almeno una foto mostrabile (come le vere: una
 * persona con soli media nascosti non c'e', D28). Le vere (core-faces) hanno la loro prova.
 */
class HiddenOfPeople(private val people: InMemoryPeople, private val candidates: List<PersonId>) : HiddenPeople {
  override fun hidden(): Flow<List<PersonCard>> =
    combine(candidates.map { people.person(it) }) { views -> views.filterNotNull().filter { it.hidden && it.card.photos > 0 }.map { it.card } }
}
