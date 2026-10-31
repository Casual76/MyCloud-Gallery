package dev.pampa.mycloudgallery.core.release

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 10 (P0, D29, E-REL9): la scadenza del manifest si giudica con `ReleaseClock.bestNow` (l'ora del NAS quando c'e'), mai con l'ora del telefono letta qui. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
