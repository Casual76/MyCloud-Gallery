package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R4 della revisione di sicurezza della 5A: "Ferma" e' una **pausa** che dura. Il segno sta in `noBackupFilesDir` (come il resto della
 * memoria del backup): regge fra un giro e l'altro e alla morte del processo, e lo toglie solo "Riprendi" / "Fai ora" o l'azzeramento.
 */
class BackupPauseTest {

  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun senzaSegnoNonCePausa() {
    assertThat(BackupRunMemory(folder.root).isPaused()).isFalse()
  }

  @Test
  fun laPausaVaSubitoInMemoriaEPoiSuFile() = runBlocking<Unit> {
    val memory = BackupRunMemory(folder.root)
    memory.pauseNow()
    // Il cancello la vede al passo dopo, senza aspettare la scrittura del file.
    assertThat(memory.isPaused()).isTrue()
    memory.setPaused(true)
    assertThat(memory.isPaused()).isTrue()
  }

  @Test
  fun laPausaSopravvivealLaMorteDelProcesso() = runBlocking<Unit> {
    BackupRunMemory(folder.root).setPaused(true)
    // Un processo nuovo: nessuna memoria, solo il file.
    val reborn = BackupRunMemory(folder.root)
    assertThat(reborn.isPaused()).isTrue()
  }

  @Test
  fun unGiroOUnaTappaNuoviNonTolgonoLaPausa() = runBlocking<Unit> {
    val memory = BackupRunMemory(folder.root)
    memory.setPaused(true)
    // Il bilancio di un giro finito, l'ultimo problema, il battito: nessuno di questi tocca il segno (e' il difetto di R4).
    memory.setProblem(null)
    memory.markComplete(1_000L)
    memory.photoBeat.beat(2_000L)
    assertThat(memory.isPaused()).isTrue()
    assertThat(BackupRunMemory(folder.root).isPaused()).isTrue()
  }

  @Test
  fun riprendiTogliLaPausaDavveroAncheSuFile() = runBlocking<Unit> {
    val memory = BackupRunMemory(folder.root)
    memory.setPaused(true)
    memory.setPaused(false)
    assertThat(memory.isPaused()).isFalse()
    assertThat(BackupRunMemory(folder.root).isPaused()).isFalse()
    assertThat(File(folder.root, BackupRunMemory.FILES.last()).exists()).isFalse()
  }

  @Test
  fun lAzzeramentoTogliAncheLaPausa() {
    // L'azzeramento cancella i file di `FILES` (LocalDataEraserTest): la pausa e' fra questi.
    assertThat(BackupRunMemory.FILES).contains("backup-user-paused")
  }
}
