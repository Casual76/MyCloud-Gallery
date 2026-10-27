package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IL BANCO DELLE INTERRUZIONI DEI VOLTI SUL NAS FINTO (Fase 8, P12; docs/fase8-progetto.md §5.5, riga P12 del §4.4): 2.000 giri a ogni `check.ps1`.
 * Il calcolo vero (P4), la pubblicazione e la replica vere (P3), il raggruppamento con il custode (P5), "Disattiva e cancella" vera (P8) e l'interruttore
 * vero (P7) su 2-3 dispositivi di un account, con la rete che cade prima e dopo, la sessione scaduta, lo stallo, lo spazio finito, la morte del processo a
 * ogni salvataggio (database, outbox, istantanea, diario della cancellazione, registro dei dati personali), la generazione che cambia a meta' e un
 * dispositivo spento che torna. Dopo ogni morte, a fine passo e in una rilettura finale: gli otto invarianti del §5.5 (`ChaosVerifier`).
 *
 * Variabili: `MCG_FACES_CHAOS_ROUNDS` (2.000), `MCG_FACES_CHAOS_SEED` (20261029), `MCG_FACES_CHAOS_FIRST_ROUND`; per cercare i difetti noti (§F6) `MCG_FACES_CHAOS_NO_AVOID=1`
 * (il banco non evita O2) e `MCG_FACES_CHAOS_DB_ERRORS=1` (anche gli errori del database che la produzione non guarda: O1). Il rapporto va in `build/chaos/`.
 */
class FacesChaosBenchTest {

  @Test
  fun duemilaGiriSulNasFinto(): Unit = runBlocking {
    val rounds = ChaosEnv.number("MCG_FACES_CHAOS_ROUNDS") ?: DEFAULT_ROUNDS
    val seed = ChaosEnv.number("MCG_FACES_CHAOS_SEED") ?: DEFAULT_SEED
    val first = (ChaosEnv.number("MCG_FACES_CHAOS_FIRST_ROUND") ?: 0L).toInt()
    val config = ChaosConfig(
      MemoryTarget.capabilities,
      avoid = if (System.getenv("MCG_FACES_CHAOS_NO_AVOID") == "1") emptySet() else KnownDefect.entries.toSet(),
      databaseErrors = System.getenv("MCG_FACES_CHAOS_DB_ERRORS") == "1",
    )
    val result = ChaosBench.run(rounds.toInt(), seed, MemoryTarget, config, firstRound = first)
    result.writeReport(File("build/chaos/faces-jvm-report-fake.txt"))
    println(result.summary())
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isGreaterThan(rounds.toInt())
  }

  private companion object {
    const val DEFAULT_ROUNDS = 2_000L
    const val DEFAULT_SEED = 20261029L
  }
}

/** Le variabili d'ambiente del banco: numeri positivi, o null. */
internal object ChaosEnv {
  fun number(name: String): Long? = System.getenv(name)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}
