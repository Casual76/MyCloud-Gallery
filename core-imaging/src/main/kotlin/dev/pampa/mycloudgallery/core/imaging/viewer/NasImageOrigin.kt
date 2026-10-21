package dev.pampa.mycloudgallery.core.imaging.viewer

import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'avanzamento dell'originale di un media del NAS che il viewer ha chiesto (con lo zoom, D19). Il flusso di [OriginalFetcher.fetch]
 * emette zero o piu' [Reading] e un solo esito finale.
 */
sealed interface OriginalFetch {

  /** In lettura; [progress] da 0 a 1 se la dimensione e' nota. */
  data class Reading(val progress: Float?) : OriginalFetch

  /** Sul telefono, completo: i tasselli leggono da qui. */
  data class Ready(val file: File) : OriginalFetch

  /** Non adesso (NAS irraggiungibile, rete non ammessa, dati mobili da confermare, E-V2). */
  data class Blocked(val reason: UnavailableReason, val sizeBytes: Long?) : OriginalFetch

  /** Il media non c'e' piu' (E-V6). */
  data object Missing : OriginalFetch

  data class Failed(val code: ImageFailure) : OriginalFetch
}

/**
 * Porta sul telefono l'originale di un media remoto, a richiesta (lo zoom). Realizzato da [NasImageOrigin]; il viewer lo
 * riceve come capacita' facoltativa (`ViewerServices.originals`).
 */
interface OriginalFetcher {

  /** L'originale gia' sul telefono, completo e non vecchio, o null. Non tocca la rete. */
  fun localFile(ref: MediaRef): File?

  /** Legge l'originale (se manca) mostrando l'avanzamento; annullare la raccolta annulla la lettura e toglie il temporaneo. */
  fun fetch(ref: MediaRef): Flow<OriginalFetch>
}

/**
 * Da dove arrivano i byte di un media del NAS per il viewer (Fase 4, P8; docs/fase4-progetto.md §2.6, D19, E-V1, E-V2,
 * E-V5, E-V6, E-L4).
 *
 * **Cosa risponde [open], in ordine:**
 *  1. un **originale gia' sul telefono** (letto prima, completo): subito;
 *  2. l'**HQ della replica** (2048 px, anche offline): subito, e non e' l'originale ([OriginResult.Opened.isOriginal] falso,
 *     quindi i limiti dello zoom non si ricavano da lui). L'originale intero non si scarica per ogni foto che si sfoglia: lo
 *     chiede lo zoom ([fetch]);
 *  3. senza HQ, l'**originale letto dal NAS** in un temporaneo, con l'avanzamento: e' la nitida (o, se non si puo', una
 *     nota che dice perche').
 *
 * **Casi normali, non eccezioni.** Il NAS che non risponde, la rete non consentita, i dati mobili da confermare sono
 * [OriginResult.Unreachable] con la dimensione (la miniatura resta sotto la nota); un media sparito dal catalogo o dal NAS e'
 * [OriginResult.Missing]; un disco pieno o un originale oltre il tetto sono [OriginResult.Failed]. Nessuna di queste cose
 * lancia: solo l'annullamento risale, e lascia il NAS chiuso e nessun `.part` sul disco (E-V5: il pager che lascia la pagina
 * annulla la lettura).
 *
 * **Dati mobili (E-V2).** Con il collegamento a consumo ([NasLink]) l'originale non si legge finche' l'utente non l'ha
 * accettato per quel media ([MobileDataConsent]); l'HQ della replica e i temporanei gia' sul telefono non costano rete e
 * non chiedono niente.
 *
 * **Una lettura per media.** Due richieste per lo stesso media (la pagina e lo zoom) non leggono due volte: la seconda aspetta
 * la prima e trova il temporaneo.
 *
 * Nessun percorso, nome o id nei log.
 */
