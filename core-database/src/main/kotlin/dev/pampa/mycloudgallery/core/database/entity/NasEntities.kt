package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v3 (Fase 4, docs/fase4-progetto.md §2.1-§2.3, §2.7; docs/modello-dati.md §11): la scansione del NAS, l'indice
// condiviso (outbox di chi pubblica, replica di chi legge) e il diario delle operazioni sul NAS (4B).
//
// Regole comuni (oltre a quelle di MediaEntities.kt):
// - nessuna tabella qui punta a `media` o a `media_version`: le identita' che compaiono nell'indice sono DATI dei record
//   (com'erano firmati), non riferimenti locali; l'unione e l'adozione (D7) passano dal catalogo
//   (`MediaCatalog.TABLES_WITHOUT_MEDIA_IDS`, `MediaCatalog.TABLES_WITH_INDEX_IDS`, `MergeReferencesTest`);
// - `share_key` e' la forma di confronto del nome della share (`Location.Nas.shareKeyOf`): SMB non distingue le maiuscole;
// - le classi dei segmenti sono il `SegmentClass.code` di core-trust (1 meta, 2 thumbS, 3 thumbM, 4 analysis, 5 hq,
//   6 frames, 16 personal): un intero stabile del formato, non un ordinale;
// - impronte e chiavi in esadecimale minuscolo (64 cifre); istanti in millisecondi, **ora del NAS** dove lo dice il campo;
// - percorsi del NAS e nomi di file sono dati sensibili come il GPS: stanno solo qui, mai nei log o nella Diagnostica.

/**
 * Il cursore della scansione di una share per un account (§2.3, porta `NasScanStore` di core-nasmedia). Si scrive **nella
 * stessa transazione** delle osservazioni che lo fanno avanzare (`MediaCatalog.recordAll` con `nasCursor`,
 * `confirmAllPresentNas`, `markVanishedInShare`): dopo un riavvio a meta' giro catalogo e cursore dicono la stessa cosa
 * (E-P4).
 *
 * - `epoch`: inizio del giro completo in corso (ora del NAS), il "visto a" delle posizioni viste nel giro; NULL = nessuno.
 * - `resume_after`: l'ultima cartella **completata** del giro in corso, nell'ordine di visita della scansione.
 * - `last_full_scan_at`: fine dell'ultimo giro completo (ora del NAS).
 * - `freeze_*`: il congelamento in corso (D10): motivo (`FreezeReason.code`), da quando (ora del NAS) e i conteggi visti;
 *   tutti NULL se la share non e' congelata. Con un congelamento il catalogo **non** segna assenze in quella share.
 */
@Entity(tableName = "nas_scan_state", primaryKeys = ["account_id", "share_key"])
data class NasScanStateEntity(
  @ColumnInfo(name = "account_id") val accountId: String,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "share_name") val shareName: String,
  @ColumnInfo(name = "epoch") val epoch: Long?,
  @ColumnInfo(name = "resume_after") val resumeAfter: String?,
  @ColumnInfo(name = "last_full_scan_at") val lastFullScanAt: Long?,
  @ColumnInfo(name = "freeze_reason") val freezeReason: String?,
  @ColumnInfo(name = "freeze_since") val freezeSince: Long?,
  @ColumnInfo(name = "freeze_known_present") val freezeKnownPresent: Int?,
  @ColumnInfo(name = "freeze_vanished") val freezeVanished: Int?,
  @ColumnInfo(name = "freeze_changed") val freezeChanged: Int?,
  @ColumnInfo(name = "freeze_not_media") val freezeNotMedia: Int?,
)

// --- Indice condiviso: pubblicazione (P3, §2.1) -------------------------------------------------------------------

/**
 * Lo stato della pubblicazione di **questo** dispositivo in una share (P3): il prossimo `seq` (D5, assegnato da
 * `IndexPublishDao.takeSeq` nella stessa transazione in cui il record entra nell'outbox) e l'ultima testa pubblicata.
 *
 * - `head_version`: la versione dell'ultima testa pubblicata (0 = mai); la prossima e' `head_version + 1`.
 * - `head_hash`: l'impronta dei byte firmati di quella testa.
 * - `head_published_at`: quando (ora del NAS): la regola M3 (una testa ogni 5 minuti per share) si decide da qui.
 */
