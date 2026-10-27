package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v6 (Fase 7, `docs/fase7-progetto.md` D34; `docs/modello-dati.md` §14): le tabelle degli album smart, degli album condivisi,
// della condivisione e dei viaggi. Regole comuni, come nelle versioni precedenti:
// - i codici (`kind`, `state`, `level`, ...) sono `StableCode.code` di core-model e di core-albums, scritti come testo; una riga con un
//   codice che questa versione non conosce (scritta da un'altra app) e' "non per me" e si salta in lettura;
// - gli istanti sono millisecondi (ora del NAS quando la voce e' sincronizzata, ora di riferimento del catalogo altrimenti); i giorni
//   sono `aaaammgg` locali dello scatto (`local_day`, D36);
// - le tabelle degli album sono **derivate**: lo stato vero sta nei dati personali (D3-D5) o nei contributi dell'area comune (D6);
//   si ricostruiscono. Quelle che puntano a `media` hanno la chiave esterna DEFERRABLE come il resto del grafo e l'unione di due
//   media le sposta (`MergeDao`, `MediaCatalog.MEDIA_REFERENCES`);
// - nessun titolo, cartella, luogo o coordinata esce da qui nei log (D37): sono dati dell'utente, restano nel database privato. I
//   `toString()` delle entita' che li portano non li stampano.

/**
 * Una regola di un album smart (D13, D4). Una riga per regola: `period`, `place`, `text`, `sources`, `types` (una sola ciascuna,
 * `position` 0) e `example` (fino a 6, `position` 0..5, con `media_id`). Il **valore** e' un testo che il database non interpreta:
 * lo scrivono la proiezione (P3) e il motore (P6) e lo leggono loro (il campo `smart:*` della voce personale ha la stessa forma).
 */
@Entity(
  tableName = "album_rule",
  primaryKeys = ["album_id", "rule", "position"],
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
  indices = [Index(value = ["media_id"])],
)
data class AlbumRuleEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "rule") val rule: String,
  @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
  @ColumnInfo(name = "value", defaultValue = "''") val value: String = "",
  /** Solo per `rule = 'example'`: la foto d'esempio (mai un nascosto fra i risultati, ma l'esempio puo' esserlo). */
  @ColumnInfo(name = "media_id") val mediaId: String? = null,
) {
  /** I7: il valore e' il periodo, il luogo (centro e coordinate) o la descrizione di un album: dati dell'utente, mai in un log. */
  override fun toString(): String = "AlbumRuleEntity($rule)"
}

/** Un elemento escluso da un album smart (voce `exclude:`, D16, E-AL5): per sempre, anche se le regole cambiano. */
@Entity(
  tableName = "album_exclusion",
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
  indices = [Index(value = ["media_id"])],
)
data class AlbumExclusionEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  /** L'impronta `<qh1 esadecimale>:<dimensione>` della voce, come in `album_item.fp`. */
  @ColumnInfo(name = "fp") val fp: String? = null,
) {
  /** I7: l'id di un media e la sua impronta non finiscono in un log. */
  override fun toString(): String = "AlbumExclusionEntity"
}

/**
 * Un "suggerito" di un album smart (D14, "Suggerite"): un dubbio che il motore ha calcolato (non e' un dato personale, non si
 * sincronizza). `reason` e' il codice della regola S1-S5 (lo sceglie P6); `added_at` serve al conteggio "Nuove: 12".
 */
@Entity(
  tableName = "album_suggestion",
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
  indices = [Index(value = ["media_id"])],
)
data class AlbumSuggestionEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "reason") val reason: String,
  @ColumnInfo(name = "added_at") val addedAt: Long,
) {
  /** I7: l'id di un media non finisce in un log; resta la regola (S1-S5, un codice fisso). */
  override fun toString(): String = "AlbumSuggestionEntity($reason)"
}

