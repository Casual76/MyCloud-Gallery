package dev.pampa.mycloudgallery.core.rules.run

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoWallClockSources
import java.io.File
import org.junit.Test

/**
 * Fase 4, §4.3 (D32): il codice che decide scadenze e durate dei lavori a tappe usa solo un orologio monotono, mai l'ora del
 * telefono (circa 55 s indietro rispetto al NAS, e spostabile). Il resto di `core-rules` legge l'ora locale per la fascia
 * notte e il cambio mese (E-P5): e' cosa diversa e resta com'e'; qui si controlla solo il package `run`.
 */
class RunNoWallClockTest {
  @Test
  fun ilPackageRunNonLeggeLOrologioDelTelefono() {
    assertThat(NoWallClockSources.violations(File("src/main/kotlin/dev/pampa/mycloudgallery/core/rules/run"))).isEmpty()
  }
}