@Entity(tableName = "index_publish_state")
data class IndexPublishStateEntity(
  @PrimaryKey @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "next_seq") val nextSeq: Long,
  @ColumnInfo(name = "head_version", defaultValue = "0") val headVersion: Long = 0,
  @ColumnInfo(name = "head_hash") val headHash: String? = null,
  @ColumnInfo(name = "head_published_at") val headPublishedAt: Long? = null,
)

/**
 * I segmenti **propri** elencati dall'ultima testa pubblicata in una share (P3): la testa nuova li elenca tutti piu' quelli
 * nuovi (lo stato e' funzione della testa, D5); la compattazione (4B) ne toglie. `entries` = voci del segmento.
 */
@Entity(tableName = "index_own_segment", primaryKeys = ["share_key", "segment_hash"])
data class IndexOwnSegmentEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "entries") val entries: Int,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Un record `meta` in attesa di pubblicazione (l'**outbox**, P3): niente si perde se il processo muore fra `stage` e
 * `flush`. Uno per chiave: un record piu' nuovo per lo stesso percorso sostituisce quello in coda (vince il `seq` piu'
 * alto, D5).
 *
 * - `record_key`: la chiave del record nel segmento, SHA-256("mcg-path\0" ‖ percorso piegato), in esadecimale.
 * - `path`: il percorso com'e' nel record (relativo alla share). `gone`: lapide.
 * - `body`: il JSON canonico del record (al massimo 4 KiB, `TrustLimits`), gia' con il suo `seq`. Porta gli id di media e
 *   versione del momento in cui e' entrato in coda: una rinomina o un'unione successiva converge comunque al minore (D7).
 */
@Entity(
  tableName = "index_outbox_record",
  primaryKeys = ["share_key", "record_key"],
  indices = [Index(value = ["share_key", "seq"])],
)
class IndexOutboxRecordEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "record_key") val recordKey: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "path") val path: String,
  @ColumnInfo(name = "gone") val gone: Boolean,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
  @ColumnInfo(name = "staged_at") val stagedAt: Long,
)

/**
 * Un blob in attesa di pubblicazione (P3): i byte stanno in un pacco temporaneo per classe in
 * `noBackupFilesDir/index-outbox/` (`pack` = nome del file, `offset` e `length` il tratto); qui solo l'indice, cosi' una
 * ripresa dopo un crash sa che cosa c'e'. `content_key` = chiave di contenuto (`ContentKey.hex`).
 */
@Entity(
  tableName = "index_outbox_blob",
  primaryKeys = ["share_key", "cls", "content_key"],
  indices = [Index(value = ["pack"])],
)
data class IndexOutboxBlobEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "pack") val pack: String,
  @ColumnInfo(name = "byte_offset") val offset: Long,
  @ColumnInfo(name = "byte_length") val length: Int,
  @ColumnInfo(name = "staged_at") val stagedAt: Long,
)

// --- Indice condiviso: replica locale (P4, §2.2) ------------------------------------------------------------------

/**
 * L'ultima testa **accettata** di un dispositivo in una share (il `HeadMark` dell'anti-rollback, D5), salvata nella stessa
 * transazione dell'applicazione dei suoi segmenti `meta`. `applied_at` in ora del NAS.
 */
@Entity(tableName = "index_head", primaryKeys = ["share_key", "device_id"])
data class IndexHeadEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "content_hash") val contentHash: String,
  @ColumnInfo(name = "registry_version") val registryVersion: Long?,
  @ColumnInfo(name = "applied_at") val appliedAt: Long,
)

/**
 * Un segmento elencato dalla testa accettata di un dispositivo (P4). Una testa nuova si confronta con queste righe: i
 * segmenti nuovi si scaricano, quelli tolti si dimenticano con i loro record e i loro pacchi (D5).
 *
 * `state`: `listed` (da scaricare), `applied` (record applicati o pacco pronto), `missing` (non trovato o con un'altra
 * impronta, E-I17: i dati gia' nella replica restano), `rejected` (scartato dal codec, E-I14). `pack_id`: il pacco della
 * replica per le classi d'immagine (D15), NULL per `meta`.
 */
