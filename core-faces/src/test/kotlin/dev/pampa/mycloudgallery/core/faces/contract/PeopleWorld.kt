package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.UUID

/**
 * Il mondo delle prove delle persone (P0, `PeopleContract`): tre persone gia' raggruppate dal custode, un media per contenuto, la Libreria
 * finta con la visibilita' (nascosti compresi) e un volto non riconoscibile. Ogni persona ha [PHOTOS] foto; la persona [c] ha **solo** media
 * nascosti (E-F38); un media di [a] e' nascosto. Nessun dato vero: vettori sintetici, id deterministici.
 */
class PeopleWorld(seed: Long = 5) {
  private val identities = SyntheticIdentities(seed)
  private val ids = GroupingStories.ids(seed)

  val a: PersonId = ids.next()
  val b: PersonId = ids.next()
  val c: PersonId = ids.next()
  val author: String = "0".repeat(26)

  private val facesByPerson = LinkedHashMap<PersonId, List<GroupFace>>()
  val faces: List<GroupFace>
  val media = LinkedHashMap<ContentKey, MediaId>()
  val none: FaceLocator

  init {
    var photo = 0
    val all = ArrayList<GroupFace>()
    for ((n, person) in listOf(a, b, c).withIndex()) {
      val list = (0 until PHOTOS).map { GroupingStories.face(identities, seed, photo++, 0, identity = n) }
      facesByPerson[person] = list
      all += list
    }
    faces = all.sortedBy { it.ref }
    for ((i, face) in faces.withIndex()) media[face.ref.contentKey] = MediaId(UUID(0x8_0000_0000L, i.toLong()).toString())
    val first = facesByPerson.getValue(a).first().ref.contentKey
    none = FaceLocator(first, 0, FaceBox(60_000, 60_000, 61_000, 61_000))
  }

  fun facesOf(person: PersonId): List<GroupFace> = facesByPerson.getValue(person)

  /** Il media nascosto di [a] e tutti quelli di [c]. */
  val hidden: Set<MediaId> = (facesOf(c).map { media.getValue(it.ref.contentKey) } + media.getValue(facesOf(a).last().ref.contentKey)).toSet()

  val visibility: FakeVisibility = FakeVisibility(
    snapshot = snapshot(media.values.filter { it !in hidden }),
    present = media.values.toSet(),
    hidden = hidden,
  )

  val mediaOf: (ContentKey) -> Set<MediaId> = { key -> setOfNotNull(media[key]) }
  val keysOf: (MediaId) -> Set<ContentKey> = { id -> media.filterValues { it == id }.keys }

  /** L'istantanea del custode: le tre persone con i loro volti automatici. */
  val state: GroupingState = run {
    val persons = listOf(a, b, c).associateWith { id ->
      GroupPerson(id, PersonState.ACTIVE, null, 1, 1, facesOf(id).take(GroupingParams.MAX_EXEMPLARS).map { it.ref })
    }
    val assignments = listOf(a, b, c).flatMap { id -> facesOf(id).map { GroupAssignment(it.ref, it.locator, id, 0.6f, AssignOrigin.AUTO) } }
    GroupingState(1, 0, persons, assignments.associateBy { it.face })
  }

  /** Una proposta "E' la stessa persona?" fra [a] e [b]. */
  val suggestion = MergeSuggestion(PersonPair.of(a, b), 0.45f)

  private fun snapshot(cells: List<MediaId>): InMemoryTimelineSnapshot {
    val n = cells.size
    val uuids = cells.map { UUID.fromString(it.value) }
    return InMemoryTimelineSnapshot(
      seq = 1,
      idMsb = LongArray(n) { uuids[it].mostSignificantBits },
      idLsb = LongArray(n) { uuids[it].leastSignificantBits },
      sortTime = LongArray(n) { 1_700_000_000_000L - it * 60_000L },
      localDay = IntArray(n) { 20_250_101 },
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
