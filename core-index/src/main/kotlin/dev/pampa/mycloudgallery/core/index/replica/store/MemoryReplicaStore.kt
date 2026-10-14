package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.TreeSet

/**
 * [ReplicaStore] in memoria: lo stesso contratto dell'adattatore sui DAO (P11), una transazione per chiamata
 * (`synchronized`). Lo usano i test di P4 e, finche' P11 non lega il database, i grafi di prova. Nessun dato su disco.
 */
class MemoryReplicaStore : ReplicaStore {

  private val lock = Any()
  private val heads = HashMap<Pair<String, String>, HeadRow>()
  private val segments = HashMap<Pair<String, String>, LinkedHashMap<String, SegmentRow>>()

  /** share → chiave del percorso → righe (di ogni dispositivo e segmento). */
  private val records = HashMap<String, HashMap<String, MutableList<RecordRow>>>()

  /** (share, dispositivo, segmento) → chiavi dei percorsi, per togliere un segmento in fretta. */
  private val bySegment = HashMap<Triple<String, String, String>, MutableSet<String>>()
  private val packs = LinkedHashMap<Long, PackRow>()
  private val blobs = HashMap<Long, MutableList<BlobRow>>()
  private val access = HashMap<String, ShareAccessRow>()
  private val dirty = HashMap<String, TreeSet<String>>()
  private var nextPackId = 1L
  private var level: QualityLevel? = null

  /** Quante transazioni di scrittura (per i test di atomicita' e ripresa). */
  @Volatile var writes: Int = 0
    private set

  override suspend fun heads(share: String): List<HeadRow> = synchronized(lock) {
    heads.values.filter { it.share == share }.sortedBy { it.deviceId }
  }

  override suspend fun segments(share: String, deviceId: String): List<SegmentRow> = synchronized(lock) {
    segments[share to deviceId]?.values?.toList().orEmpty()
  }

  override suspend fun segmentsWhere(states: Set<SegmentState>, classes: Set<SegmentClass>): List<SegmentRow> = synchronized(lock) {
    segments.values.flatMap { it.values }.filter { it.state in states && it.cls in classes }
  }

  override suspend fun applyHead(application: HeadApplication): StoreChange = synchronized(lock) {
    writes++
    val head = application.head
    val key = head.share to head.deviceId
    val touched = HashSet<String>()
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    val current = segments.getOrPut(key) { LinkedHashMap() }
    for (hash in application.dropped) {
      val row = current.remove(hash) ?: continue
      dropSegmentData(head.share, head.deviceId, row, touched, released, removedBlobs)
    }
    val fresh = LinkedHashMap<String, SegmentRow>()
    for (row in application.segments) fresh[row.hash] = row.copy(packId = row.packId ?: current[row.hash]?.packId)
    // Un segmento che non compare piu' ne' fra gli elencati ne' fra i trattenuti e non e' fra i tolti: lo si toglie
    // comunque (nessun dato orfano).
    for ((hash, row) in current) if (hash !in fresh) dropSegmentData(head.share, head.deviceId, row, touched, released, removedBlobs)
    segments[key] = fresh
    for ((segment, rows) in application.records) {
      val byPath = records.getOrPut(head.share) { HashMap() }
      // Un segmento riapplicato (assente, poi ricomparso con una testa nuova) non raddoppia le righe.
      bySegment[Triple(head.share, head.deviceId, segment)]?.forEach { pathKey ->
        byPath[pathKey]?.removeAll { it.deviceId == head.deviceId && it.segment == segment }
      }
      val keys = bySegment.getOrPut(Triple(head.share, head.deviceId, segment)) { HashSet() }
      for (row in rows) {
        byPath.getOrPut(row.pathKey) { ArrayList(2) }.add(row)
        keys += row.pathKey
        touched += row.pathKey
      }
    }
    application.pruneLocalUpToSeq?.let { upTo ->
      val localKey = Triple(head.share, head.deviceId, RecordRow.LOCAL_SEGMENT)
      val keys = bySegment[localKey]
      if (keys != null) {
        val byPath = records[head.share].orEmpty()
        val iterator = keys.iterator()
        while (iterator.hasNext()) {
          val pathKey = iterator.next()
          val list = byPath[pathKey] ?: continue
          if (list.removeAll { it.deviceId == head.deviceId && it.segment == RecordRow.LOCAL_SEGMENT && it.record.seq <= upTo }) {
            touched += pathKey
            if (list.none { it.deviceId == head.deviceId && it.segment == RecordRow.LOCAL_SEGMENT }) iterator.remove()
          }
        }
      }
    }
    heads[key] = head
    markDirty(head.share, touched)
    StoreChange(touched, released, removedBlobs)
  }

