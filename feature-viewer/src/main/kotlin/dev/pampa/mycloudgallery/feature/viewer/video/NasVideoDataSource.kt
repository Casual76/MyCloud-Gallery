package dev.pampa.mycloudgallery.feature.viewer.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocator
import dev.pampa.mycloudgallery.core.imaging.viewer.blockedReason
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking

/**
 * Se un video del NAS puo' partire adesso (Fase 4, D19, E-V2). Non tocca la rete: risolve la posizione e guarda il
 * collegamento. Se il NAS non risponde lo dira' il lettore, con un errore di rete che il viewer spiega e a cui offre
 * "Riprova" (E-L4).
 */
sealed interface NasVideoGate {

  /** Si puo' riprodurre; [sizeBytes] e' il peso secondo il catalogo. */
  data class Ready(val sizeBytes: Long) : NasVideoGate

  /** Dati mobili da confermare per quel video: "Questo video pesa 1,2 GB. Guardarlo con i dati mobili?" (E-V2). */
  data class NeedsMobileData(val sizeBytes: Long) : NasVideoGate

  /**
   * D-15 delle prove sul campo: da questa rete il NAS non si raggiunge ([reason]: VPN spenta fuori casa, nessuna rete, rete non consentita). Non si
   * propongono i dati mobili: non servirebbero a niente. Il viewer lo dice e riprova da solo quando la rete cambia.
   */
  data class Unreachable(val reason: UnavailableReason) : NasVideoGate

  /** Il media non e' piu' nel catalogo (E-V6). */
  data object Missing : NasVideoGate
}

/**
 * I video del NAS per il viewer (Fase 4, P8; docs/fase4-progetto.md §2.6, D19).
 *
 * **Streaming a intervalli, mai interi.** [NasVideoDataSource] e' il `DataSource` di Media3 che ExoPlayer usa per questi
 * video: legge dal NAS solo cio' che il lettore chiede (l'indice del contenitore, poi i blocchi che servono), con una
 * lettura anticipata che parte piccola e arriva a 4 MiB. **Non esiste nessun altro `DataSource` dietro**: niente
 * `DefaultDataSource`, niente rete che non sia `NasMediaReader` (che passa dal cancello di rete e dalla recinzione D33).
 *
 * Un'istanza per app, condivisa con le foto del NAS: lo stesso [MobileDataConsent] e lo stesso [NasLink].
 */
class NasVideoSource(
  private val reader: NasMediaReader,
  private val locator: NasMediaLocator,
  private val link: NasLink = NasLink.Unmetered,
  private val consent: MobileDataConsent = MobileDataConsent(),
  private val purpose: Purpose = Purpose.INTERACTIVE,
) {

  /**
   * Puo' partire? Si ferma qui, prima di aprire il NAS, se la rete di adesso non lo permette: D-15, prima il percorso (VPN spenta fuori casa,
   * nessuna rete, rete non consentita: [NasVideoGate.Unreachable]), poi i dati mobili senza conferma ([NasVideoGate.NeedsMobileData]).
   */
  suspend fun check(ref: MediaRef.Nas): NasVideoGate {
    val location = locator.locate(ref) ?: return NasVideoGate.Missing
    return when (val reason = link.blockedReason(consent.isConfirmed(ref.locationId))) {
      null -> NasVideoGate.Ready(location.sizeBytes)
      UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION -> NasVideoGate.NeedsMobileData(location.sizeBytes)
      else -> NasVideoGate.Unreachable(reason)
    }
  }

  /** La sorgente da dare al lettore per [ref]: un'etichetta senza percorsi, con l'estensione che aiuta l'estrattore. */
  fun playbackSource(ref: MediaRef.Nas): PlaybackSource.Nas =
    PlaybackSource.Nas(ref, Uri.parse("$SCHEME://v/${ref.locationId}${extensionOf(ref.mime)}"))

  /** Il `DataSource` per [source]: legato a quel video e a nessun altro indirizzo. */
  internal fun newDataSource(source: PlaybackSource.Nas): NasVideoDataSource =
    NasVideoDataSource(source.ref, source.uri, reader, locator, purpose)

  internal companion object {
    const val SCHEME: String = "nasmedia"

    fun extensionOf(mime: String?): String = when (mime?.lowercase()) {
      "video/mp4" -> ".mp4"
      "video/quicktime" -> ".mov"
      "video/x-matroska" -> ".mkv"
      "video/webm" -> ".webm"
      "video/3gpp" -> ".3gp"
      else -> ""
    }
  }
}

/**
 * Il `DataSource` di Media3 sopra [NasMediaReader] (D19). Apre il video **una volta** per ogni `open` (una `CREATE` SMB),
 * poi serve `read` da un buffer riempito a blocchi a intervalli: il primo da [firstChunk] (l'indice `moov`/`ftyp` non vuole
 * megabyte), poi raddoppia fino a [maxChunk] (4 MiB, la lettura anticipata). Un salto del lettore (scorrimento) e' un nuovo
 * `open` alla posizione nuova: non si legge mai il file dall'inizio per arrivarci.
 *
 * **Il lettore lo annulla interrompendo il thread.** Media3 chiama `open`/`read` da un thread di caricamento e per
 * annullare lo interrompe: `runBlocking` risponde all'interruzione annullando la lettura sospesa e noi lanciamo
 * [InterruptedIOException], che il caricatore tratta come annullamento (non un errore). Il file si chiude in [close].
 *
 * **Gli errori hanno un codice di Media3** (`DataSourceException`) cosi' il lettore li riporta al viewer: rete o NAS non
 * raggiungibile → `IO_NETWORK_CONNECTION_FAILED`/`_TIMEOUT` (che il viewer spiega come "il NAS non risponde"), media
 * sparito → `IO_FILE_NOT_FOUND`, permesso negato → `IO_NO_PERMISSION`. Un indirizzo diverso da quello della sorgente non
 * si apre (nessun `http`, nessun `file`, nessun altro media).
 *
 * Nessun percorso, nome o id nei log.
 */
