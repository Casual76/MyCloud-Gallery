package dev.pampa.mycloudgallery.core.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D2, D7, C431): core-widget non usa la rete (miniature solo locali, nessun NAS). Nessuna eccezione a `NoNetworkSources`. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
