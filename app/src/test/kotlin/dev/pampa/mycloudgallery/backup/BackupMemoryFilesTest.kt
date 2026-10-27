package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.common.files.Patience
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La memoria del backup scrive e toglie i suoi file di stato **davvero**, o dice di no.
 *
 * Su Windows (la JVM dei test) `File.delete()` e `File.renameTo()` falliscono in silenzio finche' un altro handle tiene il file: il vecchio
 * codice non se ne accorgeva e un "problema" del backup, una pausa o una marca di "NAS occupato" restavano per sempre. Qui "l'altro handle" e' un
 * `FileInputStream` (e' cio' che apre `File.readText()`), e si chiude **dentro l'attesa** del codice che si prova ([Patience.pause]): niente
 * orologio, nessun test che cade sotto carico. Su Linux e su Android un file aperto non impedisce niente e le stesse prove passano senza attese.
 */
class BackupMemoryFilesTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

  /** Un altro handle sul file; la prima attesa del codice che si prova lo chiude. */
  private class Holder(file: File) {
    private val stream = FileInputStream(file)
    var waits = 0
      private set
    val patience = Patience(listOf(1L, 1L, 1L, 1L, 1L)) {
      waits++
      stream.close()
    }

    fun close() = stream.close()
  }

  private fun file(name: String) = File(folder.root, name)

  private fun waitedIfWindows(holder: Holder) {
    if (windows) assertThat(holder.waits).isAtLeast(1)
  }

  private inline fun <R> Holder.holding(block: (Holder) -> R): R = try {
    block(this)
  } finally {
    close()
  }

  @Test
  fun unProblemaSiTogliePerDavveroAncheSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    BackupRunMemory(folder.root).setProblem(BackupProblemCode.NAS_FULL)
    val holder = Holder(file("backup-last-problem"))

    val removed = holder.holding { BackupRunMemory(folder.root, it.patience).setProblem(null) }

    assertThat(removed).isTrue()
    assertThat(file("backup-last-problem").exists()).isFalse()
    assertThat(BackupRunMemory(folder.root).lastProblem()).isNull()
    waitedIfWindows(holder)
  }

  @Test
  fun laPausaSiTogliePerDavveroAncheSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    BackupRunMemory(folder.root).setPaused(true)
    val holder = Holder(file("backup-user-paused"))

    val saved = holder.holding { BackupRunMemory(folder.root, it.patience).setPaused(false) }

    assertThat(saved).isTrue()
    assertThat(BackupRunMemory(folder.root).isPaused()).isFalse()
    waitedIfWindows(holder)
  }

  @Test
  fun ilSegnoDelNasOccupatoSiTogliePerDavveroAncheSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    BackupRunMemory(folder.root).setBusy(BackupKind.VIDEOS, 5_000L)
    val holder = Holder(file("backup-nas-busy-videos"))

    val removed = holder.holding { BackupRunMemory(folder.root, it.patience).setBusy(BackupKind.VIDEOS, null) }

    assertThat(removed).isTrue()
    assertThat(BackupRunMemory(folder.root).busySince(BackupKind.VIDEOS)).isNull()
    waitedIfWindows(holder)
  }

  @Test
  fun lAvanzamentoSiTogliePerDavveroAncheSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    BackupRunMemory(folder.root).setProgress("1 2 3 4 5 6")
    val holder = Holder(file("backup-progress"))

    val removed = holder.holding { BackupRunMemory(folder.root, it.patience).setProgress("") }

    assertThat(removed).isTrue()
    assertThat(BackupRunMemory(folder.root).progress()).isNull()
    waitedIfWindows(holder)
  }

  @Test
  fun unaScritturaSostituisceIlSegnoAncheSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    BackupRunMemory(folder.root).markComplete(1_000L)
    val holder = Holder(file("backup-last-complete"))

    val saved = holder.holding { BackupRunMemory(folder.root, it.patience).markComplete(2_000L) }

    assertThat(saved).isTrue()
    assertThat(BackupRunMemory(folder.root).lastComplete()).isEqualTo(2_000L)
    assertThat(folder.root.list().orEmpty().filter { it.endsWith(".tmp") }).isEmpty()
    waitedIfWindows(holder)
  }

  @Test
  fun seIlFileNonSiTogliePerDavvero_laRispostaEFalsaEIlSegnoSiVedeAncora() = runBlocking<Unit> {
    // Un nome occupato da una cartella non vuota: nessuna attesa lo risolve, uguale su Windows e su Linux.
    val memory = BackupRunMemory(folder.root, Patience.NONE)
    val busy = file("backup-last-problem")
    busy.mkdirs()
    File(busy, "dentro").writeText("1")

    val removed = memory.setProblem(null)

    assertThat(removed).isFalse()
    assertThat(busy.exists()).isTrue()
  }

  @Test
  fun seUnSegnoNonSiScrive_laRispostaEFalsa() = runBlocking<Unit> {
    val memory = BackupRunMemory(folder.root, Patience.NONE)
    val busy = file("backup-last-complete")
    busy.mkdirs()
    File(busy, "dentro").writeText("1")

    assertThat(memory.markComplete(5_000L)).isFalse()
    assertThat(File(busy, "dentro").readText()).isEqualTo("1")
    assertThat(folder.root.list().orEmpty().toList()).containsExactly("backup-last-complete")
  }
}
