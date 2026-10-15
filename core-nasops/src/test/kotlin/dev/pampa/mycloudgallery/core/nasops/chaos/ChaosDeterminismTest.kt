package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Un giro e' ripetibile (docs/prova-500.md): stesso seme, stessi passi, stessi colpi, stesso esito. Senza questo un giro che fallisce non si
 * puo' rifare per capirlo. In piu' il banco deve coprire tutte le primitive e tutti i tipi di colpo.
 */
class ChaosDeterminismTest {

  private fun roundsOf(rounds: Int, seed: Long): List<RoundReport> = runBlocking {
    val out = ArrayList<RoundReport>()
    for (i in 0 until rounds) out += ChaosRound(i, ChaosBench.seedOf(seed, i), InMemoryTarget(), FakeChaosStackFactory, ChaosConfig(), ChaosStats()).run()
    out
  }

  @Test
  fun stessoSemeStessoGiro() {
    val first = roundsOf(60, 99L)
    val second = roundsOf(60, 99L)
    assertThat(second.map { it.steps }).isEqualTo(first.map { it.steps })
    assertThat(second.map { it.faults.map(FiredFault::toString) }).isEqualTo(first.map { it.faults.map(FiredFault::toString) })
    assertThat(second.map { it.violations.map(Violation::toString) }).isEqualTo(first.map { it.violations.map(Violation::toString) })
    assertThat(second.map { it.deaths }).isEqualTo(first.map { it.deaths })
    // E semi diversi danno giri diversi.
    val other = roundsOf(60, 100L)
    assertThat(other.map { it.steps }).isNotEqualTo(first.map { it.steps })
  }

  @Test
  fun unGiroSiRifaDaSoloConIlSuoNumero(): Unit = runBlocking {
    val all = roundsOf(60, 99L)
    val again = ChaosRound(37, ChaosBench.seedOf(99L, 37), InMemoryTarget(), FakeChaosStackFactory, ChaosConfig(), ChaosStats()).run()
    assertThat(again.steps).isEqualTo(all[37].steps)
    assertThat(again.faults.map(FiredFault::toString)).isEqualTo(all[37].faults.map(FiredFault::toString))
    assertThat(again.violations.map(Violation::toString)).isEqualTo(all[37].violations.map(Violation::toString))
  }

  @Test
  fun seiCentoGiriColpisconoOgniPrimitivaEOgniTipoDiColpo(): Unit = runBlocking {
    val result = ChaosBench.run(600, 20261015L, InMemoryTarget(), FakeChaosStackFactory)
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    val stats = result.stats
    // Tutti i tipi di colpo e tutti e due i momenti.
    for (kind in FaultKind.entries) assertWithMessage("colpi di tipo $kind").that(stats.firedBy(kind)).isGreaterThan(20)
    for (moment in Moment.entries) assertWithMessage("colpi $moment").that(stats.firedBy(moment)).isGreaterThan(100)
    // Ogni primitiva di scrittura e ogni scrittura del diario e' stata colpita sia prima sia dopo, tranne quelle rarissime con lo stack fake.
    val rare = setOf(Primitive.DELETE_OWN_TEMP)
    for (primitive in Primitive.entries) {
      if (primitive in FakeChaosStackFactory.unfaultable || primitive in rare) continue
      if (primitive.side == Side.NAS_READ) {
        assertWithMessage("$primitive chiamata").that(stats.callsOf(primitive)).isGreaterThan(0)
        continue
      }
      assertWithMessage("$primitive colpita prima").that(stats.firedAt(primitive, Moment.BEFORE)).isGreaterThan(0)
      assertWithMessage("$primitive colpita dopo").that(stats.firedAt(primitive, Moment.AFTER)).isGreaterThan(0)
    }
  }
}
