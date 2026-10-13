package dev.pampa.mycloudgallery.core.imaging.placeholder

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.model.ThumbHash
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Il decodificatore di un [ThumbHash] in pixel ARGB (come `Bitmap.setPixels`) con il lato lungo di `maxSide`. Lo
 * fornisce `:app` con `ThumbHashCodec::decode` di `core-metadata`: questo modulo non dipende da `core-metadata` (grafo
 * del §1.1 di `docs/fase3-progetto.md`: `core-imaging` vede solo model e common).
 */
fun interface ThumbHashDecoder {
  fun decode(hash: ThumbHash, maxSide: Int): IntArray
}

/**
 * I segnaposto sfocati (D5): ogni cella disegna questa bitmap prima di qualunque miniatura, quindi il primo
 * fotogramma della Libreria non ha buchi.
 *
 * - **Piccola e a voci limitate.** La bitmap ha il lato lungo di 32 px (circa 4 KB): [maxEntries] voci, LRU. Tenerle
 *   tutte costerebbe piu' che decodificarle, ma non vanno rifatte a ogni ricomposizione.
 * - **Proporzione.** Un ThumbHash porta con se' la proporzione dell'immagine: la bitmap la rispetta (32x24, 24x32...),
 *   e la cella la riempie con lo stesso ritaglio della miniatura vera, quindi la sostituzione non sposta niente.
 * - **Mai un'eccezione.** Se il decodificatore sbaglia o restituisce un numero di pixel che non torna, la cella riceve
 *   un grigio neutro: un segnaposto rotto non deve fermare la griglia.
 * - **Thread.** Si chiama dalla composizione (thread principale): costa microsecondi per voce, ma la prima volta
 *   decodifica; per questo la griglia puo' scaldare la cache fuori dal thread principale con [image] da un worker.
 */
class ThumbHashPlaceholderCache(
  private val decoder: ThumbHashDecoder,
  private val maxEntries: Int = 512,
) : PlaceholderCache {

  init {
    require(maxEntries >= 1) { "Serve almeno una voce." }
  }

  private val cache = object : LinkedHashMap<ThumbHash, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbHash, ImageBitmap>?): Boolean = size > maxEntries
  }

  /** Quante voci ci sono adesso (per i test e la Diagnostica). */
  val size: Int get() = synchronized(cache) { cache.size }

  override fun image(h: ThumbHash): ImageBitmap {
    synchronized(cache) { cache[h] }?.let { return it }
    val decoded = decode(h)
    return synchronized(cache) { cache.getOrPut(h) { decoded } }
  }

  private fun decode(h: ThumbHash): ImageBitmap {
    val (w, hgt) = ThumbHashGeometry.sizeOf(h, SIDE)
    val pixels = try {
      decoder.decode(h, SIDE)
    } catch (_: RuntimeException) {
      null
    }
    val bitmap = createBitmap(w, hgt)
    if (pixels != null && pixels.size == w * hgt) {
      bitmap.setPixels(pixels, 0, w, 0, 0, w, hgt)
    } else if (pixels != null && pixels.size == SIDE * SIDE) {
      // Un decodificatore che restituisce sempre un quadrato: si usa cosi' com'e'.
      return Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
    } else {
      bitmap.eraseColor(NEUTRAL)
    }
    return bitmap.asImageBitmap()
  }

  private companion object {
    const val SIDE = 32
    const val NEUTRAL = 0xFF808080.toInt()
  }
}

/**
 * La geometria di un ThumbHash: la proporzione sta nei primi 5 byte (numero di componenti di luminanza per lato,
 * come nell'algoritmo pubblico). Serve a sapere larghezza e altezza della bitmap decodificata senza decodificarla.
 */
internal object ThumbHashGeometry {

  /** Larghezza e altezza di un ThumbHash decodificato con il lato lungo [maxSide]. Un hash troppo corto da' un quadrato. */
  fun sizeOf(hash: ThumbHash, maxSide: Int): Pair<Int, Int> {
    val ratio = aspectRatio(hash.bytes())
    return if (ratio > 1f) {
      maxSide to max(1, (maxSide / ratio).roundToInt())
    } else {
      max(1, (maxSide * ratio).roundToInt()) to maxSide
    }
  }

  /** `lx / ly` dell'algoritmo ThumbHash; 1 se i byte non bastano. */
  fun aspectRatio(bytes: ByteArray): Float {
    if (bytes.size < 5) return 1f
    val header24 = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or ((bytes[2].toInt() and 0xFF) shl 16)
    val header16 = (bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)
    val hasAlpha = (header24 shr 23) != 0
    val isLandscape = (header16 shr 15) != 0
    val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
    val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
    return lx.toFloat() / ly
  }
}
