package dev.pampa.mycloudgallery.core.common.coroutines

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class DispatcherProviderTest {

  @Test
  fun iDispatcherPredefinitiSonoQuelliDiKotlinx() {
    assertThat(DefaultDispatcherProvider.io).isEqualTo(Dispatchers.IO)
    assertThat(DefaultDispatcherProvider.default).isEqualTo(Dispatchers.Default)
  }
}
