package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Un giro e' ripetibile: stesso seme, stesse scelte, stessi colpi, stesso esito (il codice sotto prova e' deterministico). E' cio' che permette
 * di rifare un giro rosso con `MCG_BACKUP_CHAOS_SEED` e `MCG_BACKUP_CHAOS_FIRST_ROUND` (docs/prova-500.md). Il rapporto senza la durata deve
 * essere identico, per tutti e tre i bersagli e per entrambi i motori.
 */
class BackupChaosDeterminismTest {

  private fun report(target: () -> BackupChaosTarget, stack: BackupChaosStackFactory, rounds: Int, first: Int = 0, fiveB: Boolean = false): String {
    val config = if (fiveB) BackupChaosConfig(fiveB = Backup5BConfig()) else BackupChaosConfig()
    val result = runBlocking { BackupChaosBench.run(rounds, 99L, target(), stack, config = config, firstRound = first) }
    return result.summary().lines().filterNot { it.startsWith("durata:") }.joinToString("\n")
  }

  @Test
  fun stessoSemeStessaProvaSulNasFinto() {
    for (stack in listOf(ReferenceStack, ProductionStack)) {
      assertThat(report({ FakeNasTarget() }, stack, 80)).isEqualTo(report({ FakeNasTarget() }, stack, 80))
    }
  }

  @Test
  fun stessoSemeStessaProvaSulSimulatoreSmb() {
    for (stack in listOf(ReferenceStack, ProductionStack)) {
      assertThat(report({ SimSmbTarget() }, stack, 25)).isEqualTo(report({ SimSmbTarget() }, stack, 25))
    }
  }

  @Test
  fun stessoSemeStessaProvaConLaCinqueBSulNasFintoESulSimulatoreSmb() {
    for (stack in listOf(ReferenceStack, ProductionStack)) {
      assertThat(report({ FakeNasTarget() }, stack, 60, fiveB = true)).isEqualTo(report({ FakeNasTarget() }, stack, 60, fiveB = true))
      assertThat(report({ SimSmbTarget() }, stack, 15, fiveB = true)).isEqualTo(report({ SimSmbTarget() }, stack, 15, fiveB = true))
    }
  }

  @Test
  fun lAggiuntaDellaCinqueBNonCambiaIColpiDelCaricamento() {
    // La fase 5B ha un seme suo: i giri della 5A (i colpi caduti prima della 5B) restano quelli di prima. Il rapporto della 5A pura e' la
    // parte iniziale di quello con la 5B: stesse interruzioni nel caricamento, stessi passi del caricamento.
    val plain = runBlocking { BackupChaosBench.run(40, 99L, FakeNasTarget(), ProductionStack) }
    val with5B = runBlocking { BackupChaosBench.run(40, 99L, FakeNasTarget(), ProductionStack, config = BackupChaosConfig(fiveB = Backup5BConfig())) }
    assertThat(plain.stats.steps).isEqualTo(with5B.stats.stepKinds.filterKeys { !it.startsWith("5B") }.values.sum())
    assertThat(plain.stats.mediaTotal).isLessThan(with5B.stats.mediaTotal)
    for (kind in listOf("RUN_ALL", "RESUME", "RUN_PHOTOS", "RUN_VIDEOS")) {
      assertThat(with5B.stats.stepKinds[kind] ?: 0).isEqualTo(plain.stats.stepKinds[kind] ?: 0)
    }
  }

  @Test
  fun unGiroSiRifaDaSoloConIlSuoNumero() {
    // Il giro 7 fatto da solo (primo giro 7, un giro) ha gli stessi colpi del giro 7 dentro una serie: il rapporto di un giro solo e' la sua parte.
    val alone = runBlocking {
      BackupChaosBench.run(1, 99L, FakeNasTarget(), ReferenceStack, firstRound = 7).stats.totalFired
    }
    val inSeries = runBlocking {
      val stats = ChaosStats()
      BackupChaosBench.run(7, 99L, FakeNasTarget(), ReferenceStack, stats = stats)
      val before = stats.totalFired
      BackupChaosBench.run(1, 99L, FakeNasTarget(), ReferenceStack, stats = stats, firstRound = 7)
      stats.totalFired - before
    }
    assertThat(alone).isEqualTo(inSeries)
  }
}
