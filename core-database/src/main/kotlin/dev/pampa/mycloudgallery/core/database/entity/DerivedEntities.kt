package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lo stato di una fase di lavoro per un media. Una riga per (media, fase): la coda di una fase e'
 * "righe in `pending` di quella fase", servita dall'indice (`stage`, `status`).
 *
 * `version_id` e' la versione su cui il lavoro e' stato fatto: se la versione sparisce (unione di
 * doppioni) diventa NULL invece di bloccare, e lo stato resta.
 */
@Entity(
  tableName = "work_state",
  primaryKeys = ["media_id", "stage"],
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
    ForeignKey(
      entity = MediaVersionEntity::class,
      parentColumns = ["version_id"],
      childColumns = ["version_id"],
      onDelete = ForeignKey.SET_NULL,
      deferred = true,
    ),
  ],
  // v3: (stage, status, updated_at) al posto di (stage, status): stessa coda, e i fallimenti da riprovare escono gia'
  // in ordine di `updated_at` (nessun ordinamento in memoria).
  indices = [Index(value = ["stage", "status", "updated_at"]), Index(value = ["version_id"])],
)
data class WorkStateEntity(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "stage") val stage: String,
  @ColumnInfo(name = "status") val status: String,
  @ColumnInfo(name = "version_id") val versionId: String?,
  @ColumnInfo(name = "attempts") val attempts: Int,
  /** Codice d'errore (`WorkErrorCode`), mai testo libero: niente percorsi o nomi di file. */
  @ColumnInfo(name = "last_error") val lastError: String?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Un vettore di ricerca, con l'etichetta completa del suo spazio su **ogni riga** (modello,
 * dimensione, precisione: `docs/decisioni.md`). La chiave primaria include l'etichetta, quindi due
 * spazi convivono (reindicizzazione graduale, E-A10) e una query che filtra per spazio non puo'
 * mescolarli (E-I4).
 *
 * Classe normale e non `data class`: un `ByteArray` non ha l'uguaglianza per contenuto.
 */
@Entity(
  tableName = "embedding",
  primaryKeys = ["media_id", "space_id", "dimension", "precision", "vector_index"],
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
    ForeignKey(
      entity = MediaVersionEntity::class,
      parentColumns = ["version_id"],
      childColumns = ["version_id"],
      onDelete = ForeignKey.SET_NULL,
      deferred = true,
    ),
  ],
  indices = [
    // Scansione di uno spazio (ricerca per forza bruta della Fase 6) in ordine di media.
    Index(value = ["space_id", "dimension", "precision", "media_id", "vector_index"]),
    Index(value = ["version_id"]),
  ],
)
class EmbeddingEntity(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "space_id") val spaceId: String,
  @ColumnInfo(name = "dimension") val dimension: Int,
  @ColumnInfo(name = "precision") val precision: String,
  @ColumnInfo(name = "vector_index") val vectorIndex: Int,
  @ColumnInfo(name = "vector", typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
  @ColumnInfo(name = "version_id") val versionId: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * I dati personali di un media (preferito, nascosto), separati dai dati del media: non si
 * condividono mai e dalla Fase 4.7 si sincronizzano solo tra i dispositivi della stessa persona.
 * Nessuna riga = nessun flag.
 *
 * v2: `favorite_changed_at` e `hidden_changed_at` dicono quando **ogni** flag e' cambiato l'ultima volta
 * (ora di riferimento). Preparano la sincronizzazione per campo della 4.7 (vince la modifica piu' recente
 * di quel flag, non della riga) senza un'altra migrazione. La migrazione 1 -> 2 li inizializza con
 * `updated_at`.
 */
@Entity(
  tableName = "media_personal",
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
  ],
  indices = [Index(value = ["favorite"]), Index(value = ["hidden"])],
)
data class MediaPersonalEntity(
  @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "favorite") val favorite: Boolean,
  @ColumnInfo(name = "hidden") val hidden: Boolean,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  @ColumnInfo(name = "favorite_changed_at", defaultValue = "0") val favoriteChangedAt: Long = updatedAt,
  @ColumnInfo(name = "hidden_changed_at", defaultValue = "0") val hiddenChangedAt: Long = updatedAt,
)

/**
 * Un album. Nato nella v1 come album manuale minimo (cosi' gli album puntano a identita' stabili); la v6 (Fase 7, D34) lo estende per
 * tutti i tipi: **personale** (manuale o smart, `state` NULL; voci `ALBUM` dei dati personali proiettate dal catalogo, D3-D5) e
 * **condiviso** (`state` non NULL: la replica fusa degli album dell'area comune, D6-D11; il resto e' in `shared_album`). La riga e'
 * **derivata**: si puo' ricostruire in ogni momento dallo stato (D5). Il perche' e le colonne: `docs/modello-dati.md` §14.
 *
 * - `kind`: `AlbumKind.code` (`manual`, `smart`); `sort`: `AlbumSort.code`, l'ordine **dentro** l'album (D21); `frozen`: uno smart
 *   congelato non applica le regole (D16);
 * - `shared_id`: l'album condiviso nato da questo (voce personale `shared`, D4), o NULL;
 * - `state`: NULL per un album personale, `SharedState.code` per la riga di un album condiviso.
 */
@Entity(
  tableName = "album",
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["cover_media_id"],
      onDelete = ForeignKey.SET_NULL,
      deferred = true,
    ),
  ],
  indices = [Index(value = ["cover_media_id"])],
)
data class AlbumEntity(
  @PrimaryKey @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "cover_media_id") val coverMediaId: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  // --- v6 (Fase 7, D34) ---
  @ColumnInfo(name = "kind", defaultValue = "'manual'") val kind: String = "manual",
  @ColumnInfo(name = "sort", defaultValue = "'newest'") val sort: String = "newest",
  @ColumnInfo(name = "frozen", defaultValue = "0") val frozen: Boolean = false,
  @ColumnInfo(name = "shared_id") val sharedId: String? = null,
  @ColumnInfo(name = "state") val state: String? = null,
) {
  /** Mai il titolo (D37). */
  override fun toString(): String = "AlbumEntity(kind=$kind, state=$state)"
}

/**
 * Un elemento di un album. v6: `origin` dice come c'e' entrato (`auto` = un "sicuro" di uno smart, `manual` = voce `item:`,
 * `accepted` = un suggerito accettato, voce `accept:`); `fp` e' l'**impronta** `<qh1 esadecimale>:<dimensione>` che la voce porta
 * (D4: serve a ritrovare il media se gli id divergono fra dispositivi); `added_by` l'account di chi l'ha aggiunto (solo album
 * condivisi: "Aggiunta da ...", Q6, e i permessi).
 */
@Entity(
  tableName = "album_item",
  primaryKeys = ["album_id", "media_id"],
  foreignKeys = [
    ForeignKey(
      entity = AlbumEntity::class,
      parentColumns = ["album_id"],
      childColumns = ["album_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
  ],
  indices = [Index(value = ["media_id"]), Index(value = ["album_id", "position"])],
)
data class AlbumItemEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "position") val position: Long,
  @ColumnInfo(name = "added_at") val addedAt: Long,
  // --- v6 (Fase 7, D34) ---
  @ColumnInfo(name = "origin", defaultValue = "'manual'") val origin: String = "manual",
  @ColumnInfo(name = "fp") val fp: String? = null,
  @ColumnInfo(name = "added_by") val addedBy: String? = null,
)
