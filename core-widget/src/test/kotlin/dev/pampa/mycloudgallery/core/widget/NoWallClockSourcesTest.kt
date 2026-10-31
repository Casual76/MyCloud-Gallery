package dev.pampa.mycloudgallery.core.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D6, §4.3 "Ora"): ora locale e monotono dalla porta `WidgetClock`; mai l'ora del telefono letta qui. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
