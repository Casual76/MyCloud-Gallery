package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Pacchetto U: le 24 ore fra un controllo e l'altro si misurano con l'ora che da' `:app`; questo modulo non legge l'orologio del telefono. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
