package dev.pampa.mycloudgallery.core.release.state

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 (P1, D30, §4.3 "Stabilita'"): il deposito su file dello stato del manifest. Scrittura atomica con la **morte del processo in ogni
 * punto di un salvataggio** (prima del temporaneo, a meta' del temporaneo, prima della rinomina, dopo): chi riapre trova lo stato di prima o
 * quello nuovo, intero. Un file troncato o rovinato in qualunque byte si legge come assente, mai un crollo.
 */
class FileReleaseStateStoreTest {

  private val issued = Instant.parse("2026-10-01T00:00:00Z")

  private fun manifest(seq: Long) = ReleaseManifest(
    1, ManifestLimits.APP_ID, seq, issued, issued.plus(Duration.ofDays(30)), 4,
    listOf(VersionBlock(3, 9, BlockLevel.ALL, ReleaseMessage.DATA_RISK), VersionBlock(1, 2, BlockLevel.WRITES, ReleaseMessage.UPDATE)),
    listOf(ModelHint("model-a", "a".repeat(64), listOf("https://huggingface.co/a/e", "https://cdn-lfs.hf.co/x"))),
  )

  private val states = listOf(
    StoredRelease(0, null, null, 1, ReleaseStatus.Normal),
    StoredRelease(4, manifest(4), 1, 7, ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK)),
    StoredRelease(9, manifest(9), 250, 12, ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY)),
    StoredRelease(10, manifest(10), 2, 12, ReleaseStatus.Normal),
  )

  @Test
  fun loStatoSiRileggeUguale() = runTest {
    val store = FragileFileStore(FragileFileStore.tempDir())
    assertThat(store.read()).isNull()
    for (state in states) {
      store.write(state)
      assertThat(store.read()).isEqualTo(state)
      assertThat(FileReleaseStateStore(store.dir).read()).isEqualTo(state)
    }
    store.clear()
    assertThat(store.read()).isNull()
    assertThat(store.file.exists() || store.temp.exists()).isFalse()
  }

  @Test
  fun unFileTroncatoORovinatoSiLeggeComeAssente() {
    val bytes = StoredReleaseCodec.encode(states[1])
    assertThat(StoredReleaseCodec.decode(bytes)).isEqualTo(states[1])
    for (length in 0 until bytes.size) assertWithMessage("troncato a $length").that(StoredReleaseCodec.decode(bytes.copyOf(length))).isNull()
    for (i in bytes.indices) {
      for (bit in 0 until 8) {
        val damaged = bytes.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
        assertWithMessage("byte $i, bit $bit").that(StoredReleaseCodec.decode(damaged)).isNull()
      }
    }
    val random = Random(7)
    repeat(2_000) { assertThat(StoredReleaseCodec.decode(random.nextBytes(random.nextInt(600)))).isNull() }
    assertThat(StoredReleaseCodec.decode(bytes + byteArrayOf(0))).isNull()
    assertThat(StoredReleaseCodec.decode(ByteArray(FileReleaseStateStore.MAX_FILE_BYTES + 1))).isNull()
  }

  @Test
  fun laMorteInOgniPuntoDelSalvataggioLasciaLoStatoDiPrimaOQuelloNuovo() = runTest {
    for (point in SavePoint.entries) {
      val store = FragileFileStore(FragileFileStore.tempDir())
      store.write(states[1])
      store.dieAt(point)
      val death = runCatching { store.write(states[2]) }.exceptionOrNull()
      assertWithMessage("$point").that(death).isInstanceOf(SimulatedDeath::class.java)
      val reopened = FileReleaseStateStore(store.dir).read()
      val expected = if (point == SavePoint.AFTER_RENAME) states[2] else states[1]
      assertWithMessage("riaperto dopo la morte in $point").that(reopened).isEqualTo(expected)
      store.write(states[3])
      assertWithMessage("il salvataggio dopo la morte in $point").that(FileReleaseStateStore(store.dir).read()).isEqualTo(states[3])
      assertThat(store.temp.exists()).isFalse()
    }
  }

  @Test
  fun unTemporaneoRimastoSiIgnora() = runTest {
    val store = FragileFileStore(FragileFileStore.tempDir())
    store.write(states[1])
    store.temp.writeBytes(StoredReleaseCodec.encode(states[2]).copyOf(40))
    assertThat(store.read()).isEqualTo(states[1])
    store.temp.writeBytes(StoredReleaseCodec.encode(states[2]))
    assertWithMessage("un temporaneo intero non e' lo stato finche' non e' rinominato").that(store.read()).isEqualTo(states[1])
    store.clear()
    assertThat(store.temp.exists()).isFalse()
  }

  @Test
  fun unaVersioneSconosciutaSiLeggeComeAssente() {
    val bytes = StoredReleaseCodec.encode(states[1])
    val other = bytes.copyOf().also { it[5] = '2'.code.toByte() }
    val crc = java.util.zip.CRC32().apply { update(other, 0, other.size - 4) }.value.toInt()
    java.nio.ByteBuffer.wrap(other, other.size - 4, 4).putInt(crc)
    assertThat(StoredReleaseCodec.decode(other)).isNull()
  }

  @Test
  fun unaCartellaIlleggibileNonFaCrollare() = runTest {
    val dir = FragileFileStore.tempDir()
    java.io.File(dir, FileReleaseStateStore.FILE_NAME).mkdirs()
    java.io.File(dir, FileReleaseStateStore.FILE_NAME + "/dentro").writeText("x")
    assertThat(FileReleaseStateStore(dir).read()).isNull()
    assertThat(runCatching { FileReleaseStateStore(dir).write(states[1]) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }
}
