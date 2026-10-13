package dev.pampa.mycloudgallery.core.metadata.extract

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import java.io.InputStream
import kotlin.math.max

/** Un'anteprima piccola in pixel RGBA non premoltiplicati, riga per riga: l'ingresso di `ThumbHashCodec.encode`. */
class Preview(val width: Int, val height: Int, val rgba: ByteArray) {
  init {
    require(width > 0 && height > 0 && rgba.size >= width * height * 4) { "Anteprima non valida." }
  }
}

/**
 * Decodifica un'immagine (JPEG, PNG, GIF, WebP, BMP, HEIC: quello che il sistema sa leggere) in un'anteprima di al massimo
 * [maxSide] pixel per lato. Blocca il thread: l'estrattore la esegue fuori dal thread principale. Null se i byte non si
 * decodificano (per un JPEG, PNG... questo e' "file rovinato", E-M10). Su JVM si sostituisce con un fake.
 *
 * Due strade, perche' costano diversamente (vedi la regola in `DefaultMetadataExtractor`): una miniatura incorporata e' gia' in
 * memoria ([decode] su byte); un'immagine intera si legge **in streaming** ([decode] su [MediaBytes]), senza mai tenerne i byte in
 * un unico array.
 */
interface PreviewDecoder {

  /** Decodifica byte gia' in memoria (una miniatura Exif, l'anteprima di un DNG). */
  fun decode(data: ByteArray, maxSide: Int): Preview?

  /**
   * Decodifica un file leggendolo in streaming dalla sua [MediaBytes] (con `MediaBytes.dataSource()`): la memoria usata e' quella
   * del decodificatore e della bitmap campionata, non quella del file. Il file si legge comunque per intero (il flusso JPEG o PNG
   * non si salta): e' il ripiego, non la strada normale.
   */
  fun decode(bytes: MediaBytes, maxSide: Int): Preview?
}

/**
 * Il decodificatore vero: `BitmapFactory` con campionamento (mai la bitmap intera in memoria). Per un file intero legge da un
 * [InputStream] sopra la `MediaDataSource` di [MediaBytes] (due aperture: la prima per le sole dimensioni, che legge pochi KiB).
 */
class AndroidPreviewDecoder : PreviewDecoder {

  override fun decode(data: ByteArray, maxSide: Int): Preview? {
    if (data.isEmpty() || maxSide < 1) return null
    return decodeWith(
      maxSide,
      bounds = { options -> BitmapFactory.decodeByteArray(data, 0, data.size, options) },
      pixels = { options -> BitmapFactory.decodeByteArray(data, 0, data.size, options) },
    )
  }

  override fun decode(bytes: MediaBytes, maxSide: Int): Preview? {
    if (bytes.size <= 0L || maxSide < 1) return null
    return decodeWith(
      maxSide,
      bounds = { options -> MediaDataSourceInputStream(bytes.dataSource()).use { BitmapFactory.decodeStream(it, null, options) } },
      pixels = { options -> MediaDataSourceInputStream(bytes.dataSource()).use { BitmapFactory.decodeStream(it, null, options) } },
    )
  }

  private inline fun decodeWith(
    maxSide: Int,
    bounds: (BitmapFactory.Options) -> Bitmap?,
    pixels: (BitmapFactory.Options) -> Bitmap?,
  ): Preview? {
    return try {
      val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      bounds(probe)
      if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
      var sample = 1
      while (max(probe.outWidth, probe.outHeight) / (sample * 2) >= maxSide) sample *= 2
      val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val decoded = pixels(options) ?: return null
      toPreview(decoded, maxSide)
    } catch (_: OutOfMemoryError) {
      null
    } catch (_: RuntimeException) {
      null
    } catch (_: java.io.IOException) {
      null
    }
  }

  private fun toPreview(decoded: Bitmap, maxSide: Int): Preview {
    var bitmap = decoded
    val longest = max(decoded.width, decoded.height)
    if (longest > maxSide) {
      val scale = maxSide.toDouble() / longest
      val w = max(1, (decoded.width * scale).toInt())
      val h = max(1, (decoded.height * scale).toInt())
      bitmap = Bitmap.createScaledBitmap(decoded, w, h, true)
      if (bitmap !== decoded) decoded.recycle()
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rgba = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
      val p = pixels[i]
      rgba[i * 4] = (p shr 16).toByte()
      rgba[i * 4 + 1] = (p shr 8).toByte()
      rgba[i * 4 + 2] = p.toByte()
      rgba[i * 4 + 3] = (p ushr 24).toByte()
    }
    val result = Preview(bitmap.width, bitmap.height, rgba)
    bitmap.recycle()
    return result
  }
}

/**
 * Un [InputStream] sopra una `MediaDataSource`: legge a blocchi con `readAt`, senza mai caricare il file. Non chiude la sorgente:
 * la chiude il suo `MediaBytes` (contratto di `MediaBytes.dataSource()`), e la si riapre per la seconda passata.
 */
internal class MediaDataSourceInputStream(private val source: MediaDataSource) : InputStream() {
  private var position = 0L
  private val total = source.size

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (total in 0..position) return -1
    val n = source.readAt(position, buffer, offset, length)
    if (n <= 0) return -1
    position += n
    return n
  }

  override fun skip(n: Long): Long {
    if (n <= 0) return 0
    val skipped = if (total >= 0) minOf(n, total - position) else n
    position += skipped
    return skipped
  }

  override fun available(): Int = if (total >= 0) minOf(Int.MAX_VALUE.toLong(), total - position).toInt() else 0

  override fun close() = Unit
}

/**
 * Porta un'anteprima nell'orientamento **mostrato** (specchio, poi rotazione oraria, come `ExifInterface`): il segnaposto
 * sfocato deve avere la stessa proporzione e lo stesso verso della miniatura che sostituira'.
 */
internal fun Preview.oriented(orientation: ExifOrientation): Preview {
  if (orientation == ExifOrientation.NORMAL) return this
  val swap = orientation.swapsSides
  val outW = if (swap) height else width
  val outH = if (swap) width else height
  val out = ByteArray(outW * outH * 4)
  for (y in 0 until height) {
    for (x in 0 until width) {
      // 1. specchio orizzontale (prima della rotazione)
      val mx = if (orientation.mirrored) width - 1 - x else x
      // 2. rotazione oraria della bitmap [width x height]
      val (dx, dy) = when (orientation.degrees) {
        90 -> (height - 1 - y) to mx
        180 -> (width - 1 - mx) to (height - 1 - y)
        270 -> y to (width - 1 - mx)
        else -> mx to y
      }
      System.arraycopy(rgba, (y * width + x) * 4, out, (dy * outW + dx) * 4, 4)
    }
  }
  return Preview(outW, outH, out)
}
