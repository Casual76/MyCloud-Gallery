package dev.pampa.mycloudgallery.core.prepare.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.model.ThumbHash
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Una bitmap che sforerebbe il tetto della memoria della preparazione: il media diventa `TOO_LARGE`. */
internal class OverBudgetException : RuntimeException("Tetto di memoria superato.")

/**
 * Il registro delle bitmap vive di UNA preparazione: ne somma i byte, ricorda il picco e rifiuta una bitmap che sforerebbe
 * il tetto (`PrepBudget`). Non misura la memoria nativa del codec (non si vede da qui): misura cio' che alloca la
 * preparazione, che e' quello che `PrepBudget` governa.
 */
internal class BitmapLedger(private val liveCapBytes: Long) {
  // I byte si registrano all'ingresso: una bitmap gia' riciclata da altri non sa piu' dire quanto pesava.
  private val live = java.util.IdentityHashMap<Bitmap, Long>()
  private var liveBytes = 0L

  var peakBytes: Long = 0
    private set

  fun add(bitmap: Bitmap): Bitmap {
    if (!live.containsKey(bitmap)) {
      val bytes = bitmap.allocationByteCount.toLong()
      live[bitmap] = bytes
      liveBytes += bytes
      peakBytes = max(peakBytes, liveBytes)
      if (liveBytes > liveCapBytes) {
        free(bitmap)
        throw OverBudgetException()
      }
    }
    return bitmap
  }

  /** Libera subito la memoria di [bitmap] (le bitmap si riciclano appena non servono). */
  fun free(bitmap: Bitmap) {
    val bytes = live.remove(bitmap) ?: return
    liveBytes -= bytes
    if (!bitmap.isRecycled) bitmap.recycle()
  }

  fun freeAll() {
    for (b in live.keys.toList()) free(b)
  }
}

/** Le operazioni sulle bitmap della preparazione: riduzione a passi, appiattimento, codifica con tetto, segnaposto. */
internal object ImageOps {

  /**
   * Riduce [src] a [width] x [height] **senza ingrandire mai**. Le riduzioni forti si fanno a passi di mezzo (ogni passo e'
   * una media 2x2 esatta): da 2048 a 200 px un solo passaggio bilineare salterebbe righe e farebbe moire. [src] non si
   * libera; le bitmap intermedie si'. Restituisce [src] se la taglia e' gia' quella.
   */
  fun scaleTo(src: Bitmap, width: Int, height: Int, ledger: BitmapLedger): Bitmap {
    if (src.width == width && src.height == height) return src
    var current = src
    while (current.width / 2 >= width && current.height / 2 >= height) {
      current = step(current, src, current.width / 2, current.height / 2, ledger)
    }
    if (current.width != width || current.height != height) current = step(current, src, width, height, ledger)
    return current
  }

  private fun step(current: Bitmap, original: Bitmap, width: Int, height: Int, ledger: BitmapLedger): Bitmap {
    val next = Bitmap.createScaledBitmap(current, width, height, true)
    if (next !== current) {
      ledger.add(next)
      if (current !== original) ledger.free(current)
    }
    return next
  }

  /** Il JPEG non ha trasparenza: la si compone sul bianco (nero sarebbe peggio per il testo scuro che l'OCR deve leggere). */
  fun flattenOnWhite(src: Bitmap, ledger: BitmapLedger): Bitmap {
    if (!src.hasAlpha()) return src
    val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, false, src.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    ledger.add(flat)
    val canvas = Canvas(flat)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(src, 0f, 0f, null)
    return flat
  }

  /** Le anteprime sono SDR: la gain map dell'Ultra HDR (API 34+) non viaggia nell'indice e raddoppierebbe il JPEG. */
  fun dropGainmap(bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) bitmap.gainmap = null
  }

  /**
   * WebP con perdita (alfa e profilo colore inclusi). Se supera [maxBytes] si abbassa la qualita' a passi di 10 fino a 30;
   * oltre non si scende (null: l'immagine non entra nel tetto e il chiamante la scarta).
   */
  fun encodeWebp(bitmap: Bitmap, quality: Int, maxBytes: Int): ByteArray? = encodeWithin(bitmap, webpFormat(), quality, maxBytes)

  fun encodeJpeg(bitmap: Bitmap, quality: Int, maxBytes: Int): ByteArray? = encodeWithin(bitmap, Bitmap.CompressFormat.JPEG, quality, maxBytes)

  @Suppress("DEPRECATION")
  private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

  private fun encodeWithin(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, maxBytes: Int): ByteArray? {
    var q = quality
    while (true) {
      val out = ByteArrayOutputStream(64 * 1024)
      if (!bitmap.compress(format, q, out)) return null
      if (out.size() <= maxBytes) return out.toByteArray()
      if (q <= MIN_QUALITY) return null
      q = max(MIN_QUALITY, q - QUALITY_STEP)
    }
  }

  /** Il ThumbHash di [bitmap], ridotta prima a 100 px (il formato non vuole di piu'): pochi millisecondi. */
  fun thumbHashOf(bitmap: Bitmap, ledger: BitmapLedger): ThumbHash {
    val longest = max(bitmap.width, bitmap.height)
    val small = if (longest > ThumbBox) {
      val scale = ThumbBox.toDouble() / longest
      scaleTo(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), ledger)
    } else {
      bitmap
    }
    val w = small.width
    val h = small.height
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    if (small !== bitmap) ledger.free(small)
    val rgba = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
      val p = pixels[i]
      rgba[i * 4] = (p shr 16).toByte()
      rgba[i * 4 + 1] = (p shr 8).toByte()
      rgba[i * 4 + 2] = p.toByte()
      rgba[i * 4 + 3] = (p ushr 24).toByte()
    }
    return ThumbHashCodec.encode(w, h, rgba)
  }

  private const val ThumbBox = 100
  private const val MIN_QUALITY = 30
  private const val QUALITY_STEP = 10
}
