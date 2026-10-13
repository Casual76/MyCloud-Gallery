package dev.pampa.mycloudgallery.core.imaging.decode

import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedKind
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec

/**
 * Il formato di un'immagine riconosciuto dai primi byte (non ci si fida del nome ne' del `mime` del MediaStore: un
 * `.jpg` che e' un HEIC esiste, e l'orientamento e i tasselli dipendono dal formato vero).
 */
enum class ImageFormat {
  JPEG,
  PNG,
  GIF,
  WEBP,

  /** HEIC/HEIF (`ftyp` con i marchi `heic`, `mif1`...). */
  HEIF,
  AVIF,

  /** TIFF e i RAW che ne derivano: DNG, CR2, NEF, ARW... Android li decodifica solo come DNG. */
  TIFF_RAW,
  BMP,
  UNKNOWN,
}

/** Riconoscimento del formato e della struttura dai primi byte: funzioni pure, provate su JVM. */
object ImageSniffer {

  /** I byte da leggere dalla testa del file per riconoscere il formato e cercare `irot`/`imir` in un HEIC. */
  const val HEAD_BYTES: Int = 256 * 1024

  fun sniff(head: ByteArray, length: Int = head.size): ImageFormat {
    val n = minOf(length, head.size)
    fun at(i: Int): Int = if (i < n) head[i].toInt() and 0xFF else -1
    fun ascii(offset: Int, text: String): Boolean = text.indices.all { at(offset + it) == text[it].code }
    return when {
      at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageFormat.JPEG
      at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> ImageFormat.PNG
      ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.GIF
      ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
      ascii(4, "ftyp") -> brand(head, n)
      (at(0) == 'I'.code && at(1) == 'I'.code && at(2) == 0x2A && at(3) == 0x00) ||
        (at(0) == 'M'.code && at(1) == 'M'.code && at(2) == 0x00 && at(3) == 0x2A) -> ImageFormat.TIFF_RAW
      at(0) == 'B'.code && at(1) == 'M'.code -> ImageFormat.BMP
      else -> ImageFormat.UNKNOWN
    }
  }

  private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")
  private val AVIF_BRANDS = setOf("avif", "avis")

  /** Il marchio principale e quelli compatibili dopo `ftyp`: basta uno dei marchi noti. */
  private fun brand(head: ByteArray, n: Int): ImageFormat {
    if (n < 12) return ImageFormat.UNKNOWN
    val size = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
      ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
    val end = if (size in 16..n) size else minOf(n, 64)
    val brands = ArrayList<String>()
    var i = 8
    while (i + 4 <= end) {
      // Il marchio principale (8..12), la versione (12..16) non e' un marchio, poi i compatibili (16..).
      if (i != 12) brands += String(head, i, 4, Charsets.ISO_8859_1)
      i += 4
    }
    return when {
      brands.any { it in AVIF_BRANDS } -> ImageFormat.AVIF
      brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
      else -> ImageFormat.UNKNOWN
    }
  }

  /**
   * Vero se un HEIC dichiara una rotazione o uno specchio nel contenitore (`irot`, `imir` nella casella `ipco`): il
   * decodificatore di sistema li applica da solo, quindi non va riapplicato anche il tag EXIF (E-V3: un HEIC non si
   * ruota due volte). Cerca i quattro caratteri nella testa del file: la casella `meta` precede i dati.
   */
  fun hasContainerTransform(head: ByteArray, length: Int = head.size): Boolean {
    val n = minOf(length, head.size)
    return containsAscii(head, n, "irot") || containsAscii(head, n, "imir")
  }

  private fun containsAscii(bytes: ByteArray, n: Int, text: String): Boolean {
    val pattern = text.toByteArray(Charsets.ISO_8859_1)
    var i = 0
    while (i + pattern.size <= n) {
      var j = 0
      while (j < pattern.size && bytes[i + j] == pattern[j]) j++
      if (j == pattern.size) return true
      i++
    }
    return false
  }

  /**
   * Se i primi byte descrivono un'immagine animata, come si anima. GIF: con l'estensione di ripetizione
   * `NETSCAPE2.0` oppure con piu' di un fotogramma nella testa. WebP: il bit "animazione" della casella `VP8X`.
   * Le dimensioni sono quelle della tela, senza decodificare niente.
   */
  fun animatedSpec(head: ByteArray, length: Int = head.size): AnimatedSpec? {
    val n = minOf(length, head.size)
    return when (sniff(head, n)) {
      ImageFormat.GIF -> gifSpec(head, n)
      ImageFormat.WEBP -> webpSpec(head, n)
      else -> null
    }
  }

  private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

  private fun gifSpec(b: ByteArray, n: Int): AnimatedSpec? {
    if (n < 13) return null
    val width = u16(b, 6)
    val height = u16(b, 8)
    if (width <= 0 || height <= 0) return null
    return if (gifFrameCount(b, n) >= 2 || containsAscii(b, n, "NETSCAPE2.0") || containsAscii(b, n, "ANIMEXTS1.0")) {
      AnimatedSpec(AnimatedKind.GIF, width, height)
    } else {
      null
    }
  }

  /** Conta i fotogrammi (fino a due) camminando sui blocchi dalla tavolozza globale in poi, entro i byte letti. */
  private fun gifFrameCount(b: ByteArray, n: Int): Int {
    val packed = b[10].toInt() and 0xFF
    var i = 13
    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1))
    var frames = 0
    while (i < n && frames < 2) {
      when (b[i].toInt() and 0xFF) {
        0x21 -> { // estensione: etichetta e sotto-blocchi
          i += 2
          while (i < n) {
            val len = b[i].toInt() and 0xFF
            i += 1 + len
            if (len == 0) break
          }
        }
        0x2C -> { // descrittore di immagine: 9 byte, tavolozza locale, dimensione LZW, sotto-blocchi
          if (i + 10 > n) return frames
          val local = b[i + 9].toInt() and 0xFF
          i += 10
          if (local and 0x80 != 0) i += 3 * (1 shl ((local and 0x07) + 1))
          i += 1
          while (i < n) {
            val len = b[i].toInt() and 0xFF
            i += 1 + len
            if (len == 0) break
          }
          frames++
        }
        0x3B -> return frames
        else -> return frames
      }
    }
    return frames
  }

  private fun webpSpec(b: ByteArray, n: Int): AnimatedSpec? {
    if (n < 30 || String(b, 12, 4, Charsets.ISO_8859_1) != "VP8X") return null
    val flags = b[20].toInt() and 0xFF
    if (flags and 0x02 == 0) return null
    val width = 1 + ((b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or ((b[26].toInt() and 0xFF) shl 16))
    val height = 1 + ((b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or ((b[29].toInt() and 0xFF) shl 16))
    return AnimatedSpec(AnimatedKind.WEBP, width, height)
  }
}
