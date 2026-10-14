package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.ReplicaDao
import dev.pampa.mycloudgallery.core.database.entity.IndexHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaPackEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaProvisionalEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaSettingEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaShareAccessEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass

/**
 * [ReplicaStore] sopra il database della galleria (schema v3, P11): `index_head`, `index_segment`, `index_record`,
 * `replica_dirty`, `replica_pack`, `replica_blob`, `replica_share_access`, `replica_setting`.
 *
 * **Una transazione per metodo** (D5: lo stato di un dispositivo e' funzione della sua testa; il `HeadMark` si salva nella
 * stessa transazione che applica la testa, E-I5). Il riferimento del contratto e' [MemoryReplicaStore]:
 * `RoomReplicaStoreTest` fa le stesse operazioni sui due e confronta ogni risultato.
 *
 * I record si salvano come JSON canonico (`MetaRecords.encode`, gli stessi byte del segmento) piu' le colonne delle query.
 * I pacchi si tolgono **con** i loro blob esplicitamente (non ci si affida al `CASCADE`, che dipende da
 * `PRAGMA foreign_keys`).
 */
class RoomReplicaStore(private val database: GalleryDatabase) : ReplicaStore {

  private val dao: ReplicaDao = database.replicaDao()

  // --- Teste e segmenti ---------------------------------------------------------------------------------------------

  override suspend fun heads(share: String): List<HeadRow> = dao.heads(share).map { it.toRow() }

  override suspend fun segments(share: String, deviceId: String): List<SegmentRow> = dao.segments(share, deviceId).mapNotNull { it.toRow() }

  override suspend fun segmentsWhere(states: Set<SegmentState>, classes: Set<SegmentClass>): List<SegmentRow> {
    if (states.isEmpty() || classes.isEmpty()) return emptyList()
    return dao.segmentsWhere(states.map { it.code }, classes.map { it.code }).mapNotNull { it.toRow() }
  }

  override suspend fun applyHead(application: HeadApplication): StoreChange = database.transaction {
    val head = application.head
    val share = head.share
    val device = head.deviceId
    val touched = HashSet<String>()
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    val current = LinkedHashMap<String, SegmentRow>()
    dao.segments(share, device).forEach { entity -> entity.toRow()?.let { current[it.hash] = it } }
    for (hash in application.dropped) {
      val row = current.remove(hash) ?: continue
      dropSegmentData(share, device, row, touched, released, removedBlobs)
    }
    val fresh = LinkedHashMap<String, SegmentRow>()
    for (row in application.segments) fresh[row.hash] = row.copy(packId = row.packId ?: current[row.hash]?.packId)
    // Un segmento che non compare piu' ne' fra gli elencati ne' fra i trattenuti e non e' fra i tolti: si toglie comunque.
    for ((hash, row) in current) if (hash !in fresh) dropSegmentData(share, device, row, touched, released, removedBlobs)
    dao.deleteSegments(share, device)
    if (fresh.isNotEmpty()) dao.saveSegments(fresh.values.mapIndexed { i, row -> row.toEntity(i) })
    for ((segment, rows) in application.records) {
      // Un segmento riapplicato (assente, poi ricomparso con una testa nuova) non raddoppia le righe.
      dao.deleteRecordsOfSegment(share, device, segment)
      if (rows.isNotEmpty()) dao.saveRecords(rows.map { it.toEntity(share) })
      rows.forEach { touched += it.pathKey }
    }
    application.pruneLocalUpToSeq?.let { upTo ->
      val keys = dao.keysUpToSeq(share, device, RecordRow.LOCAL_SEGMENT, upTo)
      if (keys.isNotEmpty()) {
        dao.deleteUpToSeq(share, device, RecordRow.LOCAL_SEGMENT, upTo)
        touched += keys
      }
    }
    dao.saveHead(IndexHeadEntity(share, device, head.version, head.contentHash, head.registryVersion, head.createdAtNasMillis))
    markDirty(share, touched)
    StoreChange(touched, released, removedBlobs)
  }

  override suspend fun setSegmentState(share: String, deviceId: String, hash: String, state: SegmentState) {
    dao.setSegmentState(share, deviceId, hash, state.code)
  }