@Entity(
  tableName = "index_segment",
  primaryKeys = ["share_key", "device_id", "segment_hash"],
  indices = [Index(value = ["share_key", "state"])],
)
data class IndexSegmentEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "pack_id") val packId: Long?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Un record `meta` di un dispositivo approvato, com'e' nel suo segmento (P4): per (share, dispositivo, chiave) resta
 * quello col `seq` piu' alto (D5). La fusione fra dispositivi (D6) legge tutte le righe di un percorso
 * (`share_key`, `record_key`) e sceglie per timbro, classi preparate e id di dispositivo.
 *
 * Le colonne sono quelle che servono alle query (timbro, impronte, chiave di contenuto ridotta, classi preparate,
 * fallimento D14, id **remoti** di media e versione per D7); `body` e' il JSON canonico intero per tutto il resto. Gli
 * id remoti si chiamano `remote_*` di proposito: sono dati del record firmato, non riferimenti locali (l'unione non li
 * tocca, `MergeReferencesTest`).
 */
@Entity(
  tableName = "index_record",
  primaryKeys = ["share_key", "device_id", "record_key"],
  indices = [
    // D6: tutti i dispositivi per un percorso.
    Index(value = ["share_key", "record_key"]),
    // D5: i record di un segmento tolto dalla testa.
    Index(value = ["share_key", "device_id", "segment_hash"]),
    // Unita' di lavoro e "chi ha gia' preparato questa chiave" (D13).
    Index(value = ["content_key"]),
  ],
)
class IndexRecordEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "record_key") val recordKey: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "path") val path: String,
  @ColumnInfo(name = "gone") val gone: Boolean,
  @ColumnInfo(name = "size") val size: Long?,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long?,
  @ColumnInfo(name = "file_id") val fileId: Long?,
  @ColumnInfo(name = "quick_hash") val quickHash: String?,
  @ColumnInfo(name = "full_hash") val fullHash: String?,
  /** `ContentKey.short`: la forma ridotta della chiave di contenuto (8 byte), come in `MediaRef.Nas`. */
  @ColumnInfo(name = "content_key") val contentKey: Long?,
  @ColumnInfo(name = "remote_media_id") val remoteMediaId: String?,
  @ColumnInfo(name = "remote_version_id") val remoteVersionId: String?,
  /** Maschera delle classi pubblicate per la chiave di contenuto (`prep`, `SegmentClass.mask`). */
  @ColumnInfo(name = "prep", defaultValue = "0") val prep: Int,
  @ColumnInfo(name = "fail_code") val failCode: String?,
  @ColumnInfo(name = "fail_decoder") val failDecoder: String?,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
)

/**
 * Un pacco della replica (D15): un segmento di una classe d'immagine scaricato e tenuto **immutabile** in
 * `noBackupFilesDir/replica/packs/<file_name>` (nome derivato dall'impronta), letto con mappatura in memoria.
 */
@Entity(
  tableName = "replica_pack",
  indices = [
    Index(value = ["file_name"], unique = true),
    Index(value = ["share_key", "device_id", "segment_hash"]),
  ],
)
data class ReplicaPackEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "pack_id") val packId: Long = 0,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "file_name") val fileName: String,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Un blob dentro un pacco della replica (`ReplicaBlobs.locate`): chiave di contenuto intera e ridotta, classe, tratto.
 * Cancellare il pacco cancella i suoi blob.
 */
