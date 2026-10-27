package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Fase 9 (P0, D23): la seconda eccezione di rete dichiarata. Il solo codice di rete del modulo (e della fase) sta in `feature/map/net/`
 * (`MapTileHttp`, P1); tutto il resto di feature-map, schermate e MapLibre compresi, non nomina la rete. Phase6ArchitectureTest controlla che
 * questa sia, con quella di core-models, l'UNICA eccezione a `NoNetworkSources`, e che sia esattamente questo pacchetto.
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneFuoriDaNetUsaLaRete() {
    assertThat(NoNetworkSources.violationsExcept(File("src/main"), listOf("kotlin/dev/pampa/mycloudgallery/feature/map/net/"))).isEmpty()
  }
}
