package dev.pampa.mycloudgallery.feature.integrations.receive

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

// Fase 5B (P11): i file di prova della ricezione. Piccoli, ma con la firma vera: il tipo di un file in arrivo si legge dai byte, non dal nome.

internal object ReceiveSamples {

  private fun filler(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0x7F or 0x80).toByte() }

  /** Un JPEG finto: la firma `FF D8 FF` e poi byte che non sono testo. */
  fun jpeg(size: Int = 4_000, seed: Int = 1): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + filler(size - 4, seed)

  fun png(size: Int = 4_000, seed: Int = 2): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + filler(size - 8, seed)

  /** Un MP4: `ftyp` con la marca [brand]. */
  fun mp4(size: Int = 4_000, brand: String = "isom", seed: Int = 3): ByteArray =
    byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + brand.toByteArray(Charsets.ISO_8859_1) +
      byteArrayOf(0, 0, 0x02, 0) + "isomiso2".toByteArray() + filler(size - 24, seed)

  /** Un HEIC: `ftyp` con la marca `heic`. */
  fun heic(size: Int = 4_000, seed: Int = 4): ByteArray = mp4(size, "heic", seed)

  /** Una pagina HTML con il nome `.jpg`: quello che un download fallito lascia. */
  fun fakeJpegThatIsHtml(): ByteArray = "<!DOCTYPE html><html><body>Accesso negato</body></html>".toByteArray()

  fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  /** Un flusso che dopo [good] byte (la sua testa e un po' di corpo) lancia come un permesso ritirato. */
  class RevokedAfter(private val bytes: ByteArray, private val good: Int) : InputStream() {
    private var at = 0

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (at >= bytes.size) return -1
      if (at >= good) throw SecurityException("Permesso ritirato.")
      val n = minOf(len, bytes.size - at, good - at)
      System.arraycopy(bytes, at, b, off, n)
      at += n
      return n
    }
  }

  /** Un flusso che consegna sempre byte e non finisce mai (un'infinita' dopo la testa di un JPEG). */
  class Endless(private val head: ByteArray) : InputStream() {
    var produced: Long = 0
      private set
    private var at = 0

    override fun read(): Int = if (read(ByteArray(1), 0, 1) < 0) -1 else 1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      var n = 0
      while (n < len) {
        b[off + n] = if (at < head.size) head[at++] else 0x55
        n++
      }
      produced += n
      return n
    }
  }
}

internal fun File.listNames(): List<String> = listFiles()?.map { it.name }?.sorted().orEmpty()
