package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// LA PRIMA PASSATA (D24): le prime 256 componenti **rinormalizzate** in int8 con una scala per vettore (260 byte a vettore, 26 MB
// per 100 mila). Il file `coarse-<spazio>.bin` e' solo una cache: si ricava dal file dei vettori e si ricostruisce se manca, se e'
// rovinato o se appartiene a un'altra generazione del file dei vettori.

internal object CoarseCodes {
  /** Quante componenti usa la prima passata (EmbeddingGemma e' addestrato a "matrioska": le prime contano di piu'). */
  const val DIMS: Int = 256

  /**
   * Il codice int8 delle prime [DIMS] componenti di [values] (rinormalizzate) in [codes] a partire da [at]; la scala per tornare
   * al coseno sulle 256 componenti. Un vettore nullo da' codice nullo e scala 0 (punteggio 0).
   */
  fun encode(values: FloatArray, codes: ByteArray, at: Int): Float {
    var norm = 0.0
    for (i in 0 until DIMS) norm += values[i].toDouble() * values[i]
    val n = sqrt(norm).toFloat()
    var max = 0f
    for (i in 0 until DIMS) max = maxOf(max, abs(values[i]))
    if (n == 0f || max == 0f) {
      codes.fill(0, at, at + DIMS)
      return 0f
    }
    val k = 127f / max
    for (i in 0 until DIMS) codes[at + i] = (values[i] * k).roundToInt().coerceIn(-127, 127).toByte()
    return max / 127f / n
  }

  /** Come [encode] dai bit float16. */
  fun encodeHalves(halves: ShortArray, codes: ByteArray, at: Int): Float =
    encode(FloatArray(DIMS) { HalfTable.of(halves[it]) }, codes, at)

  /** La query della prima passata: le prime 256 componenti in int8 ([out]) e la scala. */
  fun query(values: FloatArray, out: ByteArray): Float = encode(values, out, 0)
}

/** I codici della prima passata salvati su file, per posizione nel file dei vettori. */
internal class CoarseEntries(val offsets: LongArray, val scales: FloatArray, val codes: ByteArray) {
  val size: Int get() = offsets.size
}

internal object CoarseFile {
  private const val MAGIC = 0x4D434743 // "MCGC"
  private const val VERSION = 1

  /**
   * Scrive [entries] per la generazione [generation] del file dei vettori (temporaneo e rinomina atomica: mai mezzo file). E' solo una cache:
   * se non si riesce a scriverla il file di prima (o nessuno) resta e si ricostruisce; l'errore e' nel log di [StateFiles].
   */
  fun write(file: File, generation: Long, entries: CoarseEntries) {
    val crc = CRC32()
    try {
      StateFiles.write(file) { raw ->
        DataOutputStream(CheckedOutputStream(BufferedOutputStream(raw, 1 shl 16), crc)).use { out ->
          out.writeInt(MAGIC)
          out.writeInt(VERSION)
          out.writeInt(CoarseCodes.DIMS)
          out.writeLong(generation)
          out.writeInt(entries.size)
          for (i in 0 until entries.size) {
            out.writeLong(entries.offsets[i])
            out.writeFloat(entries.scales[i])
            out.write(entries.codes, i * CoarseCodes.DIMS, CoarseCodes.DIMS)
          }
          out.flush()
          out.writeInt(crc.value.toInt())
        }
      }
    } catch (_: IOException) {
      // Una cache: senza il file nuovo si ricostruisce (vale quella di prima solo se e' della stessa generazione).
    }
  }

  /** I codici salvati, o null se il file manca, e' rovinato o e' di un'altra generazione (allora si ricostruisce). */
  fun read(file: File, generation: Long): CoarseEntries? {
    if (!file.isFile) return null
    return runCatching {
      val crc = CRC32()
      DataInputStream(CheckedInputStream(BufferedInputStream(file.inputStream(), 1 shl 16), crc)).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != CoarseCodes.DIMS) return null
        if (input.readLong() != generation) return null
        val count = input.readInt()
        if (count < 0 || count.toLong() * (12 + CoarseCodes.DIMS) > file.length()) return null
        val offsets = LongArray(count)
        val scales = FloatArray(count)
        val codes = ByteArray(count * CoarseCodes.DIMS)
        for (i in 0 until count) {
          offsets[i] = input.readLong()
          scales[i] = input.readFloat()
          input.readFully(codes, i * CoarseCodes.DIMS, CoarseCodes.DIMS)
        }
        val expected = crc.value.toInt()
        if (input.readInt() != expected) return null
        CoarseEntries(offsets, scales, codes)
      }
    }.getOrNull()
  }
}
