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
 * - `window_*` (R9 della revisione 4A): la finestra di piu' giri (7 giorni di ora del NAS) in cui si sommano spariti,
 *   cambiati e "non piu' media", con i file presenti all'inizio: 49 a giro non passano piu' sotto la soglia per sempre.
 *   Tutti NULL se non c'e' una finestra aperta.
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
  @ColumnInfo(name = "window_start") val windowStart: Long? = null,
  @ColumnInfo(name = "window_known") val windowKnown: Int? = null,
  @ColumnInfo(name = "window_vanished") val windowVanished: Int? = null,
  @ColumnInfo(name = "window_changed") val windowChanged: Int? = null,
  @ColumnInfo(name = "window_not_media") val windowNotMedia: Int? = null,
)

/**
 * Le posizioni del NAS la cui impronta rapida **non** e' stata letta qui ma adottata da un record dell'indice di un altro
 * dispositivo (R6 della revisione 4A; D8, D9 passo 4): `quick_hash` e' l'impronta adottata. Un'impronta adottata non lega mai
 * un media del telefono ne' un altro file del NAS (niente `LinkingRules` per contenuto, niente gruppi provvisori), e la
 * scansione la rilegge quando nessun record valido la sostiene piu' (autore revocato o record cambiato). La riga sparisce
 * appena la scansione rileva il file qui.
 */
@Entity(tableName = "nas_adopted_fingerprint")
data class NasAdoptedFingerprintEntity(
  @PrimaryKey @ColumnInfo(name = "location_id") val locationId: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
)

// --- Indice condiviso: pubblicazione (P3, §2.1) -------------------------------------------------------------------
//
// P11 (riconciliazione dello schema v3 non rilasciato): le tabelle della pubblicazione sono quelle che chiede la porta di
// P3 (`core-index/.../store/PublishStore.kt`, `IndexPublishStore`), una riga per cambiamento dell'outbox con il suo id: un
// `flush` consuma esattamente le righe che ha pubblicato (per id), mai una arrivata nel frattempo. L'adattatore
// (`RoomIndexPublishStore` di core-index) fa **una transazione per metodo**.

/**
 * Lo stato della pubblicazione di **questo** dispositivo in una share (P3): il prossimo `seq` (D5, assegnato nella stessa
 * transazione in cui il record entra nell'outbox, mai riusato) e l'ultima testa pubblicata.
 *
 * - `share_name`: la share com'e' scritta (le chiavi sono senza maiuscole).
 * - `head_version`: la versione dell'ultima testa pubblicata (0 = mai); la prossima e' `head_version + 1`.
 * - `head_hash`: l'impronta dei byte firmati di quella testa (`HeadMark.contentHash`).
 * - `head_published_at`: quando (ora del NAS): la regola M3 (una testa ogni 5 minuti per share) si decide da qui.
 */
@Entity(tableName = "index_publish_state")
data class IndexPublishStateEntity(
  @PrimaryKey @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "share_name") val shareName: String,
  @ColumnInfo(name = "next_seq") val nextSeq: Long,
  @ColumnInfo(name = "head_version", defaultValue = "0") val headVersion: Long = 0,
  @ColumnInfo(name = "head_hash") val headHash: String? = null,
  @ColumnInfo(name = "head_published_at") val headPublishedAt: Long? = null,
)

/**
 * I segmenti **propri** pubblicati in una share (P3): `listed` = elencato dalla testa corrente (la testa nuova li elenca
 * tutti piu' quelli nuovi, D5; la compattazione ne toglie). Le righe non elencate restano: sono il **diario** dei segmenti
 * propri per la pulizia della 4B (`OwnFilesPolicy`: "presenza dell'impronta nel diario locale"). `entries` = voci.
 */
@Entity(
  tableName = "index_own_segment",
  primaryKeys = ["share_key", "segment_hash"],
  indices = [Index(value = ["share_key", "listed"])],
)
data class IndexOwnSegmentEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "entries") val entries: Int,
  @ColumnInfo(name = "listed") val listed: Boolean,
)

/**
 * Un record `meta` in attesa di pubblicazione (l'**outbox**, P3): niente si perde se il processo muore fra `stage` e
 * `flush`. Ogni cambiamento e' una riga con il suo `seq` (unico per share, D5); la pubblicazione consuma le righe per id.
 *
 * - `path_key`: `IndexKeys.metaHex(path)` (percorso piegato): "di questo percorso ho detto qualcosa" (M3).
 * - `content_key`: la chiave di contenuto (`ContentKey.hex`) di un record presente, NULL per una lapide: la regola M3 per
 *   i blob ("un blob e' di questa share se un record presente lo dice").
 * - `body`: il JSON canonico del record (al massimo 4 KiB, `TrustLimits`), gia' con il suo `seq`.
 * - `sunk`: gia' passato alla replica locale (`OwnIndexSink`); la ripresa dopo una morte del processo ripassa gli altri.
 */
