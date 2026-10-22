package dev.pampa.mycloudgallery.core.ai.cutout

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import java.io.File
import java.io.IOException
import kotlin.math.max

// LA SORGENTE DEL RITAGLIO (docs/fase6-progetto.md D44, §2.10 "Sorgente dell'immagine", E-M1-M4): l'immagine migliore GIA' sul
// dispositivo (originale del telefono, originale gia' aperto nel viewer, altrimenti l'HQ della replica; mai uno scaricamento solo per
// ritagliare), decodificata dal decodificatore della piattaforma (lo stesso del viewer) **gia' ridotta** a un lato lungo di al piu'
// 4096 px, raddrizzata dall'orientamento, in sRGB. Una foto da 200 megapixel non e' mai in memoria per intero: `ImageDecoder` riduce
// mentre decodifica.
//
// Tetti (E-M1-M4, tetti di `ImagingBudget`): il lato lungo (4096, `RAW_MAX_SIDE`), i pixel del risultato (16,8 milioni: 4096x4096; una
// foto 4:3 da 4096 ne ha 12,6) e le dimensioni dichiarate dall'intestazione, lette PRIMA di allocare (oltre [MAX_SOURCE_PIXELS] il file
// non si apre: un'intestazione che mente non fa allocare gigabyte).

/** L'esito di una decodifica. */
internal sealed interface CutoutDecode {
  class Decoded(val image: CutoutImage) : CutoutDecode

  /** Non e' un'immagine che sappiamo leggere (formato, file rovinato o troppo grande per il tetto). */
  data object Unreadable : CutoutDecode

  data object OutOfMemory : CutoutDecode

  val error: AiEngineError?
    get() = when (this) {
      is Decoded -> null
      Unreadable -> AiEngineError.BAD_INPUT
      OutOfMemory -> AiEngineError.OUT_OF_MEMORY
    }
}

object CutoutImages {

  /** Il tetto dei pixel dichiarati dall'intestazione della sorgente: 268 milioni (le foto da 200 MP e i panorami lunghi entrano). */
  const val MAX_SOURCE_PIXELS: Long = 1L shl 28

  /** Il tetto del lato lungo dichiarato. */
  const val MAX_SOURCE_SIDE: Int = 65_535

  /** I pixel massimi del risultato: oltre la tela di 4096 x 3072 (12,6 MP) si riduce ancora. */
  const val MAX_RESULT_PIXELS: Long = 4096L * 3072L

  /**
   * La taglia di destinazione di una sorgente [width]x[height]: lato lungo entro [maxSide] e pixel entro [maxPixels], mai
   * ingrandita; null se la sorgente e' fuori dai tetti.
   */
  fun targetSize(width: Int, height: Int, maxSide: Int = CutoutImage.MAX_SIDE, maxPixels: Long = MAX_RESULT_PIXELS): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || max(width, height) > MAX_SOURCE_SIDE) return null
    if (width.toLong() * height > MAX_SOURCE_PIXELS) return null
    var scale = 1.0
    scale = minOf(scale, maxSide.toDouble() / max(width, height))
    scale = minOf(scale, Math.sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
    if (scale >= 1.0) return width to height
    // Per difetto: il risultato non supera mai i tetti (un arrotondamento per eccesso li sfora di qualche pixel).
    return max(1, (width * scale + 1e-6).toInt()) to max(1, (height * scale + 1e-6).toInt())
  }

  internal fun decode(source: ImageDecoder.Source, maxSide: Int = CutoutImage.MAX_SIDE, maxPixels: Long = MAX_RESULT_PIXELS): CutoutDecode {
    val bitmap: Bitmap = try {
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val target = targetSize(info.size.width, info.size.height, maxSide, maxPixels) ?: throw IOException("Fuori dai tetti")
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        if (target.first != info.size.width || target.second != info.size.height) decoder.setTargetSize(target.first, target.second)
      }
    } catch (_: IOException) {
      return CutoutDecode.Unreadable
    } catch (_: RuntimeException) {
      return CutoutDecode.Unreadable
    } catch (_: OutOfMemoryError) {
      return CutoutDecode.OutOfMemory
    }
    return try {
      val w = bitmap.width
      val h = bitmap.height
      if (w !in 1..CutoutImage.MAX_SIDE || h !in 1..CutoutImage.MAX_SIDE) return CutoutDecode.Unreadable
      val pixels = IntArray(w * h)
      bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
      if (bitmap.hasAlpha()) flattenOnWhite(pixels)
      CutoutDecode.Decoded(CutoutImage.adopt(w, h, pixels))
    } catch (_: OutOfMemoryError) {
      CutoutDecode.OutOfMemory
    } finally {
      bitmap.recycle()
    }
  }

  /** Dal file (l'originale o l'HQ gia' sul dispositivo). */
  internal fun decode(file: File, maxSide: Int = CutoutImage.MAX_SIDE, maxPixels: Long = MAX_RESULT_PIXELS): CutoutDecode =
    decode(ImageDecoder.createSource(file), maxSide, maxPixels)

  /** Da un `content://` (una riga del MediaStore del telefono). */
  internal fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int = CutoutImage.MAX_SIDE, maxPixels: Long = MAX_RESULT_PIXELS): CutoutDecode =
    decode(ImageDecoder.createSource(resolver, uri), maxSide, maxPixels)

  /** La trasparenza di una sorgente PNG o WebP si compone sul bianco: il ritaglio parte da un'immagine opaca. */
  private fun flattenOnWhite(pixels: IntArray) {
    for (i in pixels.indices) {
      val p = pixels[i]
      val a = p ushr 24
      if (a == 255) continue
      val r = (((p shr 16) and 0xff) * a + 255 * (255 - a) + 127) / 255
      val g = (((p shr 8) and 0xff) * a + 255 * (255 - a) + 127) / 255
      val b = ((p and 0xff) * a + 255 * (255 - a) + 127) / 255
      pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
}
