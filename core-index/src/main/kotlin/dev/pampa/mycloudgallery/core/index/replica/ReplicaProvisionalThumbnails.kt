package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.replica.store.ProvisionalStore
import dev.pampa.mycloudgallery.core.model.ContentKey

/**
 * Le miniature provvisorie (D17): la miniatura EXIF del rilevamento (P5) e la decodifica dell'originale visibile (P7).
 * Solo JPEG (`FF D8 FF`) e al massimo [MAX_BYTES]: e' comunque un dato letto da un file del NAS, e chi lo decodifica lo
 * controlla ancora con `ImageBounds`. Restano locali, non si pubblicano.
 */
class ReplicaProvisionalThumbnails(private val store: ProvisionalStore) : ProvisionalThumbnails {

  override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) {
    if (!isJpeg(jpeg) || jpeg.size > MAX_BYTES) return
    store.put(contentKey, jpeg)
  }

  override fun get(contentKey: Long): ByteArray? = store.get(contentKey)

  override suspend fun forget(contentKey: Long) = store.remove(contentKey)

  companion object {
    const val MAX_BYTES: Int = 256 * 1024

    fun isJpeg(bytes: ByteArray): Boolean =
      bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
  }
}
