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