@OptIn(UnstableApi::class)
internal class NasVideoDataSource(
  private val ref: MediaRef.Nas,
  private val boundUri: Uri,
  private val reader: NasMediaReader,
  private val locator: NasMediaLocator,
  private val purpose: Purpose = Purpose.INTERACTIVE,
  private val firstChunk: Int = FIRST_CHUNK_BYTES,
  private val maxChunk: Int = MAX_CHUNK_BYTES,
) : BaseDataSource(/* isNetwork = */ true) {

  private var dataSpec: DataSpec? = null
  private var file: NasRandomFile? = null
  private var opened = false

  /** I byte che mancano alla fine di questa lettura (dopo `length` o la fine del file). */
  private var bytesRemaining = 0L

  /** Dove sta, nel file, il prossimo byte da leggere dal NAS. */
  private var nextFileOffset = 0L

  private var buffer: ByteArray = EMPTY
  private var bufferPosition = 0
  private var bufferLimit = 0
  private var chunk = firstChunk

  /** Quanti byte ha chiesto al NAS in tutto: la lettura a intervalli si controlla con questo (i video non si scaricano interi). */
  var bytesRequested: Long = 0
    private set

  /** Quante letture dal NAS (blocchi), per controllare la lettura anticipata. */
  var chunksRead: Int = 0
    private set

  override fun open(dataSpec: DataSpec): Long {
    if (dataSpec.uri != boundUri) throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    transferInitializing(dataSpec)
    this.dataSpec = dataSpec
    val location: NasMediaLocation = blocking { locator.locate(ref) }
      ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    val opened = blocking { reader.open(location.share, location.path, purpose) }
    val nasFile = when (opened) {
      is NasResult.Ok -> opened.value
      is NasResult.Err -> throw failureOf(opened.error)
    }
    try {
      val position = dataSpec.position
      if (position > nasFile.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
      val remaining = nasFile.size - position
      bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
    } catch (e: Exception) {
      nasFile.close()
      throw e
    }
    file = nasFile
    nextFileOffset = dataSpec.position
    bufferPosition = 0
    bufferLimit = 0
    chunk = firstChunk
    this.opened = true
    transferStarted(dataSpec)
    return bytesRemaining
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
    if (bufferPosition >= bufferLimit) fill()
    val n = minOf(length.toLong(), (bufferLimit - bufferPosition).toLong(), bytesRemaining).toInt()
    System.arraycopy(buffer, bufferPosition, target, offset, n)
    bufferPosition += n
    bytesRemaining -= n
    bytesTransferred(n)
    return n
  }

  override fun getUri(): Uri? = dataSpec?.uri

  override fun close() {
    try {
      file?.close()
    } finally {
      file = null
      buffer = EMPTY
      bufferPosition = 0
      bufferLimit = 0
      if (opened) {
        opened = false
        transferEnded()
      }
    }
  }

  /** Legge il blocco seguente dal NAS nel buffer: [chunk] byte (o quanti ne restano), poi il blocco successivo raddoppia. */
  private fun fill() {
    val nasFile = file ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    val want = minOf(chunk.toLong(), bytesRemaining).toInt()
    if (buffer.size < want) buffer = ByteArray(minOf(maxChunk.toLong(), maxOf(want.toLong(), bytesRemaining)).toInt())
    var done = 0
    bytesRequested += want
    chunksRead++
    while (done < want) {
      val got = try {
        blocking { nasFile.read(nextFileOffset + done, buffer, done, want - done) }
      } catch (e: NasIoException) {
        throw failureOf(e.error)
      }
      // Il file si e' accorciato (modificato dal PC mentre lo guardavamo): non e' piu' quello che il lettore sta leggendo.
      if (got <= 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
      done += got
    }
    nextFileOffset += done
    bufferPosition = 0
    bufferLimit = done
    chunk = minOf(chunk.toLong() * 2, maxChunk.toLong()).toInt()
  }

  /**
   * Fa girare una chiamata sospesa su questo thread di caricamento. L'interruzione del thread (il lettore annulla) annulla
   * la chiamata e diventa [InterruptedIOException]; un errore di Media3 gia' pronto passa com'e'.
   */
  private fun <T> blocking(block: suspend () -> T): T = try {
    runBlocking { block() }
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedIOException("Lettura annullata.")
  } catch (e: IOException) {
    throw e
  } catch (e: RuntimeException) {
    // Un catalogo o un lettore che lancia altro non deve far morire il lettore con un errore generico: e' un'I/O fallita.
    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
  }

  private fun failureOf(error: NasError): IOException = DataSourceException(
    when (error) {
      NasError.NotFound -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
      NasError.PermissionDenied -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
      NasError.Timeout, NasError.Stalled -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
      else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    },
  )

  companion object {
    /** Il primo blocco: l'indice del contenitore sta nei primi KiB, non servono megabyte per cominciare. */
    const val FIRST_CHUNK_BYTES: Int = 256 * 1024

    /** La lettura anticipata a regime (D19). */
    const val MAX_CHUNK_BYTES: Int = 4 * 1024 * 1024

    private val EMPTY = ByteArray(0)
  }
}
