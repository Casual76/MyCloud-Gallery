package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Le immagini e i finti delle prove della catena delle miniature del NAS. */
internal object NasTestSupport {

  /** Un'immagine di un colore solo [w] x [h]. */
  fun solid(color: Int, w: Int = 64, h: Int = 48): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return bitmap
  }

  fun jpeg(color: Int, w: Int = 64, h: Int = 48): ByteArray = TestImages.jpeg(solid(color, w, h))

  fun webp(color: Int, w: Int = 64, h: Int = 48): ByteArray {
    val out = ByteArrayOutputStream()
    check(solid(color, w, h).compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "Codifica WebP non riuscita." }
    return out.toByteArray()
  }

  /** La chiave di contenuto di un numero (64 cifre esadecimali): la sua forma ridotta (`short`) e' il numero stesso. */
  fun key(n: Int): ContentKey = ContentKey("%016x".format(n.toLong()) + "0".repeat(48))

  fun ref(n: Int, locationId: Long = 100L + n, mime: String? = "image/jpeg"): MediaRef.Nas =
    MediaRef.Nas(locationId = locationId, contentKey = key(n).short, mime = mime)

  /** Il colore del pixel centrale di [bitmap]. */
  fun center(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

  fun near(actual: Int, expected: Int): Boolean = TestImages.near(actual, expected)

  /**
   * Un PNG che **dichiara** [w] x [h] pixel nell'intestazione (con il CRC giusto) ma non ha i dati: quello che farebbe un
   * file ostile per far allocare una bitmap enorme.
   */
  fun hostilePng(w: Int, h: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
    fun chunk(type: String, data: ByteArray) {
      out.write(ByteBuffer.allocate(4).putInt(data.size).array())
      val typed = type.toByteArray(Charsets.US_ASCII) + data
      out.write(typed)
      val crc = CRC32().apply { update(typed) }
      out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
    chunk("IHDR", ihdr)
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
  }

  /** Un decodificatore finto: la bitmap ha il colore dato dal primo byte (1 rosso, 2 verde, 3 blu, altro grigio). */
  class ColorByFirstByte(val calls: MutableList<Int> = mutableListOf()) : BlobDecoder {
    override fun decode(bytes: ByteBuffer, px: Int, cancel: CancellationSignal): Bitmap? {
      cancel.throwIfCanceled()
      if (!bytes.hasRemaining()) return null
      val tag = bytes.get(bytes.position()).toInt()
      calls += tag
      if (tag == 0) return null
      val color = when (tag) {
        1 -> Color.RED
        2 -> Color.GREEN
        3 -> Color.BLUE
        else -> Color.GRAY
      }
      return solid(color, 8, 6)
    }
  }
}
