package dev.pampa.mycloudgallery.core.metadata.contract

import android.media.MediaDataSource

/**
 * I byte di un media, letti **a intervalli**.
 *
 * **Perche' un'astrazione e non un `File` o uno stream.** Un'impronta, l'EXIF e la ricerca di una motion photo
 * guardano solo la testa e la coda di un file (64 KiB ciascuna), mai tutto: un JPEG da 200 MP pesa 60 MB e un
 * video 4K qualche GB. Con letture a intervalli lo stesso codice gira sul telefono (`ContentResolver`, con
 * `setRequireOriginal` quando serve la posizione), sui test (un `ByteArray`) e, in Fase 4, sul NAS via SMB
 * (`read` con offset). Nessun chiamante si aspetta di poter rileggere due volte lo stesso intervallo a costo zero.
 *
 * L'implementazione e' di chi apre il file (P2 per il telefono); il `MediaBytes` non deve restare aperto oltre la
 * singola estrazione.
 */
interface MediaBytes {

  /** La dimensione in byte. */
  val size: Long

  /**
   * Legge [range] (estremi inclusi, come `LongRange`); gli intervalli oltre [size] si accorciano. Un intervallo
   * vuoto o fuori dal file da' un array vuoto. Su un errore di I/O lancia l'eccezione di I/O: l'estrattore la
   * trasforma in `Extraction.Corrupt`.
   */
  suspend fun read(range: LongRange): ByteArray

  /**
   * Una sorgente per `MediaMetadataRetriever` e `MediaPlayer` (video e motion photo). Chiude con il `MediaBytes`.
   * I metodi di `MediaDataSource` sono sincroni e girano su thread di lavoro del sistema.
   */
  fun dataSource(): MediaDataSource
}

/**
 * Un intervallo di byte `[start, endExclusive)` dentro un file: la parte video di una motion photo (si riproduce
 * con `ContentDataSource` e un intervallo, D7), il blocco XMP, la coda con il trailer di Samsung.
 */
data class ByteRange(val start: Long, val endExclusive: Long) {

  init {
    require(start >= 0 && endExclusive >= start) { "Intervallo di byte non valido." }
  }

  val length: Long get() = endExclusive - start

  val isEmpty: Boolean get() = length == 0L

  /** Come `LongRange` (estremi inclusi), per [MediaBytes.read]; un intervallo vuoto da' un `LongRange` vuoto. */
  fun toLongRange(): LongRange = if (isEmpty) LongRange.EMPTY else start until endExclusive
}
