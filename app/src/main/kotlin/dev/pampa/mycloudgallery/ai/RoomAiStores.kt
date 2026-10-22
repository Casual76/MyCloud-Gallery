package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.AiIndexDao
import dev.pampa.mycloudgallery.core.database.dao.AiKeyRow
import dev.pampa.mycloudgallery.core.database.entity.AiOwnHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnLiveEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.AiPendingPublishEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiLiveEntry
import dev.pampa.mycloudgallery.core.index.ai.AiOutboxRow
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AiPublishCommit
import dev.pampa.mycloudgallery.core.index.ai.AiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.AiRowRef
import dev.pampa.mycloudgallery.core.index.ai.AiRowState
import dev.pampa.mycloudgallery.core.index.ai.AiStaged
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import java.util.Locale

// Gli ADATTATORI ROOM delle porte di persistenza dei dati AI dell'indice condiviso (Fase 6, P11; P1 le tabelle e i DAO, P2 le porte in
// `core-index/.../ai/AiStores.kt`). **Una transazione per metodo della porta**, come chiede il contratto; `share` gia' in minuscolo, `cls`
// = `AiClass.code`, `content_key` = `ContentKey.hex` intera, liste di chiavi a pezzi di `AiIndexDao.MAX_KEYS`. Le sequenze sono quelle di
// `AiShareTablesTest` (core-database) e i test di :app (`RoomAiStoresTest`) le confrontano con le implementazioni in memoria di P2.

private fun key(share: String): String = share.lowercase(Locale.ROOT)

private fun AiKeyRow.toKey(): AiKey? = AiClass.fromCode(cls)?.let { c -> runCatching { AiKey(ContentKey(contentKey), c) }.getOrNull() }

private inline fun <T> chunks(keys: Collection<T>, block: (List<T>) -> Unit) = keys.chunked(AiIndexDao.MAX_KEYS).forEach(block)

/** [AiPublishStore] sopra `ai_pending_publish`, `ai_own_head`, `ai_own_segment` e `ai_own_live` (P1). */
class RoomAiPublishStore(private val database: GalleryDatabase) : AiPublishStore {
  private val dao get() = database.aiPublishDao()

  override suspend fun stage(share: String, state: AiRowState, entries: List<AiStaged>): List<AiOutboxRow> = database.transaction {
    if (entries.isEmpty()) return@transaction emptyList()
    val rows = entries.map { AiPendingPublishEntity(0, key(share), state.code, it.cls.code, it.contentKey.hex, it.recipe, it.body, it.digest) }
    val ids = dao.stage(rows)
    entries.mapIndexed { i, staged -> AiOutboxRow(ids[i], state, staged) }
  }

  override suspend fun rows(share: String, state: AiRowState, limit: Int): List<AiOutboxRow> =
    dao.rows(key(share), state.code, limit).mapNotNull { e ->
      val cls = AiClass.fromCode(e.cls) ?: return@mapNotNull null
      val content = runCatching { ContentKey(e.contentKey) }.getOrNull() ?: return@mapNotNull null
      runCatching { AiOutboxRow(e.id, state, AiStaged(cls, content, e.recipe, e.body)) }.getOrNull()
    }

  override suspend fun refs(share: String, state: AiRowState): List<AiRowRef> =
    dao.refs(key(share), state.code).mapNotNull { r -> AiKeyRow(r.cls, r.contentKey).toKey()?.let { AiRowRef(r.id, it, r.digest) } }

  override suspend fun move(share: String, ids: Set<Long>, to: AiRowState) = database.transaction {
    chunks(ids) { dao.move(key(share), it, to.code) }
  }

  override suspend fun discard(share: String, ids: Set<Long>) = database.transaction {
    chunks(ids) { dao.discard(key(share), it) }
  }

  override suspend fun pending(): AiPendingCounts = database.transaction {
    AiPendingCounts(staged = dao.count(AiRowState.OUTBOX.code), waitingForShare = dao.count(AiRowState.HELD.code))
  }

  override suspend fun ownHead(share: String): OwnHead? = database.transaction {
    val head = dao.ownHead(key(share)) ?: return@transaction null
    OwnHead(
      version = head.version,
      mark = HeadMark(head.version, head.headHash),
      publishedAtNas = head.publishedAt,
      segments = dao.ownSegments(key(share)).mapNotNull { s ->
        val cls = SegmentClass.fromCode(s.cls) ?: return@mapNotNull null
        OwnSegment(ClassedSegment(cls, SegmentRef(s.hash, s.size)), s.entries)
      },
    )
  }

  override suspend fun live(share: String, keys: Collection<AiKey>): Map<AiKey, AiLiveEntry> = database.transaction {
    val out = HashMap<AiKey, AiLiveEntry>()
    for ((cls, ofClass) in keys.groupBy { it.cls }) {
      chunks(ofClass.map { it.contentKey.hex }) { hexes ->
        for (row in dao.ownLive(key(share), cls.code, hexes)) {
          val k = AiKeyRow(row.cls, row.contentKey).toKey() ?: continue
          out[k] = AiLiveEntry(k, row.recipe, row.digest, row.segment)
        }
      }
    }
    out
  }

  override suspend fun commit(share: String, commit: AiPublishCommit) = database.transaction {
    val s = key(share)
    val head = commit.head
    dao.saveOwnHead(AiOwnHeadEntity(s, head.version, head.mark.contentHash, head.publishedAtNas))
    if (commit.newSegments.isNotEmpty()) {
      dao.saveOwnSegments(commit.newSegments.map { AiOwnSegmentEntity(s, it.hash, it.segment.cls.code, it.segment.ref.size, it.entries) })
    }
    val keep = head.segments.map { it.hash }
    if (keep.size <= AiIndexDao.MAX_KEYS) {
      dao.deleteOwnSegmentsNotIn(s, keep.ifEmpty { listOf("") })
    } else {
      // Oltre il limite delle variabili di SQLite (API 29) il `NOT IN` non si puo' spezzare: si tolgono uno per uno quelli non elencati.
      val listed = keep.toHashSet()
      val sql = database.openHelper.writableDatabase
      for (gone in dao.ownSegments(s).filter { it.hash !in listed }) {
        sql.execSQL("DELETE FROM ai_own_segment WHERE share = ? AND hash = ?", arrayOf<Any>(s, gone.hash))
      }
    }
    chunks(commit.consumed) { dao.discard(s, it) }
    if (commit.live.isNotEmpty()) {
      dao.saveOwnLive(commit.live.map { AiOwnLiveEntity(s, it.key.cls.code, it.key.contentKey.hex, it.recipe, it.digest, it.segment) })
    }
  }

  override suspend fun shares(): Set<String> = dao.shares().toSet()
}
