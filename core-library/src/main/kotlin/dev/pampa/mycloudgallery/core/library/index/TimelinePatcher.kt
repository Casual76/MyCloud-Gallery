package dev.pampa.mycloudgallery.core.library.index

import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * L'aggiornamento a pezzi dell'indice (D2): un `CatalogChange` dice quali identita' rileggere; le loro righe vecchie
 * escono, quelle nuove (le identita' che sono ancora nell'indice) entrano al loro posto nell'ordine. Il risultato e'
 * **lo stesso indice** che darebbe una lettura completa del catalogo in quello stato (`TimelinePatchTest` lo verifica
 * contro la ricostruzione su migliaia di cambiamenti casuali).
 *
 * Costo: O(n) copie di array per colonna (`System.arraycopy` a tratti) + O(k log n) per le k righe: un aggiornamento
 * di 100 mila elementi e' una copia di qualche megabyte, fuori dal thread principale. I cambiamenti a raffica si
 * accorpano prima (una patch per lotto, `CatalogTimelineRepository`).
 */
object TimelinePatcher {

  /**
   * @property removed quante identita' sono uscite dall'indice (erano dentro e non ci sono piu': nascoste, sparite,
   *   unite, dietro una copertina). Se e' piu' di zero l'istantanea su file si riscrive subito (E-H1).
   * @property droppedRefs i riferimenti ai byte delle identita' uscite: il viewer puo' ancora chiederli per un attimo.
   */
  class Result(
    val snapshot: ColumnarTimelineSnapshot,
    val removed: Int,
    val droppedRefs: Map<MediaId, MediaRef.Phone>,
  )

  /**
   * Applica un cambiamento: [ids] sono le identita' annunciate, [rows] le loro righe ancora nell'indice (riletta dal
   * catalogo dopo il commit), [refs] i loro riferimenti ai byte (per `media_id`), [groupSizes] i numeri di tutti i
   * gruppi se sono da ricalcolare (cambiamento strutturale o righe con un gruppo), altrimenti null.
   */
  fun patch(
    current: ColumnarTimelineSnapshot,
    ids: Collection<MediaId>,
    rows: List<IndexRow>,
    refs: Map<String, MediaRef.Phone>,
    groupSizes: Map<String, Int>?,
    newSeq: Long,
  ): Result {
    val fresh = TimelineIndexReader.fromRows(rows.associateBy { it.mediaId }.values.toList(), newSeq, groupSizes ?: emptyMap(), refs)
    val dropList = ArrayList<Int>(ids.size + rows.size)
    for (id in ids) current.indexOf(id).let { if (it >= 0) dropList += it }
    for (k in 0 until fresh.size) current.indexOf(fresh.idMsb[k], fresh.idLsb[k]).let { if (it >= 0) dropList += it }
    val drop = dropList.toIntArray().apply { sort() }.distinctSorted()

    var removed = 0
    val dropped = HashMap<MediaId, MediaRef.Phone>()
    for (i in drop) {
      if (fresh.indexOf(current.idMsb[i], current.idLsb[i]) < 0) {
        removed++
        current.refAt(i)?.let { dropped[current.idAt(i)] = it }
      }
    }
    var merged = merge(current, drop, fresh, newSeq)
    if (groupSizes != null) merged = withGroupSizes(merged, groupSizes)
    return Result(merged, removed, dropped)
  }

  /** L'indice senza [ids], stessa sequenza: il "togli subito" prima di nascondere (E-H1). */
  fun remove(current: ColumnarTimelineSnapshot, ids: Collection<MediaId>): ColumnarTimelineSnapshot {
    val drop = ids.mapNotNull { id -> current.indexOf(id).takeIf { it >= 0 } }.toIntArray().apply { sort() }.distinctSorted()
    if (drop.isEmpty()) return current
    return merge(current, drop, ColumnarTimelineSnapshot.EMPTY, current.seq)
  }

