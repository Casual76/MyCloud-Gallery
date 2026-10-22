package dev.pampa.mycloudgallery.core.ai.cutout

import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Un lettore di PNG **indipendente** dal codificatore (solo per i test): riconosce la firma, controlla il CRC di ogni blocco, rilegge
 * `IHDR`, concatena gli `IDAT`, decomprime e toglie i cinque filtri. Supporta solo RGBA a 8 bit senza interlacciamento, che e' tutto
 * cio' che scrive `PngEncoder`. Sta nei test perche' la JVM di `testDebugUnitTest` non ha `javax.imageio`.
 */
internal class PngReader private constructor(
  val width: Int,
  val height: Int,
  val chunkTypes: List<String>,
  private val rgba: ByteArray,
) {

  /** Il pixel come ARGB (i colori sono quelli scritti, anche dove l'alfa e' 0). */
  fun getRGB(x: Int, y: Int): Int {
    val i = (y * width + x) * 4
    return ((rgba[i + 3].toInt() and 0xff) shl 24) or ((rgba[i].toInt() and 0xff) shl 16) or ((rgba[i + 1].toInt() and 0xff) shl 8) or (rgba[i + 2].toInt() and 0xff)
  }

  companion object {
    fun read(png: ByteArray): PngReader {
      val buffer = ByteBuffer.wrap(png)
      val signature = ByteArray(8)
      buffer.get(signature)
      check(signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) { "Firma PNG sbagliata" }
      val types = ArrayList<String>()
      var width = 0
      var height = 0
      val idat = java.io.ByteArrayOutputStream()
      while (buffer.hasRemaining()) {
        val length = buffer.int
        val typeBytes = ByteArray(4)
        buffer.get(typeBytes)
        val data = ByteArray(length)
        buffer.get(data)
        val crc = buffer.int
        val check = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        check(crc == check) { "CRC sbagliato" }
        val type = String(typeBytes, Charsets.US_ASCII)
        types += type
        when (type) {
          "IHDR" -> {
            val header = ByteBuffer.wrap(data)
            width = header.int
            height = header.int
            check(data[8].toInt() == 8 && data[9].toInt() == 6 && data[10].toInt() == 0 && data[11].toInt() == 0 && data[12].toInt() == 0) { "Formato non supportato" }
          }
          "IDAT" -> idat.write(data)
        }
      }
      val stride = width * 4
      val raw = ByteArray((stride + 1) * height)
      val inflater = Inflater()
      inflater.setInput(idat.toByteArray())
      var filled = 0
      while (filled < raw.size) {
        val n = inflater.inflate(raw, filled, raw.size - filled)
        check(n > 0 || !inflater.finished()) { "Dati compressi troncati" }
        filled += n
      }
      inflater.end()
      val out = ByteArray(stride * height)
      for (y in 0 until height) {
        val filter = raw[y * (stride + 1)].toInt()
        for (i in 0 until stride) {
          val x = raw[y * (stride + 1) + 1 + i].toInt() and 0xff
          val left = if (i >= 4) out[y * stride + i - 4].toInt() and 0xff else 0
          val up = if (y > 0) out[(y - 1) * stride + i].toInt() and 0xff else 0
          val upLeft = if (y > 0 && i >= 4) out[(y - 1) * stride + i - 4].toInt() and 0xff else 0
          val value = when (filter) {
            0 -> x
            1 -> x + left
            2 -> x + up
            3 -> x + (left + up) / 2
            4 -> x + paeth(left, up, upLeft)
            else -> error("Filtro sconosciuto $filter")
          }
          out[y * stride + i] = value.toByte()
        }
      }
      return PngReader(width, height, types, out)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
      val p = a + b - c
      val pa = kotlin.math.abs(p - a)
      val pb = kotlin.math.abs(p - b)
      val pc = kotlin.math.abs(p - c)
      return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
  }
}
