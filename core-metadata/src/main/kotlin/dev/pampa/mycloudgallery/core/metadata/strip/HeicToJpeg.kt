package dev.pampa.mycloudgallery.core.metadata.strip

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

// Fase 5B, P11 (docs/fase5-progetto.md D26, D28, L41): un HEIC si condivide senza posizione CONVERTITO IN JPEG. Decodificare i pixel e
// ricomprimerli e' il modo piu' sicuro di non portarsi dietro niente: la bitmap non ha metadati, quindi il JPEG che esce non ha EXIF, XMP ne'
// posizione (niente da pulire, niente da dimenticare). `ImageDecoder` applica da solo la rotazione (`irot`/EXIF); `Bitmap.compress` e' del
// sistema (D28: nessuna dipendenza nuova). Un telefono senza decodificatore HEVC non converte: la condivisione si ferma con il motivo.

/** L'esito di una conversione. */
sealed interface HeicConversion {
  /** Il JPEG e' scritto in `dst`. */
  data object Done : HeicConversion

  /** Nessun convertitore legato, o il telefono non sa decodificare questo file (codec mancante): non e' un guasto, e' un limite. */
  data object NotAvailable : HeicConversion

  /** Il sorgente non si legge o non si decodifica, o la scrittura e' fallita. */
  data class Failed(val reason: WriteFailure) : HeicConversion
}

/** Converte un HEIF/HEIC in JPEG. Sincrono e bloccante: il chiamante lo gira su un dispatcher di I/O. */
fun interface HeicToJpeg {
  /** [dst] esiste gia' (vuoto, creato in esclusiva dal chiamante): ci si scrive il JPEG. */
  fun convert(src: File, dst: File): HeicConversion

  companion object {
    /** Nessun convertitore: mai un successo finto. */
    val NotAvailable: HeicToJpeg = HeicToJpeg { _, _ -> HeicConversion.NotAvailable }
  }
}


/**
 * Da dove vengono i pixel di un HEIC: il decodificatore del sistema. Separato dalla conversione perche' `ImageDecoder` non gira su JVM (qui
 * si prova la conversione con un'altra sorgente di bitmap; la sorgente vera la prova F42 sull'S25, con file HEIC veri).
 */
fun interface BitmapSource {
  /**
   * Decodifica [src] in una bitmap di al massimo [maxPixels] pixel (riducendola se serve), con la rotazione gia' applicata. Null se il sistema
   * non la decodifica (codec HEVC assente, file rovinato). Un errore di lettura lancia `IOException`.
   */
  fun decode(src: File, maxPixels: Long): Bitmap?
}

/** `ImageDecoder` (API 28+): applica da solo la rotazione (`irot`, EXIF) e riduce in decodifica, senza mai tenere la bitmap intera. */
object ImageDecoderBitmaps : BitmapSource {

  override fun decode(src: File, maxPixels: Long): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { decoder, info, _ ->
      // Software: la bitmap si ricomprime, non si disegna; e niente memoria della GPU.
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      scaledSize(info.size.width, info.size.height, maxPixels)?.let { (w, h) -> decoder.setTargetSize(w, h) }
    }
  } catch (_: ImageDecoder.DecodeException) {
    null
  }

  /** La dimensione ridotta per stare sotto [maxPixels], o null se la foto ci sta gia'. Mantiene le proporzioni e non scende sotto 1x1. */
  internal fun scaledSize(width: Int, height: Int, maxPixels: Long): Pair<Int, Int>? {
    val pixels = width.toLong() * height
    if (pixels <= maxPixels || width <= 0 || height <= 0) return null
    val scale = sqrt(maxPixels.toDouble() / pixels)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
  }
}

/**
 * Il convertitore vero: i pixel dal sistema ([source]) e `Bitmap.compress` in JPEG. Una foto da 50 MP non si tiene intera in memoria: oltre
 * [maxPixels] si riduce (e' una copia da condividere, non l'originale). La bitmap non ha metadati: il JPEG che esce non ha EXIF, XMP ne' posizione.
 */
class AndroidHeicToJpeg(
  private val maxPixels: Long = MAX_PIXELS,
  private val quality: Int = QUALITY,
  private val source: BitmapSource = ImageDecoderBitmaps,
) : HeicToJpeg {

  override fun convert(src: File, dst: File): HeicConversion {
    var bitmap: Bitmap? = null
    return try {
      // Un HEIC che questo telefono non decodifica (codec HEVC assente) o rovinato: non si converte, e non e' un guasto.
      bitmap = source.decode(src, maxPixels) ?: return HeicConversion.NotAvailable
      val ok = dst.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
      if (ok) HeicConversion.Done else HeicConversion.Failed(WriteFailure.IO)
    } catch (_: IOException) {
      HeicConversion.Failed(WriteFailure.SOURCE_UNREADABLE)
    } catch (_: RuntimeException) {
      HeicConversion.Failed(WriteFailure.SOURCE_UNREADABLE)
    } finally {
      bitmap?.recycle()
    }
  }

  companion object {
    /** 32 MP: ~128 MB di bitmap. Un telefono da 50 MP arriva a 8000x6000. */
    const val MAX_PIXELS: Long = 32_000_000L

    const val QUALITY: Int = 92
  }
}
