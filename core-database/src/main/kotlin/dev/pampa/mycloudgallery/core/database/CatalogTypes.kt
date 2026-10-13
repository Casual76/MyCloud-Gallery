package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.database.entity.MediaStoreCursorEntity
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume

/**
 * Un cambiamento del catalogo, annunciato da [MediaCatalog.changes] **dopo il commit** della transazione
 * che l'ha fatto: chi lo riceve e rilegge il database vede gia' i dati nuovi.
 *
 * - [seq] e' il valore di `change_seq` scritto da quella transazione: sale di **uno** a ogni
 *   transazione che cambia l'indice. Un salto (seq piu' grande dell'ultimo visto + 1) vuol dire che un
 *   cambiamento e' andato perso (il flusso non trattiene chi resta indietro): chi tiene un indice in
 *   memoria allora lo ricostruisce, come all'avvio quando l'istantanea su file ha un `seq` vecchio.
 * - [ids] sono i media la cui riga dell'indice (o il riferimento ai byte) puo' essere cambiata, compresi
 *   quelli usciti dall'indice o cancellati da un'unione: si rileggono con `TimelineIndexDao.indexRows`.
 * - [structural] dice se e' cambiato l'**insieme** o l'**ordine** delle righe (media nuovi, spariti,
 *   nascosti, uniti, raggruppati, data di scatto o giorno cambiati); se e' falso sono cambiati solo
 *   attributi di righe esistenti (preferito, proporzione, segnaposto, flag, presenza, "al sicuro").
 */
data class CatalogChange(
  val seq: Long,
  val ids: Set<MediaId>,
  val structural: Boolean,
)

/**
 * Il cursore della lettura del MediaStore su un volume (vedi `MediaStoreCursorEntity` per il
 * significato di ogni campo). Si salva insieme alle osservazioni che lo fanno avanzare
 * ([MediaCatalog.recordAll], [MediaCatalog.confirmAllPresent], [MediaCatalog.markVanishedNotSeenSince]) o
 * da solo con [MediaCatalog.saveMediaStoreCursor].
 */
data class MediaStoreCursor(
  val volume: String,
  /** La `MediaStore.getVersion()` con cui valgono gli `_ID` in cache. */
  val msVersion: String? = null,
  val generation: Long? = null,
  val deletedGeneration: Long? = null,
  /** Inizio della scansione completa in corso (ora di riferimento), `null` se non ce n'e' una. */
  val scanEpoch: Long? = null,
  /** L'ultimo `_ID` della scansione completa in corso gia' scritto: si riprende da qui (E-P4, E-P8). */
  val scanResumeAfterId: Long? = null,
  val lastFullScanAt: Long? = null,
  val lastReconcileAt: Long? = null,
) {
  init {
    require(isValidMediaStoreVolume(volume)) { "Nome di volume MediaStore non valido." }
    require(scanResumeAfterId == null || scanEpoch != null) { "Si riprende solo una scansione in corso." }
  }
}

/** Una riga del MediaStore come la vede la scorciatoia del timbro: posto e timbro, nessun byte letto. */
data class PhoneStamp(
  val location: Location.Phone,
  val stamp: FileStamp,
)

internal fun MediaStoreCursorEntity.toDomain(): MediaStoreCursor = MediaStoreCursor(
  volume = volume,
  msVersion = msVersion,
  generation = generation,
  deletedGeneration = deletedGeneration,
  scanEpoch = scanEpoch,
  scanResumeAfterId = scanResumeAfterId,
  lastFullScanAt = lastFullScanAt,
  lastReconcileAt = lastReconcileAt,
)

internal fun MediaStoreCursor.toEntity(): MediaStoreCursorEntity = MediaStoreCursorEntity(
  volume = volume,
  msVersion = msVersion,
  generation = generation,
  deletedGeneration = deletedGeneration,
  scanEpoch = scanEpoch,
  scanResumeAfterId = scanResumeAfterId,
  lastFullScanAt = lastFullScanAt,
  lastReconcileAt = lastReconcileAt,
)
