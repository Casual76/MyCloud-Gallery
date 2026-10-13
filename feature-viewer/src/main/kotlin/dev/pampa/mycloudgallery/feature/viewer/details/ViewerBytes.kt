package dev.pampa.mycloudgallery.feature.viewer.details

import android.content.ContentResolver
import android.media.MediaDataSource
import android.net.Uri
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.withContext

/**
 * Come il viewer arriva ai **byte** di un media, per due cose sole e solo su richiesta: i dettagli EXIF del pannello
 * informazioni e la ricerca della parte video di una motion photo. Le immagini e i tasselli passano da
 * `ViewerImageSource` e `ZoomableMedia`, non da qui.
 *
 * Un'interfaccia: nel telefono e' [ContentResolverViewerBytes]; nei test e' un `ByteArray`; dalla Fase 4 il NAS
 * (SMB, a intervalli) si aggiunge senza cambiare il viewer.
 */
fun interface ViewerBytes {

  /** I byte di [ref], o null se non si possono aprire adesso. */
  suspend fun open(ref: MediaRef): MediaBytes?
}

/** Nessun accesso ai byte: niente EXIF e niente motion photo. */
object NoViewerBytes : ViewerBytes {
  override suspend fun open(ref: MediaRef): MediaBytes? = null
}

/**
 * I byte di un media del telefono, letti dal suo URI `content://media/...` con il `ContentResolver`. Ogni lettura apre
 * il file, legge l'intervallo e lo richiude: nessun descrittore resta aperto fra una lettura e l'altra, e un file
 * cancellato nel frattempo da' un errore di I/O, non un crash.
 *
 * @param uriOf l'URI della riga (`PhoneMediaIntents.uriOf`).
 */
class ContentResolverViewerBytes(
  private val resolver: ContentResolver,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : ViewerBytes {

  override suspend fun open(ref: MediaRef): MediaBytes? {
    val phone = ref as? MediaRef.Phone ?: return null
    val uri = uriOf(phone)
    val size = withContext(dispatchers.io) {
      try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
      } catch (_: IOException) {
        null
      } catch (_: SecurityException) {
        null
      }
    } ?: return null
    return UriMediaBytes(resolver, uri, size, dispatchers)
  }
}

private class UriMediaBytes(
  private val resolver: ContentResolver,
  private val uri: Uri,
  override val size: Long,
  private val dispatchers: DispatcherProvider,
) : MediaBytes {

  override suspend fun read(range: LongRange): ByteArray = withContext(dispatchers.io) {
    if (range.isEmpty() || range.first >= size) return@withContext ByteArray(0)
    val first = range.first.coerceAtLeast(0L)
    val last = range.last.coerceAtMost(size - 1)
    val length = (last - first + 1).toInt()
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("File non apribile.")
    descriptor.use { pfd ->
      FileInputStream(pfd.fileDescriptor).use { input ->
        val channel = input.channel
        channel.position(first)
        val buffer = java.nio.ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
          if (channel.read(buffer) < 0) break
        }
        buffer.array().copyOf(buffer.position())
      }
    }
  }

  override fun dataSource(): MediaDataSource {
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("File non apribile.")
    val stream = FileInputStream(descriptor.fileDescriptor)
    return object : MediaDataSource() {
      override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= this@UriMediaBytes.size) return -1
        stream.channel.position(position)
        return stream.read(buffer, offset, size)
      }

      override fun getSize(): Long = this@UriMediaBytes.size

      override fun close() {
        stream.close()
        descriptor.close()
      }
    }
  }
}