  override suspend fun applyBlobSegment(pack: PackRow, blobs: List<BlobRow>): Long? = database.transaction {
    val share = requireNotNull(pack.share)
    val device = requireNotNull(pack.deviceId)
    val hash = requireNotNull(pack.segment)
    dao.segment(share, device, hash) ?: return@transaction null
    val id = dao.packByName(pack.name)?.packId ?: dao.insertPack(pack.toEntity(id = 0))
    dao.deleteBlobsOfPack(id)
    if (blobs.isNotEmpty()) dao.saveBlobs(blobs.map { it.copy(packId = id).toEntity() })
    dao.setSegmentPack(share, device, hash, SegmentState.APPLIED.code, id)
    id
  }

  override suspend fun forgetDevice(share: String, deviceId: String): StoreChange = database.transaction {
    val touched = HashSet<String>()
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    dao.segments(share, deviceId).mapNotNull { it.toRow() }.forEach { dropSegmentData(share, deviceId, it, touched, released, removedBlobs) }
    dao.deleteSegments(share, deviceId)
    // Il resto dei record del dispositivo: quelli propri non ancora riletti dalla testa ("local").
    val remaining = dao.keysOfDevice(share, deviceId)
    if (remaining.isNotEmpty()) {
      dao.deleteRecordsOfDevice(share, deviceId)
      touched += remaining
    }
    dao.deleteHead(share, deviceId)
    markDirty(share, touched)
    StoreChange(touched, released, removedBlobs)
  }

  override suspend fun forgetShare(share: String): StoreChange = database.transaction {
    val touched = dao.pathKeys(share).toHashSet()
    dao.deleteRecordsOfShare(share)
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    for (pack in dao.packsOfShare(share)) {
      released += pack.toRow()
      removedBlobs += dao.blobsOfPack(pack.packId).mapNotNull { it.toRow() }
      dao.deleteBlobsOfPack(pack.packId)
      dao.deletePack(pack.packId)
    }
    dao.deleteSegmentsOfShare(share)
    dao.deleteHeads(share)
    dao.clearDirtyOfShare(share)
    StoreChange(touched, released, removedBlobs)
  }

  // --- Record -------------------------------------------------------------------------------------------------------

  override suspend fun records(share: String, pathKeys: Collection<String>): List<RecordRow> {
    if (pathKeys.isEmpty()) return emptyList()
    val keys = pathKeys.distinct()
    return database.transaction { keys.chunked(ReplicaDao.MAX_KEYS).flatMap { dao.records(share, it) } }.mapNotNull { it.toRow() }
  }

  override suspend fun preparedContentCount(classMask: Int): Int = dao.preparedContentCount(classMask)

  override suspend fun pathKeys(share: String): Set<String> = dao.pathKeys(share).toSet()

  override suspend fun presentContentKeys(share: String, deviceId: String): Set<ContentKey> =
    dao.presentContentKeys(share, deviceId).mapNotNullTo(HashSet()) { hex -> runCatching { ContentKey(hex) }.getOrNull() }

  override suspend fun putLocalRecords(share: String, rows: List<RecordRow>): Set<String> {
    rows.forEach { require(it.segment == RecordRow.LOCAL_SEGMENT) { "Solo record locali." } }
    if (rows.isEmpty()) return emptySet()
    return database.transaction {
      val touched = HashSet<String>()
      for ((device, deviceRows) in rows.groupBy { it.deviceId }) {
        val existing = HashMap<String, Long>()
        deviceRows.map { it.pathKey }.distinct().chunked(ReplicaDao.MAX_KEYS).forEach { chunk ->
          dao.recordsOf(share, device, RecordRow.LOCAL_SEGMENT, chunk).forEach { existing[it.recordKey] = it.seq }
        }
        val winners = LinkedHashMap<String, RecordRow>()
        for (row in deviceRows) {
          val previous = winners[row.pathKey]?.record?.seq ?: existing[row.pathKey]
          if (previous != null && previous >= row.record.seq) continue
          winners[row.pathKey] = row
        }
        if (winners.isNotEmpty()) dao.saveRecords(winners.values.map { it.toEntity(share) })
        touched += winners.keys
      }
      markDirty(share, touched)
      touched
    }
  }

