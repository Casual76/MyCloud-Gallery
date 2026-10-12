package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomWarnings

// Schema v1 della replica locale (piano 1.3). Il perche' delle scelte e' in docs/modello-dati.md.
//
// Regole comuni:
// - le enumerazioni si salvano con il loro codice stabile (StableCode), mai con nome o ordinale;
// - gli istanti sono millisecondi dall'epoca (ora di riferimento fornita da chi scrive);
// - le chiavi esterne del grafo media/versioni/posizioni sono DEFERRABLE: dentro una transazione si
//   puo' scrivere in qualunque ordine (il media prima della sua versione e viceversa) e il controllo
//   avviene al commit. Fuori da una transazione valgono subito.

/**
 * Un media: identita' stabile e pochi campi **derivati** messi in cache per le liste.
 *
 * `sort_time`, `presence` e `safety` sono calcolati dalle regole di dominio (`SafetyRules`, metadati
 * della versione corrente) e riscritti da `MediaCatalog` nella stessa transazione che cambia versioni
 * o posizioni. Stanno qui perche' la timeline di 100 mila elementi deve ordinarsi e filtrarsi con un
 * indice, senza unire ogni volta versioni, posizioni e metadati (piano §4: avvio < 1 s).
 *
 * La chiave esterna composta (`media_id`, `current_version_id`) garantisce nel database che la
 * versione corrente appartenga **a questo** media. Room chiederebbe un indice sulle due colonne: non
 * serve, perche' `media_id` e' la chiave primaria e la ricerca del controllo passa gia' da li'; un
 * indice in piu' su 100 mila righe costerebbe megabyte senza velocizzare nulla.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
  tableName = "media",
  foreignKeys = [
    ForeignKey(
      entity = MediaVersionEntity::class,
      parentColumns = ["media_id", "version_id"],
      childColumns = ["media_id", "current_version_id"],
      deferred = true,
    ),
    ForeignKey(
      entity = MediaGroupEntity::class,
      parentColumns = ["group_id"],
      childColumns = ["group_id"],
      onDelete = ForeignKey.SET_NULL,
      deferred = true,
    ),
  ],
  indices = [
    // Timeline: ORDER BY sort_time DESC, media_id DESC con paginazione a chiave (keyset).
    Index(value = ["sort_time", "media_id"]),
    // Filtri rapidi (screenshot, WhatsApp) nello stesso ordine della timeline.
    Index(value = ["source", "sort_time"]),
    // "312 in coda", "Tutto al sicuro": conteggi per stato.
    Index(value = ["safety"]),
    Index(value = ["group_id"]),
  ],
)
data class MediaEntity(
  @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "owner_account") val ownerAccount: String?,
  @ColumnInfo(name = "group_id") val groupId: String?,
  @ColumnInfo(name = "current_version_id") val currentVersionId: String,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "presence") val presence: String,
  @ColumnInfo(name = "safety") val safety: String,
)

/**
 * Una versione: byte precisi (impronta) in una catena. La catena e' nel database (`parent_version_id`)
 * e una versione con copie ancora conosciute **non si puo' cancellare** (la chiave esterna delle
 * posizioni lo impedisce): nessuna versione sparisce per errore.
 */