@Entity(
  tableName = "replica_blob",
  primaryKeys = ["pack_id", "content_key"],
  foreignKeys = [
    ForeignKey(
      entity = ReplicaPackEntity::class,
      parentColumns = ["pack_id"],
      childColumns = ["pack_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["content_key_short", "cls"])],
)
data class ReplicaBlobEntity(
  @ColumnInfo(name = "pack_id") val packId: Long,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "content_key_short") val contentKeyShort: Long,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "byte_offset") val offset: Long,
  @ColumnInfo(name = "byte_length") val length: Int,
)

/**
 * Lo stato della sincronizzazione di una share (P4): ultimo giro e ultimo giro completo (ora del NAS) e i giorni di
 * accesso negato (E-I8, E-R10: `ACCESS_DENIED` o `BAD_NETWORK_NAME` in **due giorni diversi** di ora del NAS → i dati
 * della share si tolgono dalla replica). I giorni sono `aaaammgg`; un giro riuscito li azzera.
 */
@Entity(tableName = "index_sync_state")
data class IndexSyncStateEntity(
  @PrimaryKey @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
  @ColumnInfo(name = "last_complete_at") val lastCompleteAt: Long?,
  @ColumnInfo(name = "denied_first_day") val deniedFirstDay: Int?,
  @ColumnInfo(name = "denied_last_day") val deniedLastDay: Int?,
)

// --- Diario delle operazioni sul NAS (4B, P12, §2.7) ---------------------------------------------------------------

/**
 * Un'operazione sui file del NAS nata da una conferma dell'utente (D24): crea cartella, rinomina, sposta, elimina,
 * ripristina, elimina subito. `kind` e `state` sono codici stabili di P12; `plan_hash` lega l'operazione al foglio di
 * conferma (`UserConfirmation`). Nessun dato letto dal NAS crea righe qui (test di architettura di P12).
 */
@Entity(tableName = "nas_op", indices = [Index(value = ["state"])])
data class NasOpEntity(
  @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "plan_hash") val planHash: String,
  @ColumnInfo(name = "item_count") val itemCount: Int,
  @ColumnInfo(name = "total_bytes") val totalBytes: Long,
  @ColumnInfo(name = "error_code") val errorCode: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Un elemento di un'operazione, con la sua macchina a stati (§2.7: `PLANNED → … → DONE`). **Ogni passo si scrive prima di
 * farlo**: un nome alternativo, il temporaneo, l'impronta verificata, l'id nel cestino stanno qui prima della chiamata al
 * NAS, cosi' la ripresa dopo un'interruzione e' idempotente (prova delle 500 interruzioni).
 *
 * Il timbro atteso della sorgente (`size`, `modified_at`, `file_id`) serve a fermarsi se il file e' cambiato (E-D13);
 * `next_attempt_at` alle attese crescenti dei file bloccati (E-N13, `WAITING_LOCKED`).
 */
@Entity(
  tableName = "nas_op_item",
  primaryKeys = ["op_id", "item_index"],
  foreignKeys = [
    ForeignKey(
      entity = NasOpEntity::class,
      parentColumns = ["op_id"],
      childColumns = ["op_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["state", "next_attempt_at"])],
)
data class NasOpItemEntity(
  @ColumnInfo(name = "op_id") val opId: String,
  @ColumnInfo(name = "item_index") val itemIndex: Int,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "is_folder") val isFolder: Boolean,
  @ColumnInfo(name = "account_id") val accountId: String,
  @ColumnInfo(name = "src_share") val srcShare: String,
  @ColumnInfo(name = "src_path") val srcPath: String,
  @ColumnInfo(name = "dst_share") val dstShare: String?,
  @ColumnInfo(name = "dst_path") val dstPath: String?,
  @ColumnInfo(name = "temp_name") val tempName: String?,
  @ColumnInfo(name = "size") val size: Long?,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long?,
  @ColumnInfo(name = "file_id") val fileId: Long?,
  @ColumnInfo(name = "sha256") val sha256: String?,
  @ColumnInfo(name = "trash_id") val trashId: String?,
  @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int,
  @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
  @ColumnInfo(name = "error_code") val errorCode: String?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Il diario **locale** del cestino del NAS (D25, formato §9.3): una riga per ogni file o cartella che **questo**
 * dispositivo ha messo in `.mycloudgallery/trash/<io>/`. La pulizia dopo 30 giorni di ora del NAS parte solo da qui (mai
 * da record remoti, E-D4, E-S11). `reason`: `delete` o `move`; `state`: `trashed`, `restored`, `purged`.
 */
@Entity(tableName = "nas_trash_entry", indices = [Index(value = ["state", "trashed_at"])])
data class NasTrashEntryEntity(
  @PrimaryKey @ColumnInfo(name = "trash_id") val trashId: String,
  @ColumnInfo(name = "account_id") val accountId: String,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "share_name") val shareName: String,
  @ColumnInfo(name = "original_path") val originalPath: String,
  @ColumnInfo(name = "trashed_path") val trashedPath: String,
  @ColumnInfo(name = "reason") val reason: String,
  @ColumnInfo(name = "is_folder") val isFolder: Boolean,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "file_count") val fileCount: Int?,
  @ColumnInfo(name = "trashed_at") val trashedAt: Long,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "op_id") val opId: String?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
