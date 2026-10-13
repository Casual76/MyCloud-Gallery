package dev.pampa.mycloudgallery.core.imaging.decode

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * I byte di un media aperto, visti da chi li decodifica. Ogni chiamata a [openStream] e [decoderSource] da' un
 * accesso **nuovo** (la testa per riconoscere il formato, l'EXIF e la decodifica leggono ciascuno per conto suo), e
 * [close] rilascia cio' che il media tiene aperto. Le implementazioni non leggono il file intero: un JPEG da 200 MP
 * pesa 60 MB e non entra mai in memoria (D6, E-M1).
 */
interface ImageBytes : AutoCloseable {

  /** Il peso in byte, se noto. */
  val sizeBytes: Long?

  /** Un flusso nuovo dall'inizio. Lancia `FileNotFoundException` se il media non c'e' piu'. */
  fun openStream(): InputStream

  /** Una sorgente nuova per `ImageDecoder` (a flusso, mai tutto il file in memoria). */
  fun decoderSource(): ImageDecoder.Source

  override fun close() = Unit
}

/** Un media in memoria: i test, le fixture e le sorgenti finte. Non per i file veri. */
class ByteArrayImageBytes(private val bytes: ByteArray) : ImageBytes {
  override val sizeBytes: Long get() = bytes.size.toLong()

  override fun openStream(): InputStream = ByteArrayInputStream(bytes)

  override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
}

/**
 * Un media del `ContentResolver` (il MediaStore del telefono): il flusso e la sorgente leggono dal file descriptor
 * del provider, quindi sia la testa sia la decodifica toccano solo i byte che servono.
 */
class ContentImageBytes(
  private val resolver: ContentResolver,
  private val uri: Uri,
) : ImageBytes {

  override val sizeBytes: Long? by lazy {
    try {
      resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
    } catch (_: java.io.IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  override fun openStream(): InputStream =
    resolver.openInputStream(uri) ?: throw FileNotFoundException("Il provider non ha aperto il media.")

  override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(resolver, uri)
}