@Entity(
  tableName = "media_version",
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
      childColumns = ["parent_version_id"],
      deferred = true,
    ),
  ],
  indices = [
    // Genitore della chiave esterna composta di media: (media_id, version_id) e' unico.
    Index(value = ["media_id", "version_id"], unique = true),
    // Collegamento per impronta: prima dimensione + hash rapido, poi hash completo.
    Index(value = ["size", "quick_hash"]),
    Index(value = ["full_hash"]),
    Index(value = ["parent_version_id"]),
  ],
)
data class MediaVersionEntity(
  @PrimaryKey @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "parent_version_id") val parentVersionId: String?,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
  @ColumnInfo(name = "full_hash") val fullHash: String?,
  /** Codici di `CorrectionField` separati da virgola, in ordine; vuoto se non e' una correzione. */
  @ColumnInfo(name = "corrections") val corrections: String,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Una posizione: un file del telefono o del NAS con i byte di una versione. Una tabella sola per i
 * due tipi (colonne del tipo che non c'entra a NULL) perche' le domande vere sono trasversali: "dove
 * sono le copie di questa versione?", "che cosa c'e' in questo posto?".
 *
 * `location_key` e' la chiave normalizzata (NFC, minuscole) calcolata dal dominio: l'indice **unico**
 * impedisce nel database i doppioni fantasma per maiuscole (E-N7). I nomi originali restano nelle
 * colonne di visualizzazione (`relative_path`, `display_name`, `nas_path`), usati per aprire il file.
 */
@Entity(
  tableName = "media_location",
  foreignKeys = [
    ForeignKey(
      entity = MediaVersionEntity::class,
      parentColumns = ["version_id"],
      childColumns = ["version_id"],
      deferred = true,
    ),
  ],
  indices = [
    Index(value = ["location_key"], unique = true),
    Index(value = ["version_id"]),
    // Elenchi per cartella (Cartelle NAS) e confronti di una scansione a blocchi (E-N5).
    Index(value = ["folder_key"]),
    // Cache MediaStore: ritrovare in fretta la riga di un _ID (mai come identita').
    Index(value = ["volume", "media_store_id"]),
    // Posizioni assenti da dimenticare dopo la tolleranza.
    Index(value = ["state", "absent_since"]),
  ],
)
data class MediaLocationEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "location_id") val locationId: Long = 0,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "location_type") val locationType: String,
  @ColumnInfo(name = "location_key") val locationKey: String,
  @ColumnInfo(name = "folder_key") val folderKey: String,
  @ColumnInfo(name = "state") val state: String,
  // --- telefono ---
  @ColumnInfo(name = "volume") val volume: String?,
  @ColumnInfo(name = "relative_path") val relativePath: String?,
  @ColumnInfo(name = "display_name") val displayName: String?,
  @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
  @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String?,
  @ColumnInfo(name = "generation") val generation: Long?,
  // --- NAS ---
  @ColumnInfo(name = "account_id") val accountId: String?,
  @ColumnInfo(name = "share_name") val shareName: String?,
  @ColumnInfo(name = "share_key") val shareKey: String?,
  @ColumnInfo(name = "nas_path") val nasPath: String?,
  // --- comuni ---
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long,
  @ColumnInfo(name = "absent_since") val absentSince: Long?,
  @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
  @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

/**
 * I metadati di una versione. Latitudine e longitudine sono **dati sensibili** (`@Sensitive` nel
 * dominio): restano nello spazio privato dell'app, mai nei log o nella Diagnostica.
 */
@Entity(
  tableName = "version_metadata",
  foreignKeys = [
    ForeignKey(
      entity = MediaVersionEntity::class,
      parentColumns = ["version_id"],
      childColumns = ["version_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
  ],
)
data class VersionMetadataEntity(
  @PrimaryKey @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "mime_type") val mimeType: String?,
  @ColumnInfo(name = "width") val width: Int?,
  @ColumnInfo(name = "height") val height: Int?,
  @ColumnInfo(name = "orientation") val orientation: Int,
  @ColumnInfo(name = "duration_ms") val durationMs: Long?,
  @ColumnInfo(name = "taken_at_utc") val takenAtUtc: Long?,
  @ColumnInfo(name = "taken_offset_min") val takenOffsetMinutes: Int?,
  @ColumnInfo(name = "taken_confidence") val takenConfidence: String?,
  @ColumnInfo(name = "taken_source") val takenSource: String?,
  @ColumnInfo(name = "latitude") val latitude: Double?,
  @ColumnInfo(name = "longitude") val longitude: Double?,
  @ColumnInfo(name = "altitude") val altitude: Double?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Le share viste da un account con la loro area (dalla configurazione di famiglia, Fase 2). */
@Entity(tableName = "nas_share", primaryKeys = ["account_id", "share_key"])
data class NasShareEntity(
  @ColumnInfo(name = "account_id") val accountId: String,
  @ColumnInfo(name = "share_key") val shareKey: String,
  @ColumnInfo(name = "share_name") val shareName: String,
  @ColumnInfo(name = "area") val area: String,
)

/** Un gruppo di media mostrati come uno (raffica, coppia RAW+JPEG). */
@Entity(
  tableName = "media_group",
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
data class MediaGroupEntity(
  @PrimaryKey @ColumnInfo(name = "group_id") val groupId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "cover_media_id") val coverMediaId: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Un'identita' assorbita da un'unione: `alias_id` (il media perdente) porta a `media_id` (il
 * sopravvissuto). Cosi' un riferimento esterno con la vecchia identita' trova ancora il media.
 */
@Entity(
  tableName = "media_alias",
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
  ],
  indices = [Index(value = ["media_id"])],
)
data class MediaAliasEntity(
  @PrimaryKey @ColumnInfo(name = "alias_id") val aliasId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "merged_at") val mergedAt: Long,
)
