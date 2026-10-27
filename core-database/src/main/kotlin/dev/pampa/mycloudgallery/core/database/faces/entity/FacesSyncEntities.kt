package dev.pampa.mycloudgallery.core.database.faces.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Database dei volti, schema v1: lo stato della sincronizzazione (Fase 8, P3 + P6; `docs/fase8-progetto.md` §2.4, D21, D30; `docs/modello-dati.md`
// §15.6). Due porte di `core-faces/.../sync/` (la replica dei dispositivi dell'account e il pubblicatore di questo) tengono qui il loro stato,
// cosi' una morte del processo non rifa' da capo le letture dal NAS e non perde cio' che era in coda. Regole comuni: vedi `FaceBodyEntities.kt`.
//
// - Sono **numeri, impronte e nomi di file**; i valori sensibili (i centroidi delle persone dell'istantanea del custode) stanno solo in
//   `faces_chunk.data`, sigillato con la chiave dati: il database non lo interpreta.
// - Lo stato di una porta si salva **intero e atomico** (una transazione), ma si scrive solo cio' che e' cambiato: chi lo usa tiene l'ultimo
//   stato scritto in memoria e fa la differenza (`store/RoomFacesReplicaStore`, `RoomFacesPublishStore`).

/**
 * Quello che la replica ricorda di un dispositivo dell'account (`DeviceReplica`): l'ultima testa accettata ([markVersion], [markHash]:
 * anti-rollback ed equivocazione), l'istantanea che la testa dichiara ([snapshotSeq], [snapshotBase]), la testa da cui viene l'ultima
 * istantanea valida ([snapshotHeadVersion], [snapshotHeadHash]) e la sua `seq` e `base` ([snapSeq], [snapBase], le voci stanno in
 * `faces_chunk`), la testa gia' giudicata ([checkedVersion], [checkedHash]). Gli NULL sono "non c'e'".
 */
@Entity(tableName = "replica_device", primaryKeys = ["device_id"])
class ReplicaDeviceEntity(
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "mark_version") val markVersion: Long?,
  @ColumnInfo(name = "mark_hash") val markHash: String?,
  @ColumnInfo(name = "snapshot_seq") val snapshotSeq: Long?,
  @ColumnInfo(name = "snapshot_base") val snapshotBase: Long?,
  @ColumnInfo(name = "snapshot_head_version") val snapshotHeadVersion: Long?,
  @ColumnInfo(name = "snapshot_head_hash") val snapshotHeadHash: String?,
  @ColumnInfo(name = "snap_seq") val snapSeq: Long?,
  @ColumnInfo(name = "snap_base") val snapBase: Long?,
  @ColumnInfo(name = "checked_version") val checkedVersion: Long?,
  @ColumnInfo(name = "checked_hash") val checkedHash: String?,
) {
  override fun toString(): String = "ReplicaDeviceEntity"
}

/** I segmenti che la testa di un dispositivo elenca, nell'ordine della testa ([position]): classe (codice del formato), impronta e dimensione. */
@Entity(tableName = "replica_segment", primaryKeys = ["device_id", "position"])
class ReplicaSegmentEntity(
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "position") val position: Int,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "hash") val hash: String,
  @ColumnInfo(name = "size") val size: Long,
) {
  override fun toString(): String = "ReplicaSegmentEntity"
}

/** Un segmento gia' letto di un dispositivo ([done]: applicato o rifiutato per sempre) e quante voci ha portato ([entries], per i tetti). */
@Entity(tableName = "replica_segment_state", primaryKeys = ["device_id", "hash"])
class ReplicaSegmentStateEntity(
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "hash") val hash: String,
  @ColumnInfo(name = "done") val done: Boolean,
  @ColumnInfo(name = "entries") val entries: Int?,
) {
  override fun toString(): String = "ReplicaSegmentStateEntity"
}

/** Un dispositivo revocato o perso che la replica ha gia' tolto dall'archivio (L99): non si ritoglie a ogni giro. */
@Entity(tableName = "replica_revoked", primaryKeys = ["device_id"])
class ReplicaRevokedEntity(
  @ColumnInfo(name = "device_id") val deviceId: String,
) {
  override fun toString(): String = "ReplicaRevokedEntity"
}

