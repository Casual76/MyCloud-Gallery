package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il banco della condivisione sul **simulatore SMB**, con la sessione di produzione e le **vere** primitive di scrittura (riga P5 del §4.4: 600 giri):
 * lo stesso giro del banco sul NAS finto ([ShareChaosBenchTest]) e in piu' i colpi sulle chiamate SMB del motore (`FaultPlan`: connessione caduta, sessione
 * scaduta, socket fermo, `DISK_FULL`, morte del processo, prima o dopo ogni chiamata) e la morte del processo a ogni salvataggio del diario di core-nasops.
 * Il motore e' quello vero (`NasOpsEngine` con la porta di completamento), il cestino e la copia lato server sono quelli di core-nas.
 *
 * `MCG_SHARE_CHAOS_SMB_ROUNDS` (600 di norma, al massimo 5.000), `MCG_SHARE_CHAOS_SEED`, `MCG_SHARE_CHAOS_FIRST_ROUND`. Rapporto in
 * `build/chaos/share-jvm-report-smb.txt`.
 */
class ShareChaosSmbBenchTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  @Test
  fun seicentoGiriSulSimulatoreSmbNonLascianoNullaAMetaNePerditeNeDoppioniNeVociSenzaFile() {
    val rounds = (env("MCG_SHARE_CHAOS_SMB_ROUNDS")?.toInt() ?: DEFAULT_ROUNDS).coerceIn(1, MAX_ROUNDS)
    val seed = env("MCG_SHARE_CHAOS_SEED")?.toLong() ?: DEFAULT_SEED
    val first = env("MCG_SHARE_CHAOS_FIRST_ROUND")?.toInt() ?: 0
    val result = runBlocking { ShareChaosBench.run(rounds, seed, SimulatedChaosTargets, firstRound = first) }
    println(result.summary())
    result.writeReport(File("build/chaos/share-jvm-report-smb.txt"))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    assertThat(result.stats.totalFired).isAtLeast(rounds)
    // Le chiamate SMB del motore e il suo diario sono stati colpiti davvero, con la morte compresa.
    if (rounds >= DEFAULT_ROUNDS) {
      assertThat(result.stats.firedOn(Surface.NAS)).isGreaterThan(0)
      assertThat(result.stats.firedOn(Surface.OPS_STORE)).isGreaterThan(0)
      assertThat(result.stats.deathsOn(Surface.NAS)).isGreaterThan(0)
    }
  }

  private companion object {
    const val DEFAULT_ROUNDS = 600
    const val MAX_ROUNDS = 5_000
    const val DEFAULT_SEED = 20261027L
  }
}
