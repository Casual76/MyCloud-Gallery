package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
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
 *
 * **Share e chiave intera** (R1 della revisione 4A). [MediaRef.Nas] porta solo i primi 64 bit della chiave di contenuto: con
 * [scopes] la miniatura si cerca solo nei pacchi delle share in cui il media ha una posizione (la sua area per prima) e con
 * la chiave intera del catalogo. La risoluzione costa una lettura del catalogo per media e si tiene in memoria (le ultime
 * [SCOPE_CACHE] posizioni), cosi' [canUpgrade] resta un controllo in memoria.
 */
class NasThumbnailSource(
  private val blobs: ReplicaBlobs,
  private val provisional: ProvisionalThumbnails,
  private val interactive: NasOriginalThumbnails = NoNasOriginalThumbnails,
  private val decoder: BlobDecoder = PlatformBlobDecoder,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val scopes: NasBlobScopes? = null,
) : ThumbnailSource {

  private val scopeCache = ScopeCache(SCOPE_CACHE)

  /** La richiesta diretta (anteprima del viewer): l'utente la guarda, quindi e' visibile. */
  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? =
    (loadResult(ref, px, cancel, ThumbContext.Visible) as? ThumbResult.Loaded)?.bitmap

  override suspend fun loadResult(ref: MediaRef, px: Int, cancel: CancellationSignal, context: ThumbContext): ThumbResult? {
    val nas = ref as? MediaRef.Nas ?: return null
    val side = max(px, 1)
    return try {
      val scope = scopeOf(nas)
      val local = withContext(dispatcher) { fromReplicaOrProvisional(nas, scope, side, cancel) }
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
    val scope = scopeCache[nas.locationId]
    return classesFor(max(px, 1)).any { locate(nas, scope, it) != null }
  }

  /** Il perimetro del media (R1), dal catalogo e poi dalla memoria; null se non c'e' chi lo risolve o la posizione e' sparita. */
  private suspend fun scopeOf(ref: MediaRef.Nas): BlobScope? {
    val resolver = scopes ?: return null
    scopeCache[ref.locationId]?.let { return it }
    val resolved = try {
      resolver.scopeOf(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      note("perimetro non risolto")
      null
    } ?: return null
    scopeCache[ref.locationId] = resolved
    return resolved
  }

  private fun locate(ref: MediaRef.Nas, scope: BlobScope?, cls: SegmentClass) =
    if (scope == null) blobs.locate(ref.contentKey, cls) else blobs.locate(ref.contentKey, cls, scope)

  // --- I gradini 1 e 2 -------------------------------------------------------------------------------------------

  private fun fromReplicaOrProvisional(ref: MediaRef.Nas, scope: BlobScope?, px: Int, cancel: CancellationSignal): ThumbResult? {
    for (cls in classesFor(px)) {
      cancel.throwIfCanceled()
      val location = locate(ref, scope, cls) ?: continue
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

    /** Le posizioni di cui si ricorda il perimetro (R1): qualche schermata di griglia, pochi KB. */
    const val SCOPE_CACHE: Int = 4_096

    /**
     * Le classi della replica da provare per una taglia chiesta, dalla migliore: la S (lato corto 200 px) basta fino alla
     * taglia piccola della griglia, la M (400 px) per le altre. Se manca la prima si ripiega sull'altra: meglio una
     * miniatura un po' piccola, ridimensionata dalla cella, che il segnaposto.
     */
    fun classesFor(px: Int): List<SegmentClass> =
      if (px <= ThumbBucket.S.px) listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M) else listOf(SegmentClass.THUMB_M, SegmentClass.THUMB_S)
  }
}

/**
 * Dove cercare le anteprime di un media del NAS (R1 della revisione 4A): le share delle sue posizioni, la sua per prima, e la
 * chiave di contenuto intera. Lo realizza `:app` dal catalogo; null = posizione sparita (si cerca come prima, solo fra i blob
 * attestati).
 */
fun interface NasBlobScopes {
  suspend fun scopeOf(ref: MediaRef.Nas): BlobScope?
}

/** Una memoria piccola (LRU, sincronizzata) dei perimetri per id di posizione. */
internal class ScopeCache(private val capacity: Int) {
  private val map = object : LinkedHashMap<Long, BlobScope>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BlobScope>?): Boolean = size > capacity
  }

  operator fun get(locationId: Long): BlobScope? = synchronized(map) { map[locationId] }

  operator fun set(locationId: Long, scope: BlobScope) {
    synchronized(map) { map[locationId] = scope }
  }
}
