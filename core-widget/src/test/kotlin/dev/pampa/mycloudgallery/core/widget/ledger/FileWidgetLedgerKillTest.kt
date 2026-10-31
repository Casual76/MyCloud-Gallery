package dev.pampa.mycloudgallery.core.widget.ledger

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **La morte vera del processo** (§4.3 "Stabilita'": "registro del widget con scrittura atomica e il test della morte del processo"): un processo
 * figlio ([main] in `LedgerWriterMain.kt`) riscrive il registro di continuo e il padre lo **uccide** (`destroyForcibly`) in un istante a caso, per
 * 14 volte di fila sulla stessa cartella. Dopo ogni morte il registro deve leggersi intero, con le sette celle della **stessa** epoca (mai meta' di
 * una e meta' di un'altra) e con un'epoca che non torna mai indietro. Il figlio e' un processo nostro: non si tocca nessun altro processo.
 */
class FileWidgetLedgerKillTest {

  @get:Rule
  val folder = TemporaryFolder()

  private fun javaBinary(): String = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java").path

  @Test
  fun unProcessoUccisoAMetaScritturaLasciaUnRegistroIntero() = runBlocking {
    val dir = folder.newFolder("widget")
    val file = File(dir, FileLedgerStorage.FILE_NAME)
    // Il percorso delle classi in un file di argomenti: su Windows la riga di comando ha un tetto e quello di Gradle e' lungo.
    val argFile = folder.newFile("classpath.args")
    argFile.writeText("-cp\n\"" + System.getProperty("java.class.path").orEmpty().replace("\\", "\\\\") + "\"\n")
    val random = Random(31)
    var lastEpoch = 0L
    var kills = 0
    var leftovers = 0
    repeat(14) { round ->
      val log = folder.newFile("figlio-$round.log")
      val process = ProcessBuilder(
        javaBinary(), "-XX:TieredStopAtLevel=1", "-Xshare:auto", "@${argFile.path}", "dev.pampa.mycloudgallery.core.widget.ledger.LedgerWriterMainKt", dir.path,
      ).redirectErrorStream(true).redirectOutput(log).start()
      try {
        // Aspetta che il figlio dica di aver scritto (ha gia' una scrittura completa), poi lo uccide dopo un istante a caso (0-40 ms): a volte dentro una scrittura.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline && process.isAlive && !log.readText().contains("scritto")) Thread.sleep(5)
        assertWithMessage("il figlio non ha scritto (round $round): ${log.readText().take(600)}").that(log.readText()).contains("scritto")
        Thread.sleep(random.nextLong(0, 40))
      } finally {
        process.destroyForcibly()
        process.waitFor(30, TimeUnit.SECONDS)
        kills++
      }
      // Un temporaneo rimasto accanto al file vuol dire che la morte e' arrivata dentro una scrittura.
      if (File(file.path + ".tmp").exists()) leftovers++
      // Dopo la morte: intero, coerente, mai indietro.
      val reopened = FileWidgetLedger(dir)
      val slot = WidgetSlot(1)
      val epoch = reopened.epochOf(slot)
      assertWithMessage("epoca dopo la morte numero $round").that(epoch).isGreaterThan(lastEpoch)
      assertWithMessage("il registro e' vuoto dopo la morte numero $round (file di ${file.length()} byte)").that(reopened.slots()).containsExactly(slot)
      for (index in 0 until 7) {
        assertWithMessage("cella $index, morte numero $round").that(reopened.cell(slot, index)?.media?.value)
          .isEqualTo(UUID(epoch, index.toLong()).toString())
      }
      assertThat(reopened.cell(slot, 7)).isNull()
      lastEpoch = epoch
    }
    assertThat(kills).isEqualTo(14)
    // Il registro e' ancora scrivibile dopo tutte le morti, anche con un temporaneo rimasto accanto.
    assertThat(lastEpoch).isGreaterThan(14)
    println("MISURA morte del processo: 14 uccisioni, ultima epoca $lastEpoch, $leftovers volte dentro una scrittura (temporaneo rimasto), file di ${file.length()} byte, nessun registro rovinato")
  }
}
