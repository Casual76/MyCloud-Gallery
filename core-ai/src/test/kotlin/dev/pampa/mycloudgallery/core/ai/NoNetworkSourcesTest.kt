package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 6, D33: nessuna rete nei motori e nel calcolo: i byte del NAS arrivano dalle porte di core-index, i modelli solo da ModelFiles (file verificati). ML Kit manda le sue statistiche solo con la lettura del testo attiva (D41): non e' codice nostro. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}