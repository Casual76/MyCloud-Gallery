package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La prova delle interruzioni del backup passa anche dalle **primitive vere** del backup (`SmbNasBackupOps` sopra le primitive SMB della 4B:
 * `FILE_CREATE`, rinomina senza sostituire, fine file sullo stesso handle, `reopenTemp`) e dalla lettura vera (`readAll` a 4 flussi) della
 * sessione SMB di produzione, sopra il simulatore SMB di core-nas ([SimSmbTarget], semantica Samba: maiuscole, condivisioni e blocchi, handle
 * che il NAS tiene finche' la connessione non cade). Stessi giri, stessi colpi (prima e dopo ogni chiamata, morti del processo, connessione
 * chiusa davvero: la morte del processo chiude la sua connessione, come sul telefono) e stessi invarianti del banco sul NAS finto.
 *
 * Giri: `MCG_BACKUP_CHAOS_SMB_ROUNDS` (di default [DEFAULT_ROUNDS], il numero del brief; mai piu' di [MAX_ROUNDS]). Rapporti in
 * `build/chaos/backup-report-smb-*.txt`.
 */
class BackupChaosSmbBenchTest {

  private fun bench(factory: BackupChaosStackFactory, report: String) {
    val rounds = (System.getenv("MCG_BACKUP_CHAOS_SMB_ROUNDS")?.toIntOrNull() ?: DEFAULT_ROUNDS).coerceIn(1, MAX_ROUNDS)
    val seed = System.getenv("MCG_BACKUP_CHAOS_SEED")?.toLongOrNull() ?: BackupChaosBenchTest.DEFAULT_SEED
    val first = System.getenv("MCG_BACKUP_CHAOS_FIRST_ROUND")?.toIntOrNull() ?: 0
    val dir = File("build/chaos")
    val result = runBlocking {
      BackupChaosBench.run(rounds, seed, SimSmbTarget(), factory, config = BackupChaosConfig(fiveB = Backup5BConfig()), firstRound = first, statesDir = dir)
    }
    println(result.summary())
    result.writeReport(File(dir, report))
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.rounds).isEqualTo(rounds)
    assertThat(result.stats.totalFired).isAtLeast(rounds)
    // Le primitive vere sono state chiamate e colpite: creazione e scrittura del temporaneo, punto di ripresa, pubblicazione, cancellazione del proprio temporaneo.
    for (
      primitive in listOf(
        Primitive.OPEN_TEMP, Primitive.WRITE_TEMP, Primitive.CHECKPOINT, Primitive.FINISH_TEMP, Primitive.PUBLISH_TEMP, Primitive.DELETE_OWN_TEMP,
        // 5B: le due rinomine vere delle versioni (`SmbNasFileOps.renameVersion`) e la copia lato server del guardiano.
        Primitive.ARCHIVE_VERSION, Primitive.RESTORE_VERSION, Primitive.COPY_TO_TEMP,
      )
    ) {
      assertWithMessage("$primitive chiamata sulle primitive vere").that(result.stats.callsOf(primitive)).isGreaterThan(0)
    }
  }

  @Test
  fun leInterruzioniSulMotoreVeroEIlSimulatoreSmbNonLascianoNullaAMetaNePerditeNeDoppioni() {
    bench(ProductionStack, "backup-report-smb-vero.txt")
  }

  @Test
  fun leInterruzioniSull_esecutoreDiRiferimentoEIlSimulatoreSmbNonLascianoNullaAMeta() {
    bench(ReferenceStack, "backup-report-smb-riferimento.txt")
  }

  private companion object {
    const val DEFAULT_ROUNDS = 600
    const val MAX_ROUNDS = 5_000
  }
}
