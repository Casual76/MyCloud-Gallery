package dev.pampa.mycloudgallery.core.describe

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D2, D22): core-describe non usa la rete (luoghi offline, persone e testo letto da porte). Nessuna eccezione a `NoNetworkSources`. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
