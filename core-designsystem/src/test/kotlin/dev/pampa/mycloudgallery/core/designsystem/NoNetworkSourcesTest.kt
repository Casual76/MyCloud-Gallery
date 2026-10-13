package dev.pampa.mycloudgallery.core.designsystem

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * D24: nessuna rete nei sorgenti di produzione del design system. Vale per tutto il modulo e quindi anche per i
 * sotto-package che la Fase 3 aggiunge (`grid/` di P7, `viewer/` di P8): le celle, la barra del video e le sezioni
 * informazioni non caricano niente da Internet.
 */
class NoNetworkSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneUsaLaRete() {
    assertThat(NoNetworkSources.violations(File("src/main"))).isEmpty()
  }
}
