package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.PhotoKey
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * I volti correnti di un passo (§2.5), in ordine di riferimento, con gli indici che il riferimento a forza bruta ricostruisce scorrendo
 * tutto: riferimento → posizione, foto ("la stessa foto" = contenuto e fotogramma, E-F15) e contenuto come numeri, i volti di ogni foto.
 */
internal class FaceTable(sorted: List<GroupFace>) {
  val faces: List<GroupFace> = sorted
  val size: Int = sorted.size
  val matrix: FaceMatrix = FaceMatrix(sorted.map { it.vector() })

  private val position = HashMap<FaceRef, Int>(size * 2)

  /** La foto di ogni volto (un numero per [PhotoKey]) e il contenuto (un numero per chiave di contenuto). */
  val photo = IntArray(size)
  val content = IntArray(size)
  val core = BooleanArray(size)

  private val photoIds = HashMap<PhotoKey, Int>()
  private val photoFaces = ArrayList<IntArrayBuilder>()

  init {
    val contentIds = HashMap<ContentKey, Int>()
    for ((i, f) in sorted.withIndex()) {
      require(i == 0 || sorted[i - 1].ref < f.ref) { "Volti non in ordine di riferimento o ripetuti." }
      position[f.ref] = i
      val p = photoIds.getOrPut(f.photo) { photoFaces.add(IntArrayBuilder()); photoFaces.size - 1 }
      photoFaces[p].add(i)
      photo[i] = p
      content[i] = contentIds.getOrPut(f.ref.contentKey) { contentIds.size }
      core[i] = f.tier == FaceTier.CORE
    }
  }

  private val byPhoto: Array<IntArray> = Array(photoFaces.size) { photoFaces[it].toArray() }

  fun indexOf(ref: FaceRef): Int = position[ref] ?: -1

  operator fun contains(ref: FaceRef): Boolean = ref in position

  /** I volti della foto [photoId], in ordine di riferimento. */
  fun facesOfPhoto(photoId: Int): IntArray = byPhoto[photoId]

  /** I volti nello stesso contenuto e fotogramma di [locator] (candidati all'abbinamento per posto), in ordine di riferimento. */
  fun facesAt(locator: FaceLocator): IntArray {
    val p = photoIds[PhotoKey(locator.contentKey, locator.frame)] ?: return EMPTY
    return byPhoto[p]
  }

  private companion object {
    val EMPTY = IntArray(0)
  }
}

/** Un elenco di interi che cresce (senza scatole). */
internal class IntArrayBuilder(capacity: Int = 4) {
  private var items = IntArray(capacity)
  var size: Int = 0
    private set

  fun add(value: Int) {
    if (size == items.size) items = items.copyOf(maxOf(4, size * 2))
    items[size++] = value
  }

  operator fun get(i: Int): Int = items[i]

  fun toArray(): IntArray = items.copyOf(size)
}

/**
 * Le correzioni dell'utente (D23) indicizzate per foto: le stesse risposte di `GroupingSteps` del riferimento (che scorre ogni
 * correzione per ogni volto) in tempo costante. Un localizzatore corrisponde a un volto con lo stesso contenuto e fotogramma e IoU >= 0,5.
 * - [fixedFor]: la prima correzione "E' …" in ordine di forma testo ([FaceLocator.encode]) che corrisponde;
 * - [isIgnored]: una correzione "Ignora" che corrisponde;
 * - [rejectedRaw]: l'unione delle persone di ogni "Non e' …" che corrisponde (non ancora risolte negli alias).
 */
internal class CorrectionIndex(corrections: GroupingCorrections) {
  private val fixed = HashMap<PhotoKey, List<Pair<FaceLocator, PersonId>>>()
  private val ignored = HashMap<PhotoKey, List<FaceLocator>>()
  private val rejected = HashMap<PhotoKey, List<Pair<FaceLocator, Set<PersonId>>>>()

  init {
    corrections.fixed.entries.map { it.key to it.value }.sortedBy { it.first.encode() }
      .groupBy { PhotoKey(it.first.contentKey, it.first.frame) }.forEach { (k, v) -> fixed[k] = v }
    corrections.ignored.groupBy { PhotoKey(it.contentKey, it.frame) }.forEach { (k, v) -> ignored[k] = v }
    corrections.rejected.entries.map { it.key to it.value }
      .groupBy { PhotoKey(it.first.contentKey, it.first.frame) }.forEach { (k, v) -> rejected[k] = v }
  }

  private fun key(locator: FaceLocator) = PhotoKey(locator.contentKey, locator.frame)

  fun fixedFor(locator: FaceLocator): PersonId? = fixed[key(locator)]?.firstOrNull { it.first.matches(locator) }?.second

  fun isIgnored(locator: FaceLocator): Boolean = ignored[key(locator)]?.any { it.matches(locator) } == true

  fun rejectedRaw(locator: FaceLocator): Set<PersonId> {
    val list = rejected[key(locator)] ?: return emptySet()
    var out: MutableSet<PersonId>? = null
    for ((l, persons) in list) {
      if (!l.matches(locator)) continue
      if (out == null) out = LinkedHashSet()
      out.addAll(persons)
    }
    return out ?: emptySet()
  }

  /** Vero se qualche "Non e' …" riguarda la foto di [locator] (filtro veloce prima di [rejectedRaw]). */
  fun mayBeRejected(locator: FaceLocator): Boolean = key(locator) in rejected
}
