package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IL BANCO DELLE 500 INTERRUZIONI DELLA CONDIVISIONE sul NAS finto (docs/fase7-progetto.md §5.5, riga P5 del §4.4): 2.000 giri casuali, a ogni
 * `check.ps1`. Ogni giro ha il suo mondo (media nell'area privata, solo sul telefono, gia' nell'area comune, nascosti), da 1 a 3 passi dell'utente
 * ("condividi", "aggiungi", "riporta", "adotta") e i colpi di `FaultPlan` alle porte dell'orchestratore, alla porta di completamento e a ogni
 * salvataggio del diario della condivisione (la morte del processo, prima o dopo la scrittura), piu' i guasti dell'ambiente (un file aperto o cambiato
 * dal PC, area congelata, spazio finito, scritture degli album ferme). Gli invarianti del §5.5 si controllano dopo ogni morte, a fine passo e in una
 * rilettura finale con l'hash di ogni file. Sul NAS finto il motore delle operazioni e' quello vero ma il suo diario e le sue chiamate SMB non si
 * colpiscono: lo fa il simulatore SMB ([ShareChaosSmbBenchTest]).
 *
 * Per cambiare i numeri senza toccare il codice: `MCG_SHARE_CHAOS_ROUNDS`, `MCG_SHARE_CHAOS_SEED` e `MCG_SHARE_CHAOS_FIRST_ROUND` (poi `--rerun`: Gradle
 * non vede le variabili). Un giro che fallisce si rifa' con lo stesso seme, `MCG_SHARE_CHAOS_FIRST_ROUND=<numero del giro>` e `MCG_SHARE_CHAOS_ROUNDS=1`.
 * Il rapporto resta in `build/chaos/share-jvm-report-fake.txt`.
 */
class ShareChaosBenchTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  @Test
  fun duemilaGiriSulNasFintoNonLascianoNullaAMetaNePerditeNeDoppioniNeVociSenzaFile() {
    val rounds = env("MCG_SHARE_CHAOS_ROUNDS")?.toInt() ?: DEFAULT_ROUNDS
    val seed = env("MCG_SHARE_CHAOS_SEED")?.toLong() ?: DEFAULT_SEED
    val first = env("MCG_SHARE_CHAOS_FIRST_ROUND")?.toInt() ?: 0
    val result = runBlocking { ShareChaosBench.run(rounds, seed, FakeChaosTargets, firstRound = first) }
    println(result.summary())
    result.writeReport(File("build/chaos/share-jvm-report-fake.txt"))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    // Il banco deve davvero aver colpito: senza colpi un banco verde non prova niente.
    assertThat(result.stats.totalFired).isAtLeast(rounds)
    assertThat(result.stats.totalDeaths).isAtLeast(rounds / 4)
  }

  private companion object {
    const val DEFAULT_ROUNDS = 2_000
    const val DEFAULT_SEED = 20261026L
  }
}
