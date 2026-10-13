package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.ContentResolver
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * I byte di un URI `content://media/...`, letti **a intervalli** con letture posizionali sul descrittore (nessuno
 * stream da scorrere: testa e coda di un video da 4 GB costano due letture). Si apre una volta e si chiude con
 * [close]: chi lo usa lo chiude subito dopo l'impronta o l'estrazione.
 */
class ContentUriMediaBytes private constructor(private val descriptor: ParcelFileDescriptor) : MediaBytes, AutoCloseable {

  private val input = FileInputStream(descriptor.fileDescriptor)
  private val channel: FileChannel = input.channel

  override val size: Long = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= size || range.last < 0) return ByteArray(0)
    val from = maxOf(range.first, 0L)
    val to = minOf(range.last, size - 1)
    return readAt(from, (to - from + 1).toInt())
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= this@ContentUriMediaBytes.size) return -1
      val n = channel.read(ByteBuffer.wrap(buffer, offset, size), position)
      return if (n <= 0) -1 else n
    }

    override fun getSize(): Long = this@ContentUriMediaBytes.size

    override fun close() = this@ContentUriMediaBytes.close()
  }

  override fun close() {
    try {
      channel.close()
    } catch (_: IOException) {
      // ignorata: il descrittore si chiude sotto
    }
    descriptor.close()
  }

  private fun readAt(position: Long, length: Int): ByteArray {
    val buffer = ByteBuffer.allocate(length)
    var at = position
    while (buffer.hasRemaining()) {
      val n = channel.read(buffer, at)
      if (n < 0) break
      at += n
    }
    return if (buffer.position() == length) buffer.array() else buffer.array().copyOf(buffer.position())
  }

  companion object {
    /** Apre [uri] in sola lettura. `FileNotFoundException` se la riga non c'e' piu'. */
    fun open(resolver: ContentResolver, uri: Uri): ContentUriMediaBytes {
      val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Nessun descrittore.")
      return try {
        ContentUriMediaBytes(descriptor)
      } catch (e: IOException) {
        descriptor.close()
        throw e
      }
    }
  }
}
