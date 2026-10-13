package dev.pampa.mycloudgallery.feature.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * D24: nessuna rete nei sorgenti di produzione di questo modulo. Qui conta piu' che altrove: Media3 arriva con
 * `DefaultDataSource`, che apre anche `https://`, e il viewer deve leggere solo `ContentDataSource` (D7).
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
