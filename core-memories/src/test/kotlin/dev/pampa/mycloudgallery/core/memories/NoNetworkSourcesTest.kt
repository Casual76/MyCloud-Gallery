package dev.pampa.mycloudgallery.core.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 9 (P0, D2, D4): core-memories non usa la rete (ne' il NAS: Phase9ArchitectureTest). I ricordi si generano sul dispositivo. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