  /** Una copia con un altro numero di sequenza (stesse colonne, condivise: sono immutabili). */
  fun withSeq(s: ColumnarTimelineSnapshot, seq: Long): ColumnarTimelineSnapshot = if (s.seq == seq) s else ColumnarTimelineSnapshot(
    seq, s.size, s.idMsb, s.idLsb, s.sortTime, s.localDay, s.kind, s.source, s.flags, s.aspect, s.duration, s.place, s.groupSize,
    s.thumbOffsets, s.thumbBytes, s.refId, s.refVolume, s.refMime, s.volumes, s.mimes, s.groupIndex, s.groupMsb, s.groupLsb,
  )

  /** I numeri dei gruppi ricalcolati per tutte le copertine (le altre colonne sono condivise). */
  fun withGroupSizes(s: ColumnarTimelineSnapshot, sizes: Map<String, Int>): ColumnarTimelineSnapshot {
    if (s.groupIndex.isEmpty()) return s
    val groupSize = s.groupSize.copyOf()
    for (g in s.groupIndex.indices) {
      val n = sizes[Uuids.toText(s.groupMsb[g], s.groupLsb[g])] ?: 1
      groupSize[s.groupIndex[g]] = n.coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
    }
    return ColumnarTimelineSnapshot(
      s.seq, s.size, s.idMsb, s.idLsb, s.sortTime, s.localDay, s.kind, s.source, s.flags, s.aspect, s.duration, s.place, groupSize,
      s.thumbOffsets, s.thumbBytes, s.refId, s.refVolume, s.refMime, s.volumes, s.mimes, s.groupIndex, s.groupMsb, s.groupLsb,
    )
  }

  /** I riferimenti ai byte riletti per tutto l'indice (lettura completa): [refs] per posizione. */
  fun withRefs(s: ColumnarTimelineSnapshot, refs: Map<Int, MediaRef.Phone>): ColumnarTimelineSnapshot {
    val volumes = LinkedHashMap<String, Int>()
    val mimes = LinkedHashMap<String, Int>()
    fun intern(table: LinkedHashMap<String, Int>, value: String): Int =
      table[value] ?: if (table.size >= 255) 0 else (table.size + 1).also { table[value] = it }
    val refId = LongArray(s.size)
    val refVolume = ByteArray(s.size)
    val refMime = ByteArray(s.size)
    for ((i, ref) in refs) {
      if (i !in 0 until s.size) continue
      val v = intern(volumes, ref.volume)
      if (v == 0) continue
      refId[i] = ref.mediaStoreId
      refVolume[i] = v.toByte()
      refMime[i] = (ref.mime?.let { intern(mimes, it) } ?: 0).toByte()
    }
    return ColumnarTimelineSnapshot(
      s.seq, s.size, s.idMsb, s.idLsb, s.sortTime, s.localDay, s.kind, s.source, s.flags, s.aspect, s.duration, s.place, s.groupSize,
      s.thumbOffsets, s.thumbBytes, refId, refVolume, refMime, volumes.keys.toTypedArray(), mimes.keys.toTypedArray(),
      s.groupIndex, s.groupMsb, s.groupLsb,
    )
  }

  /**
   * L'unione ordinata di [current] senza le posizioni [drop] (ordinate, senza ripetizioni) e di [fresh] (ordinato).
   * Le parti di [current] fra un evento e l'altro si copiano a blocchi.
   */
  private fun merge(current: ColumnarTimelineSnapshot, drop: IntArray, fresh: ColumnarTimelineSnapshot, seq: Long): ColumnarTimelineSnapshot {
    val n = current.size
    val k = fresh.size
    val outSize = n - drop.size + k
    val out = Out(outSize, current, fresh)
    // Dove entra ogni riga nuova: quante righe di [current] vengono prima di lei.
    val insertAt = IntArray(k) { j -> lowerBound(current, fresh.sortTime[j], fresh.idMsb[j], fresh.idLsb[j]) }
    var cur = 0
    var d = 0
    var j = 0
    while (cur < n || j < k) {
      val nextInsert = if (j < k) insertAt[j] else Int.MAX_VALUE
      val nextDrop = if (d < drop.size) drop[d] else Int.MAX_VALUE
      val boundary = minOf(nextInsert, nextDrop, n)
      if (boundary > cur) {
        out.copyRun(cur, boundary)
        cur = boundary
      }
      if (j < k && insertAt[j] == cur) {
        out.copyFresh(j)
        j++
      } else if (d < drop.size && drop[d] == cur) {
        cur++
        d++
      } else if (cur >= n && j >= k) {
        break
      }
    }
    check(out.size == outSize) { "Patch incoerente." }
    return out.build(seq)
  }

