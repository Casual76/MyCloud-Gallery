package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.store.FileOutboxBlobStore
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Il pacco dei blob in attesa su disco: scrittura atomica, nomi da hash, pulizia degli orfani. */
class FileOutboxBlobStoreTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun scritturaLetturaPuliziaEOrfani() = runTest {
    val dir = File(temp.root, "index-outbox")
    val store = FileOutboxBlobStore(dir, Dispatchers.Unconfined)
    val a = store.write("test-comune", SegmentClass.THUMB_S, byteArrayOf(1, 2, 3))
    val b = store.write("test-comune", SegmentClass.THUMB_S, byteArrayOf(1, 2, 3))

    assertThat(a).isNotEqualTo(b)
    assertThat(a).matches("m[0-9a-z]{52}")
    assertThat(store.read(a)).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(store.handles()).containsExactly(a, b)

    // Un temporaneo rimasto da una morte a meta' scrittura e un file estraneo: la ripresa li toglie.
    File(dir, "${a}-tmp").writeBytes(byteArrayOf(9))
    File(dir, "estraneo").writeBytes(byteArrayOf(9))
    store.retainOnly(setOf(a))
    assertThat(dir.list()!!.toSet()).containsExactly(a)

    store.delete(listOf(a))
    assertThat(store.read(a)).isNull()
    // Un handle che non e' un nome nostro non diventa mai un percorso.
    assertThat(store.read("../../segreto")).isNull()
  }
}
