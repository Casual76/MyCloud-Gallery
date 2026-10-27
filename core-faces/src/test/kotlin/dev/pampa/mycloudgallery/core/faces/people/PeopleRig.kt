package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.store.FacesStore
import dev.pampa.mycloudgallery.core.faces.store.StoreWorld
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

// Il BANCO DELLE PROVE delle persone vere (P9): un archivio vero (Room cifrato con le chiavi finte di P0, Robolectric) con il mondo delle
// prove (`PeopleWorld`: tre persone, un media per contenuto, nascosti, un volto non riconoscibile) e `ArchivePeople` sopra, con la Libreria
// finta, un pubblicatore che registra e l'orologio del NAS finto. Solo vettori sintetici: nessun volto, nessun dato vero (§5.2).

/** Il pubblicatore che si limita a ricordare cosa e' stato accodato (le voci `people` nell'ordine in cui sono state scritte). */
internal class RecordingPublisher : FacesPublisher {
  val changes: MutableList<FacesChange> = java.util.Collections.synchronizedList(mutableListOf())

  /** Se vero, `stage` lancia: la voce e' gia' nell'archivio e deve valere lo stesso. */
  @Volatile var failing: Boolean = false

  override suspend fun stage(change: FacesChange) {
    if (failing) throw IllegalStateException("outbox guasta")
    changes += change
  }

  override suspend fun flush(reason: FlushReason): FacesPublishOutcome = FacesPublishOutcome.NothingToPublish

  override val pending: StateFlow<Int> = MutableStateFlow(0)

  /** Le voci `people` accodate, nell'ordine. */
  fun edits(): List<PeopleEdit> = changes.toList().filterIsInstance<FacesChange.People>().flatMap { it.edits }
}

/** Le celle del mondo di prova: un media per contenuto (la cella e' il media). */
internal class WorldCells(private val world: PeopleWorld) : PeopleCells {
  override suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = keys.associateWith { world.mediaOf(it) }

  override suspend fun keysOf(media: MediaId): Set<ContentKey> = world.keysOf(media)
}

/** Scrive nell'archivio i corpi dei volti e l'istantanea del raggruppamento di un mondo (come farebbero il calcolo e il custode). */
internal object ArchiveSeeding {
  const val AUTHOR_OF_BODIES: String = "00000000000000000000000000"

  /**
   * I corpi di [faces] (uno per contenuto, volti in ordine di indice), piu' i volti non riconoscibili [none] (in coda al corpo del loro
   * contenuto) e l'istantanea [state]. [vectorOf] permette di cambiare i vettori (le prove delle proposte avvicinano due persone).
   */
  suspend fun seed(
    archive: FacesArchive,
    faces: List<GroupFace>,
    state: GroupingState,
    none: List<FaceLocator> = emptyList(),
    vectorOf: (GroupFace) -> FloatArray = { it.vector() },
  ) {
    for ((key, list) in faces.groupBy { it.ref.contentKey }) {
      val sorted = list.sortedBy { it.ref.index }
      val digest = sorted.first().ref.bodyDigest
      val entries = ArrayList<FaceEntry>()
      for (face in sorted) entries += entry(face.ref.index, face.locator.frame, face.locator.box, face.tier, Float16.encode(vectorOf(face)))
      for (extra in none.filter { it.contentKey == key }) entries += entry(entries.size, extra.frame, extra.box, FaceTier.NONE, null)
      archive.saveBody(body(key, entries, digest))
    }
    archive.saveGrouping(state)
  }

  fun body(key: ContentKey, entries: List<FaceEntry>, digest: Long = 1): FaceBody =
    FaceBody(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, AUTHOR_OF_BODIES, entries, digest)

  fun entry(index: Int, frame: Int, box: FaceBox, tier: FaceTier, vector: ShortArray?): FaceEntry = FaceEntry(
    index = index,
    frame = frame,
    box = box,
    landmarks = ShortArray(10) { (1_000 + it * 97).toShort() },
    score = 200,
    tier = tier,
    reasons = if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL,
    sidePx = 120,
    yaw = 0,
    sharpness = 100,
    vector = vector,
  )

