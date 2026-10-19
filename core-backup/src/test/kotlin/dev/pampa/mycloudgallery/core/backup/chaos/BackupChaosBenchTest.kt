package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La prova delle 500 interruzioni del backup sul NAS finto (docs/fase5-progetto.md §5.4, P6): giri casuali, ognuno con il suo telefono, il
 * suo NAS e da 1 a 3 lavori del backup con colpi casuali (connessione, sessione, socket, NAS pieno, file aperto dal PC, byte alterati,
 * "Ferma", congelamento, un file del PC, morte del processo a ogni chiamata al NAS, a ogni scrittura del diario e del catalogo e a ogni
 * lettura del telefono), e gli invarianti del §5.4 controllati dopo ogni morte, a fine passo e in una rilettura completa.
 *
 * Due volte, con lo stesso banco: sull'esecutore di riferimento di P0 ([ReferenceStack]) e sul motore vero di P2 con la deduplica di P3
 * ([ProductionStack]).
 *
 * I numeri (docs/prova-500.md §B3): nei test normali [NORMAL_ROUNDS] giri a motore, cosi' `check.ps1` resta veloce; i 2.000 giri pieni si
 * ottengono con `MCG_BACKUP_CHAOS_ROUNDS=2000` (poi `--rerun`: Gradle non vede le variabili), cioe' `tools\chaos\area.ps1 -Mode backup`.
 * `MCG_BACKUP_CHAOS_SEED` e `MCG_BACKUP_CHAOS_FIRST_ROUND` rifanno un giro rosso: il rapporto dice "giro 163, seme di base 20261016"; con
 * `MCG_BACKUP_CHAOS_ROUNDS=1` e il numero del giro come primo si rifa' solo quello. I rapporti restano in `build/chaos/`; lo stato di un giro
 * rosso in `build/chaos/backup-state-<giro>.txt`, che `BackupVerifierTest` mostra come verificare da solo.
 */
class BackupChaosBenchTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  private fun bench(factory: BackupChaosStackFactory, report: String) {
    val rounds = env("MCG_BACKUP_CHAOS_ROUNDS")?.toInt() ?: NORMAL_ROUNDS
    val seed = env("MCG_BACKUP_CHAOS_SEED")?.toLong() ?: DEFAULT_SEED
    val first = env("MCG_BACKUP_CHAOS_FIRST_ROUND")?.toInt() ?: 0
    val dir = File("build/chaos")
    val result = runBlocking {
      BackupChaosBench.run(rounds, seed, FakeNasTarget(), factory, config = BackupChaosConfig(fiveB = Backup5BConfig()), firstRound = first, statesDir = dir)
    }
    println(result.summary())
    result.writeReport(File(dir, report))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    // Il banco deve davvero aver colpito: senza colpi un banco verde non prova niente.
    assertThat(result.stats.totalFired).isAtLeast(rounds)
  }

  @Test
  fun giriCasualiSull_esecutoreDiRiferimentoNonLasciano_nulla_aMeta_nePerditeNeDoppioni() {
    bench(ReferenceStack, "backup-report-riferimento.txt")
  }

  @Test
  fun giriCasualiSulMotoreVeroDiP2NonLasciano_nulla_aMeta_nePerditeNeDoppioni() {
    bench(ProductionStack, "backup-report-vero.txt")
  }

  companion object {
    /** Il numero pieno del brief (§5.4) e' [FULL_ROUNDS]; nei test normali meno. */
    const val FULL_ROUNDS = 2_000
    const val NORMAL_ROUNDS = 2_000
    const val DEFAULT_SEED = 20261016L
  }
}
