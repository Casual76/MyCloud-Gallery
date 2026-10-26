package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Un giro e' ripetibile (docs/prova-500.md Parte E): stesso seme di base, stessi giri, stessi passi, stessi colpi, stesso rapporto. E' cio' che permette
 * di rifare UN giro rosso dal suo seme (`MCG_SHARE_CHAOS_FIRST_ROUND`, `MCG_SHARE_CHAOS_ROUNDS=1`). Il rapporto si confronta senza la durata.
 */
class ShareChaosDeterminismTest {

  private fun stable(result: ShareChaosResult): String =
    result.summary().lineSequence().filterNot { it.startsWith("durata:") }.joinToString("\n") + "\n" + result.failureText(100)

  private fun twice(factory: ChaosTargetFactory, rounds: Int, seed: Long, first: Int = 0): Pair<ShareChaosResult, ShareChaosResult> = runBlocking {
    val a = ShareChaosBench.run(rounds, seed, factory, firstRound = first)
    val b = ShareChaosBench.run(rounds, seed, factory, firstRound = first)
    a to b
  }

  @Test
  fun dueSerieConLoStessoSemeSulNasFintoHannoLoStessoRapporto() {
    val (a, b) = twice(FakeChaosTargets, 120, 99L)
    assertWithMessage("rapporti diversi").that(stable(a)).isEqualTo(stable(b))
    assertThat(a.stats.totalFired).isAtLeast(100)
  }

  @Test
  fun dueSerieConLoStessoSemeSulSimulatoreSmbHannoLoStessoRapporto() {
    val (a, b) = twice(SimulatedChaosTargets, 80, 99L)
    assertWithMessage("rapporti diversi").that(stable(a)).isEqualTo(stable(b))
    assertThat(a.stats.totalFired).isAtLeast(80)
  }

  @Test
  fun ilGiroRifattoDalSuoSemeHaGliStessiColpiENelloStessoOrdine() {
    // Un giro fatto due volte da solo (come `MCG_SHARE_CHAOS_FIRST_ROUND=17` con `MCG_SHARE_CHAOS_ROUNDS=1`): stessi passi, stessi colpi nello stesso ordine.
    fun once() = runBlocking {
      ShareChaosRound(17, ShareChaosBench.seedOf(7L, 17), FakeChaosTargets, ShareChaosConfig(FakeChaosTargets.capabilities), ShareChaosStats()).run()
    }
    val first = once()
    val again = once()
    assertThat(first.fired).isNotEmpty()
    assertThat(again.fired.map { it.toString() }).isEqualTo(first.fired.map { it.toString() })
    assertThat(again.steps).isEqualTo(first.steps)
    assertThat(again.violations.map { it.toString() }).isEqualTo(first.violations.map { it.toString() })
  }
}
