package dev.pampa.mycloudgallery.core.index.store

import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.Locale
import java.util.TreeMap

/**
 * [IndexPublishStore] in memoria: ogni metodo e' atomico (sincronizzato, calcola tutto prima di cambiare qualcosa).
 * Serve ai test (anche come "database" che sopravvive alla morte del pubblicatore) e come riferimento del contratto
 * per gli adattatori di P11 sui DAO dello schema v3.
 */
class InMemoryIndexPublishStore : IndexPublishStore {

  private class Row<T>(val value: T, var sunk: Boolean)

  private class ShareState(var name: String) {
    var nextSeq = 1L
    val records = TreeMap<Long, Row<MetaRecord>>()
    val blobs = TreeMap<Long, Row<StagedBlob>>()
    var head: OwnHead? = null
    val diary = LinkedHashMap<String, OwnSegment>()
    val liveRecords = HashMap<String, LiveRecord>()
    val liveBlobs = HashMap<Pair<SegmentClass, ContentKey>, LiveBlob>()
  }

  private val lock = Any()
  private var nextId = 1L
  private val shares = LinkedHashMap<String, ShareState>()

  /** Quante transazioni sono state applicate (per i test di "morte a ogni passo"). */
  @Volatile var transactions: Int = 0
    private set

  private fun state(share: String): ShareState =
    shares.getOrPut(share.lowercase(Locale.ROOT)) { ShareState(share) }

  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<StagedBlob>): StagedBatch = synchronized(lock) {
    val s = state(share)
    check(s.nextSeq + changes.size - 1 <= TrustLimits.MAX_RECORD_SEQ) { "seq esauriti per questa share." }
    var seq = s.nextSeq
    var id = nextId
    val records = changes.map { change ->
      val record = when (change) {
        is MetaChange.Put -> MetaRecord.Present(change.path, seq, change.file)
        is MetaChange.Remove -> MetaRecord.Gone(change.path, seq)
      }
      seq++
      OutboxRecord(id++, record)
    }
    val staged = blobs.map { OutboxBlob(id++, it) }
    // Tutto calcolato: ora si applica.
    s.nextSeq = seq
    nextId = id
    records.forEach { s.records[it.id] = Row(it.record, sunk = false) }
    staged.forEach { s.blobs[it.id] = Row(it.blob, sunk = false) }
    transactions++
    StagedBatch(records, staged)
  }

  override suspend fun markSunk(share: String, recordIds: Set<Long>, blobIds: Set<Long>): Unit = synchronized(lock) {
    val s = state(share)
    recordIds.forEach { s.records[it]?.sunk = true }
    blobIds.forEach { s.blobs[it]?.sunk = true }
    transactions++
  }

  override suspend fun unsunk(): List<OutboxSnapshot> = synchronized(lock) {
    shares.values.map { s ->
      OutboxSnapshot(
        s.name,
        s.records.filterValues { !it.sunk }.map { (id, row) -> OutboxRecord(id, row.value) },
        s.blobs.filterValues { !it.sunk }.map { (id, row) -> OutboxBlob(id, row.value) },
      )
    }.filterNot { it.isEmpty }
  }

  override suspend fun outbox(share: String): OutboxSnapshot = synchronized(lock) {
    val s = state(share)
    OutboxSnapshot(s.name, s.records.map { (id, row) -> OutboxRecord(id, row.value) }, s.blobs.map { (id, row) -> OutboxBlob(id, row.value) })
  }

  override suspend fun discard(share: String, recordIds: Set<Long>, blobIds: Set<Long>): Unit = synchronized(lock) {
    val s = state(share)
    recordIds.forEach { s.records.remove(it) }
    blobIds.forEach { s.blobs.remove(it) }
    transactions++
  }

  override suspend fun pending(): PendingCounts = synchronized(lock) {
    PendingCounts(
      records = shares.values.sumOf { it.records.size },
      blobs = shares.values.sumOf { it.blobs.size },
      bytes = shares.values.sumOf { s -> s.blobs.values.sumOf { it.value.length.toLong() } },
    )
  }

  override suspend fun ownHead(share: String): OwnHead? = synchronized(lock) { state(share).head }

  override suspend fun knownPaths(share: String, pathKeys: Set<String>): Set<String> = synchronized(lock) {
    val s = state(share)
    val pending = s.records.values.map { IndexKeys.metaHex(it.value.path) }.toSet()
    pathKeys.filterTo(HashSet()) { it in s.liveRecords || it in pending }
  }

  override suspend fun knownContentKeys(share: String, keys: Set<ContentKey>): Set<ContentKey> = synchronized(lock) {
    val s = state(share)
    val own = HashSet<ContentKey>()
    s.liveRecords.values.forEach { r -> r.contentKey?.let(own::add) }
    s.records.values.forEach { row -> (row.value as? MetaRecord.Present)?.let { own += it.file.contentKey } }
    keys.filterTo(HashSet()) { it in own }
  }

  override suspend fun liveRecordsIn(share: String, segments: Set<String>): List<LiveRecord> = synchronized(lock) {
    state(share).liveRecords.values.filter { it.segment in segments }
  }

  override suspend fun liveBlobsIn(share: String, segments: Set<String>): List<LiveBlob> = synchronized(lock) {
    state(share).liveBlobs.values.filter { it.segment in segments }
  }

  override suspend fun liveCounts(share: String): Map<String, Int> = synchronized(lock) {
    val s = state(share)
    val counts = HashMap<String, Int>()
    s.head?.segments?.forEach { counts[it.hash] = 0 }
    s.liveRecords.values.forEach { counts.merge(it.segment, 1, Int::plus) }
    s.liveBlobs.values.forEach { counts.merge(it.segment, 1, Int::plus) }
    counts
  }

  override suspend fun liveRecordCount(share: String): Int = synchronized(lock) { state(share).liveRecords.size }

  override suspend fun publishedSegments(share: String): Set<String> = synchronized(lock) { state(share).diary.keys.toSet() }

  override suspend fun commit(share: String, commit: PublishCommit): Unit = synchronized(lock) {
    val s = state(share)
    commit.consumedRecords.forEach { s.records.remove(it) }
    commit.consumedBlobs.forEach { s.blobs.remove(it) }
    s.head = commit.head
    commit.newSegments.forEach { s.diary[it.hash] = it }
    for (record in commit.records) {
      val existing = s.liveRecords[record.pathKey]
      if (existing == null || record.seq >= existing.seq) s.liveRecords[record.pathKey] = record
    }
    commit.droppedPaths.forEach { s.liveRecords.remove(it) }
    commit.blobs.forEach { s.liveBlobs[it.cls to it.contentKey] = it }
    transactions++
  }

  override suspend fun referencedHandles(): Set<String> = synchronized(lock) {
    shares.values.flatMapTo(HashSet()) { s -> s.blobs.values.map { it.value.handle } }
  }
}

/** [OutboxBlobStore] in memoria. */
class InMemoryOutboxBlobStore : OutboxBlobStore {
  private val lock = Any()
  private val blobs = LinkedHashMap<String, ByteArray>()
  private var next = 1L

  override suspend fun write(share: String, cls: SegmentClass, bytes: ByteArray): String = synchronized(lock) {
    val handle = "b${next++}"
    blobs[handle] = bytes.copyOf()
    handle
  }

  override suspend fun read(handle: String): ByteArray? = synchronized(lock) { blobs[handle]?.copyOf() }

  override suspend fun delete(handles: Collection<String>) = synchronized(lock) { handles.forEach { blobs.remove(it) } }

  override suspend fun retainOnly(referenced: Set<String>) = synchronized(lock) { blobs.keys.retainAll(referenced); Unit }

  override suspend fun handles(): Set<String> = synchronized(lock) { blobs.keys.toSet() }
}
