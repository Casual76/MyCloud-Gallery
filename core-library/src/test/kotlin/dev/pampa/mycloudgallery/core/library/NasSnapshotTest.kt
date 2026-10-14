package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * L'istantanea `MCGT2` (Fase 4, D18): andata e ritorno con i riferimenti al NAS e la chiave di contenuto, `MCGT1`
 * scartato (e l'indice si ricostruisce da solo), un file incoerente scartato senza eccezioni.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasSnapshotTest {

  private val dir: File = Files.createTempDirectory("mcgt2").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun andataERitornoConIRiferimentiAlNasELaPresenza() = runTest {
    val library = SyntheticLibrary(seed = 6, count = 8_000, placeIds = intArrayOf(7, 8), mix = NasMix.BUDGET)
    val index = library.index(seq = 777)
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    assertThat(store.write(index)).isTrue()
    val loaded = store.read()!!
    assertThat(loaded.provisional).isFalse()
    assertSameIndex(loaded.snapshot, index)
    // Versione 2 nell'intestazione ("MCGT" + int little-endian).
    val bytes = store.file.readBytes()
    assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("MCGT")
    assertThat(bytes[4].toInt()).isEqualTo(2)
    assertThat(SnapshotStore.FORMAT_VERSION).isEqualTo(2)
    // Le celle solo-NAS portano posizione e chiave di contenuto; quelle del telefono il loro `_ID`.
    val loadedColumns = loaded.snapshot as ColumnarTimelineSnapshot
    var nasOnly = 0
    for (i in 0 until loadedColumns.size) {
      loadedColumns.nasRefAt(i)?.let {
        nasOnly++
        assertThat(it).isEqualTo(library.nasRefAt(i))
      }
    }
    assertThat(nasOnly).isGreaterThan(4_000)
    assertThat(loadedColumns.nasCount).isEqualTo(index.nasCount)
  }

  @Test
  fun unFileMcgt1DellaFase3SiScartaESiRicostruisce() = runTest {
    val library = SyntheticLibrary(seed = 2, count = 1_500)
    val v1 = legacyMcgt1(library.index(seq = 5))
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    store.file.parentFile.mkdirs()
    store.file.writeBytes(v1)
    // Il file e' un MCGT1 valido (lo dimostra il suo CRC e la sua struttura): ma non e' il formato di adesso.
    assertThat(v1[4].toInt()).isEqualTo(1)
    assertThat(store.read()).isNull()

    // Il repository parte da zero: nessun indice dal file, ricostruzione dal catalogo, e il file diventa MCGT2.
    val source = ModelSource()
    for (row in library.indexRows()) source.media[row.mediaId] = ModelSource.Media(row.copy(groupId = null))
    val dispatcher = StandardTestDispatcher(testScheduler)
    val repo = CatalogTimelineRepository(source, store, backgroundScope, worker = dispatcher, writeDelayMs = 5_000)
    repo.start()
    advanceTimeBy(60_000)
    runCurrent()
    assertThat(repo.rebuilds).isEqualTo(1)
    assertSameIndex(repo.snapshot.value!!, source.expected())
    assertThat(store.file.readBytes()[4].toInt()).isEqualTo(2)
    assertThat(store.read()).isNotNull()
  }

  @Test
  fun unFileMcgt2IncoerenteSiScartaSenzaEccezioni() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    val good = SyntheticLibrary(seed = 4, count = 200, mix = NasMix.BUDGET).index(seq = 3)
    assertThat(store.write(good)).isTrue()
    assertThat(store.read()).isNotNull()

    fun withColumns(refId: LongArray = good.refIdColumn(), refVolume: ByteArray = good.refVolumeColumn(), contentKey: LongArray = good.contentKeyColumn()) =
      ColumnarTimelineSnapshot(
        good.seq, good.size, good.idMsbColumn(), good.idLsbColumn(), good.sortTimeColumn(), good.localDayColumn(), good.kindColumn(),
        good.sourceColumn(), good.flagsColumn(), good.aspectColumn(), good.durationColumn(), good.placeColumn(), good.groupSizeColumn(),
        good.thumbOffsetsColumn(), good.thumbBytesColumn(), refId, refVolume, good.refMimeColumn(), contentKey, good.volumesTable(),
        good.mimesTable(), good.groupIndexColumn(), good.groupMsbColumn(), good.groupLsbColumn(),
      )

    val nasCell = (0 until good.size).first { good.nasRefAt(it) != null }
    // Un riferimento al NAS senza posizione: incoerente.
    assertThat(store.write(withColumns(refId = good.refIdColumn().also { it[nasCell] = 0L }))).isTrue()
    assertThat(store.read()).isNull()
    // Una cella con un volume del telefono e una chiave di contenuto: incoerente (la chiave e' solo del NAS).
    val phoneCell = (0 until good.size).first { good.refAt(it) != null }
    assertThat(store.write(withColumns(contentKey = good.contentKeyColumn().also { it[phoneCell] = 99L }))).isTrue()
    assertThat(store.read()).isNull()
    // Un volume oltre la tabella: incoerente.
    assertThat(store.write(withColumns(refVolume = good.refVolumeColumn().also { it[phoneCell] = 200.toByte() }))).isTrue()
    assertThat(store.read()).isNull()
    // Un file valido torna a leggersi.
    assertThat(store.write(good)).isTrue()
    assertSameIndex(store.read()!!.snapshot, good)
  }

  @Test
  fun unIndiceVuotoESenzaNasFannoAndataERitorno() = runTest {
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    val empty = TimelineIndexReader.fromRows(emptyList(), 4, emptyMap())
    assertThat(store.write(empty)).isTrue()
    assertThat(store.read()!!.snapshot.size).isEqualTo(0)
    val phoneOnly = SyntheticLibrary(seed = 8, count = 500).index(seq = 6)
    assertThat(store.write(phoneOnly)).isTrue()
    assertSameIndex(store.read()!!.snapshot, phoneOnly)
  }

  // --- Le colonne, per costruire file incoerenti (internal: i test sono dello stesso modulo) --------------------------

  private fun ColumnarTimelineSnapshot.refIdColumn() = refId.copyOf()
  private fun ColumnarTimelineSnapshot.refVolumeColumn() = refVolume.copyOf()
  private fun ColumnarTimelineSnapshot.contentKeyColumn() = contentKey.copyOf()
  private fun ColumnarTimelineSnapshot.refMimeColumn() = refMime.copyOf()
  private fun ColumnarTimelineSnapshot.idMsbColumn() = idMsb.copyOf()
  private fun ColumnarTimelineSnapshot.idLsbColumn() = idLsb.copyOf()
  private fun ColumnarTimelineSnapshot.sortTimeColumn() = sortTime.copyOf()
  private fun ColumnarTimelineSnapshot.localDayColumn() = localDay.copyOf()
  private fun ColumnarTimelineSnapshot.kindColumn() = kind.copyOf()
  private fun ColumnarTimelineSnapshot.sourceColumn() = source.copyOf()
  private fun ColumnarTimelineSnapshot.flagsColumn() = flags.copyOf()
  private fun ColumnarTimelineSnapshot.aspectColumn() = aspect.copyOf()
  private fun ColumnarTimelineSnapshot.durationColumn() = duration.copyOf()
  private fun ColumnarTimelineSnapshot.placeColumn() = place.copyOf()
  private fun ColumnarTimelineSnapshot.groupSizeColumn() = groupSize.copyOf()
  private fun ColumnarTimelineSnapshot.thumbOffsetsColumn() = thumbOffsets.copyOf()
  private fun ColumnarTimelineSnapshot.thumbBytesColumn() = thumbBytes.copyOf()
  private fun ColumnarTimelineSnapshot.volumesTable() = volumes.copyOf()
  private fun ColumnarTimelineSnapshot.mimesTable() = mimes.copyOf()
  private fun ColumnarTimelineSnapshot.groupIndexColumn() = groupIndex.copyOf()
  private fun ColumnarTimelineSnapshot.groupMsbColumn() = groupMsb.copyOf()
  private fun ColumnarTimelineSnapshot.groupLsbColumn() = groupLsb.copyOf()

  /**
   * Un file `MCGT1` come lo scriveva la Fase 3 (senza la colonna della chiave di contenuto): il layout, scritto qui a
   * mano e indipendente dal codice sotto prova. Per un indice senza riferimenti al NAS.
   */
  private fun legacyMcgt1(s: ColumnarTimelineSnapshot): ByteArray {
    val out = ByteArrayOutputStream()
    val crc = CRC32()
    fun put(bytes: ByteArray, len: Int = bytes.size) {
      crc.update(bytes, 0, len)
      out.write(bytes, 0, len)
    }
    fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun int(v: Int) = put(buf(4).putInt(v).array())
    fun long(v: Long) = put(buf(8).putLong(v).array())
    fun strings(values: List<String>) {
      int(values.size)
      for (v in values) {
        val b = v.toByteArray(Charsets.UTF_8)
        int(b.size)
        put(b)
      }
    }
    fun longs(a: LongArray) = a.forEach { long(it) }
    fun ints(a: IntArray) = a.forEach { int(it) }
    put("MCGT".toByteArray(Charsets.US_ASCII))
    int(1)
    int(0)
    long(s.seq)
    int(s.size)
    int(s.thumbBytes.size)
    int(s.groupIndex.size)
    strings(MediaKind.entries.map { it.code })
    strings(MediaSource.entries.map { it.code })
    strings(s.volumes.asList())
    strings(s.mimes.asList())
    longs(s.idMsb)
    longs(s.idLsb)
    longs(s.sortTime)
    ints(s.localDay)
    put(s.kind)
    put(s.source)
    ints(s.flags)
    s.aspect.forEach { put(buf(2).putShort(it).array()) }
    ints(s.duration)
    ints(s.place)
    s.groupSize.forEach { put(buf(2).putShort(it).array()) }
    ints(s.thumbOffsets)
    put(s.thumbBytes)
    longs(s.refId)
    put(s.refVolume)
    put(s.refMime)
    ints(s.groupIndex)
    longs(s.groupMsb)
    longs(s.groupLsb)
    out.write(buf(4).putInt(crc.value.toInt()).array())
    return out.toByteArray()
  }
}
