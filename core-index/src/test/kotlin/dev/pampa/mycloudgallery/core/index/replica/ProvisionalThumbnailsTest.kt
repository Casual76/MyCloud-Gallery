package dev.pampa.mycloudgallery.core.index.replica

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryProvisionalStore
import dev.pampa.mycloudgallery.core.index.sync.Fx
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** D17: miniature provvisorie solo locali, solo JPEG, con un tetto. */
class ProvisionalThumbnailsTest {

  private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

  @Test
  fun soloJpegEntroIlTettoEDimenticabili() = runTest {
    val store = MemoryProvisionalStore()
    val thumbs = ReplicaProvisionalThumbnails(store)
    thumbs.put(Fx.key(1), jpeg)
    thumbs.put(Fx.key(2), byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0))
    thumbs.put(Fx.key(3), jpeg + ByteArray(ReplicaProvisionalThumbnails.MAX_BYTES))
    assertThat(thumbs.get(Fx.key(1).short)).isEqualTo(jpeg)
    assertThat(thumbs.get(Fx.key(2).short)).isNull()
    assertThat(thumbs.get(Fx.key(3).short)).isNull()
    // Una copia: chi la riceve non puo' cambiare quella tenuta.
    thumbs.get(Fx.key(1).short)!![4] = 99
    assertThat(thumbs.get(Fx.key(1).short)).isEqualTo(jpeg)
    thumbs.forget(Fx.key(1).short)
    assertThat(thumbs.get(Fx.key(1).short)).isNull()
    assertThat(store.size()).isEqualTo(0)
  }
}
