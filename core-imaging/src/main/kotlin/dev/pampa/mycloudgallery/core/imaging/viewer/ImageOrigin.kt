package dev.pampa.mycloudgallery.core.imaging.viewer

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ContentImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileNotFoundException
import java.io.IOException

/** L'esito dell'apertura di un media per il viewer. */
sealed interface OriginResult {

  /** Aperto: i byte si possono leggere. Chi lo riceve lo chiude. */
  class Opened(val bytes: ImageBytes) : OriginResult

  /**
   * Non raggiungibile adesso (il NAS non risponde, rete non ammessa, dati mobili da confermare, volume tolto):
   * non e' un errore, e' un caso normale (E-V1, E-V2). Il viewer lo spiega e mostra la miniatura che ha.
   */
  class Unreachable(val reason: UnavailableReason, val sizeBytes: Long? = null) : OriginResult

  /** Il media non esiste piu' (cancellato da Galleria o da un altro dispositivo, E-V6). */
  data object Missing : OriginResult
}

/**
 * Da dove arrivano i byte di un media per il viewer. Per il telefono e' il `ContentResolver` ([ContentImageOrigin]);
 * dalla Fase 4 ne arrivera' una per il NAS, che puo' essere lenta o irraggiungibile: per questo [open] sospende e
 * puo' rispondere [OriginResult.Unreachable] (provato con sorgenti finte, E-V1/E-V5/E-V2).
 */
interface ImageOrigin {

  /** Apre [ref]. Puo' sospendere a lungo (rete); l'annullamento della coroutine la interrompe. */
  suspend fun open(ref: MediaRef): OriginResult

  /**
   * Solo se i byte sono locali e subito disponibili: serve a `zoomSpec` e `animated`, che non sospendono. Una
   * sorgente lenta restituisce null (le due risposte arrivano dalla cache riempita da `state`).
   */
  fun openLocal(ref: MediaRef): ImageBytes? = null
}

/**
 * I byte del telefono, dal MediaStore. Apre il file descriptor del provider **solo per verificare che esista**; la
 * decodifica legge poi a flusso. Nessun URI finisce nei log.
 */
class ContentImageOrigin(private val resolver: ContentResolver) : ImageOrigin {

  override suspend fun open(ref: MediaRef): OriginResult {
    val bytes = openLocal(ref) ?: return OriginResult.Missing
    return try {
      // Una lettura da un byte: il provider risponde subito se il file e' sparito o il permesso e' stato ritirato.
      bytes.openStream().use { it.read() }
      OriginResult.Opened(bytes)
    } catch (_: FileNotFoundException) {
      OriginResult.Missing
    } catch (_: IOException) {
      OriginResult.Missing
    } catch (_: SecurityException) {
      OriginResult.Missing
    }
  }

  override fun openLocal(ref: MediaRef): ImageBytes? {
    val phone = ref as? MediaRef.Phone ?: return null
    return ContentImageBytes(resolver, uriOf(phone))
  }

  private fun uriOf(phone: MediaRef.Phone): Uri {
    val video = phone.mime?.startsWith("video/") == true
    // `getContentUri(volume, id)` esiste solo dall'API 30: si compone con quella per volume (API 29) e l'id.
    val base = if (video) MediaStore.Video.Media.getContentUri(phone.volume) else MediaStore.Images.Media.getContentUri(phone.volume)
    return ContentUris.withAppendedId(base, phone.mediaStoreId)
  }
}
