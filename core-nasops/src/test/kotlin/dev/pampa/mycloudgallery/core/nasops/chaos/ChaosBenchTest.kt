package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * La prova delle 500 interruzioni sul NAS finto (docs/fase4-progetto.md §5.5, P15): 2.000 giri casuali, sempre. Ogni giro ha il suo mondo,
 * da 2 a 6 azioni dell'utente con colpi casuali (connessione, sessione, socket, morte del processo) e gli invarianti controllati dopo ogni
 * morte, a fine passo e in una rilettura completa.
 *
 * Due volte, con lo stesso banco: sulle operazioni FAKE di P0-B (sempre) e sulle operazioni VERE di P12 (quando c'e'
 * `ProductionChaosStackFactory`, vedi docs/prova-500.md; altrimenti il test si salta e lo dice).
 *
 * Per cambiare i numeri senza toccare il codice: `MCG_CHAOS_JVM_ROUNDS`, `MCG_CHAOS_SEED` e `MCG_CHAOS_FIRST_ROUND` (poi `--rerun`: Gradle non vede
 * le variabili). Un giro che fallisce si rifa' con lo stesso seme, `MCG_CHAOS_FIRST_ROUND=<numero del giro>` e `MCG_CHAOS_JVM_ROUNDS=1`.
 * I rapporti restano in `build/chaos/jvm-report-fake.txt` e `jvm-report-vere.txt`.
 */
class ChaosBenchTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  private fun bench(factory: ChaosStackFactory, report: String) {
    val rounds = env("MCG_CHAOS_JVM_ROUNDS")?.toInt() ?: DEFAULT_ROUNDS
    val seed = env("MCG_CHAOS_SEED")?.toLong() ?: DEFAULT_SEED
    val first = env("MCG_CHAOS_FIRST_ROUND")?.toInt() ?: 0
    val result = runBlocking { ChaosBench.run(rounds, seed, InMemoryTarget(), factory, firstRound = first) }
    println(result.summary())
    result.writeReport(File("build/chaos/$report"))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    // Il banco deve davvero aver colpito: senza colpi un banco verde non prova niente.
    assertThat(result.stats.totalFired).isAtLeast(rounds)
  }

  @Test
  fun duemilaGiriCasualiSulleOperazioniFakeNonLasciano_nulla_aMeta_nePerdite_neDoppioni() {
    bench(FakeChaosStackFactory, "jvm-report-fake.txt")
  }

  @Test
  fun duemilaGiriCasualiSulleOperazioniVereDiP12() {
    val factory = ChaosStacks.production()
    assumeTrue(
      "le operazioni vere di P12 non sono ancora agganciate al banco: manca ${ChaosStacks.PRODUCTION_CLASS} (docs/prova-500.md)",
      factory != null,
    )
    bench(factory!!, "jvm-report-vere.txt")
  }

  private companion object {
    const val DEFAULT_ROUNDS = 2_000
    const val DEFAULT_SEED = 20261015L
  }
}
