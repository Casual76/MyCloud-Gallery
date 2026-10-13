package dev.pampa.mycloudgallery.core.library.store

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexBuilder
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * L'istantanea su file dell'indice (D2, §1.2): `noBackupFilesDir/replica/timeline.idx`, per l'avvio a freddo sotto il
 * secondo (si legge in pochi millisecondi, prima di aprire il database). Formato **`MCGT1`**: "MCGT" + versione 1,
 * colonne binarie little-endian, CRC32 in coda. Un file di un'altra versione, troncato o corrotto si scarta
 * ([read] da' null) e l'indice si ricostruisce dal catalogo: mai un'eccezione verso la Libreria.
 *
 * **Scrittura atomica**: si scrive `timeline.idx.tmp`, si forza su disco, poi si rinomina sopra il vecchio. Un crash a
 * meta' lascia il file precedente intero. L'accorpamento (al piu' una scrittura ogni 5 s) e' di [SnapshotWriter].
 *
 * **Nascosti (E-H1)**: il file contiene solo cio' che l'indice contiene, e l'indice non contiene mai un nascosto.
 * [provisional] marca un file scritto in anticipo (un "togli subito" prima di nascondere): al prossimo avvio si mostra
 * ma si ricostruisce comunque, perche' il suo `seq` non garantisce piu' l'uguaglianza con il catalogo.
 */
class SnapshotStore(
  dir: File,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  val file: File = File(dir, FILE_NAME)
  private val tmp: File = File(dir, "$FILE_NAME.tmp")
  private val lock = Mutex()

  /** Cio' che si legge dal file: l'indice e se era provvisorio. */
  class Loaded(val snapshot: ColumnarTimelineSnapshot, val provisional: Boolean)

  /** L'istantanea, o null se non c'e' o non e' valida (versione, troncata, CRC). Mai un'eccezione. */
  suspend fun read(): Loaded? = withContext(io) {
    lock.withLock {
      if (!file.isFile) return@withLock null
      try {
        BufferedInputStream(FileInputStream(file), BUFFER).use { decode(it, file.length()) }
      } catch (e: IOException) {
        GalleryLog.w(TAG, e) { "Istantanea della timeline illeggibile: si ricostruisce." }
        null
      } catch (e: RuntimeException) {
        GalleryLog.w(TAG, e) { "Istantanea della timeline non valida: si ricostruisce." }
        null
      }
    }
  }

  /**
   * Scrive [snapshot] in modo atomico. Falso se non e' stato possibile (disco pieno, E-P3): il file precedente resta
   * intero e il temporaneo si cancella. Un indice che non e' a colonne si copia prima (test, Libreria sintetica).
   */
  suspend fun write(snapshot: TimelineSnapshot, provisional: Boolean = false): Boolean = withContext(io) {
    lock.withLock {
      val s = snapshot as? ColumnarTimelineSnapshot ?: columnarCopy(snapshot)
      try {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
          encode(s, provisional, out)
          out.flush()
          out.fd.sync()
        }
        move(tmp, file)
        true
      } catch (e: IOException) {
        GalleryLog.w(TAG, e) { "Istantanea della timeline non scritta." }
        tmp.delete()
        false
      }
    }
  }

  /** Cancella l'istantanea (E-H1: quando non si puo' riscriverla senza un elemento appena nascosto). */
  suspend fun delete(): Boolean = withContext(io) {
    lock.withLock {
      tmp.delete()
      !file.exists() || file.delete()
    }
  }

  private fun move(from: File, to: File) {
    try {
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  companion object {
    const val FILE_NAME: String = "timeline.idx"

    /** La cartella dell'istantanea: `noBackupFilesDir/replica/` (fuori dal backup del telefono, come il database). */
    fun directory(context: Context): File = File(context.noBackupFilesDir, "replica")

    /** "MCGT" e la versione del formato: un cambiamento delle colonne alza la versione, e i file vecchi si scartano. */
    const val FORMAT_VERSION: Int = 1
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'T'.code.toByte())

    private const val FLAG_PROVISIONAL = 1
    private const val BUFFER = 64 * 1024
    private const val TAG = "Library"

    /** Le stesse colonne in un indice a colonne (senza riferimenti ai byte: non stanno nel contratto). */
    fun columnarCopy(s: TimelineSnapshot): ColumnarTimelineSnapshot {
      val builder = TimelineIndexBuilder(s.size.coerceAtLeast(16))
      val id = LongArray(2)
      for (i in 0 until s.size) {
        val uuid = java.util.UUID.fromString(s.idAt(i).value)
        id[0] = uuid.mostSignificantBits
        id[1] = uuid.leastSignificantBits
        builder.add(
          msb = id[0], lsb = id[1], sortTimeMs = s.sortTimeAt(i), localDay = s.localDayAt(i), kind = s.kindAt(i),
          source = s.sourceAt(i), flags = s.flagsAt(i), aspectMilli = s.aspectMilliAt(i), durationMs = s.durationMsAt(i),
          placeId = s.placeAt(i), thumbHash = s.thumbHashAt(i)?.bytes(), groupSize = s.groupSizeAt(i),
        )
      }
      return builder.build(s.seq)
    }

    // --- Formato ---------------------------------------------------------------------------------------------------

    internal fun encode(s: ColumnarTimelineSnapshot, provisional: Boolean, out: OutputStream) {
      val w = ColumnWriter(out)
      w.bytes(MAGIC)
      w.int(FORMAT_VERSION)
      w.int(if (provisional) FLAG_PROVISIONAL else 0)
      w.long(s.seq)
      w.int(s.size)
      w.int(s.thumbBytes.size)
      w.int(s.groupIndex.size)
      w.strings(MediaKind.entries.map { it.code })
      w.strings(MediaSource.entries.map { it.code })
      w.strings(s.volumes.asList())
      w.strings(s.mimes.asList())
      val n = s.size
      w.longs(s.idMsb, n)
      w.longs(s.idLsb, n)
      w.longs(s.sortTime, n)
      w.ints(s.localDay, n)
      w.bytes(s.kind, n)
      w.bytes(s.source, n)
      w.ints(s.flags, n)
      w.shorts(s.aspect, n)
      w.ints(s.duration, n)
      w.ints(s.place, n)
      w.shorts(s.groupSize, n)
      w.ints(s.thumbOffsets, n + 1)
      w.bytes(s.thumbBytes, s.thumbBytes.size)
      w.longs(s.refId, n)
      w.bytes(s.refVolume, n)
      w.bytes(s.refMime, n)
      w.ints(s.groupIndex, s.groupIndex.size)
      w.longs(s.groupMsb, s.groupMsb.size)
      w.longs(s.groupLsb, s.groupLsb.size)
      w.trailer()
    }

    /** Legge il formato; null se non e' un `MCGT1` valido. [length] serve a rifiutare un'intestazione assurda. */
    internal fun decode(input: InputStream, length: Long): Loaded? {
      val r = ColumnReader(input)
      val magic = ByteArray(4)
      r.bytes(magic, 4)
      if (!magic.contentEquals(MAGIC)) return null
      if (r.int() != FORMAT_VERSION) return null
      val flags = r.int()
      val seq = r.long()
      val n = r.int()
      val thumbLength = r.int()
      val groups = r.int()
      // Ogni elemento occupa almeno 50 byte nel file: un conteggio che non ci sta e' un file corrotto.
      if (n < 0 || thumbLength < 0 || groups < 0 || groups > n || n.toLong() * 50 > length || thumbLength > length) return null
      val kindMap = r.strings().map { code -> MediaKind.entries.firstOrNull { it.code == code }?.ordinal ?: return null }
      val sourceMap = r.strings().map { code -> MediaSource.entries.firstOrNull { it.code == code }?.ordinal ?: return null }
      val volumes = r.strings().toTypedArray()
      val mimes = r.strings().toTypedArray()
      val idMsb = r.longs(n)
      val idLsb = r.longs(n)
      val sortTime = r.longs(n)
      val localDay = r.ints(n)
      val kind = ByteArray(n).also { r.bytes(it, n) }
      val source = ByteArray(n).also { r.bytes(it, n) }
      val cellFlags = r.ints(n)
      val aspect = r.shorts(n)
      val duration = r.ints(n)
      val place = r.ints(n)
      val groupSize = r.shorts(n)
      val thumbOffsets = r.ints(n + 1)
      val thumbBytes = ByteArray(thumbLength).also { r.bytes(it, thumbLength) }
      val refId = r.longs(n)
      val refVolume = ByteArray(n).also { r.bytes(it, n) }
      val refMime = ByteArray(n).also { r.bytes(it, n) }
      val groupIndex = r.ints(groups)
      val groupMsb = r.longs(groups)
      val groupLsb = r.longs(groups)
      if (!r.checkTrailer()) return null
      // I codici del file diventano gli ordinali di questa versione dell'app.
      for (i in 0 until n) {
        val k = kind[i].toInt() and 0xFF
        val so = source[i].toInt() and 0xFF
        if (k >= kindMap.size || so >= sourceMap.size) return null
        kind[i] = kindMap[k].toByte()
        source[i] = sourceMap[so].toByte()
      }
      // Coerenza interna (il CRC protegge dai byte rovinati, non da uno scrittore sbagliato).
      if (thumbOffsets[0] != 0 || thumbOffsets[n] != thumbLength) return null
      for (i in 0 until n) {
        if (thumbOffsets[i + 1] < thumbOffsets[i]) return null
        if ((refVolume[i].toInt() and 0xFF) > volumes.size || (refMime[i].toInt() and 0xFF) > mimes.size) return null
      }
      for (g in 0 until groups) {
        if (groupIndex[g] !in 0 until n || (g > 0 && groupIndex[g] <= groupIndex[g - 1])) return null
      }
      val snapshot = ColumnarTimelineSnapshot(
        seq, n, idMsb, idLsb, sortTime, localDay, kind, source, cellFlags, aspect, duration, place, groupSize,
        thumbOffsets, thumbBytes, refId, refVolume, refMime, volumes, mimes, groupIndex, groupMsb, groupLsb,
      )
      return Loaded(snapshot, provisional = flags and FLAG_PROVISIONAL != 0)
    }
  }
}

/** Scrive colonne little-endian a blocchi da 64 KiB, aggiornando il CRC. */
private class ColumnWriter(private val out: OutputStream) {
  private val crc = CRC32()
  private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

  private fun emit(len: Int) {
    crc.update(buffer.array(), 0, len)
    out.write(buffer.array(), 0, len)
  }

  fun bytes(a: ByteArray) = bytes(a, a.size)

  fun bytes(a: ByteArray, n: Int) {
    crc.update(a, 0, n)
    out.write(a, 0, n)
  }

  fun int(v: Int) {
    buffer.clear()
    buffer.putInt(v)
    emit(4)
  }

  fun long(v: Long) {
    buffer.clear()
    buffer.putLong(v)
    emit(8)
  }

  fun strings(values: List<String>) {
    int(values.size)
    for (v in values) {
      val b = v.toByteArray(Charsets.UTF_8)
      int(b.size)
      bytes(b)
    }
  }

  fun longs(a: LongArray, n: Int) {
    var off = 0
    while (off < n) {
      val len = minOf(n - off, buffer.capacity() / 8)
      buffer.clear()
      buffer.asLongBuffer().put(a, off, len)
      emit(len * 8)
      off += len
    }
  }

  fun ints(a: IntArray, n: Int) {
    var off = 0
    while (off < n) {
      val len = minOf(n - off, buffer.capacity() / 4)
      buffer.clear()
      buffer.asIntBuffer().put(a, off, len)
      emit(len * 4)
      off += len
    }
  }

  fun shorts(a: ShortArray, n: Int) {
    var off = 0
    while (off < n) {
      val len = minOf(n - off, buffer.capacity() / 2)
      buffer.clear()
      buffer.asShortBuffer().put(a, off, len)
      emit(len * 2)
      off += len
    }
  }

  fun trailer() {
    val value = crc.value.toInt()
    buffer.clear()
    buffer.putInt(value)
    out.write(buffer.array(), 0, 4)
  }
}

/** Legge colonne little-endian a blocchi da 64 KiB, aggiornando il CRC. Un file troppo corto e' un [EOFException]. */
private class ColumnReader(private val input: InputStream) {
  private val crc = CRC32()
  private val chunk = ByteArray(64 * 1024)
  private val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

  private fun fill(len: Int) {
    var off = 0
    while (off < len) {
      val read = input.read(chunk, off, len - off)
      if (read < 0) throw EOFException()
      off += read
    }
    crc.update(chunk, 0, len)
    buffer.clear()
  }

  fun bytes(a: ByteArray, n: Int) {
    var off = 0
    while (off < n) {
      val read = input.read(a, off, n - off)
      if (read < 0) throw EOFException()
      off += read
    }
    crc.update(a, 0, n)
  }

  fun int(): Int {
    fill(4)
    return buffer.getInt(0)
  }

  fun long(): Long {
    fill(8)
    return buffer.getLong(0)
  }

  fun strings(): List<String> {
    val count = int()
    if (count !in 0..MAX_STRINGS) throw IOException("Tabella non valida.")
    return List(count) {
      val len = int()
      if (len !in 0..MAX_STRING_BYTES) throw IOException("Stringa non valida.")
      val b = ByteArray(len)
      bytes(b, len)
      String(b, Charsets.UTF_8)
    }
  }

  fun longs(n: Int): LongArray {
    val a = LongArray(n)
    var off = 0
    while (off < n) {
      val len = minOf(n - off, chunk.size / 8)
      fill(len * 8)
      buffer.asLongBuffer().get(a, off, len)
      off += len
    }
    return a
  }

  fun ints(n: Int): IntArray {
    val a = IntArray(n)
    var off = 0
    while (off < n) {
      val len = minOf(n - off, chunk.size / 4)
      fill(len * 4)
      buffer.asIntBuffer().get(a, off, len)
      off += len
    }
    return a
  }

  fun shorts(n: Int): ShortArray {
    val a = ShortArray(n)
    var off = 0
    while (off < n) {
      val len = minOf(n - off, chunk.size / 2)
      fill(len * 2)
      buffer.asShortBuffer().get(a, off, len)
      off += len
    }
    return a
  }

  /** Il CRC in coda e nessun byte dopo. */
  fun checkTrailer(): Boolean {
    val expected = crc.value.toInt()
    val tail = ByteArray(4)
    var off = 0
    while (off < 4) {
      val read = input.read(tail, off, 4 - off)
      if (read < 0) return false
      off += read
    }
    if (input.read() != -1) return false
    return ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == expected
  }

  private companion object {
    const val MAX_STRINGS = 255
    const val MAX_STRING_BYTES = 4096
  }
}
