package dev.pampa.mycloudgallery.core.imaging.viewer

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ContentImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileNotFoundException
import java.io.IOException

/** L'esito dell'apertura di un media per il viewer. */
sealed interface OriginResult {

  /**
   * Aperto: i byte si possono leggere. Chi lo riceve lo chiude.
   *
   * @property isOriginal falso se i byte sono un **ripiego** dell'originale (l'HQ della replica per un media del NAS,
   *   Fase 4): si decodificano per la nitida, ma le sue dimensioni e il suo formato non sono quelli dell'originale e non
   *   decidono lo zoom a tasselli.
   */
  class Opened(val bytes: ImageBytes, val isOriginal: Boolean = true) : OriginResult

  /**
   * Non raggiungibile adesso (il NAS non risponde, rete non ammessa, dati mobili da confermare, volume tolto):
   * non e' un errore, e' un caso normale (E-V1, E-V2). Il viewer lo spiega e mostra la miniatura che ha.
   */
  class Unreachable(val reason: UnavailableReason, val sizeBytes: Long? = null) : OriginResult

  /** Il media non esiste piu' (cancellato da Galleria o da un altro dispositivo, E-V6). */
  data object Missing : OriginResult

  /**
   * Non si e' potuto aprire per una ragione nostra, non della rete: il telefono non ha piu' posto per il temporaneo
   * (`STORAGE_FULL`), l'originale supera quello che la memoria regge (`OUT_OF_MEMORY`), la posizione non si risolve.
   */
  class Failed(val code: ImageFailure) : OriginResult
}

/** Le dimensioni dritte di un originale note senza aprirlo (dal catalogo): per i limiti dello zoom di un media remoto. */
class DeclaredSize(val width: Int, val height: Int) {
  init {
    require(width > 0 && height > 0) { "Dimensioni non valide." }
  }
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
   * Come [open], ma racconta l'avanzamento (da 0 a 1) di una lettura lunga: il viewer lo mostra come `Loading(progress)`
   * (Fase 4, un originale letto dal NAS). Le sorgenti che aprono subito non hanno niente da dire e lasciano il default.
   * [onProgress] puo' essere chiamata da un thread di lavoro, e deve essere economica.
   */
  suspend fun open(ref: MediaRef, onProgress: (Float?) -> Unit): OriginResult = open(ref)

  /**
   * Le dimensioni dritte dell'originale, se si sanno **senza aprirlo** e senza sospendere (Fase 4: dal catalogo, una volta
   * risolta la posizione). Serve a `zoomSpec` finche' l'originale di un media remoto non e' sul telefono. Null = non note.
   */
  fun declaredSize(ref: MediaRef): DeclaredSize? = null

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
