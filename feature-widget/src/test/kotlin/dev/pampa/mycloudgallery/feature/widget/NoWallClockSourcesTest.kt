package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D6): le ore di quiete e i budget del widget passano da `WidgetClock` (ora locale e monotono), mai dall'ora del telefono letta qui. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
