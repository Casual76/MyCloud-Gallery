package dev.pampa.mycloudgallery.core.prepare.photo

import android.graphics.ImageDecoder
import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Lettura dell'originale UNA volta sola, con l'hash completo che segue la lettura (§2.4: foto fino a 64 MiB in memoria,
// oltre in un temporaneo). Qui solo byte e hash; la decodifica e' in PhotoPreparer.

/** Il NAS (o chi ha dato i byte) non risponde: il media si riprova senza contare il tentativo. */
internal class OriginUnreachableException(cause: Throwable?) : IOException("Origine non raggiungibile.", cause)

/** Il file e' cambiato (o e' piu' corto del previsto) durante la lettura: si salta, lo sistema la prossima scansione. */
internal class SourceChangedException : IOException("La sorgente e' cambiata durante la lettura.")

/** Un problema del disco locale (temporaneo pieno o illeggibile): si riprova al giro dopo. */
internal class LocalIoException(cause: Throwable?) : IOException("Errore del disco locale.", cause)

internal class InMemoryPhoto(val bytes: ByteArray, val sha256: Sha256)

internal object PhotoIo {

  /** Il file intero in un array, a blocchi di [chunkBytes], con lo SHA-256 calcolato durante la lettura. */
  suspend fun readAll(source: MediaBytes, chunkBytes: Int): InMemoryPhoto {
    val size = source.size
    require(size in 1..Int.MAX_VALUE.toLong()) { "Dimensione non valida per la memoria." }
    val out = ByteArray(size.toInt())
    val digest = MessageDigest.getInstance("SHA-256")
    var position = 0L
    while (position < size) {
      coroutineContext.ensureActive()
      val want = minOf(chunkBytes.toLong(), size - position)
      val part = readChunk(source, position, want)
      System.arraycopy(part, 0, out, position.toInt(), part.size)
      digest.update(part)
      position += want
    }
    return InMemoryPhoto(out, Sha256(Hex.of(digest.digest())))
  }

  /** Copia [source] in [target] a blocchi, con lo SHA-256 calcolato durante la copia. */
  suspend fun spill(source: MediaBytes, target: File, chunkBytes: Int): Sha256 {
    val size = source.size
    val digest = MessageDigest.getInstance("SHA-256")
    try {
      withContext(Dispatchers.IO) {
        FileOutputStream(target).use { out ->
          var position = 0L
          while (position < size) {
            coroutineContext.ensureActive()
            val want = minOf(chunkBytes.toLong(), size - position)
            val part = readChunk(source, position, want)
            digest.update(part)
            try {
              out.write(part)
            } catch (e: IOException) {
              throw LocalIoException(e)
            }
            position += want
          }
        }
      }
    } catch (e: OriginUnreachableException) {
      throw e
    } catch (e: SourceChangedException) {
      throw e
    } catch (e: LocalIoException) {
      throw e
    } catch (e: IOException) {
      throw LocalIoException(e)
    }
    return Sha256(Hex.of(digest.digest()))
  }

  /** Lo SHA-256 e l'impronta rapida di un file locale, in una sola lettura. */
  suspend fun hashFile(file: File, chunkBytes: Int): Pair<Sha256, QuickHash> {
    try {
      return withContext(Dispatchers.IO) {
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
          val buffer = ByteArray(chunkBytes)
          var total = 0L
          while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
          }
          if (total != size) throw SourceChangedException()
        }
        Sha256(Hex.of(digest.digest())) to quickHashOf(file)
      }
    } catch (e: SourceChangedException) {
      throw e
    } catch (e: IOException) {
      throw LocalIoException(e)
    }
  }

  /** `qh1` di un file locale: due letture da 64 KiB (o il file intero fino a 128 KiB). */
  fun quickHashOf(file: File): QuickHash = RandomAccessFile(file, "r").use { raf ->
    val size = raf.length()
    val regions = QuickHashAlgorithm.regions(size)
    if (regions.size <= 1) {
      QuickHashAlgorithm.compute(size, readAt(raf, 0, size.toInt()), ByteArray(0))
    } else {
      val chunk = QuickHashAlgorithm.CHUNK_BYTES
      QuickHashAlgorithm.compute(size, readAt(raf, 0, chunk), readAt(raf, size - chunk, chunk))
    }
  }

  private fun readAt(raf: RandomAccessFile, position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    raf.seek(position)
    raf.readFully(out)
    return out
  }

  /** Un blocco dall'origine: un errore di I/O e' "non raggiungibile", un blocco corto e' "il file e' cambiato". */
  private suspend fun readChunk(source: MediaBytes, position: Long, want: Long): ByteArray {
    val part = try {
      source.read(position until position + want)
    } catch (e: IOException) {
      throw OriginUnreachableException(e)
    }
    if (part.size.toLong() != want) throw SourceChangedException()
    return part
  }
}

internal object Hex {
  private const val DIGITS = "0123456789abcdef"

  fun of(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
      val v = bytes[i].toInt() and 0xFF
      out[i * 2] = DIGITS[v ushr 4]
      out[i * 2 + 1] = DIGITS[v and 0x0F]
    }
    return String(out)
  }
}

/** Un file locale per `ImageProbe` e `ImageDecoder`: la decodifica legge a flusso, mai il file intero in memoria. */
internal class FileImageBytes(private val file: File) : ImageBytes {
  override val sizeBytes: Long get() = file.length()

  override fun openStream(): InputStream = FileInputStream(file)

  override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(file)
}

/** Un file locale come [MediaBytes] (l'indice di un video locale si legge come quello di un video del NAS). */
internal class FileMediaBytes(private val file: File) : MediaBytes {
  override val size: Long get() = file.length()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty()) return ByteArray(0)
    return withContext(Dispatchers.IO) {
      try {
        RandomAccessFile(file, "r").use { raf ->
          val total = raf.length()
          val from = maxOf(range.first, 0L)
          if (from >= total) return@use ByteArray(0)
          val to = minOf(range.last, total - 1)
          val out = ByteArray((to - from + 1).toInt())
          raf.seek(from)
          raf.readFully(out)
          out
        }
      } catch (e: IOException) {
        throw LocalIoException(e)
      }
    }
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    private val raf by lazy { RandomAccessFile(file, "r") }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= raf.length()) return -1
      raf.seek(position)
      return raf.read(buffer, offset, size)
    }

    override fun getSize(): Long = file.length()

    override fun close() {
      runCatching { raf.close() }
    }
  }
}
