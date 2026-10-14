package dev.pampa.mycloudgallery.core.imaging.viewer

import android.graphics.ImageDecoder
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Un file locale (il temporaneo dell'originale del NAS): la testa per riconoscere il formato, l'EXIF e la decodifica
 * leggono ciascuno per conto suo, e un JPEG da 200 MP non entra mai in memoria intero. Se il file e' sparito (la cache e'
 * stata svuotata) `openStream` lancia `FileNotFoundException`, che il viewer legge come "non c'e' piu'".
 */
class FileImageBytes(private val file: File) : ImageBytes {

  override val sizeBytes: Long? get() = file.length().takeIf { it > 0 }

  override fun openStream(): InputStream = FileInputStream(file)

  override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(file)
}

/**
 * Un blob della replica locale (l'HQ del NAS: WebP o JPEG di al massimo 1 MiB, D15). Si copia una volta in un array:
 * `ImageDecoder` non legge un `ByteBuffer` di sola lettura che non sia diretto, e il blob e' piccolo; la mappatura del
 * pacco resta intatta e libera.
 */
class BufferImageBytes(buffer: ByteBuffer) : ImageBytes {

  private val bytes: ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

  override val sizeBytes: Long get() = bytes.size.toLong()

  override fun openStream(): InputStream = ByteArrayInputStream(bytes)

  override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
}
