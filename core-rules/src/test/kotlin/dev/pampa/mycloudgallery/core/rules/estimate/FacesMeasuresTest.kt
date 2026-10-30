package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fase 8 (P2, D10, `docs/regole-attivita.md` "Persone"): `DeviceMeasures.facesPerSecond`, le foto al secondo del rilevatore e dell'embedding dei volti
 * sulla CPU. Il valore di partenza era una stima (25 foto/s sull'S25); dalle correzioni C (CA-2) e' la **misura sul campo** dell'S25 (F104 del 27/10: 8,8 foto
 * al secondo). Qui: il campo c'e', ha la misura del campo, e' controllato, e sostituirlo cambia solo lui.
 */
class FacesMeasuresTest {

  @Test
  fun ilCalcoloDeiVoltiHaLaMisuraDelCampoDellS25() {
    // CA-2: F104 del 27/10 sull'S25, `facesPerSecond_suggerito` 8,80 (non le 25 foto/s ipotizzate da P2: la stima di "Analizza ora" sarebbe stata tre volte troppo corta).
    assertThat(DeviceMeasures.PHASE_0.facesPerSecond).isEqualTo(8.8)
    assertThat(DeviceMeasures().facesPerSecond).isAtLeast(8.0)
    assertThat(DeviceMeasures().facesPerSecond).isAtMost(10.0)
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
    // Il tempo di N foto, come lo dice regole-attivita.md: N / facesPerSecond (qui 12.000 foto a 8,8 foto/s = 22 minuti e 44, a 12 foto/s = 16 minuti e 40).
    assertThat(12_000 / DeviceMeasures.PHASE_0.facesPerSecond).isWithin(1e-9).of(12_000 / 8.8)
    assertThat(12_000 / DeviceMeasures.PHASE_0.copy(facesPerSecond = 12.0).facesPerSecond).isWithin(1e-9).of(1000.0)
  }
}
