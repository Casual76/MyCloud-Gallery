package dev.pampa.mycloudgallery.core.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/** Fase 6, D33: nessuna ricerca lascia il dispositivo. core-search non vede ne' rete ne' NAS (Phase6ArchitectureTest), e qui nessun sorgente nomina la rete. */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}