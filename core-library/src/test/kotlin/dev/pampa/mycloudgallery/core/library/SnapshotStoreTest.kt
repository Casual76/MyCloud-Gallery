package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.store.SnapshotWriter
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** L'istantanea `MCGT2`: andata e ritorno, versione, file rovinati, scrittura atomica e accorpata. */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotStoreTest {

  private val dir: File = Files.createTempDirectory("mcgt").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun withRefs(s: ColumnarTimelineSnapshot): ColumnarTimelineSnapshot = TimelinePatcher.withRefs(
    s,
    (0 until s.size step 3).associateWith { MediaRef.Phone(if (it % 2 == 0) "external_primary" else "1a2b-3c4d", 10L + it, if (it % 5 == 0) null else "image/heic") },
  )

  @Test
  fun andataERitornoDi100MilaElementiConIBudget() = runTest {
    val library = SyntheticLibrary(seed = 4, count = SyntheticLibrary.FULL_COUNT, placeIds = intArrayOf(7, 8, 9))
    val index = withRefs(library.index(seq = 4242))
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    var ok = false
    val writeMs = TimelineIndexTest.timeMs { ok = store.write(index) }
    assertThat(ok).isTrue()
    var loaded: SnapshotStore.Loaded? = null
    val readMs = TimelineIndexTest.timeMs { loaded = store.read() }
    // Una seconda lettura, a JIT caldo: e' quella che conta per l'avvio a freddo (§2.6: sotto i 20 ms sul dispositivo).
    val warmMs = TimelineIndexTest.timeMs { store.read() }
    println("SNAPSHOT_BUDGET: file=${store.file.length() / 1024}KiB write=${writeMs}ms read=${readMs}ms readWarm=${warmMs}ms")
    assertThat(loaded!!.provisional).isFalse()
    assertSameIndex(loaded!!.snapshot, index)
    assertThat(loaded!!.snapshot.seq).isEqualTo(4242)
    assertThat(store.file.length()).isLessThan(100L * index.size)
    assertThat(writeMs).isLessThan(5_000)
    assertThat(warmMs).isLessThan(2_000)
    // Nessun temporaneo lasciato in giro.
    assertThat(File(dir, "${SnapshotStore.FILE_NAME}.tmp").exists()).isFalse()
  }

  @Test
  fun versioneDiversaFileTroncatoOCorrottoSiScartano() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    assertThat(store.read()).isNull()
    val index = withRefs(SyntheticLibrary(seed = 5, count = 3_000).index(seq = 9))
    assertThat(store.write(index, provisional = true)).isTrue()
    assertThat(store.read()!!.provisional).isTrue()
    val good = store.file.readBytes()

    // Un'altra versione del formato: la 1 (MCGT1, Fase 3) e una futura.
    store.file.writeBytes(good.copyOf().also { it[4] = 1 })
    assertThat(store.read()).isNull()
    store.file.writeBytes(good.copyOf().also { it[4] = 3 })
    assertThat(store.read()).isNull()
    // Un altro formato.
    store.file.writeBytes(good.copyOf().also { it[0] = 'X'.code.toByte() })
    assertThat(store.read()).isNull()
    // Troncato.
    store.file.writeBytes(good.copyOf(good.size / 2))
    assertThat(store.read()).isNull()
    // Un byte cambiato in mezzo (CRC).
    store.file.writeBytes(good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
    assertThat(store.read()).isNull()
    // Un byte in piu' in coda.
    store.file.writeBytes(good + byteArrayOf(0))
    assertThat(store.read()).isNull()
    // Un conteggio assurdo nell'intestazione non alloca gigabyte.
    store.file.writeBytes(good.copyOf().also { it[20] = 0x7F; it[21] = 0x7F; it[22] = 0x7F; it[23] = 0x7F })
    assertThat(store.read()).isNull()
    // E il file buono torna buono.
    store.file.writeBytes(good)
    assertSameIndex(store.read()!!.snapshot, index)
  }

  @Test
  fun unaScritturaFallitaLasciaIlFileDiPrima() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    val first = SyntheticLibrary(seed = 6, count = 500).index(seq = 1)
    assertThat(store.write(first)).isTrue()
    // Il temporaneo non si puo' creare (una cartella al suo posto): la scrittura fallisce, il file resta quello di prima.
    File(dir, "${SnapshotStore.FILE_NAME}.tmp").mkdirs()
    assertThat(store.write(SyntheticLibrary(seed = 7, count = 500).index(seq = 2))).isFalse()
    assertSameIndex(store.read()!!.snapshot, first)
    File(dir, "${SnapshotStore.FILE_NAME}.tmp").delete()
    // Un temporaneo rimasto da un crash non disturba ne' la lettura ne' la scrittura successiva.
    RandomAccessFile(File(dir, "${SnapshotStore.FILE_NAME}.tmp"), "rw").use { it.write(ByteArray(10)) }
    assertSameIndex(store.read()!!.snapshot, first)
    assertThat(store.write(SyntheticLibrary(seed = 8, count = 10).index(seq = 3))).isTrue()
    assertThat(store.read()!!.snapshot.seq).isEqualTo(3)
    assertThat(store.delete()).isTrue()
    assertThat(store.read()).isNull()
  }

  @Test
  fun deleteDiceFalsoSeIlFileNonSiTogliePerDavvero() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    // Un nome occupato da una cartella non vuota: nessuna attesa lo risolve. Un nascosto che resta in un file non tolto non passa per tolto.
    store.file.mkdirs()
    File(store.file, "dentro").writeText("1")

    assertThat(store.delete()).isFalse()
    assertThat(store.file.exists()).isTrue()
  }

  @Test
  fun unIndiceQualunqueSiCopiaPrimaDiScrivere() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    val library = SyntheticLibrary(seed = 9, count = 200)
    assertThat(store.write(library.snapshot)).isTrue()
    assertSameIndex(store.read()!!.snapshot, library.snapshot)
    val empty = TimelineIndexReader.fromRows(emptyList(), 0, emptyMap())
    assertThat(store.write(empty)).isTrue()
    assertThat(store.read()!!.snapshot.size).isEqualTo(0)
  }

  @Test
  fun laScritturaEAccorpataA5SecondiESubitoQuandoServe() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = SnapshotStore(dir, io = dispatcher)
    val writer = SnapshotWriter(store, backgroundScope, delayMs = 5_000)
    val base = SyntheticLibrary(seed = 10, count = 300).index(seq = 1)
    // Dieci cambiamenti in 3 secondi: una scrittura sola, con l'ultimo.
    for (k in 1..10) {
      writer.schedule(TimelinePatcher.withSeq(base, k.toLong()))
      advanceTimeBy(300)
    }
    runCurrent()
    assertThat(writer.writes).isEqualTo(0)
    advanceTimeBy(2_100)
    runCurrent()
    assertThat(writer.writes).isEqualTo(1)
    assertThat(store.read()!!.snapshot.seq).isEqualTo(10)
    // Un flusso continuo scrive comunque ogni 5 secondi (il ritardo non si sposta).
    for (k in 11..30) {
      writer.schedule(TimelinePatcher.withSeq(base, k.toLong()))
      advanceTimeBy(500)
      runCurrent()
    }
    assertThat(writer.writes).isEqualTo(3)
    // Subito, e una scrittura in attesa piu' vecchia non lo sovrascrive.
    writer.schedule(TimelinePatcher.withSeq(base, 31))
    assertThat(writer.writeNow(TimelinePatcher.withSeq(base, 32))).isTrue()
    advanceTimeBy(6_000)
    runCurrent()
    assertThat(store.read()!!.snapshot.seq).isEqualTo(32)
  }
}