/**
 * La definizione fusa di un album condiviso (D6-D11): cio' che la replica sa del suo stato dopo la fusione dei contributi di tutti i
 * dispositivi (`AlbumFusion`). Il titolo, la copertina e l'ordine stanno nella riga `album` corrispondente (`state` non NULL); qui il
 * resto. **Senza chiavi esterne** (replica): la cartella e' un dato dell'utente (percorso nell'area comune), mai nei log.
 */
@Entity(tableName = "shared_album")
data class SharedAlbumEntity(
  @PrimaryKey @ColumnInfo(name = "album_id") val albumId: String,
  /** `SharedState.code`: `active`, `closed`, `deleted` (gli ultimi due sono definitivi per quell'id, D31). */
  @ColumnInfo(name = "state") val state: String,
  /** `ShareLevel.code` in vigore adesso (la storia dei livelli sta nei contributi, D10). */
  @ColumnInfo(name = "level") val level: String,
  /** L'account del creatore (D11: il creatore e' un account) e quello dell'erede, se c'e' la voce `heir`. */
  @ColumnInfo(name = "creator") val creator: String,
  @ColumnInfo(name = "heir") val heir: String? = null,
  /** La cartella dell'album nell'area comune, relativa alla share (E-SH16: puo' cambiare). */
  @ColumnInfo(name = "folder") val folder: String? = null,
  /** `OthersAfterClose.code` (`remove`, `keep_copy`): solo con `closed`/`deleted` (Q5). */
  @ColumnInfo(name = "after_close") val afterClose: String? = null,
  /** L'ora del NAS dell'ultima modifica nota ("Aggiornati di recente") e di quando e' stato chiuso. */
  @ColumnInfo(name = "updated_at_nas") val updatedAtNas: Long = 0,
  @ColumnInfo(name = "closed_at_nas") val closedAtNas: Long? = null,
) {
  override fun toString(): String = "SharedAlbumEntity(${state})"
}

/**
 * Le voci verificate di un contributo (`albums/<album>/<dispositivo>/`): la **replica** su cui `AlbumFusion` rifonde (D7). Una riga per
 * (album, dispositivo, chiave della voce). Senza chiavi esterne: i `MediaId` dentro [body] sono quelli di chi ha scritto, non i
 * locali; l'elemento si attesta sul catalogo con la chiave di contenuto (D9). Le colonne sono quelle per cui si cerca; il corpo canonico
 * (al piu' 4 KiB) e' opaco per il database.
 */
@Entity(
  tableName = "shared_album_entry",
  primaryKeys = ["album_id", "device_id", "entry_key"],
  indices = [Index(value = ["album_id", "content_key"]), Index(value = ["album_id", "field"])],
)
class SharedAlbumEntryEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  /** SHA-256("mcg-album\0" || id album || campo), 64 cifre esadecimali (D7). */
  @ColumnInfo(name = "entry_key") val entryKey: String,
  /** `definition`, `level`, `cover`, `sort`, `item`, `heir` (il tipo della voce). */
  @ColumnInfo(name = "field") val field: String,
  /** L'ora del NAS dichiarata dalla voce (`at`). */
  @ColumnInfo(name = "at") val at: Long,
  /** L'account di chi ha scritto la voce (uguale al `nasUser` del dispositivo nel registro, D8). */
  @ColumnInfo(name = "account") val account: String,
  /** Per un `item`: la chiave di contenuto intera dell'elemento (64 cifre), per attestarlo e per accorgersi di un doppione (E-SH12). */
  @ColumnInfo(name = "content_key") val contentKey: String? = null,
  /** Una lapide (`value` assente): l'elemento o il campo e' stato tolto. */
  @ColumnInfo(name = "tombstone", defaultValue = "0") val tombstone: Boolean = false,
  @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
) {
  override fun equals(other: Any?): Boolean =
    other is SharedAlbumEntryEntity && albumId == other.albumId && deviceId == other.deviceId && entryKey == other.entryKey &&
      field == other.field && at == other.at && account == other.account && contentKey == other.contentKey &&
      tombstone == other.tombstone && body.contentEquals(other.body)

  override fun hashCode(): Int = (albumId.hashCode() * 31 + deviceId.hashCode()) * 31 + entryKey.hashCode()

  override fun toString(): String = "SharedAlbumEntryEntity($field, tombstone=$tombstone)"
}

