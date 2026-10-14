package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/**
 * Fase 4, §4.3 (D32): scadenze, prenotazioni, stabilita' dei file e ordinamenti usano solo l'ora del NAS (NasClock) o
 * un orologio monotono, mai l'orologio del telefono.
 */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