  /** La prima posizione di [s] che non viene prima della chiave (t, m, l). */
  private fun lowerBound(s: ColumnarTimelineSnapshot, t: Long, m: Long, l: Long): Int {
    var lo = 0
    var hi = s.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (compareIndexOrder(s.sortTime[mid], s.idMsb[mid], s.idLsb[mid], t, m, l) < 0) lo = mid + 1 else hi = mid
    }
    return lo
  }

  private fun IntArray.distinctSorted(): IntArray {
    if (size < 2) return this
    var w = 1
    for (r in 1 until size) if (this[r] != this[w - 1]) this[w++] = this[r]
    return if (w == size) this else copyOf(w)
  }

  /** Le colonne in uscita, riempite a blocchi da [current] e a righe da [fresh]. */
  private class Out(capacity: Int, private val current: ColumnarTimelineSnapshot, private val fresh: ColumnarTimelineSnapshot) {
    var size = 0
    val idMsb = LongArray(capacity)
    val idLsb = LongArray(capacity)
    val sortTime = LongArray(capacity)
    val localDay = IntArray(capacity)
    val kind = ByteArray(capacity)
    val source = ByteArray(capacity)
    val flags = IntArray(capacity)
    val aspect = ShortArray(capacity)
    val duration = IntArray(capacity)
    val place = IntArray(capacity)
    val groupSize = ShortArray(capacity)
    val thumbOffsets = IntArray(capacity + 1)
    var thumbBytes = ByteArray(current.thumbBytes.size + fresh.thumbBytes.size)
    var thumbLength = 0
    val refId = LongArray(capacity)
    val refVolume = ByteArray(capacity)
    val refMime = ByteArray(capacity)

    // I volumi e i MIME di [current] restano agli stessi indici; quelli nuovi di [fresh] si aggiungono in coda.
    private val volumes = ArrayList(current.volumes.asList())
    private val mimes = ArrayList(current.mimes.asList())
    private val freshVolume = IntArray(fresh.volumes.size + 1) { if (it == 0) 0 else intern(volumes, fresh.volumes[it - 1]) }
    private val freshMime = IntArray(fresh.mimes.size + 1) { if (it == 0) 0 else intern(mimes, fresh.mimes[it - 1]) }

    private var groupCount = 0
    private var groupIndex = IntArray(current.groupIndex.size + fresh.groupIndex.size)
    private var groupMsb = LongArray(groupIndex.size)
    private var groupLsb = LongArray(groupIndex.size)
    private var nextCurrentGroup = 0

    fun copyRun(from: Int, to: Int) {
      val len = to - from
      val at = size
      System.arraycopy(current.idMsb, from, idMsb, at, len)
      System.arraycopy(current.idLsb, from, idLsb, at, len)
      System.arraycopy(current.sortTime, from, sortTime, at, len)
      System.arraycopy(current.localDay, from, localDay, at, len)
      System.arraycopy(current.kind, from, kind, at, len)
      System.arraycopy(current.source, from, source, at, len)
      System.arraycopy(current.flags, from, flags, at, len)
      System.arraycopy(current.aspect, from, aspect, at, len)
      System.arraycopy(current.duration, from, duration, at, len)
      System.arraycopy(current.place, from, place, at, len)
      System.arraycopy(current.groupSize, from, groupSize, at, len)
      System.arraycopy(current.refId, from, refId, at, len)
      System.arraycopy(current.refVolume, from, refVolume, at, len)
      System.arraycopy(current.refMime, from, refMime, at, len)
      val bytesFrom = current.thumbOffsets[from]
      val bytesLen = current.thumbOffsets[to] - bytesFrom
      System.arraycopy(current.thumbBytes, bytesFrom, thumbBytes, thumbLength, bytesLen)
      val shift = thumbLength - bytesFrom
      for (q in 1..len) thumbOffsets[at + q] = current.thumbOffsets[from + q] + shift
      thumbLength += bytesLen
      // I gruppi di [current] nel tratto copiato (gli altri, prima di [from], sono stati scartati).
      val gi = current.groupIndex
      while (nextCurrentGroup < gi.size && gi[nextCurrentGroup] < from) nextCurrentGroup++
      while (nextCurrentGroup < gi.size && gi[nextCurrentGroup] < to) {
        addGroup(at + gi[nextCurrentGroup] - from, current.groupMsb[nextCurrentGroup], current.groupLsb[nextCurrentGroup])
        nextCurrentGroup++
      }
      size += len
    }

    fun copyFresh(j: Int) {
      val at = size
      idMsb[at] = fresh.idMsb[j]
      idLsb[at] = fresh.idLsb[j]
      sortTime[at] = fresh.sortTime[j]
      localDay[at] = fresh.localDay[j]
      kind[at] = fresh.kind[j]
      source[at] = fresh.source[j]
      flags[at] = fresh.flags[j]
      aspect[at] = fresh.aspect[j]
      duration[at] = fresh.duration[j]
      place[at] = fresh.place[j]
      groupSize[at] = fresh.groupSize[j]
      val v = freshVolume[fresh.refVolume[j].toInt() and 0xFF]
      refVolume[at] = v.toByte()
      refId[at] = if (v == 0) 0L else fresh.refId[j]
      refMime[at] = freshMime[fresh.refMime[j].toInt() and 0xFF].toByte()
      val from = fresh.thumbOffsets[j]
      val len = fresh.thumbOffsets[j + 1] - from
      if (thumbLength + len > thumbBytes.size) thumbBytes = thumbBytes.copyOf(thumbLength + len)
      System.arraycopy(fresh.thumbBytes, from, thumbBytes, thumbLength, len)
      thumbLength += len
      thumbOffsets[at + 1] = thumbLength
      val g = fresh.groupIndex.binarySearch(j)
      if (g >= 0) addGroup(at, fresh.groupMsb[g], fresh.groupLsb[g])
      size = at + 1
    }

    private fun addGroup(index: Int, msb: Long, lsb: Long) {
      if (groupCount == groupIndex.size) {
        val c = maxOf(4, groupIndex.size * 2)
        groupIndex = groupIndex.copyOf(c)
        groupMsb = groupMsb.copyOf(c)
        groupLsb = groupLsb.copyOf(c)
      }
      groupIndex[groupCount] = index
      groupMsb[groupCount] = msb
      groupLsb[groupCount] = lsb
      groupCount++
    }

    fun build(seq: Long): ColumnarTimelineSnapshot = ColumnarTimelineSnapshot(
      seq, size, idMsb, idLsb, sortTime, localDay, kind, source, flags, aspect, duration, place, groupSize,
      thumbOffsets, if (thumbLength == thumbBytes.size) thumbBytes else thumbBytes.copyOf(thumbLength),
      refId, refVolume, refMime, volumes.toTypedArray(), mimes.toTypedArray(),
      groupIndex.copyOf(groupCount), groupMsb.copyOf(groupCount), groupLsb.copyOf(groupCount),
    )

    private companion object {
      fun intern(list: ArrayList<String>, value: String): Int {
        val existing = list.indexOf(value)
        if (existing >= 0) return existing + 1
        if (list.size >= 255) return 0
        list += value
        return list.size
      }
    }
  }
}
