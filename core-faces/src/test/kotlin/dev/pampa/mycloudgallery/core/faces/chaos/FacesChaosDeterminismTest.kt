package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Un giro si rifa' identico dal suo seme: stessi passi, stessi colpi, stesse interruzioni cadute (il rapporto di due serie con lo stesso seme e' lo stesso). */
class FacesChaosDeterminismTest {

  private fun report(factory: ChaosTargetFactory, first: Int): String = runBlocking {
    val result = ChaosBench.run(6, 777L, factory, firstRound = first)
    result.failureText(100) + result.stats.totalFired + "|" + result.stats.totalDeaths + "|" + result.stats.coverageTable()
  }

  @Test
  fun dueSerieConLoStessoSemeHannoLoStessoRapporto() {
    assertThat(report(MemoryTarget, 10)).isEqualTo(report(MemoryTarget, 10))
  }

  @Test
  fun unGiroSiRifaDalSuoSeme() {
    fun single(index: Int): String = runBlocking {
      val result = ChaosBench.run(1, 777L, MemoryTarget, firstRound = index)
      result.stats.totalFired.toString() + result.stats.coverageTable()
    }
    assertThat(single(13)).isEqualTo(single(13))
  }
}
