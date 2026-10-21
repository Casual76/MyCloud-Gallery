package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Fase 6, D33: core-models e' l'UNICO modulo con codice HTTPS, e solo nel pacchetto `download/https/` (classi `internal`, P3).
 * Tutto il resto del modulo (catalogo, gestore, verifica, sorgente NAS, cartella dal PC, scaffale) non nomina la rete. Che
 * l'eccezione sia solo questa e solo qui lo controlla Phase6ArchitectureTest (core-common).
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteFuoriDalPacchettoHttpsUsaLaRete() {
    assertThat(NoNetworkSources.violationsExcept(File("src/main"), allowedPackages = listOf(HTTPS_PACKAGE))).isEmpty()
  }

  companion object {
    const val HTTPS_PACKAGE: String = "kotlin/dev/pampa/mycloudgallery/core/models/download/https/"
  }
}