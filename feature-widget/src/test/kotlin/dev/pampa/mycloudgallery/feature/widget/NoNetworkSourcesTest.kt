package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D7, C431): il widget non usa la rete ne' il NAS, solo miniature gia' sul telefono. Nessuna eccezione a `NoNetworkSources`. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
