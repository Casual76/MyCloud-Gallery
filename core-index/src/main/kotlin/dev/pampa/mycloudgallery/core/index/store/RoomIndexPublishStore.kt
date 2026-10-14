package dev.pampa.mycloudgallery.core.index.store

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.IndexPublishDao
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexPublishStateEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * [IndexPublishStore] sopra il database della galleria (schema v3, P11): le tabelle `index_publish_state`,
 * `index_outbox_record`, `index_outbox_blob`, `index_own_segment`, `index_own_record`, `index_own_blob`.
 *
 * **Una transazione per metodo** (la regola d'oro della porta): una morte del processo prima del ritorno lascia lo stato
 * di prima, dopo il ritorno quello di dopo. Le share si confrontano senza maiuscole (`share.lowercase(Locale.ROOT)`); il
 * nome com'e' stato scritto la prima volta resta in `index_publish_state.share_name`. Il riferimento del contratto e'
 * [InMemoryIndexPublishStore]: `RoomIndexPublishStoreTest` fa le stesse operazioni sui due e confronta.
 *
 * I record si salvano come JSON canonico (`MetaRecords.encode`) e si rileggono con `MetaRecords.verify`: sono gli stessi
 * byte che finiranno nel segmento.
 */
class RoomIndexPublishStore(private val database: GalleryDatabase) : IndexPublishStore {

  private val dao: IndexPublishDao = database.indexPublishDao()

  private fun key(share: String): String = share.lowercase(Locale.ROOT)

  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<StagedBlob>): StagedBatch = database.transaction {
    val shareKey = key(share)
    val state = dao.publishState(shareKey) ?: IndexPublishStateEntity(shareKey = shareKey, shareName = share, nextSeq = 1)
    check(state.nextSeq + changes.size - 1 <= TrustLimits.MAX_RECORD_SEQ) { "seq esauriti per questa share." }
    var seq = state.nextSeq
    val records = changes.map { change ->
      val record = when (change) {
        is MetaChange.Put -> MetaRecord.Present(change.path, seq, change.file)
        is MetaChange.Remove -> MetaRecord.Gone(change.path, seq)
      }
      seq++
      record
    }
    val recordIds = if (records.isEmpty()) {
      emptyList()
    } else {
      dao.insertRecords(
        records.map { record ->
          IndexOutboxRecordEntity(
            shareKey = shareKey,
            seq = record.seq,
            pathKey = IndexKeys.metaHex(record.path),
            contentKey = (record as? MetaRecord.Present)?.file?.contentKey?.hex,
            body = MetaRecords.encode(record),
            sunk = false,
          )
        },
      )
    }
    val blobIds = if (blobs.isEmpty()) {
      emptyList()
    } else {
      dao.insertBlobs(blobs.map { IndexOutboxBlobEntity(0, shareKey, it.cls.code, it.contentKey.hex, it.length, it.handle, sunk = false) })
    }
    dao.savePublishState(state.copy(nextSeq = seq))
    StagedBatch(
      records.zip(recordIds) { record, id -> OutboxRecord(id, record) },
      blobs.zip(blobIds) { blob, id -> OutboxBlob(id, blob) },
    )
  }

  override suspend fun markSunk(share: String, recordIds: Set<Long>, blobIds: Set<Long>) {
    if (recordIds.isEmpty() && blobIds.isEmpty()) return
    val shareKey = key(share)
    database.transaction {
      recordIds.chunked(IndexPublishDao.MAX_KEYS).forEach { dao.markRecordsSunk(shareKey, it) }
      blobIds.chunked(IndexPublishDao.MAX_KEYS).forEach { dao.markBlobsSunk(shareKey, it) }
    }
  }

  override suspend fun unsunk(): List<OutboxSnapshot> = database.transaction {
    val names = dao.publishStates().associate { it.shareKey to it.shareName }
    val records = dao.unsunkRecords().groupBy { it.shareKey }
    val blobs = dao.unsunkBlobs().groupBy { it.shareKey }
    (records.keys + blobs.keys).sorted().map { shareKey ->
      OutboxSnapshot(
        names[shareKey] ?: shareKey,
        records[shareKey].orEmpty().mapNotNull { it.toOutbox() },
        blobs[shareKey].orEmpty().map { it.toOutbox() },
      )
    }.filterNot { it.isEmpty }
  }

  override suspend fun outbox(share: String): OutboxSnapshot = database.transaction {
    val shareKey = key(share)
    OutboxSnapshot(
      dao.publishState(shareKey)?.shareName ?: share,
      dao.outboxRecords(shareKey).mapNotNull { it.toOutbox() },
      dao.outboxBlobs(shareKey).map { it.toOutbox() },
    )
  }

  override suspend fun discard(share: String, recordIds: Set<Long>, blobIds: Set<Long>) {
    if (recordIds.isEmpty() && blobIds.isEmpty()) return
    val shareKey = key(share)
    database.transaction {
      recordIds.chunked(IndexPublishDao.MAX_KEYS).forEach { dao.deleteRecords(shareKey, it) }
      blobIds.chunked(IndexPublishDao.MAX_KEYS).forEach { dao.deleteBlobs(shareKey, it) }
    }
  }

  override suspend fun pending(): PendingCounts = database.transaction {
    val blobs = dao.pendingBlobs()
    PendingCounts(records = dao.pendingRecordCount(), blobs = blobs.items, bytes = blobs.bytes)
  }

  override suspend fun ownHead(share: String): OwnHead? = database.transaction {
    val shareKey = key(share)
    val state = dao.publishState(shareKey) ?: return@transaction null
    val hash = state.headHash ?: return@transaction null
    if (state.headVersion <= 0) return@transaction null
    OwnHead(
      version = state.headVersion,
      mark = HeadMark(state.headVersion, hash),
      publishedAtNas = state.headPublishedAt ?: 0L,
      segments = dao.listedSegments(shareKey).map { it.toOwn() },
    )
  }

  override suspend fun knownPaths(share: String, pathKeys: Set<String>): Set<String> {
    if (pathKeys.isEmpty()) return emptySet()
    val shareKey = key(share)
    return database.transaction {
      val out = HashSet<String>()
      for (chunk in pathKeys.toList().chunked(IndexPublishDao.MAX_KEYS)) {
        out += dao.livePathKeys(shareKey, chunk)
        out += dao.pendingPathKeys(shareKey, chunk)
      }
      out
    }
  }

  override suspend fun knownContentKeys(share: String, keys: Set<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val shareKey = key(share)
    return database.transaction {
      val out = HashSet<ContentKey>()
      for (chunk in keys.map { it.hex }.chunked(IndexPublishDao.MAX_KEYS)) {
        dao.liveContentKeys(shareKey, chunk).forEach { out += ContentKey(it) }
        dao.pendingContentKeys(shareKey, chunk).forEach { out += ContentKey(it) }
      }
      out
    }
  }

  override suspend fun liveRecordsIn(share: String, segments: Set<String>): List<LiveRecord> {
    if (segments.isEmpty()) return emptyList()
    val shareKey = key(share)
    return database.transaction {
      segments.toList().chunked(IndexPublishDao.MAX_KEYS).flatMap { dao.ownRecordsIn(shareKey, it) }.map { it.toLive() }
    }
  }

  override suspend fun liveBlobsIn(share: String, segments: Set<String>): List<LiveBlob> {
    if (segments.isEmpty()) return emptyList()
    val shareKey = key(share)
    return database.transaction {
      segments.toList().chunked(IndexPublishDao.MAX_KEYS).flatMap { dao.ownBlobsIn(shareKey, it) }.mapNotNull { row ->
        SegmentClass.fromCode(row.cls)?.let { LiveBlob(it, ContentKey(row.contentKey), row.segmentHash) }
      }
    }
  }

  override suspend fun liveCounts(share: String): Map<String, Int> = database.transaction {
    val shareKey = key(share)
    val counts = HashMap<String, Int>()
    if (dao.publishState(shareKey)?.headHash != null) dao.listedSegments(shareKey).forEach { counts[it.segmentHash] = 0 }
    dao.ownRecordCounts(shareKey).forEach { counts.merge(it.segment, it.items, Int::plus) }
    dao.ownBlobCounts(shareKey).forEach { counts.merge(it.segment, it.items, Int::plus) }
    counts
  }

  override suspend fun liveRecordCount(share: String): Int = dao.ownRecordCount(key(share))

  override suspend fun publishedSegments(share: String): Set<String> = dao.publishedSegmentHashes(key(share)).toSet()

  override suspend fun commit(share: String, commit: PublishCommit) {
    val shareKey = key(share)
    database.transaction {
      commit.consumedRecords.toList().chunked(IndexPublishDao.MAX_KEYS).forEach { dao.deleteRecords(shareKey, it) }
      commit.consumedBlobs.toList().chunked(IndexPublishDao.MAX_KEYS).forEach { dao.deleteBlobs(shareKey, it) }
      // La testa: lo stato e' funzione della testa (D5). I segmenti elencati sono tutti e soli quelli della testa nuova;
      // il diario conserva anche quelli non piu' elencati.
      val state = dao.publishState(shareKey) ?: IndexPublishStateEntity(shareKey = shareKey, shareName = share, nextSeq = 1)
      dao.savePublishState(
        state.copy(headVersion = commit.head.version, headHash = commit.head.mark.contentHash, headPublishedAt = commit.head.publishedAtNas),
      )
      dao.unlistSegments(shareKey)
      val listed = commit.head.segments.associateBy { it.hash }
      val diary = (commit.newSegments.associateBy { it.hash } + listed).values
      dao.saveOwnSegments(diary.map { it.toEntity(shareKey, listed = it.hash in listed) })
      // I record vivi: vince il seq piu' alto (a pari seq il piu' recente, come il riferimento in memoria).
      for (chunk in commit.records.chunked(IndexPublishDao.MAX_KEYS)) {
        val existing = dao.ownRecords(shareKey, chunk.map { it.pathKey }).associateBy { it.pathKey }
        val winners = LinkedHashMap<String, LiveRecord>()
        for (record in chunk) {
          val previous = winners[record.pathKey]?.seq ?: existing[record.pathKey]?.seq
          if (previous == null || record.seq >= previous) winners[record.pathKey] = record
        }
        if (winners.isNotEmpty()) dao.saveOwnRecords(winners.values.map { it.toEntity(shareKey) })
      }
      commit.droppedPaths.toList().chunked(IndexPublishDao.MAX_KEYS).forEach { dao.deleteOwnRecords(shareKey, it) }
      if (commit.blobs.isNotEmpty()) {
        dao.saveOwnBlobs(commit.blobs.map { IndexOwnBlobEntity(shareKey, it.cls.code, it.contentKey.hex, it.segment) })
      }
    }
  }

  override suspend fun referencedHandles(): Set<String> = dao.referencedHandles().toSet()

  // --- Conversioni ----------------------------------------------------------------------------------------------------

  private fun IndexOutboxRecordEntity.toOutbox(): OutboxRecord? =
    (MetaRecords.verify(body) as? Verdict.Accepted)?.value?.let { OutboxRecord(id, it) }

  private fun IndexOutboxBlobEntity.toOutbox(): OutboxBlob =
    OutboxBlob(id, StagedBlob(checkNotNull(SegmentClass.fromCode(cls)) { "Classe sconosciuta." }, ContentKey(contentKey), length, handle))

  private fun IndexOwnSegmentEntity.toOwn(): OwnSegment =
    OwnSegment(ClassedSegment(checkNotNull(SegmentClass.fromCode(cls)) { "Classe sconosciuta." }, SegmentRef(segmentHash, size)), entries)

  private fun OwnSegment.toEntity(shareKey: String, listed: Boolean): IndexOwnSegmentEntity =
    IndexOwnSegmentEntity(shareKey, segment.ref.hash, segment.cls.code, segment.ref.size, entries, listed)

  private fun IndexOwnRecordEntity.toLive(): LiveRecord =
    LiveRecord(pathKey, SharePath.parse(path), seq, gone, contentKey?.let(::ContentKey), segmentHash)

  private fun LiveRecord.toEntity(shareKey: String): IndexOwnRecordEntity =
    IndexOwnRecordEntity(shareKey, pathKey, path.value, seq, gone, contentKey?.hex, segment)
}
