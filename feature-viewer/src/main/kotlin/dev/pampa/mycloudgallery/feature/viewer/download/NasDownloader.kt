package dev.pampa.mycloudgallery.feature.viewer.download

import android.webkit.MimeTypeMap
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocator
import dev.pampa.mycloudgallery.core.imaging.viewer.blockedReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Il collegamento al catalogo della copia scritta sul telefono (D20): `attachCopy` con la posizione e l'impronta, **prima**
 * che il file diventi visibile. :app (P11) lo realizza leggendo da [ref.locationId] il media e la versione della posizione
 * del NAS e chiamando `MediaCatalog.attachCopy(mediaId, versionId, copy.location, FileStamp(copy.size, ...), null)`. Se lancia,
 * il file non si pubblica ([PhoneMediaWriter]): visibile implica collegato.
 */
fun interface CopyLinker {
  suspend fun link(ref: MediaRef.Nas, copy: PendingCopy)
}

/**
 * "Scarica sul telefono" (Fase 4, P8; docs/fase4-progetto.md §2.6, D20, E-L2): legge l'originale dal NAS con `readAll`
 * (4 flussi, ordine, SHA-256 mentre arriva) e lo consegna a [PhoneMediaWriter], che lo scrive in `Pictures/MyCloud Gallery`
 * con `IS_PENDING`, lo collega al catalogo tramite [CopyLinker] e solo dopo lo rende visibile.
 *
 * **Uno alla volta.** I download vanno in coda (un permesso): il NAS e il telefono non si contendono banda e spazio, e dieci
 * media scelti dalla selezione arrivano in ordine, con lo stato di ciascuno ([states]). Un download interrotto (NAS spento a
 * meta', processo morto, annullamento) non lascia mai un file a meta': vedi [PhoneMediaWriter].
 *
 * **Dati mobili.** Come per le foto e i video: con il collegamento a consumo e senza conferma per quel media si ferma prima di
 * leggere, con la dimensione ([DownloadState.NeedsMobileData]).
 *
 * **Integrita'.** L'hash completo noto all'indice (`NasMediaLocation.knownSha256`), se c'e', si confronta con quello dei byte
 * arrivati: diverso = `INTEGRITY` e nessun file; la dimensione della copia deve essere quella del file aperto sul NAS. Se il
 * catalogo non e' piu' d'accordo con il file (modificato dal PC) il collegamento rifiuta e il file non compare: meglio un
 * secondo tentativo dopo la riscansione che una copia legata alla versione sbagliata.
 *
 * @param scope lo scope dell'app: il download sopravvive al viewer.
 */
