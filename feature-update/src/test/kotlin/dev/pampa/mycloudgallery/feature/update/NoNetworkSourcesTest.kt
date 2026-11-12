package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Pacchetto U (12/11/2026, decisione dell'utente): la terza eccezione di rete dichiarata. Il solo codice di rete del modulo sta in `feature/update/net/`
 * (il client avvolto e il cancello degli host); tutto il resto (regole, installazione, pagina, avviso) non nomina la rete. Phase6ArchitectureTest
 * controlla che le eccezioni siano solo questa, quella di core-models e quella di feature-map, e che questa sia esattamente questo pacchetto.
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneFuoriDaNetUsaLaRete() {
    assertThat(NoNetworkSources.violationsExcept(File("src/main"), listOf("kotlin/dev/pampa/mycloudgallery/feature/update/net/"))).isEmpty()
  }
}
