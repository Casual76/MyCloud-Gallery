package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.publish.IndexProvenanceException
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.run.PrepRecords
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CancellationException

/**
 * Il record `meta` di base di una versione dal catalogo locale (tipo, sorgente, MIME, data di scatto, posizione, dimensioni...) per
 * il file pubblicato con timbro [stamp] e impronte [quickHash] e [fullHash] (calcolate qui dagli stessi byte). Lo lega `:app` (P7) a
 * `MediaCatalog`; null = la versione non c'e' piu' o non si pubblica.
 */
fun interface UploadRecords {
  suspend fun record(mediaId: MediaId, versionId: VersionId, stamp: NasStamp, quickHash: QuickHash, fullHash: Sha256): MetaFile?
}

/**
 * D10, "chi carica prepara" (§2.2, F4-§2.11), sopra il [Preparer] di core-prepare e l'[IndexPublisher] di core-index:
 * - [prepare] fa miniature, anteprime e fotogrammi da `PrepareSource.Bytes` sui **byte appena letti dal telefono** (gli stessi dello
 *   SHA-256: una foto fino a 64 MiB in memoria, un video a intervalli sulla stessa apertura); le uscite valgono solo se i byte preparati
 *   sono quelli dell'hash (`qh1` e, se c'e', l'hash completo uguali: D8);
 * - [publish], dopo che il media e' "al sicuro", mette nell'indice il record del file pubblicato con il **timbro letto dopo la
 *   pubblicazione** (la scansione lo adotta senza rilevarlo, F4-D6) e le immagini. Con F4-D31 acceso o con una share non pubblicabile
 *   `IndexPublisher.stage` le tiene solo nella replica locale (R2 della 4A): niente qui lo scavalca.
 *
 * Nessun errore esce: l'indice non decide niente della sicurezza, e cio' che non si pubblica qui lo rifa' la preparazione (D10).
 */
class PreparingUploadIndexing(
  private val preparer: Preparer,
  private val publisher: IndexPublisher,
  private val records: UploadRecords,
  private val spec: PrepSpec = PrepSpec(),
) : UploadIndexing {

  override val enabled: Boolean = true

  private class Prepared(val done: PrepResult.Done) : PreparedUpload(done.outputs.sumOf { it.bytes() })

  override suspend fun prepare(subject: UploadSubject, bytes: MediaBytes): PreparedUpload? {
    val description = subject.description ?: return null
    val result = try {
      preparer.prepare(PrepareSource.Bytes(bytes, description.kind, description.mime), spec)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "preparazione dai byte del telefono non riuscita (${e::class.java.simpleName})" }
      return null
    }
    val done = result as? PrepResult.Done ?: return null
    // D8: le immagini valgono per la chiave di contenuto solo se i byte preparati sono quelli dell'hash.
    if (done.quickHash != null && done.quickHash != subject.quickHash) return null
    if (done.fullHash != null && done.fullHash != subject.sha256) return null
    return Prepared(done)
  }

  override suspend fun publish(subject: UploadSubject, share: String, path: SharePath, stamp: NasStamp, prepared: PreparedUpload?) {
    val base = records.record(subject.mediaId, subject.versionId, stamp, subject.quickHash, subject.sha256) ?: return
    // Il record descrive il file pubblicato: timbro e `qh1` sono quelli letti qui, mai altri.
    if (!base.stamp.sameAs(stamp) || base.quickHash != subject.quickHash || base.stamp.size != subject.size) return
    val done = (prepared as? Prepared)?.done
    val record = (if (done != null) PrepRecords.merge(base, done) else base).copy(fullHash = subject.sha256)
    val key = ContentKey.of(subject.quickHash, stamp.size)
    val blobs = done?.outputs.orEmpty().map { output ->
      when (output) {
        is PrepOutput.Image -> BlobPut.Image(output.cls, key, output.bytes)
        is PrepOutput.Frames -> BlobPut.Frames(key, output.frames)
      }
    }
    try {
      publisher.stage(share, listOf(MetaChange.Put(path, record)), blobs)
    } catch (e: IndexProvenanceException) {
      // M3: il catalogo dell'indice non conosce ancora il file (la scansione non e' passata): lo adottera' la scansione, le immagini le
      // rifara' la preparazione. Mai un errore del caricamento.
      GalleryLog.i(TAG) { "record non pubblicato: il file non e' ancora nel catalogo della share" }
    }
  }

  private companion object {
    const val TAG = "BackupUpload"
  }
}

/** I byte di un'uscita della preparazione (il tetto della memoria delle uscite tenute dal motore). */
private fun PrepOutput.bytes(): Long = when (this) {
  is PrepOutput.Image -> bytes.size.toLong()
  is PrepOutput.Frames -> frames.sumOf { it.jpeg.size.toLong() }
}