@Entity(
  tableName = "index_outbox_record",
  indices = [Index(value = ["share_key", "seq"], unique = true), Index(value = ["sunk"])],
)
class IndexOutboxRecordEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "path_key") val pathKey: String,
  @ColumnInfo(name = "content_key") val contentKey: String?,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
  @ColumnInfo(name = "sunk") val sunk: Boolean,
)

/**
 * Un blob in attesa di pubblicazione (P3): i byte stanno in un file dell'outbox (`noBackupFilesDir/index-outbox/`,
 * `OutboxBlobStore`) sotto `handle`; qui solo la riga. `content_key` = `ContentKey.hex`.
 */
@Entity(
  tableName = "index_outbox_blob",
  indices = [Index(value = ["share_key"]), Index(value = ["sunk"])],
)
data class IndexOutboxBlobEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "byte_length") val length: Int,
  @ColumnInfo(name = "handle") val handle: String,
  @ColumnInfo(name = "sunk") val sunk: Boolean,
)

/**
 * Il record **vivo** di ogni percorso nelle teste proprie (P3): quello con il `seq` piu' alto (D5), con il segmento che lo
 * contiene. Al massimo 262.144 per share (M9). `content_key` NULL per una lapide.
 */
@Entity(
  tableName = "index_own_record",
  primaryKeys = ["share_key", "path_key"],
  indices = [Index(value = ["share_key", "segment_hash"]), Index(value = ["share_key", "content_key"])],
)
data class IndexOwnRecordEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "path_key") val pathKey: String,
  @ColumnInfo(name = "path") val path: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "gone") val gone: Boolean,
  @ColumnInfo(name = "content_key") val contentKey: String?,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
)

/** Il blob **vivo** di una (classe, chiave di contenuto) nelle teste proprie (P3): quello pubblicato per ultimo. */
@Entity(
  tableName = "index_own_blob",
  primaryKeys = ["share_key", "cls", "content_key"],
  indices = [Index(value = ["share_key", "segment_hash"])],
)
data class IndexOwnBlobEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
)

// --- Indice condiviso: replica locale (P4, §2.2) ------------------------------------------------------------------
//
// P11: le tabelle che chiede la porta di P4 (`core-index/.../replica/store/ReplicaStore.kt`, `ReplicaStore`), una
// transazione per operazione (`RoomReplicaStore` di core-index). Le share si salvano gia' in forma di chiave.

/**
 * La testa **accettata** di un dispositivo in una share (il `HeadMark` dell'anti-rollback, D5, E-I5), salvata nella stessa
 * transazione che applica i suoi segmenti `meta`. `created_at` = l'istante firmato nella testa (ora del NAS).
 */
@Entity(tableName = "index_head", primaryKeys = ["share_key", "device_id"])
data class IndexHeadEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "content_hash") val contentHash: String,
  @ColumnInfo(name = "registry_version") val registryVersion: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Un segmento di un dispositivo in una share (P4): elencato dalla testa corrente (`listed`) o **trattenuto** (E-I17: la
 * testa nuova ha segmenti della stessa classe assenti o rifiutati, quindi i dati vecchi restano). `state` =
 * `SegmentState.code` (`applied`, `pending`, `skipped`, `missing`, `rejected`); `pack_id` = il pacco (D15) di un segmento
 * d'immagini applicato. `position` = l'ordine nell'elenco consegnato dalla replica.
 */
@Entity(
  tableName = "index_segment",
  primaryKeys = ["share_key", "device_id", "segment_hash"],
  indices = [Index(value = ["state", "cls"])],
)
data class IndexSegmentEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "listed") val listed: Boolean,
  @ColumnInfo(name = "pack_id") val packId: Long?,
  @ColumnInfo(name = "position") val position: Int,
)

/**
 * Un record `meta` di un dispositivo approvato com'e' nel suo segmento (P4, D5), oppure (`segment_hash = 'local'`) cio'
 * che questo dispositivo ha preparato o scoperto e non ha ancora riletto dalla propria testa (`OwnIndexSink`, D31). La
 * fusione fra dispositivi (D6) legge tutte le righe di un percorso (`share_key`, `record_key`).
 *
 * `body` e' il JSON canonico intero; le altre colonne servono alle query: `seq`, `gone`, la chiave di contenuto
 * (`ContentKey.hex`, NULL per le lapidi) e la maschera `prep` (la stima della qualita', E-L6). Gli id dei media che il
 * record porta sono **dati** firmati, non riferimenti locali: stanno solo dentro `body` (l'unione non li tocca).
 */
