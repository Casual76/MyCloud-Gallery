package dev.pampa.mycloudgallery.core.imaging.source

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.scale
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Il punto di contatto con il sistema: `ContentResolver.loadThumbnail`. Sta dietro a un'interfaccia sola perche'
 * l'implementazione vera passa da un file descriptor che Robolectric non sa decodificare su Windows: i test della
 * [PhoneThumbnailSource] provano tutto il resto (URI, dimensioni, annullamento, errori) con una finta, e uno prova che
 * la chiamata vera arrivi al provider `media` con la dimensione e il segnale giusti.
 */
fun interface ContentThumbnails {

  /** Una miniatura di circa [size] di [uri]. Lancia `IOException` se non c'e' e `OperationCanceledException` se [signal] scatta. */
  @Throws(IOException::class)
  fun load(uri: Uri, size: Size, signal: CancellationSignal): Bitmap

  companion object {
    /** `ContentResolver.loadThumbnail`: annullabile, con la cache del MediaProvider, bitmap software (pronte a `prepareToDraw`). */
    fun platform(resolver: ContentResolver): ContentThumbnails = ContentThumbnails { uri, size, signal ->
      resolver.loadThumbnail(uri, size, signal)
    }
  }
}

/**
 * Le miniature del telefono (D5): `ContentResolver.loadThumbnail` con `CancellationSignal`, che usa la cache del
 * MediaProvider (le prime richieste di un file nuovo sono lente: T-26; le successive no).
 *
 * - **Riferimenti.** Solo [MediaRef.Phone]; ogni altro riferimento da' null (la sorgente non lo conosce).
 * - **URI.** Dal `mime`: un `mime` di tipo video sul catalogo dei video, uno di tipo immagine su quello delle immagini; senza `mime` si prova
 *   prima come immagine e poi come video.
 * - **Taglia.** Il MediaProvider restituisce miniature da 96 o 512 px, a prescindere da cio' che si chiede: se il lato
 *   lungo supera di piu' del 25% quello della taglia ([tolerance]) la bitmap si riduce (la 160 non deve costare le
 *   spalle di una 512: 77 KB contro 786 KB). La bitmap del provider si libera subito, e' nostra.
 * - **Annullamento.** Lo fa il `CancellationSignal` ricevuto: ferma la lettura nel provider. Il lavoro gira su
 *   [dispatcher] (I/O), mai sul thread principale.
 * - **Errori.** File mancante, illeggibile o corrotto, permesso ritirato: null (la cella resta sul segnaposto, E-M10).
 *   Nessun log contiene nomi, percorsi o id (solo il tipo d'errore).
 */
class PhoneThumbnailSource(
  private val content: ContentThumbnails,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val tolerance: Float = 1.25f,
) : ThumbnailSource {

  constructor(
    resolver: ContentResolver,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(ContentThumbnails.platform(resolver), dispatcher)

  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
    val phone = ref as? MediaRef.Phone ?: return null
    val side = max(px, 1)
    return withContext(dispatcher) {
      try {
        loadBlocking(phone, side, cancel)
      } catch (e: OperationCanceledException) {
        throw CancellationException("Miniatura annullata.").also { it.initCause(e) }
      }
    }
  }

  private fun loadBlocking(phone: MediaRef.Phone, px: Int, cancel: CancellationSignal): Bitmap? {
    val size = Size(px, px)
    val raw = candidates(phone).firstNotNullOfOrNull { uri ->
      cancel.throwIfCanceled()
      try {
        content.load(uri, size, cancel)
      } catch (e: OperationCanceledException) {
        throw e
      } catch (e: IOException) {
        note("io")
        null
      } catch (e: SecurityException) {
        note("permission")
        null
      } catch (e: RuntimeException) {
        // IllegalArgumentException/IllegalStateException dal decodificatore o dal provider: stesso esito di un file corrotto.
        note("runtime")
        null
      }
    } ?: return null
    return fit(raw, px)
  }

  /** Gli URI da provare, nell'ordine. */
  private fun candidates(phone: MediaRef.Phone): List<Uri> {
    val mime = phone.mime
    val image = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(phone.volume), phone.mediaStoreId)
    val video = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(phone.volume), phone.mediaStoreId)
    return when {
      mime == null -> listOf(image, video)
      mime.startsWith("video/") -> listOf(video)
      else -> listOf(image)
    }
  }

  /** Riduce a [px] di lato lungo se il provider ha dato molto di piu'. */
  private fun fit(raw: Bitmap, px: Int): Bitmap {
    val longSide = max(raw.width, raw.height)
    if (longSide <= px * tolerance) return raw
    val scale = px.toFloat() / longSide
    val w = max(1, (raw.width * scale).roundToInt())
    val h = max(1, (raw.height * scale).roundToInt())
    val scaled = raw.scale(w, h, filter = true)
    if (scaled !== raw) raw.recycle()
    return scaled
  }

  private fun note(kind: String) {
    GalleryLog.d(TAG) { "miniatura del telefono non disponibile: $kind" }
  }

  private companion object {
    const val TAG = "PhoneThumbnails"
  }
}
