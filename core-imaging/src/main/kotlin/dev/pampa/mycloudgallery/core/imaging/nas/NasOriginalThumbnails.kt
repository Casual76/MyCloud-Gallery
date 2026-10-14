package dev.pampa.mycloudgallery.core.imaging.nas

import android.os.CancellationSignal
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// L'ultimo gradino della catena delle miniature del NAS (D17, docs/fase4-progetto.md §2.5): quando non c'e' ne' la
// miniatura preparata ne' quella EXIF e il contenuto e' VISIBILE, si legge l'originale e se ne decodifica una miniatura.
// Il percorso (share, cartella, nome) e' un dato sensibile come il GPS: `MediaRef.Nas` porta solo l'id della posizione, e
// il percorso si risolve qui, un istante prima di leggere (`NasLocationLookup`, a carico di :app sopra il database).

/**
 * Dove sta un file del NAS: la share e il percorso validato. Nessun `toString` utile: va nei log come "posizione".
 */
class NasFileLocation(val share: String, val path: SharePath) {
  override fun toString(): String = "NasFileLocation"
}

/**
 * Dall'id di una posizione nella replica (`MediaRef.Nas.locationId`) al percorso sul NAS. Lo implementa `:app` (P11) sopra
 * il catalogo (`media_location`, schema v3 di P2); un'implementazione nulla ([NoNasLocationLookup]) non risolve niente.
 * Null = la posizione non c'e' piu' (sparita, azzerata).
 */
interface NasLocationLookup {
  suspend fun locate(locationId: Long): NasFileLocation?
}

object NoNasLocationLookup : NasLocationLookup {
  override suspend fun locate(locationId: Long): NasFileLocation? = null
}

/**
 * La "terza fonte" di `NasThumbnailSource` (il suo parametro `interactive`, §1.2): una miniatura ricavata dall'originale
 * sul NAS, per il contenuto visibile che non ha ancora niente di meglio. Il risultato e' sempre **provvisorio**
 * ([ThumbResult.Loaded.provisional]): la sostituira' la miniatura preparata. Null = non disponibile (raggiungibile ma
 * illeggibile, formato non decodificabile, troppo grande, NAS irraggiungibile); [ThumbResult.Deferred] = non adesso
 * (dati mobili, politica di rete).
 */
interface NasOriginalThumbnails {
  suspend fun thumbnail(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult?
}

/** Nessun accesso all'originale: la catena si ferma alla provvisoria. */
object NoNasOriginalThumbnails : NasOriginalThumbnails {
  override suspend fun thumbnail(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? = null
}

/**
 * Legge l'originale da `NasMediaReader` (scopo **interattivo**: l'utente aspetta, 15 s a blocco, D3) e ne decodifica una
 * miniatura.
 *
 * - **Solo immagini decodificabili** ([isDecodable]): niente video (non si scaricano mai interi, D12: la copertina la
 *   da' la preparazione), niente RAW (DNG pesa decine di MB e la miniatura vera e' la preparazione).
 * - **Tetto di memoria**: l'originale si legge in un buffer solo se pesa al massimo [maxBytes] (20 MiB); oltre, null.
 *   Al massimo [concurrency] letture insieme, cosi' il caso peggiore e' `maxBytes * concurrency` (40 MiB) e non cresce
 *   con la finestra della griglia.
 * - **Annullamento**: la lettura e' a blocchi da 1 MiB e ogni blocco e' un punto di annullamento; se la richiesta esce
 *   dalla finestra la lettura si ferma e il file si chiude (`finally`). Il segnale [CancellationSignal] vale per la
 *   decodifica.
 * - **Rete**: [allowed] lo decide `:app` (per esempio mai sui dati mobili, D23): `false` da' [ThumbResult.Deferred].
 * - Nessun nome, percorso o share nei log.
 */
class NasMediaOriginalThumbnails(
  private val reader: NasMediaReader,
  private val locations: NasLocationLookup,
  private val decoder: BlobDecoder = PlatformBlobDecoder,
  private val allowed: () -> Boolean = { true },
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  concurrency: Int = DEFAULT_CONCURRENCY,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NasOriginalThumbnails {

  private val gate = Semaphore(concurrency)

  override suspend fun thumbnail(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? {
    if (!isDecodable(ref.mime)) return null
    if (!allowed()) return ThumbResult.Deferred
    return gate.withPermit { withContext(dispatcher) { read(ref, px, cancel) } }
  }

  private suspend fun read(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? {
    throwIfCancelled(cancel)
    val location = locations.locate(ref.locationId) ?: return null
    val opened = reader.open(location.share, location.path, Purpose.INTERACTIVE)
    val file = (opened as? NasResult.Ok)?.value ?: return null
    try {
      val size = file.size
      if (size <= 0L || size > maxBytes) return null
      val bytes = ByteArray(size.toInt())
      var done = 0
      while (done < bytes.size) {
        throwIfCancelled(cancel)
        val n = file.read(done.toLong(), bytes, done, minOf(CHUNK_BYTES, bytes.size - done))
        // Meno byte del previsto: il file e' stato accorciato dal PC mentre lo si leggeva. Non si decodifica meta' file.
        if (n <= 0) return null
        done += n
      }
      throwIfCancelled(cancel)
      val bitmap = decoder.decode(ByteBuffer.wrap(bytes), px, cancel) ?: return null
      return ThumbResult.Loaded(bitmap, provisional = true)
    } catch (e: CancellationException) {
      throw e
    } catch (e: NasIoException) {
      GalleryLog.d(TAG) { "originale del NAS non letto: ${e.error::class.simpleName}" }
      return null
    } catch (e: IOException) {
      GalleryLog.d(TAG) { "originale del NAS non letto: ${e.javaClass.simpleName}" }
      return null
    } finally {
      // Chiuso anche se la richiesta e' stata annullata: un handle SMB aperto tiene un file del NAS bloccato.
      file.close()
    }
  }

  private suspend fun throwIfCancelled(cancel: CancellationSignal) {
    currentCoroutineContext().ensureActive()
    if (cancel.isCanceled) throw CancellationException("Lettura annullata.")
  }

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 20L * 1024 * 1024
    const val DEFAULT_CONCURRENCY: Int = 2
    private const val CHUNK_BYTES = 1024 * 1024
    private const val TAG = "NasThumbnails"

    /** I MIME che `ImageDecoder` decodifica a una miniatura; senza MIME si prova (la decodifica dira' se va). */
    fun isDecodable(mime: String?): Boolean = when (mime?.lowercase()) {
      null, "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp" -> true
      else -> false
    }
  }
}