  /**
   * I volti di [b] avvicinati alle persone di [a] **di una quantita' esatta**: ogni volto di [b] diventa `weight * (centro di a) + sqrt(1 -
   * weight^2) * (la sua parte ortogonale a tutti i volti di a)`. Cosi' il coseno con ogni volto di [a] e' `weight` per il coseno di quel
   * volto con il centro (circa 0,8): niente caso, la somiglianza cade dove la prova la vuole (la fascia "forse" 0,40-0,45, o le proposte
   * 0,40-0,52). Gli altri volti restano come sono.
   */
  fun nearer(a: List<GroupFace>, b: List<GroupFace>, weight: Float): (GroupFace) -> FloatArray {
    val aVectors = a.map { it.vector() }
    val center = blend(*aVectors.map { 1f to it }.toTypedArray())
    val basis = orthonormal(aVectors)
    val own = b.mapTo(HashSet()) { it.ref }
    val rest = sqrt(1f - weight * weight)
    return { face ->
      if (face.ref !in own) {
        face.vector()
      } else {
        var u = face.vector().map { it.toDouble() }.toDoubleArray()
        for (q in basis) {
          var dot = 0.0
          for (i in u.indices) dot += u[i] * q[i]
          for (i in u.indices) u[i] -= dot * q[i]
        }
        blend(weight to center, rest to FloatArray(u.size) { u[it].toFloat() })
      }
    }
  }

  private fun orthonormal(vectors: List<FloatArray>): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    for (v in vectors) {
      val w = DoubleArray(v.size) { v[it].toDouble() }
      for (q in out) {
        var dot = 0.0
        for (i in w.indices) dot += w[i] * q[i]
        for (i in w.indices) w[i] -= dot * q[i]
      }
      val norm = sqrt(w.fold(0.0) { acc, x -> acc + x * x })
      if (norm > 1e-6) out += DoubleArray(w.size) { w[it] / norm }
    }
    return out
  }

  /** Il vettore normalizzato di [mix] pesi su [vectors]. */
  fun blend(vararg parts: Pair<Float, FloatArray>): FloatArray {
    val out = FloatArray(parts.first().second.size)
    for ((weight, vector) in parts) for (i in out.indices) out[i] += weight * vector[i]
    val norm = sqrt(out.fold(0f) { acc, x -> acc + x * x })
    return FloatArray(out.size) { out[it] / norm }
  }
}

/**
 * Un dispositivo di prova: il suo archivio vero, le persone sopra, la sua Libreria. [close] chiude archivio e coroutine. Due banchi con la
 * stessa [generation] sono due dispositivi dello stesso account (E-F5).
 */
internal class PeopleRig(
  folder: File,
  val world: PeopleWorld = PeopleWorld(),
  val device: String = world.author,
  nasNow: Long? = 1_800_000_000_000L,
  namedOnly: Boolean = true,
  val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 9 }),
  ids: PersonIdSource = GroupingStories.ids(99),
  vectorOf: (GroupFace) -> FloatArray = { it.vector() },
  seedFaces: Boolean = true,
) : AutoCloseable {
  val storeWorld = StoreWorld(folder)
  val store: FacesStore = runBlocking { storeWorld.ready(generation) }
  val archive: FacesArchive get() = store.archive
  val publisher = RecordingPublisher()
  val clock = FakeFacesClock(nasNow = nasNow)
  val variant = MutableStateFlow(if (namedOnly) PeopleVariant.NAMED_FIRST else PeopleVariant.ALL_GROUPS)
  val available = MutableStateFlow(true)
  val progress = MutableStateFlow<Int?>(null)
  val triggers = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val visibility = PeopleVisibility(world.visibility, world.visibility)
  val people: ArchivePeople

  init {
    if (seedFaces) runBlocking { ArchiveSeeding.seed(store.archive, world.faces, world.state, listOf(world.none), vectorOf) }
    people = ArchivePeople(
      archives = { if (store.isOpen) store.archive else null },
      cells = WorldCells(world),
      visibility = visibility,
      publisher = publisher,
      clock = clock,
      author = device,
      scope = scope,
      ids = ids,
      variant = variant,
      availability = available,
      progress = progress,
      triggers = triggers,
    )
    runBlocking { people.refresh() }
  }

  /** Le voci `people` che l'archivio ha adesso (anche quelle ancora da datare). */
  fun records(): Collection<PeopleRecord> = runBlocking { archive.people().values }

  override fun close() {
    scope.cancel()
    store.close()
  }
}
