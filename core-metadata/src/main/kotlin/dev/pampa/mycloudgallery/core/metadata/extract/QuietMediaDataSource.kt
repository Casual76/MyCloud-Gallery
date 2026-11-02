package dev.pampa.mycloudgallery.core.metadata.extract

import android.media.MediaDataSource

// Prova sul campo di Fase 10 (release candidata sull'S25): in 85 minuti la release ha scritto 833 stack trace su `System.err` per `ReadLimitExceededException`, il
// tetto di lettura per file del NAS (voluto: un decodificatore che chiede troppo). Non li scrive il nostro logger (`GalleryLog` in release non ha un sink): li scrive il
// **sistema**. Un'eccezione che esce da `MediaDataSource.readAt` (o da un `InputStream` letto da `BitmapFactory`) attraversa il codice nativo, che la descrive
// (`ExceptionDescribe` = `printStackTrace()` su `System.err`) prima di ripulirla e di leggere "errore di lettura". In release i log hanno solo numeri e codici (C21, S6):
// nessuna eccezione deve attraversare il codice nativo. Chi passa una `MediaDataSource` al sistema (`MediaMetadataRetriever`) la passa da qui; chi la legge come flusso
// ([MediaDataSourceInputStream]) prende l'errore da se'.

/**
 * Una [MediaDataSource] che non lancia mai: un errore di lettura (il tetto per file o del giro, il NAS che non risponde, un file rovinato) diventa `-1`, "fine dei dati",
 * come lo vedeva comunque il sistema dopo aver scritto lo stack trace. Il **perche'** non si perde: chi rileva guarda i suoi segni (`NasMediaBytes.failure`, `capHit`,
 * `limitHit`) prima di credere a "file rovinato", e quelli si scrivono prima che l'eccezione parta.
 */
class QuietMediaDataSource(private val delegate: MediaDataSource) : MediaDataSource() {

  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
    try {
      delegate.readAt(position, buffer, offset, size)
    } catch (_: Exception) {
      -1
    }

  override fun getSize(): Long =
    try {
      delegate.size
    } catch (_: Exception) {
      -1L
    }

  override fun close() {
    try {
      delegate.close()
    } catch (_: Exception) {
      // Una chiusura fallita non cambia l'esito.
    }
  }
}
