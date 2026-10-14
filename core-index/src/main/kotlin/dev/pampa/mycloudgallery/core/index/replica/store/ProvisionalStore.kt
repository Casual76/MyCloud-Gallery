package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.model.ContentKey

/**
 * Le miniature provvisorie (D17) nel database della replica (tabella `replica_provisional`, PK `content_key_short`,
 * colonne `content_key`, `jpeg` BLOB ≤ 256 KiB): solo locali, mai pubblicate, tolte con la replica. Porta di P4; P11 la
 * lega a un DAO (la lettura [get] e' bloccante: query per chiave primaria dal dispatcher delle miniature).
 */
interface ProvisionalStore {
  suspend fun put(contentKey: ContentKey, jpeg: ByteArray)

  fun get(contentKeyShort: Long): ByteArray?

  suspend fun remove(contentKeyShort: Long)
}

/** [ProvisionalStore] in memoria. */
class MemoryProvisionalStore : ProvisionalStore {
  private val map = HashMap<Long, ByteArray>()

  override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) {
    synchronized(map) { map[contentKey.short] = jpeg.copyOf() }
  }

  override fun get(contentKeyShort: Long): ByteArray? = synchronized(map) { map[contentKeyShort]?.copyOf() }

  override suspend fun remove(contentKeyShort: Long) {
    synchronized(map) { map.remove(contentKeyShort) }
  }

  fun size(): Int = synchronized(map) { map.size }
}
