package dev.pampa.mycloudgallery.core.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 8 (P0, D2, D39): core-faces non usa la rete (ne' il NAS fuori da sync/, ne' core-nasops: Phase8ArchitectureTest). */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