class NasImageOrigin(
  private val reader: NasMediaReader,
  private val locator: NasMediaLocator,
  private val store: NasOriginalStore,
  private val replica: ReplicaBlobs? = null,
  private val link: NasLink = NasLink.Unmetered,
  private val consent: MobileDataConsent = MobileDataConsent(),
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val purpose: Purpose = Purpose.INTERACTIVE,
  private val maxOriginalBytes: Long = DEFAULT_MAX_ORIGINAL_BYTES,
  /**
   * Come si leggono i byte di un temporaneo. Di norma [FileImageBytes] (`ImageDecoder.createSource(File)`, a flusso, mai il
   * file intero in memoria); i test di JVM lo sostituiscono perche' Robolectric non decodifica da un `File`.
   */
  private val bytesOf: (File) -> ImageBytes = { FileImageBytes(it) },
  /** R1 (revisione 4A): le share del media e la chiave intera, per prendere l'HQ solo dalla sua area. */
  private val scopes: NasBlobScopes? = null,
) : ImageOrigin, OriginalFetcher {

  /** Le ultime posizioni risolte: servono alle risposte che non possono sospendere ([openLocal], [declaredSize]). */
  private val located: MutableMap<Long, NasMediaLocation> = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<Long, NasMediaLocation>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NasMediaLocation>?): Boolean = size > LOCATED_ENTRIES
    },
  )

  private val locks = KeyedLocks()

  override suspend fun open(ref: MediaRef): OriginResult = open(ref) { }

  override suspend fun open(ref: MediaRef, onProgress: (Float?) -> Unit): OriginResult {
    val nas = ref as? MediaRef.Nas ?: return OriginResult.Missing
    val location = try {
      locate(nas)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "posizione non risolta: ${e.javaClass.simpleName}" }
      return OriginResult.Failed(ImageFailure.DECODE_FAILED)
    } ?: return OriginResult.Missing

    store.get(nas.locationId, location.stamp)?.let { return OriginResult.Opened(bytesOf(it)) }
    val scope = try {
      scopes?.scopeOf(nas)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    hq(nas, scope)?.let { return OriginResult.Opened(it, isOriginal = false) }
    return when (val fetched = fetchOriginal(nas, location, onProgress)) {
      is Fetched.Done -> OriginResult.Opened(bytesOf(fetched.file))
      is Fetched.Blocked -> OriginResult.Unreachable(fetched.reason, fetched.sizeBytes)
      Fetched.Missing -> OriginResult.Missing
      is Fetched.Failed -> OriginResult.Failed(fetched.code)
    }
  }

  /** Solo l'originale gia' sul telefono: e' cio' che serve a leggere la testa del file (formato, dimensioni, tasselli). */
  override fun openLocal(ref: MediaRef): ImageBytes? = localFile(ref)?.let(bytesOf)

  override fun declaredSize(ref: MediaRef): DeclaredSize? {
    val nas = ref as? MediaRef.Nas ?: return null
    val location = located[nas.locationId] ?: return null
    val w = location.width ?: return null
    val h = location.height ?: return null
    return DeclaredSize(w, h)
  }

  override fun localFile(ref: MediaRef): File? {
    val nas = ref as? MediaRef.Nas ?: return null
    return store.get(nas.locationId, located[nas.locationId]?.stamp)
  }

  override fun fetch(ref: MediaRef): Flow<OriginalFetch> = channelFlow {
    val nas = ref as? MediaRef.Nas
    if (nas == null) {
      send(OriginalFetch.Missing)
      return@channelFlow
    }
    val location = try {
      locate(nas)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      send(OriginalFetch.Failed(ImageFailure.DECODE_FAILED))
      return@channelFlow
    }
    if (location == null) {
      send(OriginalFetch.Missing)
      return@channelFlow
    }
    val ready = store.get(nas.locationId, location.stamp)
    if (ready != null) {
      send(OriginalFetch.Ready(ready))
      return@channelFlow
    }
    send(OriginalFetch.Reading(null))
    val fetched = fetchOriginal(nas, location) { p -> trySend(OriginalFetch.Reading(p)) }
    send(
      when (fetched) {
        is Fetched.Done -> OriginalFetch.Ready(fetched.file)
        is Fetched.Blocked -> OriginalFetch.Blocked(fetched.reason, fetched.sizeBytes)
        Fetched.Missing -> OriginalFetch.Missing
        is Fetched.Failed -> OriginalFetch.Failed(fetched.code)
      },
    )
  }
    // Tutto il lavoro (catalogo, disco, rete) sui thread di lavoro: chi raccoglie e' la pagina, sul thread principale.
    .flowOn(dispatchers.io)
    .conflate()

  // --- Passi ---------------------------------------------------------------------------------------------------------

  private suspend fun locate(nas: MediaRef.Nas): NasMediaLocation? {
    val location = locator.locate(nas)
    if (location != null) located[nas.locationId] = location
    return location
  }

  /** L'HQ della replica, se c'e' e si legge; un pacco sparito nel frattempo (replica azzerata) vale "non c'e'". */
  private fun hq(nas: MediaRef.Nas, scope: BlobScope?): ImageBytes? {
    val blobs = replica ?: return null
    return try {
      val blob = (if (scope == null) blobs.locate(nas.contentKey, SegmentClass.HQ) else blobs.locate(nas.contentKey, SegmentClass.HQ, scope)) ?: return null
      BufferImageBytes(blobs.read(blob))
    } catch (e: IOException) {
      null
    } catch (e: RuntimeException) {
      null
    }
  }

  private sealed interface Fetched {
    class Done(val file: File) : Fetched

    class Blocked(val reason: UnavailableReason, val sizeBytes: Long?) : Fetched

    data object Missing : Fetched

    class Failed(val code: ImageFailure) : Fetched
  }

  private suspend fun fetchOriginal(nas: MediaRef.Nas, location: NasMediaLocation, onProgress: (Float?) -> Unit): Fetched {
    if (location.sizeBytes > maxOriginalBytes) return Fetched.Failed(ImageFailure.OUT_OF_MEMORY)
    // D-15: prima il percorso (VPN spenta fuori casa, nessuna rete, rete non consentita), poi i dati mobili: il NAS che non si raggiunge non si
    // raggiunge nemmeno con la conferma.
    link.blockedReason(consent.isConfirmed(nas.locationId))?.let { return Fetched.Blocked(it, location.sizeBytes) }
    return locks.withLock(nas.locationId) {
      // Se un'altra richiesta l'ha finito mentre si aspettava, non si legge un'altra volta.
      store.get(nas.locationId, location.stamp)?.let { return@withLock Fetched.Done(it) }
      download(nas, location, onProgress)
    }
  }

  private suspend fun download(nas: MediaRef.Nas, location: NasMediaLocation, onProgress: (Float?) -> Unit): Fetched {
    val file = when (val opened = reader.open(location.share, location.path, purpose)) {
      is NasResult.Err -> return fromError(opened.error, location.sizeBytes)
      is NasResult.Ok -> opened.value
    }
    try {
      if (file.size > maxOriginalBytes) return Fetched.Failed(ImageFailure.OUT_OF_MEMORY)
      val writer = try {
        store.begin(nas.locationId, file.stamp, file.size)
      } catch (e: IOException) {
        return Fetched.Failed(if (NasOriginalStore.isNoSpace(e)) ImageFailure.STORAGE_FULL else ImageFailure.DECODE_FAILED)
      }
      var committed = false
      try {
        var writeError: IOException? = null
        var lastPercent = -1
        val total = file.size.coerceAtLeast(1L)
        val outcome = reader.readAll(
          file = file,
          sink = { buffer, length ->
            try {
              writer.write(buffer, 0, length)
            } catch (e: IOException) {
              writeError = e
              throw e
            }
          },
          progress = { done ->
            val percent = (done * PERCENT / total).toInt()
            if (percent != lastPercent) {
              lastPercent = percent
              onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
          },
        )
        writeError?.let {
          return Fetched.Failed(if (NasOriginalStore.isNoSpace(it)) ImageFailure.STORAGE_FULL else ImageFailure.DECODE_FAILED)
        }
        if (outcome is NasResult.Err) return fromError(outcome.error, location.sizeBytes)
        return try {
          Fetched.Done(writer.commit()).also { committed = true }
        } catch (e: IOException) {
          committed = true // commit ha gia' tolto il .part
          Fetched.Failed(ImageFailure.DECODE_FAILED)
        }
      } catch (e: IOException) {
        return Fetched.Failed(if (NasOriginalStore.isNoSpace(e)) ImageFailure.STORAGE_FULL else ImageFailure.DECODE_FAILED)
      } finally {
        if (!committed) writer.abort()
      }
    } finally {
      file.close()
    }
  }

  /** Cosa dire all'utente per ogni errore del NAS: quasi sempre "non risponde"; la rete negata e il media sparito hanno il loro caso. */
  private fun fromError(error: NasError, sizeBytes: Long): Fetched = when (error) {
    NasError.NotFound -> Fetched.Missing
    is NasError.NotAllowedHere, NasError.LocalNetworkBlocked -> Fetched.Blocked(UnavailableReason.NOT_ALLOWED_HERE, sizeBytes)
    else -> Fetched.Blocked(UnavailableReason.NAS_UNREACHABLE, sizeBytes)
  }

  /** Un lucchetto per chiave, che sparisce quando nessuno lo usa piu' (senza liste che crescono). */
  private class KeyedLocks {
    private class Entry {
      val mutex = Mutex()
      var users = 0
    }

    private val map = HashMap<Long, Entry>()

    suspend fun <T> withLock(key: Long, block: suspend () -> T): T {
      val entry = synchronized(map) { map.getOrPut(key) { Entry() }.also { it.users++ } }
      try {
        return entry.mutex.withLock { block() }
      } finally {
        synchronized(map) {
          if (--entry.users == 0) map.remove(key)
        }
      }
    }
  }

  companion object {
    private const val TAG = "NasImageOrigin"
    private const val LOCATED_ENTRIES = 256
    private const val PERCENT = 100

    /** Un originale oltre questa dimensione non si legge per mostrarlo (un RAW da 200 MP e' sotto): il telefono non lo regge. */
    const val DEFAULT_MAX_ORIGINAL_BYTES: Long = 512L shl 20
  }
}
