package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fase 8 (P2, D10, `docs/regole-attivita.md` "Persone"): `DeviceMeasures.facesPerSecond`, le foto al secondo del rilevatore e dell'embedding dei volti
 * sulla CPU. Il valore di partenza e' una stima (25 foto/s sull'S25, ~40 ms a foto); P2 ne misura le parti sulla JVM e scrive i test strumentati che la
 * misurano sul telefono (F104), che la sostituisce. Qui: il campo c'e', ha il valore di partenza, e' controllato, e sostituirlo cambia solo lui.
 */
class FacesMeasuresTest {

  @Test
  fun ilCalcoloDeiVoltiHaUnValoreDiPartenzaNellaFasciaDelProgetto() {
    // D10 e regole-attivita: 20-30 foto/s sull'S25 (~30-50 ms a foto), 12-20 sul Tab S9.
    assertThat(DeviceMeasures.PHASE_0.facesPerSecond).isEqualTo(25.0)
    assertThat(DeviceMeasures().facesPerSecond).isAtLeast(20.0)
    assertThat(DeviceMeasures().facesPerSecond).isAtMost(30.0)
  }

  @Test
  fun unaVelocitaNonPositivaNonEAmmessa() {
    assertThat(runCatching { DeviceMeasures(facesPerSecond = 0.0) }.isFailure).isTrue()
    assertThat(runCatching { DeviceMeasures(facesPerSecond = -3.0) }.isFailure).isTrue()
    assertThat(runCatching { DeviceMeasures(facesPerSecond = Double.NaN) }.isFailure).isTrue()
  }

  @Test
  fun sostituireLaMisuraCambiaSoloLei() {
    val measured = DeviceMeasures.PHASE_0.copy(facesPerSecond = 17.5)
    assertThat(measured.facesPerSecond).isEqualTo(17.5)
    assertThat(measured.copy(facesPerSecond = DeviceMeasures.PHASE_0.facesPerSecond)).isEqualTo(DeviceMeasures.PHASE_0)
    // Il tempo di N foto, come lo dice regole-attivita.md: N / facesPerSecond (qui 12.000 foto a 25 foto/s = 8 minuti, a 12 foto/s = 16 minuti e 40).
    assertThat(12_000 / DeviceMeasures.PHASE_0.facesPerSecond).isWithin(1e-9).of(480.0)
    assertThat(12_000 / DeviceMeasures.PHASE_0.copy(facesPerSecond = 12.0).facesPerSecond).isWithin(1e-9).of(1000.0)
  }
}