  // --- Percorsi da rifondere ----------------------------------------------------------------------------------------

  override suspend fun dirty(share: String, limit: Int): List<String> = if (limit <= 0) emptyList() else dao.dirty(share, limit)

  override suspend fun dirtyShares(): Set<String> = dao.dirtyShares().toSet()

  override suspend fun clearDirty(share: String, pathKeys: Collection<String>) {
    if (pathKeys.isEmpty()) return
    database.transaction { pathKeys.distinct().chunked(ReplicaDao.MAX_KEYS).forEach { dao.clearDirty(share, it) } }
  }

  private suspend fun markDirty(share: String, keys: Collection<String>) {
    if (keys.isNotEmpty()) dao.markDirty(keys.map { ReplicaDirtyEntity(share, it) })
  }

  // --- Pacchi e blob ------------------------------------------------------------------------------------------------

  override suspend fun packs(): List<PackRow> = dao.packs().map { it.toRow() }

  override suspend fun blobs(): List<BlobRow> = dao.blobs().mapNotNull { it.toRow() }

  override suspend fun openOwnPack(pack: PackRow): Long = dao.insertPack(pack.copy(kind = PackKind.OWN).toEntity(id = 0))

  override suspend fun appendOwnBlobs(packId: Long, newLength: Long, blobs: List<BlobRow>) {
    database.transaction {
      val pack = requireNotNull(dao.pack(packId)) { "Pacco sconosciuto." }
      require(pack.kind == PackKind.OWN.code && newLength >= pack.bytes) { "Solo aggiunte a un pacco proprio." }
      dao.setPackBytes(packId, newLength)
      if (blobs.isNotEmpty()) dao.saveBlobs(blobs.map { it.copy(packId = packId).toEntity() })
    }
  }

  override suspend fun releasePacksOutside(keep: Set<SegmentClass>, maxBytes: Long): StoreChange = database.transaction {
    val candidates = if (keep.isEmpty()) {
      dao.packs().sortedWith(compareByDescending<ReplicaPackEntity> { it.bytes }.thenBy { it.packId })
    } else {
      dao.packsOutside(keep.map { it.code })
    }
    if (candidates.isEmpty()) return@transaction StoreChange.NONE
    val released = ArrayList<PackRow>()
    val removedBlobs = ArrayList<BlobRow>()
    var total = 0L
    for (pack in candidates) {
      if (released.isNotEmpty() && total + pack.bytes > maxBytes) break
      total += pack.bytes
      released += pack.toRow()
      removedBlobs += dao.blobsOfPack(pack.packId).mapNotNull { it.toRow() }
      dao.deleteBlobsOfPack(pack.packId)
      dao.deletePack(pack.packId)
      if (pack.kind == PackKind.SEGMENT.code) {
        val share = pack.shareKey
        val device = pack.deviceId
        val segment = pack.segmentHash
        if (share != null && device != null && segment != null && dao.segment(share, device, segment) != null) {
          dao.setSegmentPack(share, device, segment, SegmentState.SKIPPED.code, null)
        }
      }
    }
    StoreChange(emptySet(), released, removedBlobs)
  }

  // --- Accesso alle share e impostazioni ----------------------------------------------------------------------------

  override suspend fun shareAccess(share: String): ShareAccessRow? = dao.shareAccess(share)?.let { row ->
    ShareAccessRow(row.shareKey, failureOf(row.failure), row.firstFailureDay, row.dropped)
  }

  override suspend fun setShareAccess(share: String, row: ShareAccessRow?) {
    if (row == null) {
      dao.deleteShareAccess(share)
    } else {
      dao.saveShareAccess(ReplicaShareAccessEntity(share, row.failure.name, row.firstFailureDay, row.dropped))
    }
  }

  override suspend fun quality(): QualityLevel? = dao.setting(QUALITY_KEY)?.let { code -> QualityLevel.entries.firstOrNull { it.code == code } }

  override suspend fun setQuality(level: QualityLevel) {
    dao.saveSetting(ReplicaSettingEntity(QUALITY_KEY, level.code))
  }

