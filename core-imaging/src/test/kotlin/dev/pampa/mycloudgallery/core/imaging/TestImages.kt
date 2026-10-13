package dev.pampa.mycloudgallery.core.imaging

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Le immagini di prova, costruite dai test (nessun file nel repository): quattro quadranti di colori molto diversi,
 * cosi' dopo qualunque rotazione o specchio si vede dove e' finito ogni angolo. Servono Android (Bitmap): i test che
 * le usano girano su Robolectric con la grafica nativa (`GraphicsMode.NATIVE`), che esegue il vero Skia.
 */
internal object TestImages {

  const val TL: Int = Color.RED
  const val TR: Int = Color.GREEN
  const val BL: Int = Color.BLUE
  const val BR: Int = Color.YELLOW

  /** Quattro quadranti: in alto a sinistra rosso, a destra verde; in basso a sinistra blu, a destra giallo. */
  fun quadrants(width: Int = 32, height: Int = 16): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val left = x < width / 2
        val top = y < height / 2
        bitmap.setPixel(x, y, if (top) (if (left) TL else TR) else (if (left) BL else BR))
      }
    }
    return bitmap
  }

  fun jpeg(bitmap: Bitmap, quality: Int = 95): ByteArray = encode(bitmap, Bitmap.CompressFormat.JPEG, quality)

  fun png(bitmap: Bitmap): ByteArray = encode(bitmap, Bitmap.CompressFormat.PNG, 100)

  private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    check(bitmap.compress(format, quality, out)) { "Codifica non riuscita." }
    return out.toByteArray()
  }

  /** Un JPEG dei quattro quadranti (landscape 32x16 di default) con il tag EXIF `Orientation` = [orientation]. */
  fun jpegWithOrientation(orientation: Int?, width: Int = 32, height: Int = 16): ByteArray {
    val bytes = jpeg(quadrants(width, height))
    if (orientation == null) return bytes
    val file = File.createTempFile("orientation", ".jpg")
    try {
      file.writeBytes(bytes)
      val exif = ExifInterface(file)
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      exif.saveAttributes()
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  /**
   * Dove deve finire ciascun angolo dell'immagine sorgente (landscape) dopo l'orientamento EXIF: la tabella
   * canonica, scritta a mano e **indipendente** dal codice sotto prova. Chiavi: il valore EXIF; valori: per
   * TL, TR, BL, BR di partenza, il quadrante di arrivo ("TL", "TR", "BL", "BR").
   */
  val EXPECTED_CORNERS: Map<Int, List<String>> = mapOf(
    1 to listOf("TL", "TR", "BL", "BR"),
    2 to listOf("TR", "TL", "BR", "BL"),
    3 to listOf("BR", "BL", "TR", "TL"),
    4 to listOf("BL", "BR", "TL", "TR"),
    5 to listOf("TL", "BL", "TR", "BR"),
    6 to listOf("TR", "BR", "TL", "BL"),
    7 to listOf("BR", "TR", "BL", "TL"),
    8 to listOf("BL", "TL", "BR", "TR"),
  )

  private val SOURCE_COLORS = listOf(TL, TR, BL, BR)

  /**
   * Verifica che [bitmap] sia la sorgente a quattro quadranti con l'[orientation] EXIF applicato: il colore di ogni
   * quadrante di arrivo e' quello dell'angolo sorgente che la tabella manda li'. Ritorna il motivo dell'errore o null.
   */
  fun checkOrientation(bitmap: Bitmap, orientation: Int): String? {
    val destinations = EXPECTED_CORNERS.getValue(orientation)
    val w = bitmap.width
    val h = bitmap.height
    fun center(quadrant: String): Pair<Int, Int> = when (quadrant) {
      "TL" -> (w / 4) to (h / 4)
      "TR" -> (3 * w / 4) to (h / 4)
      "BL" -> (w / 4) to (3 * h / 4)
      else -> (3 * w / 4) to (3 * h / 4)
    }
    for ((index, quadrant) in destinations.withIndex()) {
      val (x, y) = center(quadrant)
      val actual = bitmap.getPixel(x, y)
      if (!near(actual, SOURCE_COLORS[index])) {
        return "orientamento $orientation: in $quadrant ($x,$y) c'e' ${Integer.toHexString(actual)}, " +
          "doveva esserci ${Integer.toHexString(SOURCE_COLORS[index])}"
      }
    }
    return null
  }

  fun near(actual: Int, expected: Int, tolerance: Int = 70): Boolean =
    abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
      abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
      abs(Color.blue(actual) - Color.blue(expected)) <= tolerance

  /**
   * Un GIF animato minimo: [frames] fotogrammi di [width] x [height] pixel con indici di una tavolozza di 4 colori
   * (rosso, verde, blu, bianco). Compressione LZW con codice di azzeramento ogni due pixel: sufficiente per i test.
   */
  fun gif(width: Int, height: Int, frames: List<IntArray>, loop: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream()
    fun u16(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
    }
    out.write("GIF89a".toByteArray())
    u16(width)
    u16(height)
    out.write(0x91)
    out.write(0)
    out.write(0)
    intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF).forEach {
      out.write((it shr 16) and 0xFF)
      out.write((it shr 8) and 0xFF)
      out.write(it and 0xFF)
    }
    if (loop) {
      out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
      out.write("NETSCAPE2.0".toByteArray())
      out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }
    for (frame in frames) {
      require(frame.size == width * height)
      out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00))
      out.write(0x2C)
      u16(0)
      u16(0)
      u16(width)
      u16(height)
      out.write(0x00)
      out.write(2)
      val codes = ArrayList<Int>()
      codes += 4
      for ((i, pixel) in frame.withIndex()) {
        codes += pixel
        if (i % 2 == 1 && i != frame.lastIndex) codes += 4
      }
      codes += 5
      val bits = ByteArrayOutputStream()
      var acc = 0
      var count = 0
      for (code in codes) {
        acc = acc or (code shl count)
        count += 3
        while (count >= 8) {
          bits.write(acc and 0xFF)
          acc = acc shr 8
          count -= 8
        }
      }
      if (count > 0) bits.write(acc and 0xFF)
      val data = bits.toByteArray()
      var offset = 0
      while (offset < data.size) {
        val len = minOf(255, data.size - offset)
        out.write(len)
        out.write(data, offset, len)
        offset += len
      }
      out.write(0)
    }
    out.write(0x3B)
    return out.toByteArray()
  }

  /** Un GIF di due fotogrammi 4x2. */
  fun animatedGif(): ByteArray =
    gif(4, 2, listOf(intArrayOf(0, 1, 2, 3, 0, 1, 2, 3), intArrayOf(3, 2, 1, 0, 3, 2, 1, 0)))

  /** Un WebP animato: solo la testa (RIFF + VP8X con il bit di animazione), quanto basta per riconoscerlo. */
  fun animatedWebpHeader(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    fun u24(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
      out.write((v shr 16) and 0xFF)
    }
    fun u32(v: Int) {
      u24(v)
      out.write((v shr 24) and 0xFF)
    }
    out.write("RIFF".toByteArray())
    u32(22)
    out.write("WEBP".toByteArray())
    out.write("VP8X".toByteArray())
    u32(10)
    out.write(0x12) // animazione (0x02) + alpha (0x10)
    u24(0)
    u24(width - 1)
    u24(height - 1)
    return out.toByteArray()
  }

  /** Un HEIC "finto": solo la casella `ftyp` (marchio `heic`) e, se [irot], un `irot` dentro i primi byte. */
  fun heicHeader(irot: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    fun u32(v: Int) {
      out.write((v shr 24) and 0xFF)
      out.write((v shr 16) and 0xFF)
      out.write((v shr 8) and 0xFF)
      out.write(v and 0xFF)
    }
    out.write(byteArrayOf(0, 0, 0, 24))
    out.write("ftyp".toByteArray())
    out.write("heic".toByteArray())
    u32(0)
    out.write("mif1".toByteArray())
    out.write("heic".toByteArray())
    out.write(byteArrayOf(0, 0, 0, 0x20))
    out.write("meta".toByteArray())
    if (irot) {
      out.write(byteArrayOf(0, 0, 0, 9))
      out.write("irot".toByteArray())
      out.write(1)
    }
    out.write(ByteArray(64))
    return out.toByteArray()
  }
}
