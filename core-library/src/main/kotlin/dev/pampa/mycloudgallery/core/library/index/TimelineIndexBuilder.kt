package dev.pampa.mycloudgallery.core.library.index

import android.database.Cursor
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.ThumbHash

/**
 * Costruisce un [ColumnarTimelineSnapshot] una riga alla volta, con array che crescono raddoppiando: lo usano la
 * lettura del `Cursor` dell'indice, le righe del flusso dei cambiamenti e la Libreria sintetica. Le righe arrivano
 * nell'ordine dell'indice (dal piu' recente); se non e' cosi' [build] le riordina (un database che non ordinasse
 * romperebbe le ricerche binarie delle patch).
 */
class TimelineIndexBuilder(initialCapacity: Int = 1024) {

  var size: Int = 0
    private set

  private var capacity = initialCapacity.coerceAtLeast(8)
  private var idMsb = LongArray(capacity)
  private var idLsb = LongArray(capacity)
  private var sortTime = LongArray(capacity)
  private var localDay = IntArray(capacity)
  private var kind = ByteArray(capacity)
  private var source = ByteArray(capacity)
  private var flags = IntArray(capacity)
  private var aspect = ShortArray(capacity)
  private var duration = IntArray(capacity)
  private var place = IntArray(capacity)
  private var groupSize = ShortArray(capacity)
  private var thumbOffsets = IntArray(capacity + 1)
  private var thumbBytes = ByteArray(capacity * 26)
  private var thumbLength = 0
  private var refId = LongArray(capacity)
  private var refVolume = ByteArray(capacity)
  private var refMime = ByteArray(capacity)
  private var contentKey = LongArray(capacity)

  private val volumes = Interned(ColumnarTimelineSnapshot.MAX_VOLUMES)
  private val mimes = Interned(MAX_MIMES)

  private var groupCount = 0
  private var groupIndex = IntArray(16)
  private var groupMsb = LongArray(16)
  private var groupLsb = LongArray(16)

  /**
   * Una riga. [thumbHash] sono i byte del segnaposto (validati: oltre i 36 byte di un ThumbHash si scartano),
   * [groupMsb]/[groupLsb] l'identita' del gruppo se la cella e' una copertina (entrambi 0 = nessun gruppo).
   *
   * Un solo riferimento ai byte per cella (Fase 4, P7): [ref] (il telefono) se c'e', altrimenti [nasRef]. Se li si danno
   * entrambi vale il telefono: per un media su telefono e NAS la griglia usa la miniatura del telefono (D18).
   */
  fun add(
    msb: Long,
    lsb: Long,
    sortTimeMs: Long,
    localDay: Int,
    kind: MediaKind,
    source: MediaSource,
    flags: Int,
    aspectMilli: Int,
    durationMs: Long,
    placeId: Int,
    thumbHash: ByteArray?,
    groupSize: Int = 1,
    groupMsb: Long = 0L,
    groupLsb: Long = 0L,
    ref: MediaRef.Phone? = null,
    nasRef: MediaRef.Nas? = null,
  ) {
    ensure(size + 1)
    val i = size
    idMsb[i] = msb
    idLsb[i] = lsb
    sortTime[i] = sortTimeMs
    this.localDay[i] = localDay
    this.kind[i] = kind.ordinal.toByte()
    this.source[i] = source.ordinal.toByte()
    this.flags[i] = flags
    aspect[i] = aspectMilli.coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
    duration[i] = durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    place[i] = placeId
    this.groupSize[i] = groupSize.coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
    val thumb = thumbHash?.takeIf { it.size in 1..MAX_THUMB_BYTES }
    if (thumb != null) {
      if (thumbLength + thumb.size > thumbBytes.size) thumbBytes = thumbBytes.copyOf(maxOf(thumbBytes.size * 2, thumbLength + thumb.size))
      System.arraycopy(thumb, 0, thumbBytes, thumbLength, thumb.size)
      thumbLength += thumb.size
    }
    thumbOffsets[i + 1] = thumbLength
    if (ref != null) setRef(i, ref) else setNasRef(i, nasRef)
    if (groupMsb != 0L || groupLsb != 0L) addGroup(i, groupMsb, groupLsb)
    size = i + 1
  }

