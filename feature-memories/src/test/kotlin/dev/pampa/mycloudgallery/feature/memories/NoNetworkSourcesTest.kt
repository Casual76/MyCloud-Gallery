package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 9 (P0, D2): le schermate dei ricordi non usano la rete (la musica libera passa dallo scaffale dei modelli, Q3). */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