/**
 * Un blocco di voci `cluster` (persone con il centroide, assegnazioni) **sigillato** con la chiave dati: un segmento `cluster` letto dalla
 * replica, l'istantanea di un dispositivo o l'istantanea che il custode ha chiesto di pubblicare. [scope] dice di cosa e' (`replica.cluster`,
 * `replica.snapshot`, `publish.snapshot`), [owner] di chi (l'id del dispositivo, o vuoto), [name] quale insieme di voci (l'impronta del
 * segmento, o `0` per l'istantanea) e [part] quale pezzo: un insieme grande si spezza in righe di poche centinaia di KB (Android non legge una
 * riga oltre i 2 MB). [encHash] e [encSize] sono il riferimento del segmento con cui i byte sigillati si rileggono (`FacesDocs.decodeSegment`).
 */
@Entity(tableName = "faces_chunk", primaryKeys = ["scope", "owner", "name", "part"])
class FacesChunkEntity(
  @ColumnInfo(name = "scope") val scope: String,
  @ColumnInfo(name = "owner") val owner: String,
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "part") val part: Int,
  @ColumnInfo(name = "enc_hash") val encHash: String,
  @ColumnInfo(name = "enc_size") val encSize: Long,
  @ColumnInfo(name = "data", typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
) {
  override fun toString(): String = "FacesChunkEntity($scope)"
}

/**
 * Lo stato scalare del pubblicatore (`FacesPublishState`), una sola riga (`id` = 1): l'istantanea in coda ([snapSeq], [snapBase]; le voci
 * in `faces_chunk`), l'ultima testa pubblicata da qui ([pubVersion] NULL = mai pubblicato; [pubMarkVersion], [pubMarkHash], [pubAtNas],
 * l'istantanea che elencava, quante voci), le modifiche in coda ([pending]) e i due segni ([adopted], [uncertain]).
 */
@Entity(tableName = "publish_state")
class PublishStateEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
  @ColumnInfo(name = "snap_seq") val snapSeq: Long?,
  @ColumnInfo(name = "snap_base") val snapBase: Long?,
  @ColumnInfo(name = "pub_version") val pubVersion: Long?,
  @ColumnInfo(name = "pub_mark_version") val pubMarkVersion: Long?,
  @ColumnInfo(name = "pub_mark_hash") val pubMarkHash: String?,
  @ColumnInfo(name = "pub_at_nas") val pubAtNas: Long?,
  @ColumnInfo(name = "pub_snap_seq") val pubSnapSeq: Long?,
  @ColumnInfo(name = "pub_snap_base") val pubSnapBase: Long?,
  @ColumnInfo(name = "pub_entries") val pubEntries: Int?,
  @ColumnInfo(name = "pending") val pending: Int,
  @ColumnInfo(name = "adopted") val adopted: Boolean,
  @ColumnInfo(name = "uncertain") val uncertain: Boolean,
) {
  override fun toString(): String = "PublishStateEntity"
}

/** Una chiave di contenuto di cui questo dispositivo ha calcolato un corpo: si pubblica se passa "M3 dei volti". */
@Entity(tableName = "publish_key", primaryKeys = ["content_key"])
class PublishKeyEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
) {
  override fun toString(): String = "PublishKeyEntity"
}

/** Un segmento elencato dall'ultima testa pubblicata da qui. */
@Entity(tableName = "publish_segment", primaryKeys = ["hash"])
class PublishSegmentEntity(
  @ColumnInfo(name = "hash") val hash: String,
) {
  override fun toString(): String = "PublishSegmentEntity"
}

/** Il diario dei segmenti pubblicati da qui (la prova "e' mio" della pulizia dei propri file, M6): [ord] cresce, i piu' vecchi escono per primi. */
@Entity(tableName = "publish_diary", primaryKeys = ["hash"], indices = [Index(value = ["ord"])])
class PublishDiaryEntity(
  @ColumnInfo(name = "hash") val hash: String,
  @ColumnInfo(name = "ord") val ord: Long,
) {
  override fun toString(): String = "PublishDiaryEntity"
}