/**
 * L'ultima testa accettata di un dispositivo per un album: la memoria dell'anti-rollback e dell'equivocazione (`HeadMark`, D8) e la
 * `createdAt` con cui si rifiuta una voce nuova con `at` troppo vecchia (§2.4, "Ora delle voci").
 */
@Entity(tableName = "shared_album_head_mark", primaryKeys = ["album_id", "device_id"])
data class SharedAlbumHeadMarkEntity(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "device_id") val deviceId: String,
  @ColumnInfo(name = "version") val version: Long,
  @ColumnInfo(name = "content_hash") val contentHash: String,
  @ColumnInfo(name = "registry_version") val registryVersion: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
) {
  /** I7: l'id di un dispositivo, l'impronta del contenuto e le date non finiscono in un log. */
  override fun toString(): String = "SharedAlbumHeadMarkEntity(v$version)"
}

/**
 * Un'operazione di condivisione nel diario (D29, §2.5; `ShareOpRecord` di core-albums). **Locale**: mai sincronizzato; si toglie
 * dall'azzeramento solo a operazioni ferme. Senza chiavi esterne (l'album nuovo non ha ancora la sua riga). `plan_hash` e' unico:
 * lo stesso piano non parte due volte.
 */
@Entity(tableName = "album_share_op", indices = [Index(value = ["plan_hash"], unique = true), Index(value = ["finished"])])
data class AlbumShareOpEntity(
  @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
  @ColumnInfo(name = "album_id") val albumId: String,
  /** `SharePlanKind.code`. */
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "plan_hash") val planHash: String,
  @ColumnInfo(name = "title") val title: String,
  /** `ShareLevel.code`, per un album nuovo. */
  @ColumnInfo(name = "level") val level: String?,
  /** Il nonce (esadecimale) da cui si ricalcola l'id dell'album nuovo (D7). */
  @ColumnInfo(name = "nonce_hex") val nonceHex: String?,
  @ColumnInfo(name = "folder_share") val folderShare: String?,
  @ColumnInfo(name = "folder_path") val folderPath: String?,
  /** `HiddenChoice.code`. */
  @ColumnInfo(name = "hidden") val hidden: String,
  @ColumnInfo(name = "finished", defaultValue = "0") val finished: Boolean = false,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  override fun toString(): String = "AlbumShareOpEntity(${kind}, finished=$finished)"
}

/**
 * Un elemento di un'operazione di condivisione (`ShareItemRecord`). **Ogni valore di un passo si scrive prima della chiamata che lo
 * usa** (la chiave del trasferimento prima della porta, il biglietto appena risponde, il numero d'ordine della voce prima di
 * considerarla accodata). Il media segue le unioni d'identita'.
 */
@Entity(
  tableName = "album_share_item",
  primaryKeys = ["op_id", "item_index"],
  foreignKeys = [
    ForeignKey(
      entity = AlbumShareOpEntity::class,
      parentColumns = ["op_id"],
      childColumns = ["op_id"],
      onDelete = ForeignKey.CASCADE,
      deferred = true,
    ),
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["media_id"],
      deferred = true,
    ),
  ],
  indices = [Index(value = ["media_id"]), Index(value = ["transfer_key"]), Index(value = ["state"])],
)
data class AlbumShareItemEntity(
  @ColumnInfo(name = "op_id") val opId: String,
  @ColumnInfo(name = "item_index") val itemIndex: Int,
  @ColumnInfo(name = "media_id") val mediaId: String,
  /** `ShareItemAction.code`. */
  @ColumnInfo(name = "action") val action: String,
  /** `ShareItemState.code`. */
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "mine") val mine: Boolean,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "content_key") val contentKey: String? = null,
  @ColumnInfo(name = "source_share") val sourceShare: String? = null,
  @ColumnInfo(name = "source_path") val sourcePath: String? = null,
  @ColumnInfo(name = "dest_share") val destShare: String? = null,
  @ColumnInfo(name = "dest_path") val destPath: String? = null,
  @ColumnInfo(name = "transfer_key") val transferKey: String? = null,
  @ColumnInfo(name = "ticket") val ticket: String? = null,
  @ColumnInfo(name = "stage_seq") val stageSeq: Long? = null,
  @ColumnInfo(name = "safe_copy_requested", defaultValue = "0") val safeCopyRequested: Boolean = false,
  /** `ShareFailure.code`, solo con lo stato `failed`. */
  @ColumnInfo(name = "failure") val failure: String? = null,
  /** `ShareWait.code`: perche' aspetta. */
  @ColumnInfo(name = "wait") val wait: String? = null,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  override fun toString(): String = "AlbumShareItemEntity(#$itemIndex, ${action}, ${state})"
}