  // --- Interni ------------------------------------------------------------------------------------------------------

  private suspend fun dropSegmentData(
    share: String,
    deviceId: String,
    row: SegmentRow,
    touched: MutableSet<String>,
    released: MutableList<PackRow>,
    removedBlobs: MutableList<BlobRow>,
  ) {
    val keys = dao.keysOfSegment(share, deviceId, row.hash)
    if (keys.isNotEmpty()) {
      dao.deleteRecordsOfSegment(share, deviceId, row.hash)
      touched += keys
    }
    row.packId?.let { id ->
      dao.pack(id)?.let { pack ->
        released += pack.toRow()
        removedBlobs += dao.blobsOfPack(id).mapNotNull { it.toRow() }
        dao.deleteBlobsOfPack(id)
        dao.deletePack(id)
      }
    }
  }

  private fun failureOf(name: String): RemoteFailure = RemoteFailure.entries.firstOrNull { it.name == name } ?: RemoteFailure.OTHER

  private fun IndexHeadEntity.toRow() = HeadRow(shareKey, deviceId, version, contentHash, registryVersion, createdAt)

  private fun IndexSegmentEntity.toRow(): SegmentRow? {
    val cls = SegmentClass.fromCode(cls) ?: return null
    val state = SegmentState.fromCode(state) ?: return null
    return SegmentRow(shareKey, deviceId, segmentHash, cls, size, state, listed, packId)
  }

  private fun SegmentRow.toEntity(position: Int) = IndexSegmentEntity(share, deviceId, hash, cls.code, size, state.code, listed, packId, position)

  private fun IndexRecordEntity.toRow(): RecordRow? =
    (MetaRecords.verify(body) as? Verdict.Accepted)?.value?.let { RecordRow(deviceId, segmentHash, recordKey, it) }

  private fun RecordRow.toEntity(share: String): IndexRecordEntity {
    val present = record as? MetaRecord.Present
    return IndexRecordEntity(
      shareKey = share,
      deviceId = deviceId,
      segmentHash = segment,
      recordKey = pathKey,
      seq = record.seq,
      gone = present == null,
      contentKey = present?.file?.contentKey?.hex,
      prep = present?.file?.prepared ?: 0,
      body = MetaRecords.encode(record),
    )
  }

  private fun ReplicaPackEntity.toRow(): PackRow = PackRow(
    id = packId,
    name = fileName,
    kind = PackKind.entries.firstOrNull { it.code == kind } ?: PackKind.SEGMENT,
    cls = checkNotNull(SegmentClass.fromCode(cls)) { "Classe sconosciuta." },
    bytes = bytes,
    share = shareKey,
    deviceId = deviceId,
    segment = segmentHash,
  )

  private fun PackRow.toEntity(id: Long) = ReplicaPackEntity(id, name, kind.code, cls.code, bytes, share, deviceId, segment)

  private fun ReplicaBlobEntity.toRow(): BlobRow? {
    val cls = SegmentClass.fromCode(cls) ?: return null
    return BlobRow(ContentKey(contentKey), cls, packId, offset, length, deviceId, segmentHash)
  }

  private fun BlobRow.toEntity() = ReplicaBlobEntity(contentKey.hex, cls.code, packId, contentKey.short, offset, length, deviceId, segment)

  private companion object {
    const val QUALITY_KEY = "quality"
  }
}

/**
 * [ProvisionalStore] sopra `replica_provisional` (D17, P11): solo locale, tolta con la replica. [get] e' bloccante (chiave
 * primaria) e va chiamata dal dispatcher delle miniature, mai dal thread principale.
 */
class RoomProvisionalStore(database: GalleryDatabase) : ProvisionalStore {

  private val dao: ReplicaDao = database.replicaDao()

  override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) {
    dao.saveProvisional(ReplicaProvisionalEntity(contentKey.short, contentKey.hex, jpeg))
  }

  override fun get(contentKeyShort: Long): ByteArray? = dao.provisional(contentKeyShort)

  override suspend fun remove(contentKeyShort: Long) {
    dao.deleteProvisional(contentKeyShort)
  }
}