  /**
   * Una riga del catalogo (`TimelineIndexDao.indexRows`) con il numero del suo gruppo e il riferimento ai byte (il
   * telefono, o in sua mancanza il NAS).
   */
  fun add(row: IndexRow, groupSizes: Map<String, Int>, ref: MediaRef.Phone?, nasRef: MediaRef.Nas? = null) {
    val id = LongArray(2)
    if (!Uuids.parse(row.mediaId, id)) return
    val group = LongArray(2)
    val hasGroup = row.groupId != null && Uuids.parse(row.groupId!!, group)
    add(
      msb = id[0], lsb = id[1], sortTimeMs = row.sortTime, localDay = row.localDay,
      kind = Codes.kind(row.kind), source = Codes.source(row.source), flags = row.cellFlags, aspectMilli = row.aspectMilli,
      durationMs = row.durationMs ?: 0L, placeId = row.placeId ?: 0, thumbHash = row.thumbHash,
      groupSize = if (hasGroup) groupSizes[row.groupId] ?: 1 else 1,
      groupMsb = if (hasGroup) group[0] else 0L, groupLsb = if (hasGroup) group[1] else 0L, ref = ref, nasRef = nasRef,
    )
  }

  /** Il riferimento ai byte della riga [i] gia' aggiunta (la lettura completa li legge dopo le righe). */
  fun setRef(i: Int, ref: MediaRef.Phone?) {
    contentKey[i] = 0L
    if (ref == null) {
      refId[i] = 0L
      refVolume[i] = 0
      refMime[i] = 0
      return
    }
    val v = volumes.indexOf(ref.volume)
    refId[i] = if (v == 0) 0L else ref.mediaStoreId
    refVolume[i] = v.toByte()
    refMime[i] = (ref.mime?.let { mimes.indexOf(it) } ?: 0).toByte()
  }

  /** Il riferimento al NAS della riga [i] (Fase 4): la posizione nella replica, il MIME e la chiave di contenuto. */
  fun setNasRef(i: Int, ref: MediaRef.Nas?) {
    if (ref == null) {
      setRef(i, null)
      return
    }
    refId[i] = ref.locationId
    refVolume[i] = ColumnarTimelineSnapshot.NAS_REF.toByte()
    refMime[i] = (ref.mime?.let { mimes.indexOf(it) } ?: 0).toByte()
    contentKey[i] = ref.contentKey
  }

  fun build(seq: Long): ColumnarTimelineSnapshot {
    if (!isSorted()) sortInPlace()
    val n = size
    return ColumnarTimelineSnapshot(
      seq = seq, size = n,
      idMsb = idMsb.copyOf(n), idLsb = idLsb.copyOf(n), sortTime = sortTime.copyOf(n), localDay = localDay.copyOf(n),
      kind = kind.copyOf(n), source = source.copyOf(n), flags = flags.copyOf(n), aspect = aspect.copyOf(n),
      duration = duration.copyOf(n), place = place.copyOf(n), groupSize = groupSize.copyOf(n),
      thumbOffsets = thumbOffsets.copyOf(n + 1), thumbBytes = thumbBytes.copyOf(thumbLength),
      refId = refId.copyOf(n), refVolume = refVolume.copyOf(n), refMime = refMime.copyOf(n), contentKey = contentKey.copyOf(n),
      volumes = volumes.toArray(), mimes = mimes.toArray(),
      groupIndex = groupIndex.copyOf(groupCount), groupMsb = groupMsb.copyOf(groupCount), groupLsb = groupLsb.copyOf(groupCount),
    )
  }

  private fun addGroup(i: Int, msb: Long, lsb: Long) {
    if (groupCount == groupIndex.size) {
      val c = groupIndex.size * 2
      groupIndex = groupIndex.copyOf(c)
      groupMsb = groupMsb.copyOf(c)
      groupLsb = groupLsb.copyOf(c)
    }
    groupIndex[groupCount] = i
    groupMsb[groupCount] = msb
    groupLsb[groupCount] = lsb
    groupCount++
  }

