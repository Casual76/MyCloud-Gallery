package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/**
 * Fase 4, §4.3 (D32): niente orologio del telefono nelle decisioni. La conferma si data con l'orologio monotono
 * (`SystemClock.elapsedRealtime`), cio' che scade si misura in ora del NAS.
 */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
