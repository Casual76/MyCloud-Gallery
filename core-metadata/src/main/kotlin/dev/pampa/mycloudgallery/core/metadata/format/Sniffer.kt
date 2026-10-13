package dev.pampa.mycloudgallery.core.metadata.format

/** Il contenitore di un file, riconosciuto dai primi byte. */
internal enum class ContainerType(val isImage: Boolean) {
  JPEG(true),
  PNG(true),
  GIF(true),
  WEBP(true),
  BMP(true),
  TIFF(true),
  HEIF(true),
  MP4(false),
  MATROSKA(false),
  AVI(false),
  UNKNOWN(false),
}

/** Il risultato del riconoscimento: il contenitore e il tipo MIME che i byte dichiarano (non quello del nome). */
internal class Sniffed(val type: ContainerType, val mime: String?)

/**
 * Riconosce un file dai suoi primi byte (E-M12: un `.jpg` che dentro e' testo non e' un media). Non guarda mai il
 * nome ne' il tipo dichiarato dal MediaStore, tranne che per scegliere fra i video Matroska e WebM, che i byte
 * iniziali non distinguono.
 */
internal object Sniffer {

  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  /** Le marche `ftyp` (principale o compatibili) che dicono "immagine HEIF/AVIF". */
  private val IMAGE_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1", "mif2", "avif", "avis", "heif")

  /** I primi riquadri che un MP4 o MOV senza `ftyp` (vecchi QuickTime) puo' avere. */
  private val BARE_ISO_BOXES = setOf("moov", "mdat", "wide", "free", "skip", "pnot")

  /** I tipi MIME che sappiamo riconoscere dai byte: se il file dichiara uno di questi e i byte non tornano, non e' un media. */
  val SNIFFABLE_MIMES: Set<String> = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/x-ms-bmp", "image/tiff", "image/x-adobe-dng",
    "image/heic", "image/heif", "image/avif", "video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/x-matroska",
    "video/webm", "video/x-msvideo", "video/avi", "video/mpeg4",
  )

  fun sniff(head: ByteArray, hintMime: String?): Sniffed {
    if (head.size >= 3 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 && head.u8(2) == 0xFF) return Sniffed(ContainerType.JPEG, "image/jpeg")
    if (head.hasAt(0, PNG_SIGNATURE)) return Sniffed(ContainerType.PNG, "image/png")
    if (head.hasAscii(0, "GIF87a") || head.hasAscii(0, "GIF89a")) return Sniffed(ContainerType.GIF, "image/gif")
    if (head.hasAscii(0, "RIFF") && head.hasAscii(8, "WEBP")) return Sniffed(ContainerType.WEBP, "image/webp")
    if (head.hasAscii(0, "RIFF") && head.hasAscii(8, "AVI ")) return Sniffed(ContainerType.AVI, "video/x-msvideo")
    if (isTiff(head)) return Sniffed(ContainerType.TIFF, "image/tiff")
    if (isBmp(head)) return Sniffed(ContainerType.BMP, "image/bmp")
    if (head.size >= 4 && head.u8(0) == 0x1A && head.u8(1) == 0x45 && head.u8(2) == 0xDF && head.u8(3) == 0xA3) {
      val webm = hintMime?.equals("video/webm", ignoreCase = true) == true
      return Sniffed(ContainerType.MATROSKA, if (webm) "video/webm" else "video/x-matroska")
    }
    sniffIsoBmff(head)?.let { return it }
    return Sniffed(ContainerType.UNKNOWN, null)
  }

  private fun isTiff(head: ByteArray): Boolean =
    head.size >= 8 && ((head.u8(0) == 'I'.code && head.u8(1) == 'I'.code && head.u8(2) == 0x2A && head.u8(3) == 0) ||
      (head.u8(0) == 'M'.code && head.u8(1) == 'M'.code && head.u8(2) == 0 && head.u8(3) == 0x2A))

  /** `BM` da solo e' troppo poco (un testo che comincia per "BM"): si controlla anche la dimensione dell'intestazione DIB. */
  private fun isBmp(head: ByteArray): Boolean {
    if (head.size < 26 || head.u8(0) != 'B'.code || head.u8(1) != 'M'.code) return false
    val dib = head.u32le(14)
    return dib == 12L || dib == 40L || dib == 52L || dib == 56L || dib == 64L || dib == 108L || dib == 124L
  }

  private fun sniffIsoBmff(head: ByteArray): Sniffed? {
    if (head.size < 12) return null
    val type = head.fourcc(4)
    if (type == "ftyp") {
      val boxSize = head.u32be(0)
      val end = if (boxSize in 16..head.size.toLong()) boxSize.toInt() else head.size
      val major = head.fourcc(8)
      val brands = mutableListOf(major)
      var i = 16
      while (i + 4 <= end) {
        brands += head.fourcc(i)
        i += 4
      }
      if (brands.any { it.trim() in IMAGE_BRANDS }) {
        val mime = when {
          brands.any { it == "avif" || it == "avis" } -> "image/avif"
          brands.any { it.startsWith("hei") || it.startsWith("hev") } -> "image/heic"
          else -> "image/heif"
        }
        return Sniffed(ContainerType.HEIF, mime)
      }
      val mime = when {
        major == "qt  " -> "video/quicktime"
        major.startsWith("3gp") -> "video/3gpp"
        major.startsWith("3g2") -> "video/3gpp2"
        else -> "video/mp4"
      }
      return Sniffed(ContainerType.MP4, mime)
    }
    if (type in BARE_ISO_BOXES && head.u32be(0) >= 8) return Sniffed(ContainerType.MP4, "video/mp4")
    return null
  }
}
