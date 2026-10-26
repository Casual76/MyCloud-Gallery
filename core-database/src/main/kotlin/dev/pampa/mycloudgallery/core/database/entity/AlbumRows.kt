package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo

// Le righe delle query degli album (`AlbumQueries`): le legge `AlbumCatalog`, che le traduce nei tipi di `AlbumCatalogTypes.kt`. I
// codici restano testo; il significato dei bit di `cell_flags` e `copy_areas` e' di chi traduce.

/** Un candidato (una cella visibile) con cio' che serve alla sorgente, al dispositivo, al giorno e all'ora locali, al GPS e ai pixel. */
class CandidateRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "local_day") val localDay: Int,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "place_id") val placeId: Int?,
  @ColumnInfo(name = "origin_device") val originDevice: String?,
  @ColumnInfo(name = "cell_flags") val cellFlags: Int,
  @ColumnInfo(name = "copy_areas") val copyAreas: Int,
  @ColumnInfo(name = "owner_account") val ownerAccount: String?,
  @ColumnInfo(name = "taken_at_utc") val takenAtUtc: Long?,
  @ColumnInfo(name = "taken_offset_min") val takenOffsetMinutes: Int?,
  @ColumnInfo(name = "latitude") val latitude: Double?,
  @ColumnInfo(name = "longitude") val longitude: Double?,
  @ColumnInfo(name = "width") val width: Int?,
  @ColumnInfo(name = "height") val height: Int?,
)

/** I fatti di un media: la versione corrente (chiave di contenuto, dimensione) e i derivati che dicono dove sta. */
class FactsRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "owner_account") val ownerAccount: String?,
  @ColumnInfo(name = "cell_flags") val cellFlags: Int,
  @ColumnInfo(name = "hidden") val hidden: Boolean,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
)

/** Una copia presente sul NAS della versione corrente di un media, con l'area della sua share (NULL se la share non e' registrata). */
class FactsNasRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "share_name") val shareName: String,
  @ColumnInfo(name = "nas_path") val nasPath: String,
  @ColumnInfo(name = "area") val area: String?,
  @ColumnInfo(name = "location_id") val locationId: Long,
)

/** Una copia presente di una versione che ha piu' posizioni (Doppioni): dove sta, quanto pesa, se e' stata verificata. */
class DuplicateRow(
  @ColumnInfo(name = "location_id") val locationId: Long,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "location_type") val locationType: String,
  @ColumnInfo(name = "share_name") val shareName: String?,
  @ColumnInfo(name = "nas_path") val nasPath: String?,
  @ColumnInfo(name = "relative_path") val relativePath: String?,
  @ColumnInfo(name = "display_name") val displayName: String?,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long,
  @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "full_hash") val fullHash: String?,
  @ColumnInfo(name = "area") val area: String?,
)

/** Un media e il suo giorno locale (`AlbumCatalog.daysOf`). */
class MediaDayRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "local_day") val localDay: Int,
)

/** Un'identita' assorbita da un'unione e il media che l'ha assorbita. */
class AliasRow(
  @ColumnInfo(name = "alias_id") val aliasId: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
)
