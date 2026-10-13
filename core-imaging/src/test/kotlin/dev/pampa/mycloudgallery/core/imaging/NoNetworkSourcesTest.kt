package dev.pampa.mycloudgallery.core.imaging

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** D24: nessuna rete nei sorgenti di produzione di questo modulo (java.net, HTTP, DefaultDataSource, Coil, Glide...). */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
