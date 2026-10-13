package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Dove e' arrivata la lettura del MediaStore su un volume (v2, `docs/fase3-progetto.md` §2.1). Si scrive
 * **nella stessa transazione** delle osservazioni che lo fanno avanzare (`MediaCatalog.recordAll` e
 * compagni): dopo un riavvio a meta' giro il catalogo e il cursore dicono la stessa cosa (E-P4, E-P8).
 *
 * - `ms_version`: la `MediaStore.getVersion()` con cui valgono gli `_ID` in cache nelle posizioni. Una
 *   versione diversa vuol dire scansione completa (E-P12, E-P22) e, finche' una riga non viene riletta,
 *   nessun riferimento ai suoi byte (`TimelineIndexDao.phoneRefs`).
 * - `generation`, `deleted_generation`: la `GENERATION_MODIFIED` fino a cui l'incrementale e le
 *   cancellazioni sono state applicate.
 * - `scan_epoch`, `scan_resume_after_id`: la scansione completa in corso (inizio e ultimo `_ID` fatto);
 *   NULL se non ce n'e' una.
 * - `last_full_scan_at`, `last_reconcile_at`: l'ultima scansione completa finita e l'ultima
 *   riconciliazione degli `_ID`.
 */
@Entity(tableName = "media_store_cursor")
data class MediaStoreCursorEntity(
  @PrimaryKey @ColumnInfo(name = "volume") val volume: String,
  @ColumnInfo(name = "ms_version") val msVersion: String?,
  @ColumnInfo(name = "generation") val generation: Long?,
  @ColumnInfo(name = "deleted_generation") val deletedGeneration: Long?,
  @ColumnInfo(name = "scan_epoch") val scanEpoch: Long?,
  @ColumnInfo(name = "scan_resume_after_id") val scanResumeAfterId: Long?,
  @ColumnInfo(name = "last_full_scan_at") val lastFullScanAt: Long?,
  @ColumnInfo(name = "last_reconcile_at") val lastReconcileAt: Long?,
)

/**
 * Valori singoli del catalogo, come interi (v2). Chiavi in uso:
 * - `change_seq`: sale di uno a ogni transazione del catalogo che cambia l'indice della timeline; e'
 *   cio' con cui l'istantanea su file si riconcilia (`CatalogChange`);
 * - `places_dataset`: la versione del dataset dei luoghi con cui sono stati calcolati i `place_id`.
 */
@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
  @PrimaryKey @ColumnInfo(name = "key") val key: String,
  @ColumnInfo(name = "value") val value: Long,
)