  override suspend fun setSegmentState(share: String, deviceId: String, hash: String, state: SegmentState) {
    synchronized(lock) {
      writes++
      val map = segments[share to deviceId] ?: return
      val row = map[hash] ?: return
      map[hash] = row.copy(state = state)
    }
  }

  override suspend fun applyBlobSegment(pack: PackRow, blobs: List<BlobRow>): Long? = synchronized(lock) {
    val share = requireNotNull(pack.share)
    val device = requireNotNull(pack.deviceId)
    val hash = requireNotNull(pack.segment)
    val row = segments[share to device]?.get(hash) ?: return@synchronized null
    writes++
    val existing = packs.values.firstOrNull { it.name == pack.name }
    val id = existing?.id ?: nextPackId++
    if (existing == null) packs[id] = pack.copy(id = id)
    this.blobs[id] = blobs.map { it.copy(packId = id) }.toMutableList()
    segments.getValue(share to device)[hash] = row.copy(state = SegmentState.APPLIED, packId = id)
    id
  }

  override suspend fun forgetDevice(share: String, deviceId: String): StoreChange = synchronized(lock) {
    writes++
    val touched = HashSet<String>()
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    segments.remove(share to deviceId)?.values?.forEach { dropSegmentData(share, deviceId, it, touched, released, removedBlobs) }
    bySegment.remove(Triple(share, deviceId, RecordRow.LOCAL_SEGMENT))?.let { keys ->
      val byPath = records[share].orEmpty()
      keys.forEach { pathKey -> if (byPath[pathKey]?.removeAll { it.deviceId == deviceId } == true) touched += pathKey }
    }
    heads.remove(share to deviceId)
    markDirty(share, touched)
    StoreChange(touched, released, removedBlobs)
  }

  override suspend fun forgetShare(share: String): StoreChange = synchronized(lock) {
    writes++
    val touched = records.remove(share)?.keys?.toHashSet() ?: HashSet()
    bySegment.keys.removeAll { it.first == share }
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    packs.values.filter { it.share == share }.toList().forEach { pack ->
      packs.remove(pack.id)
      released += pack
      blobs.remove(pack.id)?.let { removedBlobs += it }
    }
    segments.keys.removeAll { it.first == share }
    heads.keys.removeAll { it.first == share }
    dirty.remove(share)
    StoreChange(touched, released, removedBlobs)
  }

  override suspend fun records(share: String, pathKeys: Collection<String>): List<RecordRow> = synchronized(lock) {
    val byPath = records[share] ?: return@synchronized emptyList()
    pathKeys.flatMap { byPath[it].orEmpty() }
  }

  override suspend fun pathKeys(share: String): Set<String> = synchronized(lock) {
    records[share]?.filterValues { it.isNotEmpty() }?.keys?.toSet().orEmpty()
  }

  override suspend fun presentContentKeys(share: String, deviceId: String): Set<ContentKey> = synchronized(lock) {
    records[share]?.values.orEmpty().asSequence().flatten()
      .filter { it.deviceId == deviceId }
      .mapNotNull { (it.record as? MetaRecord.Present)?.file?.contentKey }
      .toHashSet()
  }

  override suspend fun preparedContentCount(classMask: Int): Int = synchronized(lock) {
    val keys = HashSet<String>()
    for (byPath in records.values) {
      for (rows in byPath.values) {
        for (row in rows) {
          val present = row.record as? MetaRecord.Present ?: continue
          if (present.file.prepared and classMask != 0) keys += present.file.contentKey.hex
        }
      }
    }
    keys.size
  }

  override suspend fun putLocalRecords(share: String, rows: List<RecordRow>): Set<String> = synchronized(lock) {
    writes++
    val byPath = records.getOrPut(share) { HashMap() }
    val touched = HashSet<String>()
    for (row in rows) {
      require(row.segment == RecordRow.LOCAL_SEGMENT) { "Solo record locali." }
      val list = byPath.getOrPut(row.pathKey) { ArrayList(2) }
      val previous = list.firstOrNull { it.deviceId == row.deviceId && it.segment == RecordRow.LOCAL_SEGMENT }
      if (previous != null && previous.record.seq >= row.record.seq) continue
      if (previous != null) list.remove(previous)
      list += row
      bySegment.getOrPut(Triple(share, row.deviceId, RecordRow.LOCAL_SEGMENT)) { HashSet() } += row.pathKey
      touched += row.pathKey
    }
    markDirty(share, touched)
    touched
  }

