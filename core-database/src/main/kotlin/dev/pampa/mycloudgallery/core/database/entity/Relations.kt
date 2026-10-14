package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation

/** Una versione con le sue posizioni e i suoi metadati. */
data class VersionGraph(
  @Embedded val version: MediaVersionEntity,
  @Relation(parentColumn = "version_id", entityColumn = "version_id")
  val locations: List<MediaLocationEntity>,
  @Relation(parentColumn = "version_id", entityColumn = "version_id")
  val metadata: VersionMetadataEntity?,
)

/** Un media con tutto il suo grafo: e' cio' che diventa l'aggregato `Media` del dominio. */
data class MediaGraph(
  @Embedded val media: MediaEntity,
  @Relation(entity = MediaVersionEntity::class, parentColumn = "media_id", entityColumn = "media_id")
  val versions: List<VersionGraph>,
)

/** Una cella della timeline: solo cio' che serve alla griglia (badge compresi). */
data class TimelineItem(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "presence") val presence: String,
  @ColumnInfo(name = "safety") val safety: String,
  @ColumnInfo(name = "group_id") val groupId: String?,
  @ColumnInfo(name = "favorite") val favorite: Boolean,
)

/** Un conteggio per stato (code di lavoro, "al sicuro"). */
data class StatusCount(
  @ColumnInfo(name = "status") val status: String,
  @ColumnInfo(name = "count") val count: Int,
)

/**
 * Una riga dell'indice della timeline (`TimelineIndexDao`): tutto cio' che serve alla griglia, letto da
 * `media` sola. I codici (`kind`, `source`) restano testo: li traduce chi costruisce l'indice.
 */
data class IndexRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "local_day") val localDay: Int,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "cell_flags") val cellFlags: Int,
  @ColumnInfo(name = "aspect_milli") val aspectMilli: Int,
  @ColumnInfo(name = "duration_ms") val durationMs: Long?,
  @ColumnInfo(name = "place_id") val placeId: Int?,
  @ColumnInfo(name = "thumb_hash") val thumbHash: ByteArray?,
  @ColumnInfo(name = "group_id") val groupId: String?,
) {
  override fun equals(other: Any?): Boolean =
    other is IndexRow && mediaId == other.mediaId && sortTime == other.sortTime && localDay == other.localDay &&
      kind == other.kind && source == other.source && cellFlags == other.cellFlags && aspectMilli == other.aspectMilli &&
      durationMs == other.durationMs && placeId == other.placeId && groupId == other.groupId &&
      thumbHash.contentEquals(other.thumbHash)

  override fun hashCode(): Int = mediaId.hashCode()
}

/** Quanti membri visibili ha un gruppo (il numero sulla copertina). */
data class GroupSizeRow(
  @ColumnInfo(name = "group_id") val groupId: String,
  @ColumnInfo(name = "size") val size: Int,
)

/**
 * Una copia presente sul NAS (v3, `TimelineIndexDao.nasRefs`): la posizione e cio' che serve per la chiave di contenuto
 * (`ContentKey.of(qh1, dimensione)`). [isCurrent]: la copia ha i byte della versione corrente.
 */
data class NasRefRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "location_id") val locationId: Long,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
  @ColumnInfo(name = "mime_type") val mimeType: String?,
  @ColumnInfo(name = "is_current") val isCurrent: Boolean,
)

/** Una copia sul telefono leggibile adesso: l'`_ID` in cache vale per la versione attuale del MediaStore. */
data class PhoneRefRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "volume") val volume: String,
  @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
  @ColumnInfo(name = "mime_type") val mimeType: String?,
)
