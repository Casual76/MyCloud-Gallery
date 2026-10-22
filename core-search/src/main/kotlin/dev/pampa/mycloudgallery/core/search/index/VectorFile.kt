package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32

// IL FILE DEI VETTORI (docs/fase6-progetto.md §2.4, D4, D24): `noBackupFilesDir/search/vectors-<spazio>-<generazione>.bin`, in sola
// aggiunta, float16, un CRC32 per record. E' l'unica copia dei vettori sul dispositivo; la seconda passata lo legge mappato. Un
// record rovinato (CRC, file troncato) non si usa mai: l'indice lo toglie e chiede di riaverlo (replica o ricalcolo).

/** Un vettore di un contenuto: [frame] 0 per le immagini, 0..8 per i fotogrammi di un video (D11), con il loro istante. */
internal class VectorRecord(
  val key: ContentKey,
  val recipe: Int,
  val frame: Int,
  val instantMs: Int?,
  val halves: ShortArray,
) {
  override fun toString(): String = "VectorRecord(r$recipe, f$frame)"
}

/** La tabella float16 -> float32 (65.536 valori, 256 KB): la seconda passata converte con un accesso a un array. */
internal object HalfTable {
  val VALUES: FloatArray = FloatArray(65_536) { Float16.toFloat(it.toShort()) }

  fun of(half: Short): Float = VALUES[half.toInt() and 0xffff]
}

/**
 * Il file in sola aggiunta di uno spazio, **little-endian** (l'ordine dei processori Android e x86: la seconda passata copia i
 * float16 dalla mappatura senza scambiare byte). Un record:
 * `MCGV` (4) · versione (1) · ricetta (1) · fotogramma (1) · bit (1: istante presente) · chiave (32) · istante ms (4) ·
 * `dim` float16 · CRC32 dei byte precedenti (4). Tutti i record di uno spazio hanno la stessa lunghezza ([recordSize]).
 *
 * Scritture serializzate da chi lo possiede ([LocalAiIndex]); letture concorrenti sulla mappatura (solo letture assolute).
 *
 * **Revisione A (R1, R21).** Il file si mappa a **finestre** di [windowRecords] record (al piu' 1 GiB l'una), non intero: nessun tetto di
 * 2 GiB, una lettura oltre la fine risponde null. Ogni finestra e' una mappatura immutabile pubblicata da sola (un riferimento
 * `@Volatile` a un array copiato a ogni cambio): chi legge prende la mappatura e ne guarda la **capacita'**, mai una dimensione scritta a
 * parte, quindi nessuna corsa fra mappa e dimensione.
 */
internal class VectorFile(val file: File, val dimension: Int, windowRecords: Int = DEFAULT_WINDOW_RECORDS) : Closeable {

  val recordSize: Int = HEADER + 2 * dimension + 4

  /** I byte di una finestra: un multiplo intero dei record, mai oltre `Int.MAX_VALUE`. */
  private val windowBytes: Long

  init {
    require(windowRecords > 0) { "Finestra vuota." }
    windowBytes = recordSize.toLong() * minOf(windowRecords.toLong(), MAX_WINDOW_BYTES / recordSize)
  }

  private val raf = RandomAccessFile(file, "rw")
  private val channel: FileChannel = raf.channel

  /** Le finestre mappate (null = non ancora); l'array non cambia mai: si sostituisce intero (R21). */
  @Volatile private var windows: Array<MappedByteBuffer?> = arrayOfNulls(0)

  val length: Long get() = channel.size()

  /**
   * Aggiunge [records] in fondo (a pezzi da al piu' [APPEND_CHUNK_BYTES], poi `force`): le posizioni di ognuno. Prima di ogni riga
   * dell'indice (D22).
   */
  fun append(records: List<VectorRecord>): LongArray {
    // Un record lasciato a meta' da una morte del processo resta in coda: si riparte dal primo multiplo intero.
    val base = (length / recordSize) * recordSize
    val perChunk = maxOf(1, APPEND_CHUNK_BYTES / recordSize)
    var at = base
    val crc = CRC32()
    for (chunk in records.chunked(perChunk)) {
      val buffer = ByteBuffer.allocate(recordSize * chunk.size).order(ByteOrder.LITTLE_ENDIAN)
      for (r in chunk) {
        require(r.halves.size == dimension) { "Dimensione diversa da quella dello spazio." }
        val start = buffer.position()
        buffer.putInt(MAGIC).put(VERSION).put(r.recipe.toByte()).put(r.frame.toByte()).put(if (r.instantMs != null) 1 else 0)
        buffer.put(r.key.bytes()).putInt(r.instantMs ?: -1)
        for (h in r.halves) buffer.putShort(h)
        crc.reset()
        crc.update(buffer.array(), start, buffer.position() - start)
        buffer.putInt(crc.value.toInt())
      }
      buffer.flip()
      while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }
    channel.force(false)
    return LongArray(records.size) { base + it.toLong() * recordSize }
  }