class NasDownloader(
  private val scope: CoroutineScope,
  private val reader: NasMediaReader,
  private val locator: NasMediaLocator,
  private val writer: PhoneMediaWriter,
  private val linker: CopyLinker,
  private val link: NasLink = NasLink.Unmetered,
  private val consent: MobileDataConsent = MobileDataConsent(),
  private val purpose: Purpose = Purpose.INTERACTIVE,
) : PhoneDownload {

  private val mutableStates = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
  private val jobs = HashMap<Long, Job>()
  private val queue = Semaphore(1)

  override val states: StateFlow<Map<Long, DownloadState>> = mutableStates.asStateFlow()

  override fun canDownload(ref: MediaRef): Boolean = ref is MediaRef.Nas

  override fun start(ref: MediaRef.Nas) {
    synchronized(jobs) {
      if (jobs[ref.locationId]?.isActive == true) return
      set(ref.locationId, DownloadState.Queued)
      val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
          queue.withPermit { run(ref) }
        } catch (e: CancellationException) {
          set(ref.locationId, DownloadState.Cancelled)
          throw e
        }
      }
      jobs[ref.locationId] = job
      job.invokeOnCompletion {
        synchronized(jobs) {
          if (jobs[ref.locationId] === job) jobs.remove(ref.locationId)
        }
      }
      job.start()
    }
  }

  override fun cancel(ref: MediaRef.Nas) {
    val job = synchronized(jobs) { jobs[ref.locationId] }
    job?.cancel()
  }

  override fun acknowledge(locationId: Long) {
    mutableStates.update { current ->
      // D-15: anche "servono i dati mobili" si scarta: e' un'attesa di conferma, non un lavoro; se la rete cambia non e' piu' vera.
      val state = current[locationId]
      if (state?.isTerminal == true || state is DownloadState.NeedsMobileData) current - locationId else current
    }
  }

  private fun set(locationId: Long, state: DownloadState) {
    mutableStates.update { it + (locationId to state) }
  }

  private suspend fun run(ref: MediaRef.Nas) {
    set(ref.locationId, DownloadState.Running(null))
    val outcome = try {
      save(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "download non riuscito: ${e.javaClass.simpleName}" }
      DownloadState.Failed(DownloadFailure.OTHER)
    }
    set(ref.locationId, outcome)
  }

  private suspend fun save(ref: MediaRef.Nas): DownloadState {
    val location = locator.locate(ref) ?: return DownloadState.Failed(DownloadFailure.NOT_FOUND)
    // D-15: prima il percorso (senza VPN fuori casa i dati mobili non servono a niente), poi i dati mobili senza conferma.
    when (link.blockedReason(consent.isConfirmed(ref.locationId))) {
      null -> Unit
      UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION -> return DownloadState.NeedsMobileData(location.sizeBytes)
      UnavailableReason.VPN_OFF_AWAY -> return DownloadState.Failed(DownloadFailure.VPN_OFF_AWAY)
      UnavailableReason.NOT_ALLOWED_HERE -> return DownloadState.Failed(DownloadFailure.NOT_ALLOWED_HERE)
      else -> return DownloadState.Failed(DownloadFailure.NAS_UNREACHABLE)
    }
    val file = when (val opened = reader.open(location.share, location.path, purpose)) {
      is NasResult.Err -> return DownloadState.Failed(failureOf(opened.error))
      is NasResult.Ok -> opened.value
    }
    try {
      val name = location.fileName
      val mime = ref.mime ?: mimeOfName(name) ?: "application/octet-stream"
      val request = SaveRequest(name, mime, file.size)
      var nasError: NasError? = null
      val total = file.size.coerceAtLeast(1L)
      var lastPercent = -1
      val source = PhoneSaveSource { sink ->
        when (val read = reader.readAll(file, sink, progress = null)) {
          is NasResult.Err -> {
            nasError = read.error
            throw NasIoException(read.error)
          }
          is NasResult.Ok -> Unit
        }
      }
      val result = writer.saveToPictures(
        request = request,
        source = source,
        expectedSha256 = location.knownSha256,
        progress = { bytes ->
          val percent = (bytes * PERCENT / total).toInt()
          if (percent != lastPercent) {
            lastPercent = percent
            set(ref.locationId, DownloadState.Running((bytes.toFloat() / total).coerceIn(0f, 1f)))
          }
        },
        beforePublish = { copy -> linker.link(ref, copy) },
      )
      return when (result) {
        is SaveResult.Saved -> DownloadState.Done(result.copy.location.displayName)
        SaveResult.Cancelled -> DownloadState.Cancelled
        is SaveResult.Failed -> DownloadState.Failed(
          when (result.code) {
            "PHONE_FULL" -> DownloadFailure.PHONE_FULL
            "HASH_MISMATCH", "SIZE_MISMATCH" -> DownloadFailure.INTEGRITY
            "SOURCE_FAILED" -> nasError?.let(::failureOf) ?: DownloadFailure.NAS_UNREACHABLE
            else -> DownloadFailure.OTHER
          },
        )
      }
    } finally {
      file.close()
    }
  }

  private fun failureOf(error: NasError): DownloadFailure = when (error) {
    NasError.NotFound -> DownloadFailure.NOT_FOUND
    is NasError.NotAllowedHere, NasError.LocalNetworkBlocked -> DownloadFailure.NOT_ALLOWED_HERE
    NasError.NasFull, NasError.QuotaExceeded -> DownloadFailure.OTHER
    else -> DownloadFailure.NAS_UNREACHABLE
  }

  private fun mimeOfName(name: String): String? {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
  }

  private companion object {
    const val TAG = "NasDownloader"
    const val PERCENT = 100
  }
}
