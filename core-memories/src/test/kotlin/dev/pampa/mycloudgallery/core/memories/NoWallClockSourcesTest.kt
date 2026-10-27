package dev.pampa.mycloudgallery.core.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 9 (P0, D36): "oggi" e l'ora locale dalla porta `MemoryClock` (legata da :app a `Today`), l'orologio monotono per budget e durate. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
