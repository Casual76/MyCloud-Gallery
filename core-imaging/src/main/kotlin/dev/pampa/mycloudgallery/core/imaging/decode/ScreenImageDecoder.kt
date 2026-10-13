package dev.pampa.mycloudgallery.core.imaging.decode

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Il piano di una decodifica per lo schermo: a che taglia decodificare. Funzione pura (provata su JVM): e' qui che
 * si decide che un JPEG da 200 MP non diventa mai una bitmap da 800 MB (E-M1) e che un DNG resta sotto i 4096 px
 * (E-M2) e sotto il tetto da `memoryClass`.
 */
data class DecodePlan(
  /** Vero se bisogna ridimensionare (altrimenti si decodifica alla taglia originale). */
  val resample: Boolean,
  /** La taglia da chiedere a `ImageDecoder`, **nello spazio del decodificatore** (prima dell'orientamento manuale). */
  val decoderWidth: Int,
  val decoderHeight: Int,
  /** La taglia finale, dritta. */
  val uprightWidth: Int,
  val uprightHeight: Int,
)

object DecodePlanner {

  /**
   * @param sourceWidth larghezza che il decodificatore produrrebbe (quella di [ImageFacts.decoderWidth]).
   * @param manual l'orientamento che si applica dopo (scambia i lati per 5-8).
   * @param screen lo spazio in cui l'immagine deve stare, in pixel: l'immagine si contiene (nessun ritaglio) e non si
   *   ingrandisce mai oltre l'originale.
   */
  fun plan(
    sourceWidth: Int,
    sourceHeight: Int,
    manual: ExifOrientation,
    format: ImageFormat,
    screen: IntSize,
    budget: ImagingBudget,
  ): DecodePlan {
    val (uw, uh) = manual.uprightSize(sourceWidth, sourceHeight)
    var scale = 1.0
    scale = min(scale, min(max(screen.width, 1).toDouble() / uw, max(screen.height, 1).toDouble() / uh))
    scale = min(scale, sqrt(budget.viewerMaxPixels.toDouble() / (uw.toDouble() * uh)))
    if (format == ImageFormat.TIFF_RAW) scale = min(scale, budget.rawMaxSide.toDouble() / max(uw, uh))
    if (scale >= 1.0) return DecodePlan(false, sourceWidth, sourceHeight, uw, uh)
    var tw = max(1, (uw * scale).roundToInt())
    var th = max(1, (uh * scale).roundToInt())
    // L'arrotondamento puo' sforare di qualche pixel il tetto: si stringe finche' rientra (al massimo qualche giro).
    while (tw.toLong() * th > budget.viewerMaxPixels && (tw > 1 || th > 1)) {
      scale *= 0.999
      tw = max(1, (uw * scale).roundToInt())
      th = max(1, (uh * scale).roundToInt())
    }
    val (dw, dh) = if (manual.swapsSides) th to tw else tw to th
    return DecodePlan(true, dw, dh, tw, th)
  }
}

/** Un'immagine decodificata per lo schermo. */
class DecodedImage(
  val bitmap: Bitmap,
  /** Vero se porta una gain map attiva (Ultra HDR, API 34+). */
  val hdr: Boolean,
  val colorSpace: ColorSpace?,
)

/**
 * La decodifica per lo schermo del viewer: `ImageDecoder` alla taglia del [DecodePlan] (campionata dal codec: un JPEG
 * da 200 MP si decodifica ridotto, mai intero), bitmap software (pronte per `prepareToDraw`), orientamento sempre
 * dritto, **spazio colore conservato** (Display P3 resta P3, E-V4) e **gain map** attiva dove il sistema la decodifica
 * (API 34+; sotto, l'Ultra HDR si vede come SDR e `hdr` e' falso).
 *
 * Funzione bloccante: gira su un thread di lavoro (mai sul principale). Rispetta [CancellationSignal] prima di
 * cominciare e fra una fase e l'altra; la decodifica nativa in corso non si interrompe a meta', ma e' breve perche' e'
 * gia' ridotta.
 */
object ScreenImageDecoder {

  fun decode(
    bytes: ImageBytes,
    facts: ImageFacts,
    screen: IntSize,
    budget: ImagingBudget,
    signal: CancellationSignal,
  ): DecodedImage {
    signal.throwIfCanceled()
    val raw = ImageDecoder.decodeBitmap(bytes.decoderSource()) { decoder, info, _ ->
      // Un'ultima occasione per uscire prima di spendere memoria.
      signal.throwIfCanceled()
      decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
      val plan = DecodePlanner.plan(info.size.width, info.size.height, facts.manual, facts.format, screen, budget)
      if (plan.resample) decoder.setTargetSize(plan.decoderWidth, plan.decoderHeight)
    }
    signal.throwIfCanceled()
    val upright = facts.manual.applyTo(raw, recycleSource = true)
    return DecodedImage(upright, hdr = hasActiveGainmap(upright), colorSpace = upright.colorSpace)
  }

  /** La gain map esiste dall'API 34. Si guarda la bitmap finale: se una rotazione a mano non la porta con se', `hdr` e' falso. */
  fun hasActiveGainmap(bitmap: Bitmap): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
}

/**
 * Se un formato si puo' ingrandire a tasselli (`BitmapRegionDecoder`, che e' cio' che usa telephoto): JPEG, PNG e
 * WebP si; i formati animati no (si animano, non si ingrandiscono a tasselli); HEIC e AVIF **dove il sistema li
 * regge**, verificato aprendo davvero un decodificatore di regioni (E-M3: "tasselli dove `BitmapRegionDecoder` li
 * regge, altrimenti decodifica campionata"); i RAW mai (E-M2: decodifica campionata, lato lungo <= 4096).
 */
class TilePolicy(
  private val regionProbe: (ImageBytes) -> Boolean = Companion::platformRegionProbe,
) {

  /** [bytes] apre il media solo se serve (HEIC e AVIF): per gli altri formati la risposta viene dalla testa. */
  fun supportsTiles(facts: ImageFacts, bytes: () -> ImageBytes?): Boolean = when {
    facts.animated != null -> false
    else -> when (facts.format) {
      ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP -> true
      ImageFormat.HEIF, ImageFormat.AVIF -> bytes()?.use { regionProbe(it) } ?: false
      else -> false
    }
  }

  companion object {
    /** I formati a contenitore non si provano oltre questa dimensione: il sistema copia il flusso in memoria. */
    private const val PROBE_MAX_BYTES = 48L * 1024 * 1024

    @Suppress("DEPRECATION")
    fun platformRegionProbe(bytes: ImageBytes): Boolean {
      val size = bytes.sizeBytes
      if (size != null && size > PROBE_MAX_BYTES) return false
      return try {
        bytes.openStream().use { stream ->
          val decoder = BitmapRegionDecoder.newInstance(stream, false)
          val works = decoder != null && decoder.width > 0 && decoder.height > 0
          decoder?.recycle()
          works
        }
      } catch (_: Exception) {
        false
      }
    }
  }
}
