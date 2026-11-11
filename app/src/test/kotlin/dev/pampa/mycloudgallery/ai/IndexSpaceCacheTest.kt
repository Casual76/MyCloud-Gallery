package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.search.contract.IndexSpace
import kotlinx.coroutines.runBlocking
import org.junit.Test

class IndexSpaceCacheTest {
  @Test fun riaprireLaPaginaNonRileggeIFileELaPrimaLetturaNonESulChiamante() = runBlocking {
    val caller = Thread.currentThread()
    var reads = 0
    var now = 0L
    val cache = IndexSpaceCache(read = {
      assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
      reads++
      IndexSpace(10, 20)
    }, now = { now })
    repeat(20) { cache.current() }
    assertThat(reads).isEqualTo(1)
    now = 60_000
    cache.current()
    assertThat(reads).isEqualTo(2)
  }
}
