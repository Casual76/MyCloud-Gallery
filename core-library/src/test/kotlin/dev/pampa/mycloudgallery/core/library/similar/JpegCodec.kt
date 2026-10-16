package dev.pampa.mycloudgallery.core.library.similar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** JPEG vero (Skia nativo di Robolectric, libjpeg-turbo come sul telefono) per le prove: ricompressione a qualita' scelta e rilettura. */
internal object JpegCodec {

  fun toBitmap(src: Raster): Bitmap {
    val pixels = IntArray(src.width * src.height) { 0xFF000000.toInt() or src.pixel(it) }
    return Bitmap.createBitmap(pixels, src.width, src.height, Bitmap.Config.ARGB_8888)
  }

  fun fromBitmap(bitmap: Bitmap): Raster {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return Raster.of(bitmap.width, bitmap.height, pixels)
  }

  fun encode(src: Raster, quality: Int): ByteArray {
    val bitmap = toBitmap(src)
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Codifica non riuscita." }
    bitmap.recycle()
    return out.toByteArray()
  }

  /** WebP con perdita (come le miniature della Preparazione: S a 75, M a 78) e rilettura. */
  fun webpRoundTrip(src: Raster, quality: Int): Raster {
    val bitmap = toBitmap(src)
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) { "Codifica WebP non riuscita." }
    bitmap.recycle()
    return decode(out.toByteArray())
  }

  fun decode(bytes: ByteArray): Raster {
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Decodifica non riuscita." }
    return fromBitmap(bitmap).also { bitmap.recycle() }
  }

  /** Il giro completo: comprimere a [quality] e rileggere. */
  fun roundTrip(src: Raster, quality: Int): Raster = decode(encode(src, quality))
}
