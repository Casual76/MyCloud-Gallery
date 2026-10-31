package dev.pampa.mycloudgallery.core.describe

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D22): le descrizioni non leggono l'ora: data e ora vengono dai fatti del media (`MediaFacts`). */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
