package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le miniature dei media che stanno **solo sul NAS** (D17, D18, `docs/fase4-progetto.md` §2.5). La catena, dalla migliore:
 *
 * 1. **la replica** ([ReplicaBlobs]): la miniatura preparata (da chiunque nella famiglia, D4), cercata per chiave di
 *    contenuto: una rinomina fatta dal PC non la fa perdere. La classe dipende dalla taglia chiesta: S (lato corto 200 px)
 *    per le celle piccole, M (400 px) per le altre; se manca quella giusta si ripiega sull'altra (il livello Risparmio ha
 *    solo la S) invece di lasciare la cella vuota;
 * 2. **la miniatura provvisoria** ([ProvisionalThumbnails]): la miniatura EXIF letta al rilevamento (JPEG, quasi sempre
 *    nei primi 64 KiB). Solo locale, mai pubblicata;
 * 3. **l'originale, solo se la cella e' visibile** ([NasOriginalThumbnails], `interactive`): lo legge dal NAS e ne
 *    decodifica una miniatura. Se la richiesta e' solo "in arrivo" non si legge niente: [ThumbResult.Deferred] (non e'
 *    un errore) e si riprova quando la finestra cambia.
 *
 * Solo il gradino 1 e' definitivo: gli altri due danno una miniatura **provvisoria** ([ThumbResult.Loaded.provisional]),
 * che il caricatore sostituisce quando la replica riceve quella preparata ([canUpgrade]).
 *
 * **Annullamento.** Ogni gradino controlla il `CancellationSignal` prima di cominciare e dopo; la lettura dell'originale
 * si ferma a ogni blocco (e chiude il file); la decodifica di un blob e' breve (WebP di 20-50 KB) e non si interrompe a
 * meta'. Un'annullamento arriva a chi chiama come `CancellationException`.
 *
 * **Fallimenti.** Un pacco sparito (replica azzerata nel frattempo) o un blob che non si decodifica non e' un errore per
 * la cella: si passa al gradino dopo. Niente nei log che identifichi un media, un percorso o una share.
 *
 * Per i video il gradino 3 non esiste (non si scaricano interi, D12): senza copertina preparata la cella resta sul
 * segnaposto.
 */
class NasThumbnailSource(
  private val blobs: ReplicaBlobs,
  private val provisional: ProvisionalThumbnails,
  private val interactive: NasOriginalThumbnails = NoNasOriginalThumbnails,
  private val decoder: BlobDecoder = PlatformBlobDecoder,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ThumbnailSource {

  /** La richiesta diretta (anteprima del viewer): l'utente la guarda, quindi e' visibile. */
  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? =
    (loadResult(ref, px, cancel, ThumbContext.Visible) as? ThumbResult.Loaded)?.bitmap

  override suspend fun loadResult(ref: MediaRef, px: Int, cancel: CancellationSignal, context: ThumbContext): ThumbResult? {
    val nas = ref as? MediaRef.Nas ?: return null
    val side = max(px, 1)
    return try {
      val local = withContext(dispatcher) { fromReplicaOrProvisional(nas, side, cancel) }
      if (local != null) return local
      // Gli originali: solo cio' che si vede. `isVisible` si legge ora, non a inizio richiesta: la coda puo' averla fatta
      // diventare visibile, o averla persa.
      if (!NasMediaOriginalThumbnails.isDecodable(nas.mime)) return null
      if (!context.isVisible()) return ThumbResult.Deferred
      interactive.thumbnail(nas, side, cancel)
    } catch (e: OperationCanceledException) {
      throw CancellationException("Miniatura annullata.").also { it.initCause(e) }
    }
  }

  /** C'e' una miniatura preparata (non provvisoria) per [ref] a questa taglia? Un controllo in memoria. */
  override fun canUpgrade(ref: MediaRef, px: Int): Boolean {
    val nas = ref as? MediaRef.Nas ?: return false
    return classesFor(max(px, 1)).any { blobs.locate(nas.contentKey, it) != null }
  }

  // --- I gradini 1 e 2 -------------------------------------------------------------------------------------------

  private fun fromReplicaOrProvisional(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? {
    for (cls in classesFor(px)) {
      cancel.throwIfCanceled()
      val location = blobs.locate(ref.contentKey, cls) ?: continue
      val bytes = try {
        blobs.read(location)
      } catch (_: IOException) {
        // Il pacco non c'e' piu' (replica azzerata o ricostruita): come se il blob non ci fosse.
        note("replica non leggibile")
        continue
      }
      decoder.decode(bytes, px, cancel)?.let { return ThumbResult.Loaded(it, provisional = false) }
      note("blob non decodificabile")
    }
    cancel.throwIfCanceled()
    val exif = provisional.get(ref.contentKey) ?: return null
    val bitmap = decoder.decode(ByteBuffer.wrap(exif), px, cancel) ?: return null
    return ThumbResult.Loaded(bitmap, provisional = true)
  }

  private fun note(what: String) {
    GalleryLog.d(TAG) { "miniatura del NAS: $what" }
  }

  companion object {
    private const val TAG = "NasThumbnails"

    /**
     * Le classi della replica da provare per una taglia chiesta, dalla migliore: la S (lato corto 200 px) basta fino alla
     * taglia piccola della griglia, la M (400 px) per le altre. Se manca la prima si ripiega sull'altra: meglio una
     * miniatura un po' piccola, ridimensionata dalla cella, che il segnaposto.
     */
    fun classesFor(px: Int): List<SegmentClass> =
      if (px <= ThumbBucket.S.px) listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M) else listOf(SegmentClass.THUMB_M, SegmentClass.THUMB_S)
  }
}