@Entity(
  tableName = "index_record",
  primaryKeys = ["share_key", "device_id", "segment_hash", "record_key"],
  indices = [Index(value = ["share_key", "record_key"])],
)
class IndexRecordEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
  @ColumnInfo(name = "record_key") val recordKey: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "gone") val gone: Boolean,
  @ColumnInfo(name = "content_key") val contentKey: String?,
  @ColumnInfo(name = "prep", defaultValue = "0") val prep: Int,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
)

/**
 * I percorsi da rifondere (D6, D7): ogni scrittura che tocca record li segna **nella stessa transazione**; la replica li
 * consegna al catalogo e poi li toglie, cosi' un crash fra le due transazioni non lascia il catalogo indietro.
 */
@Entity(tableName = "replica_dirty", primaryKeys = ["share_key", "record_key"])
data class ReplicaDirtyEntity(
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "record_key") val recordKey: String,
)

/**
 * Un pacco della replica (D15) in `noBackupFilesDir/replica/packs/<file_name>` (nome derivato da un'impronta, mai testo
 * del NAS): un segmento d'immagini scaricato (`kind = segment`, immutabile) o le immagini preparate qui (`kind = own`, in
 * sola aggiunta). `bytes` = la lunghezza valida; share, dispositivo e segmento solo per `segment`.
 */
@Entity(
  tableName = "replica_pack",
  indices = [Index(value = ["file_name"], unique = true), Index(value = ["share_key", "device_id", "segment_hash"])],
)
data class ReplicaPackEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "pack_id") val packId: Long = 0,
  @ColumnInfo(name = "file_name") val fileName: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "bytes") val bytes: Long,
  @ColumnInfo(name = "share_key") val shareKey: String?,
  @ColumnInfo(name = "device_id") val deviceId: String?,
  @ColumnInfo(name = "segment_hash") val segmentHash: String?,
)

/**
 * Un blob dentro un pacco della replica (`ReplicaBlobs.locate`): chiave di contenuto intera e ridotta, classe, tratto, e da
 * quale dispositivo e segmento viene (D6: la scelta deterministica fra piu' dispositivi). Cancellare il pacco cancella i
 * suoi blob.
 */
@Entity(
  tableName = "replica_blob",
  primaryKeys = ["content_key", "cls", "pack_id"],
  foreignKeys = [
    ForeignKey(
      entity = ReplicaPackEntity::class,
      parentColumns = ["pack_id"],
      childColumns = ["pack_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["content_key_short", "cls"]), Index(value = ["pack_id"])],
)
data class ReplicaBlobEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "pack_id") val packId: Long,
  @ColumnInfo(name = "content_key_short") val contentKeyShort: Long,
  @ColumnInfo(name = "byte_offset") val offset: Long,
  @ColumnInfo(name = "byte_length") val length: Int,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "segment_hash") val segmentHash: String,
)

/**
 * Lo stato d'accesso di una share (E-I8, E-R10): il primo rifiuto `ACCESS_DENIED`/`SHARE_MISSING` (`failure` =
 * `RemoteFailure.name`) e il giorno (ora del NAS) in cui e' arrivato; `dropped` = dati gia' tolti dalla replica.
 */
@Entity(tableName = "replica_share_access")
data class ReplicaShareAccessEntity(
  @PrimaryKey @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "failure") val failure: String,
  @ColumnInfo(name = "first_failure_day") val firstFailureDay: Long,
  @ColumnInfo(name = "dropped") val dropped: Boolean,
)

/** Le impostazioni della replica (chiave `quality` = `QualityLevel.code`): sono del dispositivo, non si sincronizzano. */
@Entity(tableName = "replica_setting")
data class ReplicaSettingEntity(
  @PrimaryKey @ColumnInfo(name = "key") val key: String,
  @ColumnInfo(name = "value") val value: String,
)

/**
 * Le miniature **provvisorie** (D17): la miniatura EXIF letta al rilevamento (P5), solo locale, mai pubblicata, tolta con
 * la replica. `jpeg` al massimo 256 KiB. Letta per chiave primaria dal dispatcher delle miniature.
 */
@Entity(tableName = "replica_provisional")
class ReplicaProvisionalEntity(
  @PrimaryKey @ColumnInfo(name = "content_key_short") val contentKeyShort: Long,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "jpeg", typeAffinity = ColumnInfo.BLOB) val jpeg: ByteArray,
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