  /** Il record a [offset] se intero e con il CRC giusto, altrimenti null (rovinato o fuori dal file). */
  fun read(offset: Long): VectorRecord? {
    val buf = slice(offset) ?: return null
    if (!checksumOk(buf)) return null
    if (buf.getInt(0) != MAGIC || buf.get(4) != VERSION) return null
    val keyBytes = ByteArray(32)
    for (i in 0 until 32) keyBytes[i] = buf.get(8 + i)
    val hasInstant = buf.get(7).toInt() == 1
    val instant = buf.getInt(40)
    val halves = ShortArray(dimension) { buf.getShort(HEADER + 2 * it) }
    val key = runCatching { ContentKey(hex(keyBytes)) }.getOrNull() ?: return null
    return VectorRecord(key, buf.get(5).toInt() and 0xff, buf.get(6).toInt(), if (hasInstant) instant else null, halves)
  }

  /** Vero se il record a [offset] e' intero e il CRC torna (la seconda passata lo controlla una volta per processo). */
  fun verify(offset: Long): Boolean {
    val buf = slice(offset) ?: return false
    return buf.getInt(0) == MAGIC && checksumOk(buf)
  }

  /** Le prime [count] componenti del record a [offset] in [into] (senza controllo: chi chiama ha gia' verificato). */
  fun halves(offset: Long, into: ShortArray, count: Int = dimension) {
    val buf = slice(offset) ?: throw IllegalStateException("Record fuori dal file.")
    val dup = buf.duplicate()
    dup.position(HEADER)
    dup.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(into, 0, count)
  }

  /** Il prodotto scalare esatto (float32 sui valori float16) fra [query] e il record a [offset]. [scratch] di [dimension]. */
  fun dot(offset: Long, query: FloatArray, scratch: ShortArray): Float {
    halves(offset, scratch)
    val table = HalfTable.VALUES
    var sum = 0f
    for (i in 0 until dimension) sum += query[i] * table[scratch[i].toInt() and 0xffff]
    return sum
  }

  /** Tutte le posizioni dei record interi del file, in ordine (per la ricostruzione e la compattazione). */
  fun offsets(): LongSequence = LongSequence(length / recordSize, recordSize.toLong())

  override fun close() {
    windows = arrayOfNulls(0)
    runCatching { channel.close() }
    runCatching { raf.close() }
  }

  /** Il record a [offset] in sola lettura (posizione 0), o null se e' fuori dal file o non allineato. */
  private fun slice(offset: Long): ByteBuffer? {
    if (offset < 0 || offset % recordSize != 0L) return null
    val index = offset / windowBytes
    if (index > Int.MAX_VALUE) return null
    val w = index.toInt()
    val inWindow = (offset - index * windowBytes).toInt()
    var map = windows.getOrNull(w)
    // La capacita' della mappatura presa e' l'unica misura che conta (R21): mai una dimensione letta a parte.
    if (map == null || inWindow + recordSize > map.capacity()) {
      map = synchronized(this) { remap(w, inWindow) } ?: return null
    }
    val dup = map.duplicate()
    dup.position(inWindow)
    dup.limit(inWindow + recordSize)
    return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
  }

  /** Mappa (di nuovo) la finestra [w] fino alla fine del file, se il record a [inWindow] c'e'. Sotto il lucchetto. */
  private fun remap(w: Int, inWindow: Int): MappedByteBuffer? {
    windows.getOrNull(w)?.takeIf { inWindow + recordSize <= it.capacity() }?.let { return it }
    val start = w.toLong() * windowBytes
    val size = length
    if (start + inWindow + recordSize > size) return null
    val bytes = minOf(windowBytes, size - start)
    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, start, bytes)
    val next = if (w < windows.size) windows.copyOf() else windows.copyOf(w + 1)
    next[w] = mapped
    windows = next
    return mapped
  }

  private fun checksumOk(buf: ByteBuffer): Boolean {
    val body = ByteArray(recordSize - 4)
    val dup = buf.duplicate()
    dup.position(0)
    dup.get(body)
    val crc = CRC32()
    crc.update(body)
    return crc.value.toInt() == buf.getInt(recordSize - 4)
  }

  /** Le posizioni 0, step, 2 x step, ... ([count] valori), senza allocare un array. */
  class LongSequence(private val count: Long, private val step: Long) : Iterable<Long> {
    override fun iterator(): Iterator<Long> = object : Iterator<Long> {
      var i = 0L

      override fun hasNext(): Boolean = i < count

      override fun next(): Long = (i++) * step
    }
  }

  companion object {
    const val MAGIC: Int = 0x4D434756 // "MCGV"
    const val VERSION: Byte = 1
    const val HEADER: Int = 44

    /** Le finestre di serie: 262.144 record (circa 415 MB per lo spazio da 768). */
    const val DEFAULT_WINDOW_RECORDS: Int = 262_144

    /** Una finestra non supera 1 GiB (R1). */
    const val MAX_WINDOW_BYTES: Long = 1L shl 30

    /** I record si scrivono a pezzi da al piu' 4 MiB (niente buffer enormi per una consegna grande). */
    const val APPEND_CHUNK_BYTES: Int = 4 * 1024 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
      val out = CharArray(bytes.size * 2)
      for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        out[2 * i] = HEX[b ushr 4]
        out[2 * i + 1] = HEX[b and 0x0f]
      }
      return String(out)
    }
  }
}
