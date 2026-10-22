package dev.pampa.mycloudgallery.core.ai.chaos

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * I giri del banco del calcolo (§5.5): 2.000 giri sulla JVM con il NAS finto di P5, i motori finti, il `FaultPlan` del giro (rete prima e
 * dopo, sessione scaduta, stallo), la morte del processo a ogni salvataggio (prima del file, nel mezzo del record, prima della riga,
 * dopo la riga), le unita' prese da un altro dispositivo a meta', la pubblicazione chiusa e riaperta, il modello ucciso e manomesso.
 * `MCG_AI_CHAOS_ROUNDS` cambia i giri, `MCG_AI_CHAOS_SEED` il seme di base. Il rapporto va in `build/chaos/`.
 */
class AiChaosBenchTest {
  private fun env(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default

  @Test
  fun duemilaGiriSullaJvm() {
    val result = AiChaosBench.run(env("MCG_AI_CHAOS_ROUNDS", 2_000).toInt(), env("MCG_AI_CHAOS_SEED", 6101) + 2)
    result.writeReport(File("build/chaos/calcolo-jvm.txt"))
    println(result.summary())
    assertWithMessage("giri con violazioni\n${result.summary()}\n${result.failureText()}").that(result.ok).isTrue()
  }

  /** Lo stesso seme, lo stesso piano: un giro che fallisce si rilancia dal suo seme. */
  @Test
  fun ilPianoDelGiroEDeterminatoDalSeme() {
    for (seed in listOf(1L, 77L, 6101L, -5L)) {
      val a = AiRoundPlan.generate(seed)
      val b = AiRoundPlan.generate(seed)
      assertWithMessage("seme $seed").that(a.toString()).isEqualTo(b.toString())
      assertWithMessage("seme $seed").that(a.deaths.mapValues { it.value.name }).isEqualTo(b.deaths.mapValues { it.value.name })
      assertWithMessage("seme $seed").that(a.nasFaults.keys).isEqualTo(b.nasFaults.keys)
    }
  }

  /** Un giro intero e' ripetibile: due volte lo stesso piano, gli stessi numeri (niente orologi, niente thread: tutto dal seme). */
  @Test
  fun unGiroEsattamenteRipetibile() {
    for (seed in listOf(3L, 4_242L, 99_001L)) {
      val first = AiChaosRound.run(AiRoundPlan.generate(seed))
      val second = AiChaosRound.run(AiRoundPlan.generate(seed))
      assertWithMessage("seme $seed: ${first.describe()}").that(first.failures).isEmpty()
      assertWithMessage("seme $seed").that(second.stats.savesDone).isEqualTo(first.stats.savesDone)
      assertWithMessage("seme $seed").that(second.stats.deaths).isEqualTo(first.stats.deaths)
      assertWithMessage("seme $seed").that(second.stats.published).isEqualTo(first.stats.published)
      assertWithMessage("seme $seed").that(second.stats.embedded).isEqualTo(first.stats.embedded)
    }
  }
}
