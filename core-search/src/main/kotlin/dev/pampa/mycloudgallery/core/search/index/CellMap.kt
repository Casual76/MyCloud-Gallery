package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Le chiavi di contenuto delle celle, tenute in memoria e aggiornate **a pagine** (richiesta di P1: `cellContentKeys(afterSeq)` intero
 * supera i 300 ms). [sync] legge le pagine dopo l'ultimo `rowid` visto (i media nuovi) e rilegge le celle [invalidate]te (gli id di un
 * `CatalogChange`, legati da :app). Un membro di gruppo cambiato fa rileggere la sua copertina: un membro appena nascosto non porta
 * piu' il suo contenuto nella cella (D29).
 */
class CellKeys(private val source: CellKeySource, private val pageSize: Int = PAGE) {
  private val mutex = Mutex()
  private val cells = ConcurrentHashMap<MediaId, CellContent>()
  private val coverOf = ConcurrentHashMap<MediaId, MediaId>()
  private val pending: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
  private var cursor = 0L

  @Volatile var version: Long = 0
    private set

  /** Le celle con questi id (o con questi membri) vanno rilette alla prossima [sync]. */
  fun invalidate(ids: Collection<MediaId>) {
    pending += ids
  }

  /** Legge i media nuovi e le celle cambiate. Vero se qualcosa e' cambiato. */
  suspend fun sync(): Boolean = mutex.withLock {
    var changed = false
    while (true) {
      val page = source.page(cursor, pageSize)
      page.cells.forEach { put(it) }
      if (page.cells.isNotEmpty()) {
        changed = true
        cursor = maxOf(cursor, page.cells.maxOf { it.seq })
      }
      cursor = maxOf(cursor, page.next ?: cursor)
      if (page.next == null || page.cells.isEmpty()) break
    }
    if (pending.isNotEmpty()) {
      val ids = pending.toList()
      pending.removeAll(ids.toSet())
      val asked = LinkedHashSet<MediaId>()
      ids.forEach { id ->
        asked += id
        coverOf[id]?.let { asked += it }
      }
      val fresh = source.cellsOf(asked).associateBy { it.mediaId }
      for (id in asked) {
        val cell = fresh[id]
        if (cell != null) put(cell) else remove(id)
      }
      changed = true
    }
    if (changed) version++
    changed
  }

  operator fun get(id: MediaId): CellContent? = cells[id]

  val size: Int get() = cells.size

  private fun put(cell: CellContent) {
    cells.remove(cell.mediaId)?.members?.forEach { coverOf.remove(it.mediaId) }
    cells[cell.mediaId] = cell
    cell.members.forEach { coverOf[it.mediaId] = cell.mediaId }
  }

  private fun remove(id: MediaId) {
    cells.remove(id)?.members?.forEach { coverOf.remove(it.mediaId) }
  }

  companion object {
    const val PAGE: Int = 5_000
  }
}

/**
 * LA MAPPA DELLE CELLE (§2.4) per un'istantanea della Libreria e una versione dell'indice: per ogni posizione, gli slot dei vettori
 * della cella (CSR: [start], [slots]). Per ogni membro (la cella stessa, poi gli altri membri del gruppo) vale la **versione corrente**
 * se ha il vettore, altrimenti la piu' recente che lo ha (E-A10: ricetta o file nuovi non svuotano la ricerca). Un video ha uno slot per
 * fotogramma. [member] dice di quale membro e' ogni slot (per il filtro finale dei nascosti, D29).
 *
 * Si costruisce sotto `LocalAiIndex.lock` in lettura; vale finche' [indexVersion] e' quella dell'indice.
 */
internal class CellMap(
  val snapshot: TimelineSnapshot,
  val indexVersion: Long,
  val keysVersion: Long,
  val contents: Array<CellContent?>,
  val start: IntArray,
  val slots: IntArray,
  val member: IntArray,
) {
  val size: Int get() = contents.size

  fun hasVector(position: Int): Boolean = start[position + 1] > start[position]

  /** L'id del membro [ordinal] della cella (0 = la cella stessa). */
  fun memberId(position: Int, ordinal: Int): MediaId? {
    val c = contents[position] ?: return null
    return if (ordinal == 0) c.mediaId else c.members.getOrNull(ordinal - 1)?.mediaId
  }

  /** La ricetta piu' bassa fra i vettori della cella (0 = nessun vettore): la copertura della ricetta corrente (D21). */
  fun recipe(position: Int, index: LocalAiIndex): Int {
    val c = contents[position] ?: return 0
    var min = Int.MAX_VALUE
    forEachMember(c) { versions -> versions.firstOrNull { index.vectorSlots(it) != null }?.let { min = minOf(min, index.vectorRecipe(it)) } }
    return if (min == Int.MAX_VALUE) 0 else min
  }

  @Volatile private var texts: HashMap<ContentKey, IntArray>? = null

  /**
   * Per ogni chiave di testo, le posizioni delle celle che la usano ([textKeys]): il testo si cerca per chiave e si riporta alle celle.
   * Costruita alla prima ricerca con parole (sotto `index.lock` in lettura) e valida quanto la mappa.
   */
  fun textIndex(index: LocalAiIndex): Map<ContentKey, IntArray> {
    texts?.let { return it }
    val lists = HashMap<ContentKey, IntArray>()
    for (i in 0 until size) for (k in textKeys(i, index)) lists[k] = lists[k]?.plus(i) ?: intArrayOf(i)
    texts = lists
    return lists
  }

  /** Le chiavi del testo della cella: per ogni membro la versione corrente se ha il testo, altrimenti la piu' recente che lo ha. */
  fun textKeys(position: Int, index: LocalAiIndex): List<ContentKey> {
    val c = contents[position] ?: return emptyList()
    val out = ArrayList<ContentKey>(1)
    forEachMember(c) { versions -> versions.firstOrNull { index.hasText(it) }?.let { out += it } }
    return out
  }

  companion object {
    /** Costruisce la mappa (sotto `index.lock` in lettura). */
    fun build(snapshot: TimelineSnapshot, keys: CellKeys, index: LocalAiIndex, contentsFor: Array<CellContent?>? = null): CellMap {
      // Le versioni si leggono prima del contenuto: se cambiano durante la costruzione, la mappa risulta vecchia e si rifa'.
      val keysVersion = keys.version
      val indexVersion = index.version.value
      val n = snapshot.size
      val contents = contentsFor ?: Array(n) { keys[snapshot.idAt(it)] }
      val start = IntArray(n + 1)
      var slots = IntArray(n + n / 4)
      var member = IntArray(slots.size)
      var count = 0
      for (i in 0 until n) {
        start[i] = count
        val c = contents[i] ?: continue
        var ordinal = 0
        forEachMember(c) { versions ->
          val chosen = versions.firstNotNullOfOrNull { index.vectorSlots(it) }
          if (chosen != null) {
            if (count + chosen.size > slots.size) {
              slots = slots.copyOf(maxOf(slots.size * 3 / 2, count + chosen.size))
              member = member.copyOf(slots.size)
            }
            for (s in chosen) {
              slots[count] = s
              member[count] = ordinal
              count++
            }
          }
          ordinal++
        }
      }
      start[n] = count
      return CellMap(snapshot, indexVersion, keysVersion, contents, start, slots, member)
    }

    /** Le versioni di ogni membro: la cella stessa, poi gli altri membri del gruppo (P1: la corrente per prima). */
    private inline fun forEachMember(c: CellContent, block: (List<ContentKey>) -> Unit) {
      block(c.own)
      for (m in c.members) block(m.keys)
    }
  }
}
