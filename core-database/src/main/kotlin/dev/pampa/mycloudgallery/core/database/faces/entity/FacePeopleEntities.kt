package dev.pampa.mycloudgallery.core.database.faces.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Database dei volti, schema v1: le voci `people` (nomi e correzioni, §2.4, D23, D24) e il lavoro di sincronizzazione che appartiene a
// questa generazione (segni anti-rollback, outbox, unita' fatte). Regole comuni: vedi `FaceBodyEntities.kt`.

/**
 * Una voce `people` (§2.4): l'ultima scrittura per chiave ([subject], [field]) come l'ha decisa `PeopleRecord.winner`. [value] null =
 * **lapide**. Il **nome** di una persona (campo `name`) non sta mai in [value]: sta in [sealedValue], sigillato con la chiave dati; tutti
 * gli altri campi (id, localizzatori, `true`) hanno [value] in chiaro e [sealedValue] null. [atNas] = ora del NAS (null = "da datare":
 * vale subito qui, si data alla prima sincronizzazione), [author] = il dispositivo che l'ha scritta.
 */
@Entity(tableName = "people_record", primaryKeys = ["subject", "field"])
class PeopleRecordEntity(
  @ColumnInfo(name = "subject") val subject: String,
  @ColumnInfo(name = "field") val field: String,
  @ColumnInfo(name = "value") val value: String?,
  @ColumnInfo(name = "sealed_value", typeAffinity = ColumnInfo.BLOB) val sealedValue: ByteArray?,
  @ColumnInfo(name = "at_nas") val atNas: Long?,
  @ColumnInfo(name = "author") val author: String,
) {
  override fun toString(): String = "PeopleRecordEntity"
}

/**
 * L'ultima testa accettata di un dispositivo dell'account in questa generazione (`HeadMark`, §2.4): l'anti-rollback (una testa con un
 * numero minore non si accetta) e l'equivocazione (stesso numero, altra impronta). [contentHash] e' l'impronta della testa accettata.
 */
@Entity(tableName = "faces_head_mark", primaryKeys = ["device_id"])
class FacesHeadMarkEntity(
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "content_hash") val contentHash: String,
  @ColumnInfo(name = "registry_version") val registryVersion: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
) {
  override fun toString(): String = "FacesHeadMarkEntity"
}

/**
 * Cosa c'e' da pubblicare (outbox, durevole: sopravvive alla morte del processo). Una riga per ([kind], [ref]): la stessa modifica due
 * volte e' una voce sola. [entryId] cresce a ogni inserimento (anche di una voce rimessa in coda): chi pubblica toglie solo cio' che ha
 * letto, per id, e una voce rimessa in coda nel frattempo resta. `kind` = `faces` (ref = chiave di contenuto), `snapshot` (ref = `seq`)
 * o `people` (ref vuoto: si ripubblica lo stato unito, come F4-D26).
 */
@Entity(tableName = "faces_outbox", indices = [Index(value = ["kind", "ref"], unique = true)])
class FacesOutboxEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "entry_id") val entryId: Long = 0,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "ref") val ref: String,
) {
  override fun toString(): String = "FacesOutboxEntity($kind)"
}

/**
 * Un'unita' di lavoro finita (§2.3): `faces/<G>/work/<priv|comm>/<mese>/<parte>`, con la [recipe] con cui e' stata fatta e l'ora del NAS
 * ([doneAtNas], null se non la si sapeva). Serve a non rifare l'elenco di un'unita' gia' completa.
 */
@Entity(tableName = "faces_unit_done", primaryKeys = ["area", "month", "part"])
class FacesUnitDoneEntity(
  @ColumnInfo(name = "area") val area: String,
  @ColumnInfo(name = "month") val month: String,
  @ColumnInfo(name = "part") val part: Int,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "done_at_nas") val doneAtNas: Long?,
) {
  override fun toString(): String = "FacesUnitDoneEntity"
}

/**
 * Un valore di servizio del database ([name] -> [data]), sigillato o no a seconda di chi lo scrive. Oggi solo `key_check`: un testo noto
 * sigillato con la chiave dati alla creazione del database. Se all'apertura non si apre con la chiave di oggi, il database e' di un'altra
 * chiave (sparita e ricreata: E-F19) e i suoi blocchi sono tutti illeggibili: si cancella invece di fingere un archivio vuoto.
 */
@Entity(tableName = "faces_meta", primaryKeys = ["name"])
class FacesMetaEntity(
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "data", typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
) {
  override fun toString(): String = "FacesMetaEntity"
}
