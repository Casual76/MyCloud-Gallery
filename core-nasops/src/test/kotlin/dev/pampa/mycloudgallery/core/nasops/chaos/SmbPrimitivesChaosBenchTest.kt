package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I1 della revisione 4B: la prova delle interruzioni passa anche dalle **primitive vere** (`SmbNasFileOps`) e dalla lettura vera della
 * sessione SMB di produzione, sopra il simulatore SMB di core-nas ([SimSmbTarget]), con lo stack vero di P12 (`NasOpsEngine`,
 * `NasTrashService`). Stessi giri, stessi colpi (prima e dopo ogni chiamata, morti del processo, connessione chiusa davvero) e stessi
 * invarianti del banco sul NAS finto.
 *
 * Giri: `MCG_CHAOS_SMB_ROUNDS` (di default 600, cioe' ben oltre le 500 interruzioni; mai piu' di 3.000). Rapporto in
 * `build/chaos/jvm-report-smb.txt`.
 */
class SmbPrimitivesChaosBenchTest {

  @Test
  fun leInterruzioniSullePrimitiveVereNonLascianoNullaAMetaNePerditeNeDoppioni() {
    val rounds = (System.getenv("MCG_CHAOS_SMB_ROUNDS")?.toIntOrNull() ?: DEFAULT_ROUNDS).coerceIn(1, MAX_ROUNDS)
    val seed = System.getenv("MCG_CHAOS_SEED")?.toLongOrNull() ?: DEFAULT_SEED
    val result = runBlocking { ChaosBench.run(rounds, seed, SimSmbTarget(), ProductionChaosStackFactory) }
    println(result.summary())
    result.writeReport(File("build/chaos/jvm-report-smb.txt"))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    // Almeno 500 interruzioni vere (non giri) sulle primitive di produzione.
    if (rounds >= DEFAULT_ROUNDS) assertThat(result.stats.totalFired).isAtLeast(500)
    // Le primitive vere sono state chiamate e colpite: rinomina, cestino, copia lato server, temporanei, cancellazioni del cestino.
    for (primitive in listOf(Primitive.RENAME, Primitive.TRASH_MOVE, Primitive.COPY_TO_TEMP, Primitive.PUBLISH_TEMP, Primitive.DELETE_IF_UNCHANGED)) {
      assertWithMessage("$primitive chiamata sulle primitive vere").that(result.stats.callsOf(primitive)).isGreaterThan(0)
    }
  }

  private companion object {
    const val DEFAULT_ROUNDS = 600
    const val MAX_ROUNDS = 3_000
    const val DEFAULT_SEED = 20261015L
  }
}
