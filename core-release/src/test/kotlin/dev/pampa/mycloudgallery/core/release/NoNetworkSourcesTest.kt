package dev.pampa.mycloudgallery.core.release

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D2, D29; Q8 (a)): core-release non usa la rete (il manifest arriva dal NAS da una porta di :app). Nessuna eccezione a `NoNetworkSources`. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
