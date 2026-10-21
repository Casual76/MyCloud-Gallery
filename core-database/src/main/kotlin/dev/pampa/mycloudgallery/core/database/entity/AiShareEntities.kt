package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v5 (Fase 6, D7, D8, D37): le tabelle che P2 si aspetta per le porte `AiPublishStore` e `AiReplicaStore` di core-index (il
// commento in testa a `AiStores.kt` le elenca). core-database non vede core-index: gli adattatori Room li scrive P11 con i DAO di
// `AiShareDaos.kt`, **una transazione per metodo della porta** (`GalleryDatabase.transaction`).
//
// Conventions come la v3: `share` e' gia' una chiave (`share.lowercase(Locale.ROOT)`: Samba non distingue le maiuscole); `cls` e' il
// codice di `AiClass` (7 = `vec`, 8 = `ocr`, gli stessi di `SegmentClass`); `content_key` e' `ContentKey.hex` **intera** (D7: mai i
// primi 64 bit); `body` e' il corpo gia' codificato com'e' pubblicato. Nessuna punta a `media` (tutte per chiave di contenuto).

// --- Pubblicazione (questo dispositivo) -------------------------------------------------------------------------------------

/**
 * Una voce propria non ancora pubblicata (D8): una tabella sola per l'**outbox** della testa `ai` (`state` = `outbox`) e per i
 * risultati **in attesa** di una share pubblicabile (`state` = `held`, F4-D31): spostare una voce e' un `UPDATE`. `id` cresce con
 * l'ordine di arrivo (per (classe, chiave) vince la piu' recente) e non si riusa. `digest` e' l'impronta del corpo.
 */
@Entity(
  tableName = "ai_pending_publish",
  indices = [
    // Le righe di una share in uno stato, in ordine di id (le piu' vecchie prima) senza ordinare in memoria.
    Index(value = ["share", "state", "id"]),
    // Doppioni: "questa (classe, chiave) e' gia' in fila?".
    Index(value = ["share", "cls", "content_key"]),
  ],
)
class AiPendingPublishEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
  @ColumnInfo(name = "digest") val digest: String,
)

/** La testa `ai` **propria** corrente in una share (D5: lo stato di chi pubblica e' la sua testa). `published_at` = ora del NAS (M3). */
@Entity(tableName = "ai_own_head")
data class AiOwnHeadEntity(
  @PrimaryKey @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "head_hash") val headHash: String,
  @ColumnInfo(name = "published_at") val publishedAt: Long,
)

/**
 * I segmenti elencati dalla testa `ai` propria di una share (in ordine di impronta, come nella testa; e' la colonna `segments` del
 * commento di P2). Una testa nuova li sostituisce tutti (i nuovi piu' i vecchi non compattati): le righe tolte sono quelle
 * che la compattazione ha sostituito.
 */
@Entity(tableName = "ai_own_segment", primaryKeys = ["share", "hash"])
data class AiOwnSegmentEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "hash") val hash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "entries") val entries: Int,
)

/**
 * La voce **viva** di ogni (classe, chiave) nelle teste proprie di una share: il segmento che la contiene, la ricetta e l'impronta del
 * corpo (per non ripubblicare un corpo identico).
 */
@Entity(
  tableName = "ai_own_live",
  primaryKeys = ["share", "cls", "content_key"],
  indices = [Index(value = ["share", "segment"])],
)
data class AiOwnLiveEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "digest") val digest: String,
  @ColumnInfo(name = "segment") val segment: String,
)

// --- Replica (gli altri dispositivi, e le proprie teste rilette) --------------------------------------------------------------

/** La testa `ai` accettata di un dispositivo in una share: l'anti-rollback (E-I5) e `registry_version` per rivalidarla. */
@Entity(tableName = "ai_replica_head", primaryKeys = ["share", "device"])
data class AiReplicaHeadEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "device") val device: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "content_hash") val contentHash: String,
  @ColumnInfo(name = "registry_version") val registryVersion: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Un segmento `ai` di un dispositivo: `state` (`SegmentState.code`: applied, pending, missing, rejected), `entries` quante voci ha
 * dato (il tetto per dispositivo e share).
 */
@Entity(tableName = "ai_replica_segment", primaryKeys = ["share", "device", "hash"])
data class AiReplicaSegmentEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "device") val device: String,
  @ColumnInfo(name = "hash") val hash: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "entries") val entries: Int,
)

/**
 * Una voce di un segmento `ai` nella replica: i **candidati della fusione** (D7: ricetta piu' alta, poi dispositivo minore), con il
 * corpo come pubblicato (si legge solo per il vincitore). L'indice `(share, cls, content_key)` serve la fusione e `keys`.
 */
@Entity(
  tableName = "ai_replica_entry",
  primaryKeys = ["share", "device", "segment", "cls", "content_key"],
  indices = [Index(value = ["share", "cls", "content_key"])],
)
class AiReplicaEntryEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "device") val device: String,
  @ColumnInfo(name = "segment") val segment: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "digest") val digest: String,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
)

/** Il vincitore consegnato all'indice locale per (classe, chiave): autore, ricetta e impronta del corpo. */
@Entity(tableName = "ai_replica_applied", primaryKeys = ["share", "cls", "content_key"])
data class AiReplicaAppliedEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "device") val device: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "digest") val digest: String,
)

/** Le chiavi da rifondere (toccate da una testa applicata o da un dispositivo dimenticato). */
@Entity(tableName = "ai_replica_dirty", primaryKeys = ["share", "cls", "content_key"])
data class AiReplicaDirtyEntity(
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
)
