package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** §4.3, D31: nessun orologio del telefono nelle decisioni delle integrazioni (istanti in ora del NAS, durate monotone). */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
