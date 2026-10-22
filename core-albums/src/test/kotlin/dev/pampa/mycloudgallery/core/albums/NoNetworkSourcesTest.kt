package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 7 (P0, D2): core-albums non usa la rete (ne' il NAS, ne' core-nasops ne' core-backup: Phase7ArchitectureTest). */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
