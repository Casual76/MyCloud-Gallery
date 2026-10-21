package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.download.FileDigest
import dev.pampa.mycloudgallery.core.models.download.PartialFile
import dev.pampa.mycloudgallery.core.models.support.TestModels
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il file parziale (regola del confine di blocco, E-A1, §5.5 invariante 3) e lo SHA-256 su un canale (D16). */
class PartialFileAndDigestTest {
  private val dir = TestModels.tempDir()
  private val block = TestModels.BLOCK

  @Test
  fun ilPuntoDiRipresaEIlConfineDiBloccoSottoLaLunghezza() {
    val file = File(dir, "p.part")
    val total = 3L * block + 100
    assertThat(PartialFile.resumePoint(file, total)).isEqualTo(0)
    for ((length, expected) in listOf(
      0L to 0L,
      1L to 0L,
      block - 1L to 0L,
      block.toLong() to block.toLong(),
      block + 1L to block.toLong(),
      2L * block + 999 to 2L * block,
      3L * block to 3L * block,
      3L * block + 99 to 3L * block,
      total to total,
    )) {
      file.writeBytes(ByteArray(length.toInt()))
      assertThat(PartialFile.resumePoint(file, total)).isEqualTo(expected)
      // `resumePoint` non modifica il file (lo accorcia `open`).
      assertThat(file.length()).isEqualTo(length)
    }
  }

  @Test
  fun unParzialePiuLungoDelModelloSiTolgeEParteDaZero() {
    val file = File(dir, "long.part")
    file.writeBytes(ByteArray(1001))
    assertThat(PartialFile.resumePoint(file, 1000)).isEqualTo(0)
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun apriAccorciaEScriveInCoda() {
    val file = File(dir, "sub/open.part")
    file.parentFile.mkdirs()
    file.writeBytes(ByteArray(block + 500) { 9 })
    PartialFile.open(file, block.toLong()).use {
      assertThat(file.length()).isEqualTo(block.toLong())
      it.append(ByteArray(10) { 1 }, 10)
      assertThat(it.length).isEqualTo(block + 10L)
    }
    assertThat(file.length()).isEqualTo(block + 10L)
    assertThat(file.readBytes().takeLast(10)).containsExactlyElementsIn(List(10) { 1.toByte() })
  }

  @Test
  fun nonSiApreOltreLaLunghezzaDelFile() {
    val file = File(dir, "short.part")
    file.writeBytes(ByteArray(10))
    assertThrows(IOException::class.java) { PartialFile.open(file, 20) }
    // La cartella mancante si crea.
    PartialFile.open(File(dir, "nuova/cartella/x.part"), 0).use { it.append(ByteArray(3), 3) }
    assertThat(File(dir, "nuova/cartella/x.part").length()).isEqualTo(3)
  }

  @Test
  fun riconosceIlDiscoPieno() {
    assertThat(PartialFile.isNoSpace(IOException("No space left on device"))).isTrue()
    assertThat(PartialFile.isNoSpace(IOException("There is not enough space on the disk"))).isTrue()
    assertThat(PartialFile.isNoSpace(IOException("Connection reset"))).isFalse()
    assertThat(PartialFile.isNoSpace(IOException())).isFalse()
  }

  @Test
  fun loSha256SuUnCanaleEQuelloDiJava() {
    for (size in listOf(0, 1, 63, 64, block - 1, block, block + 1, 2 * block, 2 * block + 7, TestModels.SIZE)) {
      val bytes = TestModels.bytes(size, seed = size.toLong())
      val file = File(dir, "d$size")
      file.writeBytes(bytes)
      RandomAccessFile(file, "r").use {
        assertThat(runBlocking { FileDigest.sha256(it.channel, size.toLong()) }).isEqualTo(Sha256.of(bytes))
      }
    }
  }

  @Test
  fun unFileCheFinisceInAnticipoDaUnaImprontaDiversa() {
    val bytes = TestModels.bytes(1000)
    val file = File(dir, "short")
    file.writeBytes(bytes)
    RandomAccessFile(file, "r").use {
      assertThat(runBlocking { FileDigest.sha256(it.channel, 2000) }).isEqualTo(Sha256.of(bytes))
      assertThat(runBlocking { FileDigest.sha256(it.channel, 2000) }).isNotEqualTo(Sha256.of(bytes + ByteArray(1000)))
    }
  }

  @Test
  fun lAnnullamentoFermaIlCalcolo() {
    val bytes = TestModels.bytes()
    val file = File(dir, "cancel")
    file.writeBytes(bytes)
    RandomAccessFile(file, "r").use { raf ->
      val started = AtomicBoolean(false)
      val completed = AtomicInteger(0)
      val cancelled = AtomicBoolean(false)
      runBlocking {
        val job: Job = launch(Dispatchers.Default) {
          try {
            repeat(10_000) {
              started.set(true)
              FileDigest.sha256(raf.channel, bytes.size.toLong())
              completed.incrementAndGet()
            }
          } catch (e: CancellationException) {
            cancelled.set(true)
            throw e
          }
        }
        while (!started.get()) delay(1)
        job.cancelAndJoin()
      }
      // Fermato a meta' (non tutti i giri), e con una CancellationException: il calcolo controlla l'annullamento fra un blocco e l'altro.
      assertThat(cancelled.get()).isTrue()
      assertThat(completed.get()).isLessThan(10_000)
    }
  }
}