  private fun ensure(needed: Int) {
    if (needed <= capacity) return
    var c = capacity
    while (c < needed) c = c * 2
    capacity = c
    idMsb = idMsb.copyOf(c)
    idLsb = idLsb.copyOf(c)
    sortTime = sortTime.copyOf(c)
    localDay = localDay.copyOf(c)
    kind = kind.copyOf(c)
    source = source.copyOf(c)
    flags = flags.copyOf(c)
    aspect = aspect.copyOf(c)
    duration = duration.copyOf(c)
    place = place.copyOf(c)
    groupSize = groupSize.copyOf(c)
    thumbOffsets = thumbOffsets.copyOf(c + 1)
    refId = refId.copyOf(c)
    refVolume = refVolume.copyOf(c)
    refMime = refMime.copyOf(c)
    contentKey = contentKey.copyOf(c)
  }

  private fun isSorted(): Boolean {
    for (i in 1 until size) {
      if (compareIndexOrder(sortTime[i - 1], idMsb[i - 1], idLsb[i - 1], sortTime[i], idMsb[i], idLsb[i]) > 0) return false
    }
    return true
  }

  /** La via lenta: riordina tutte le colonne con una permutazione. Non succede con il `Cursor` del catalogo. */
  private fun sortInPlace() {
    val n = size
    val order = (0 until n).sortedWith { a, b ->
      compareIndexOrder(sortTime[a], idMsb[a], idLsb[a], sortTime[b], idMsb[b], idLsb[b])
    }.toIntArray()
    val rank = IntArray(n).also { r -> for (k in 0 until n) r[order[k]] = k }
    idMsb = LongArray(n) { idMsb[order[it]] }
    idLsb = LongArray(n) { idLsb[order[it]] }
    sortTime = LongArray(n) { sortTime[order[it]] }
    localDay = IntArray(n) { localDay[order[it]] }
    kind = ByteArray(n) { kind[order[it]] }
    source = ByteArray(n) { source[order[it]] }
    flags = IntArray(n) { flags[order[it]] }
    aspect = ShortArray(n) { aspect[order[it]] }
    duration = IntArray(n) { duration[order[it]] }
    place = IntArray(n) { place[order[it]] }
    groupSize = ShortArray(n) { groupSize[order[it]] }
    refId = LongArray(n) { refId[order[it]] }
    refVolume = ByteArray(n) { refVolume[order[it]] }
    refMime = ByteArray(n) { refMime[order[it]] }
    contentKey = LongArray(n) { contentKey[order[it]] }
    val oldOffsets = thumbOffsets
    val oldBytes = thumbBytes
    thumbOffsets = IntArray(n + 1)
    thumbBytes = ByteArray(thumbLength)
    var at = 0
    for (k in 0 until n) {
      val from = oldOffsets[order[k]]
      val len = oldOffsets[order[k] + 1] - from
      System.arraycopy(oldBytes, from, thumbBytes, at, len)
      at += len
      thumbOffsets[k + 1] = at
    }
    val groups = (0 until groupCount).sortedBy { rank[groupIndex[it]] }
    val gi = IntArray(groupCount) { rank[groupIndex[groups[it]]] }
    val gm = LongArray(groupCount) { groupMsb[groups[it]] }
    val gl = LongArray(groupCount) { groupLsb[groups[it]] }
    groupIndex = gi
    groupMsb = gm
    groupLsb = gl
    capacity = n
  }

  /** Una piccola tabella di stringhe (volumi, MIME): l'indice + 1 sta in un byte; oltre [limit] voci, 0 = sconosciuto. */
  private class Interned(private val limit: Int) {
    private val map = HashMap<String, Int>()
    private val list = ArrayList<String>()

    fun indexOf(value: String): Int = map[value] ?: if (list.size >= limit) 0 else {
      list += value
      map[value] = list.size
      list.size
    }

    fun toArray(): Array<String> = list.toTypedArray()
  }

  companion object {
    /** Un ThumbHash vero sta sotto i 36 byte (`ThumbHash.MAX_LENGTH` = 48 caratteri Base64). */
    const val MAX_THUMB_BYTES: Int = 36

    /** I MIME distinti che un indice sa nominare (l'indice + 1 sta in un byte). */
    const val MAX_MIMES: Int = 255
  }
}

