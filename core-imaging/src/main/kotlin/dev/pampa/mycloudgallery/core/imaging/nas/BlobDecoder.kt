package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodifica i byte di un'immagine (un blob della replica, la miniatura EXIF provvisoria, un originale letto dal NAS) in
 * una bitmap piccola. Sta dietro a un'interfaccia perche' `ImageDecoder` e' la sola parte che ha bisogno di Android: i
 * test della catena (replica, provvisoria, originale) e dell'annullamento la provano con una finta, e uno prova la
 * decodifica vera con Skia nativo (Robolectric `GraphicsMode.NATIVE`).
 */
fun interface BlobDecoder {

  /**
   * La bitmap del contenuto di [bytes], con il lato lungo al massimo [px] (mai ingrandita), software (pronta per
   * `prepareToDraw`), dritta. Null se non e' un'immagine che si decodifica (corrotta, formato sconosciuto, dimensioni
   * dichiarate fuori dai tetti). Bloccante: gira su un thread di lavoro. Rispetta [cancel] prima di cominciare e dopo
   * l'intestazione; lancia `OperationCanceledException` se e' scattato.
   */
  fun decode(bytes: ByteBuffer, px: Int, cancel: CancellationSignal): Bitmap?
}

/**
 * `ImageDecoder` alla taglia della cella.
 *
 * **Lettura difensiva** (§4.3): i blob della replica sono gia' passati dai tetti di `IndexSegments` e da `ImageBounds`
 * quando sono stati accettati, ma anche qui le dimensioni **dichiarate** nell'intestazione si controllano prima di
 * allocare ([MAX_SIDE], [MAX_PIXELS]): un blob che dichiara 60.000 x 60.000 pixel non diventa una bitmap. Una miniatura
 * non ha mai bisogno di piu' di 2.048 px (l'HQ) per lato.
 */
object PlatformBlobDecoder : BlobDecoder {

  /** Il lato massimo dichiarato che si accetta di decodificare (l'HQ ha il lato lungo a 2.048; un originale campionato di piu'). */
  const val MAX_SIDE: Int = 16_384

  /** I pixel dichiarati massimi: 200 MP; la decodifica e' comunque campionata alla taglia della cella. */
  const val MAX_PIXELS: Long = 200_000_000L

  private class Rejected : RuntimeException()

  override fun decode(bytes: ByteBuffer, px: Int, cancel: CancellationSignal): Bitmap? {
    cancel.throwIfCanceled()
    if (!bytes.hasRemaining()) return null
    val side = max(px, 1)
    val source = ImageDecoder.createSource(direct(bytes))
    return try {
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        cancel.throwIfCanceled()
        val w = info.size.width
        val h = info.size.height
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) throw Rejected()
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        val longSide = max(w, h)
        if (longSide > side) {
          val scale = side.toDouble() / longSide
          decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        }
      }
    } catch (e: OperationCanceledException) {
      throw CancellationException("Decodifica annullata.").also { it.initCause(e) }
    } catch (_: Rejected) {
      null
    } catch (_: IOException) {
      // DecodeException (formato non riconosciuto, dati corrotti) e' una IOException.
      null
    } catch (e: CancellationException) {
      throw e
    } catch (_: RuntimeException) {
      null
    }
  }

  /** Un buffer che `ImageDecoder` sa leggere: i pacchi mappati sono diretti; gli altri (heap, sola lettura) si copiano. */
  private fun direct(bytes: ByteBuffer): ByteBuffer {
    val view = bytes.duplicate()
    if (view.isDirect || view.hasArray()) return view
    val copy = ByteArray(view.remaining())
    view.get(copy)
    return ByteBuffer.wrap(copy)
  }
}
