package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/** Fase 7 (P0, D36): ora del NAS per le voci, giorni locali dello scatto per i periodi, orologio monotono per le durate, "oggi" da `Today`. */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