/** I codici stabili di tipo e sorgente come li scrive il catalogo; un codice sconosciuto degrada a foto / altro. */
internal object Codes {
  private val kinds = MediaKind.entries.associateBy { it.code }
  private val sources = MediaSource.entries.associateBy { it.code }

  fun kind(code: String?): MediaKind = kinds[code] ?: MediaKind.PHOTO

  fun source(code: String?): MediaSource = sources[code] ?: MediaSource.OTHER
}

/**
 * La lettura dell'indice completo dal `Cursor` di `TimelineIndexDao.indexCursor` (colonne di
 * `TimelineIndexDao.COLUMNS`): una riga alla volta, nessun oggetto per riga oltre alle stringhe che il `Cursor` crea.
 */
object TimelineIndexReader {

  fun read(cursor: Cursor, seq: Long, groupSizes: Map<String, Int>): ColumnarTimelineSnapshot {
    val builder = TimelineIndexBuilder(cursor.count.coerceAtLeast(16))
    val cId = cursor.getColumnIndexOrThrow("media_id")
    val cTime = cursor.getColumnIndexOrThrow("sort_time")
    val cDay = cursor.getColumnIndexOrThrow("local_day")
    val cKind = cursor.getColumnIndexOrThrow("kind")
    val cSource = cursor.getColumnIndexOrThrow("source")
    val cFlags = cursor.getColumnIndexOrThrow("cell_flags")
    val cAspect = cursor.getColumnIndexOrThrow("aspect_milli")
    val cDuration = cursor.getColumnIndexOrThrow("duration_ms")
    val cPlace = cursor.getColumnIndexOrThrow("place_id")
    val cThumb = cursor.getColumnIndexOrThrow("thumb_hash")
    val cGroup = cursor.getColumnIndexOrThrow("group_id")
    val id = LongArray(2)
    val group = LongArray(2)
    while (cursor.moveToNext()) {
      if (!Uuids.parse(cursor.getString(cId) ?: continue, id)) continue
      val groupId = if (cursor.isNull(cGroup)) null else cursor.getString(cGroup)
      val hasGroup = groupId != null && Uuids.parse(groupId, group)
      builder.add(
        msb = id[0], lsb = id[1],
        sortTimeMs = cursor.getLong(cTime),
        localDay = cursor.getInt(cDay),
        kind = Codes.kind(cursor.getString(cKind)),
        source = Codes.source(cursor.getString(cSource)),
        flags = cursor.getInt(cFlags),
        aspectMilli = cursor.getInt(cAspect),
        durationMs = if (cursor.isNull(cDuration)) 0L else cursor.getLong(cDuration),
        placeId = if (cursor.isNull(cPlace)) 0 else cursor.getInt(cPlace),
        thumbHash = if (cursor.isNull(cThumb)) null else cursor.getBlob(cThumb),
        groupSize = if (hasGroup) groupSizes[groupId] ?: 1 else 1,
        groupMsb = if (hasGroup) group[0] else 0L,
        groupLsb = if (hasGroup) group[1] else 0L,
      )
    }
    return builder.build(seq)
  }

  /** Lo stesso indice da righe gia' lette (test, piccole liste). */
  fun fromRows(
    rows: List<IndexRow>,
    seq: Long,
    groupSizes: Map<String, Int>,
    refs: Map<String, MediaRef.Phone> = emptyMap(),
    nasRefs: Map<String, MediaRef.Nas> = emptyMap(),
  ): ColumnarTimelineSnapshot {
    val builder = TimelineIndexBuilder(rows.size.coerceAtLeast(16))
    for (row in rows) builder.add(row, groupSizes, refs[row.mediaId], nasRefs[row.mediaId])
    return builder.build(seq)
  }
}

/** Il segnaposto di una cella come byte (per i test e per chi confronta un indice con un altro). */
fun ColumnarTimelineSnapshot.thumbBytesAt(i: Int): ByteArray? {
  val from = thumbOffsets[i]
  val to = thumbOffsets[i + 1]
  return if (to > from) thumbBytes.copyOfRange(from, to) else null
}

internal fun ThumbHash?.bytesOrNull(): ByteArray? = this?.bytes()
