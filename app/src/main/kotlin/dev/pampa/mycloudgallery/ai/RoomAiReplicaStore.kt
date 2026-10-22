package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.AiIndexDao
import dev.pampa.mycloudgallery.core.database.dao.AiKeyRow
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaAppliedEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaSegmentEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.index.ai.AiAppliedMark
import dev.pampa.mycloudgallery.core.index.ai.AiCandidate
import dev.pampa.mycloudgallery.core.index.ai.AiHeadApplication
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiSegmentRow
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.util.Locale

/**
 * [AiReplicaStore] sopra `ai_replica_head`, `ai_replica_segment`, `ai_replica_entry`, `ai_replica_applied` e `ai_replica_dirty` (P1).
 * Una transazione per metodo; le sequenze di [applyHead] e [forgetDevice] sono quelle di `AiShareTablesTest` (le chiavi toccate si
 * leggono prima di togliere le voci e si segnano da rifondere nella stessa transazione).
 */
class RoomAiReplicaStore(private val database: GalleryDatabase) : AiReplicaStore {
  private val dao get() = database.aiReplicaDao()

  private fun key(share: String): String = share.lowercase(Locale.ROOT)

  private fun AiKeyRow.toKey(): AiKey? = AiClass.fromCode(cls)?.let { c -> runCatching { AiKey(ContentKey(contentKey), c) }.getOrNull() }

  override suspend fun heads(share: String): List<HeadRow> = dao.heads(key(share)).map {
    HeadRow(it.share, it.device, it.version, it.contentHash, it.registryVersion, it.createdAt)
  }

  override suspend fun segments(share: String, deviceId: String): List<AiSegmentRow> = dao.segments(key(share), deviceId).mapNotNull {
    val cls = AiClass.fromCode(it.cls) ?: return@mapNotNull null
    val state = SegmentState.fromCode(it.state) ?: return@mapNotNull null
    AiSegmentRow(it.device, it.hash, cls, it.size, state, it.entries)
  }

  override suspend fun applyHead(application: AiHeadApplication): Set<AiKey> = database.transaction {
    val head = application.head
    val s = key(head.share)
    val device = head.deviceId
    val touched = HashSet<AiKey>()
    val listed = application.segments.map { it.hash }.toHashSet()
    // Segmenti non piu' elencati e segmenti riscaricati: le loro voci escono (le chiavi toccate si rifondono).
    val dropped = dao.segments(s, device).map { it.hash }.filter { it !in listed } + application.entries.keys
    for (hash in dropped.distinct()) {
      dao.keysOfSegment(s, device, hash).mapNotNullTo(touched) { it.toKey() }
      dao.deleteEntriesOfSegment(s, device, hash)
      if (hash !in listed) dao.deleteSegment(s, device, hash)
    }
    for ((hash, rows) in application.entries) {
      if (rows.isEmpty()) continue
      dao.putEntries(rows.map { AiReplicaEntryEntity(s, device, hash, it.key.cls.code, it.key.contentKey.hex, it.recipe, it.digest, it.body) })
      rows.mapTo(touched) { it.key }
    }
    if (application.segments.isNotEmpty()) {
      dao.saveSegments(application.segments.map { AiReplicaSegmentEntity(s, device, it.hash, it.cls.code, it.size, it.state.code, it.entries) })
    }
    dao.saveHead(AiReplicaHeadEntity(s, device, head.version, head.contentHash, head.registryVersion, head.createdAtNasMillis))
    markDirtyLocked(s, touched)
    touched
  }

  override suspend fun forgetDevice(share: String, deviceId: String): Set<AiKey> = database.transaction {
    val s = key(share)
    val touched = dao.keysOfDevice(s, deviceId).mapNotNullTo(HashSet()) { it.toKey() }
    dao.deleteEntriesOfDevice(s, deviceId)
    dao.deleteSegments(s, deviceId)
    dao.deleteHead(s, deviceId)
    markDirtyLocked(s, touched)
    touched
  }

  override suspend fun forgetShare(share: String) = database.transaction {
    val s = key(share)
    dao.deleteEntriesOfShare(s)
    dao.deleteSegmentsOfShare(s)
    dao.deleteHeads(s)
    dao.deleteAppliedOfShare(s)
    dao.deleteDirtyOfShare(s)
  }

  override suspend fun candidates(share: String, keys: Collection<AiKey>): List<AiCandidate> = database.transaction {
    val out = ArrayList<AiCandidate>()
    for ((cls, ofClass) in keys.groupBy { it.cls }) {
      ofClass.map { it.contentKey.hex }.distinct().chunked(AiIndexDao.MAX_KEYS).forEach { hexes ->
        for (row in dao.candidates(key(share), cls.code, hexes)) {
          val k = AiKeyRow(row.cls, row.contentKey).toKey() ?: continue
          out += AiCandidate(row.device, row.segment, k, row.recipe, row.digest)
        }
      }
    }
    out
  }

  override suspend fun body(share: String, candidate: AiCandidate): ByteArray? =
    dao.body(key(share), candidate.deviceId, candidate.segment, candidate.key.cls.code, candidate.key.contentKey.hex)

  override suspend fun keys(share: String): Set<AiKey> = dao.keys(key(share)).mapNotNullTo(HashSet()) { it.toKey() }

  override suspend fun dirty(share: String): Set<AiKey> = dao.dirty(key(share)).mapNotNullTo(LinkedHashSet()) { it.toKey() }

  override suspend fun markDirty(share: String, keys: Collection<AiKey>) = database.transaction { markDirtyLocked(key(share), keys) }

  private suspend fun markDirtyLocked(s: String, keys: Collection<AiKey>) {
    keys.chunked(AiIndexDao.MAX_KEYS).forEach { part -> dao.markDirty(part.map { AiReplicaDirtyEntity(s, it.cls.code, it.contentKey.hex) }) }
  }

  override suspend fun applied(share: String, keys: Collection<AiKey>): Map<AiKey, AiAppliedMark> = database.transaction {
    val out = HashMap<AiKey, AiAppliedMark>()
    for ((cls, ofClass) in keys.groupBy { it.cls }) {
      ofClass.map { it.contentKey.hex }.distinct().chunked(AiIndexDao.MAX_KEYS).forEach { hexes ->
        dao.applied(key(share), cls.code, hexes).forEach { row -> row.toMark()?.let { out[it.key] = it } }
      }
    }
    out
  }

  override suspend fun allApplied(share: String): List<AiAppliedMark> = dao.allApplied(key(share)).mapNotNull { it.toMark() }

  override suspend fun commitApplied(share: String, keys: Collection<AiKey>, marks: List<AiAppliedMark>) = database.transaction {
    val s = key(share)
    for ((cls, ofClass) in keys.groupBy { it.cls }) {
      ofClass.map { it.contentKey.hex }.distinct().chunked(AiIndexDao.MAX_KEYS).forEach { hexes ->
        dao.deleteApplied(s, cls.code, hexes)
        dao.clearDirty(s, cls.code, hexes)
      }
    }
    if (marks.isNotEmpty()) {
      dao.saveApplied(marks.map { AiReplicaAppliedEntity(s, it.key.cls.code, it.key.contentKey.hex, it.deviceId, it.recipe, it.digest) })
    }
  }

  override suspend fun shares(): Set<String> = dao.shares().toSet()

  private fun AiReplicaAppliedEntity.toMark(): AiAppliedMark? =
    AiKeyRow(cls, contentKey).toKey()?.let { AiAppliedMark(it, device, recipe, digest) }
}
