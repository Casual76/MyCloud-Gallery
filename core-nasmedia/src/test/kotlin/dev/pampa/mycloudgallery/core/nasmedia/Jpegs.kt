package dev.pampa.mycloudgallery.core.nasmedia

import java.io.ByteArrayOutputStream

/**
 * JPEG scritti a mano per i test (nessuna decodifica: bastano i marcatori che la scansione legge). Un JPEG "vero" per
 * quanto serve a `ExifThumbnail`: SOI, APP1 `Exif` con IFD0 e IFD1 (miniatura), SOF con le dimensioni, SOS, dati, EOI.
 */
internal object Jpegs {

  /** Una miniatura JPEG minima [width]x[height]: SOI, DQT finta, SOF0, EOI. */
  fun thumbnail(width: Int, height: Int, padding: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 0x00, 0x00))
    out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08))
    out.write(u16(height, false))
    out.write(u16(width, false))
    out.write(byteArrayOf(0x01, 0x01, 0x11, 0x00))
    if (padding > 0) out.write(ByteArray(padding) { 0x11 })
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  /**
   * Un JPEG con l'EXIF che contiene [thumbnail] nell'IFD1 (se non null), l'orientamento [orientation] nell'IFD0 e le
   * dimensioni [width]x[height] nel SOF. [little] sceglie l'ordine dei byte del TIFF. Il corpo e' lungo [bodyBytes].
   */
  fun withExif(
    width: Int,
    height: Int,
    thumbnail: ByteArray?,
    orientation: Int = 1,
    little: Boolean = false,
    bodyBytes: Int = 200_000,
    compression: Int = 6,
  ): ByteArray {
    val tiff = tiff(thumbnail, orientation, little, compression)
    val exifPayload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
    out.write(u16(exifPayload.size + 2, false))
    out.write(exifPayload)
    out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08))
    out.write(u16(height, false))
    out.write(u16(width, false))
    out.write(byteArrayOf(0x01, 0x01, 0x11, 0x00))
    out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
    out.write(ByteArray(bodyBytes) { (it % 200 + 1).toByte() }) // dati "compressi" senza FF
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  /** Il TIFF dell'EXIF: IFD0 con Orientation, IFD1 con compressione, offset e lunghezza della miniatura. */
  fun tiff(thumbnail: ByteArray?, orientation: Int, little: Boolean, compression: Int = 6): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(if (little) "II".toByteArray() else "MM".toByteArray())
    out.write(u16(42, little))
    out.write(u32(8, little))
    // IFD0 a 8: una voce (Orientation) + puntatore a IFD1.
    out.write(u16(1, little))
    out.write(entry(0x0112, 3, 1, orientation, little, short = true))
    val ifd1 = 8 + 2 + 12 + 4
    out.write(u32(if (thumbnail == null) 0 else ifd1.toLong(), little))
    if (thumbnail != null) {
      val thumbOffset = ifd1 + 2 + 3 * 12 + 4
      out.write(u16(3, little))
      out.write(entry(0x0103, 3, 1, compression, little, short = true))
      out.write(entry(0x0201, 4, 1, thumbOffset, little, short = false))
      out.write(entry(0x0202, 4, 1, thumbnail.size, little, short = false))
      out.write(u32(0, little))
      out.write(thumbnail)
    }
    return out.toByteArray()
  }

  private fun entry(tag: Int, type: Int, count: Int, value: Int, little: Boolean, short: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(u16(tag, little))
    out.write(u16(type, little))
    out.write(u32(count.toLong(), little))
    if (short) {
      out.write(u16(value, little))
      out.write(byteArrayOf(0, 0))
    } else {
      out.write(u32(value.toLong(), little))
    }
    return out.toByteArray()
  }

  fun u16(v: Int, little: Boolean): ByteArray =
    if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())

  fun u32(v: Long, little: Boolean): ByteArray =
    if (little) {
      byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    } else {
      byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }
}
