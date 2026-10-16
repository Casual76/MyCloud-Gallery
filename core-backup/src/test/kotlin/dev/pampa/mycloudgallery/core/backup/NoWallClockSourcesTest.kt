package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/**
 * Fase 5, §4.3 e D31: il backup non ha scadenze e non dipende dall'orologio del telefono. Durate e tappe con l'orologio monotono, cio'
 * che si scrive (l'istante di una verifica) con l'ora del NAS; un salto dell'ora non ferma il backup.
 */
class NoWallClockSourcesTest {
  @Test
  fun nessunSorgenteDiProduzioneLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main"))).isEmpty()
  }
}