/**
 * Una proposta della sezione Viaggi (D19, D20; `TripProposal` di core-albums). **Locale** (derivata dalle foto proprie): la riscrive la
 * rilevazione notturna. `trip_id` e' l'impronta della proposta (64 cifre): la voce `trip_dismissed:<impronta>` la scarta ovunque.
 */
@Entity(
  tableName = "trip_proposal",
  foreignKeys = [
    ForeignKey(
      entity = MediaEntity::class,
      parentColumns = ["media_id"],
      childColumns = ["cover_media_id"],
      onDelete = ForeignKey.SET_NULL,
      deferred = true,
    ),
  ],
  indices = [Index(value = ["cover_media_id"]), Index(value = ["to_day"])],
)
data class TripProposalEntity(
  @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
  /** `TripKind.code`: `trip`, `event`. */
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "from_day") val fromDay: Int,
  @ColumnInfo(name = "to_day") val toDay: Int,
  /** Il titolo gia' in italiano ("Oslo, marzo 2026"): un luogo, dato dell'utente, mai nei log. */
  @ColumnInfo(name = "label") val label: String,
  /** I media visibili della proposta (mai un nascosto, E-H7) e la copertina. */
  @ColumnInfo(name = "media_count") val mediaCount: Int,
  @ColumnInfo(name = "cover_media_id") val coverMediaId: String?,
  /** Gli id dei luoghi (`place_id`) separati da virgola, vuoto per un evento senza GPS. */
  @ColumnInfo(name = "place_ids", defaultValue = "''") val placeIds: String = "",
  /** L'album nato dalla proposta ("Crea album"): una proposta gia' album non si ripropone. */
  @ColumnInfo(name = "album_id") val albumId: String? = null,
  @ColumnInfo(name = "detected_at") val detectedAt: Long,
) {
  override fun toString(): String = "TripProposalEntity(${kind}, $mediaCount)"
}

/**
 * La "casa" e i luoghi abituali dedotti **solo in locale** dalle foto proprie con GPS (D18, C19): mai sincronizzata, pubblicata,
 * esportata o scritta nei log; la legge e la scrive solo `core-albums/.../trips/` (`Phase7ArchitectureTest`) e la toglie l'azzeramento.
 * Dati sensibili come il GPS. `slot` 0 = casa, 1.. = luoghi abituali (oltre il 10% dei giorni, non sono viaggi).
 */
@Entity(tableName = "home_estimate")
data class HomeEstimateEntity(
  @PrimaryKey @ColumnInfo(name = "slot") val slot: Int,
  /** `home`, `habitual`, oppure `searched` (VG-1: la ricerca c'e' stata e non ha trovato una casa; una riga senza coordinate). */
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "latitude") val latitude: Double,
  @ColumnInfo(name = "longitude") val longitude: Double,
  @ColumnInfo(name = "radius_km") val radiusKm: Double,
  /** I giorni distinti con foto serali o notturne che pesano su questo luogo e i giorni con GPS della finestra (per la quota). */
  @ColumnInfo(name = "days") val days: Int,
  @ColumnInfo(name = "total_days") val totalDays: Int,
  @ColumnInfo(name = "estimated_at") val estimatedAt: Long,
) {
  override fun toString(): String = "HomeEstimateEntity(slot=$slot)"
}
