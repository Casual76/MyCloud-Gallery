package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** D28: nessuna rete nelle integrazioni: il NAS solo dalle porte che `:app` lega, mai un socket o un URL qui. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