  override suspend fun dirty(share: String, limit: Int): List<String> = synchronized(lock) {
    dirty[share]?.asSequence()?.take(limit)?.toList().orEmpty()
  }

  override suspend fun dirtyShares(): Set<String> = synchronized(lock) { dirty.filterValues { it.isNotEmpty() }.keys.toSet() }

  override suspend fun clearDirty(share: String, pathKeys: Collection<String>) {
    synchronized(lock) {
      writes++
      dirty[share]?.removeAll(pathKeys.toSet())
    }
  }

  private fun markDirty(share: String, keys: Collection<String>) {
    if (keys.isNotEmpty()) dirty.getOrPut(share) { TreeSet() }.addAll(keys)
  }

  override suspend fun packs(): List<PackRow> = synchronized(lock) { packs.values.toList() }

  override suspend fun blobs(): List<BlobRow> = synchronized(lock) { blobs.values.flatten() }

  override suspend fun openOwnPack(pack: PackRow): Long = synchronized(lock) {
    writes++
    val id = nextPackId++
    packs[id] = pack.copy(id = id, kind = PackKind.OWN)
    blobs[id] = ArrayList()
    id
  }

  override suspend fun appendOwnBlobs(packId: Long, newLength: Long, blobs: List<BlobRow>) {
    synchronized(lock) {
      writes++
      val pack = requireNotNull(packs[packId]) { "Pacco sconosciuto." }
      require(pack.kind == PackKind.OWN && newLength >= pack.bytes) { "Solo aggiunte a un pacco proprio." }
      packs[packId] = pack.copy(bytes = newLength)
      this.blobs.getOrPut(packId) { ArrayList() } += blobs.map { it.copy(packId = packId) }
    }
  }

  override suspend fun releasePacksOutside(keep: Set<SegmentClass>, maxBytes: Long): StoreChange = synchronized(lock) {
    val candidates = packs.values.filter { it.cls !in keep }.sortedWith(compareByDescending<PackRow> { it.bytes }.thenBy { it.id })
    if (candidates.isEmpty()) return@synchronized StoreChange.NONE
    writes++
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    var total = 0L
    for (pack in candidates) {
      if (released.isNotEmpty() && total + pack.bytes > maxBytes) break
      total += pack.bytes
      released += pack
      packs.remove(pack.id)
      blobs.remove(pack.id)?.let { removedBlobs += it }
      if (pack.kind == PackKind.SEGMENT) {
        val map = segments[requireNotNull(pack.share) to requireNotNull(pack.deviceId)]
        val row = map?.get(pack.segment)
        if (row != null) map[row.hash] = row.copy(state = SegmentState.SKIPPED, packId = null)
      }
    }
    StoreChange(emptySet(), released, removedBlobs)
  }

  override suspend fun shareAccess(share: String): ShareAccessRow? = synchronized(lock) { access[share] }

  override suspend fun setShareAccess(share: String, row: ShareAccessRow?) {
    synchronized(lock) {
      writes++
      if (row == null) access.remove(share) else access[share] = row
    }
  }

  override suspend fun quality(): QualityLevel? = synchronized(lock) { level }

  override suspend fun setQuality(level: QualityLevel) {
    synchronized(lock) {
      writes++
      this.level = level
    }
  }

  /** Quanti record in tutto (per i test). */
  fun recordCount(): Int = synchronized(lock) { records.values.sumOf { byPath -> byPath.values.sumOf { it.size } } }

  private fun dropSegmentData(
    share: String,
    deviceId: String,
    row: SegmentRow,
    touched: MutableSet<String>,
    released: MutableList<PackRow>,
    removedBlobs: MutableList<BlobRow>,
  ) {
    bySegment.remove(Triple(share, deviceId, row.hash))?.let { keys ->
      val byPath = records[share].orEmpty()
      for (pathKey in keys) {
        val list = byPath[pathKey] ?: continue
        if (list.removeAll { it.deviceId == deviceId && it.segment == row.hash }) touched += pathKey
      }
    }
    row.packId?.let { id ->
      packs.remove(id)?.let { released += it }
      blobs.remove(id)?.let { removedBlobs += it }
    }
  }
}
