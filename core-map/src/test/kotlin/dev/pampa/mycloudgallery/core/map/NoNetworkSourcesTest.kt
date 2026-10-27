package dev.pampa.mycloudgallery.core.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Fase 9 (P0, D2, D23): core-map non usa la rete. Il cancello di rete e' una funzione **pura** (decide, non apre niente): la rete sta solo in
 * feature-map/.../net/ (Phase9ArchitectureTest). Nessuna eccezione a `NoNetworkSources` qui.
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
