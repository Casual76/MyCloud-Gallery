package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo

// Le righe lette dalle query del backup e delle copie simili (Fase 5A, P1). Nessuna e' una tabella: sono i risultati delle query
// di `BackupDaos.kt` e di `MediaCatalog`. I `toString` sono muti: percorsi, nomi e id sono dati dell'utente (mai nei log, §4.3).

/** Un candidato del backup (`BackupQueries.candidates`): il media, la versione da proteggere e la sua riga del telefono. */
data class BackupCandidateRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "local_day") val localDay: Int,
  @ColumnInfo(name = "cell_flags") val cellFlags: Int,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "version_size") val versionSize: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
  @ColumnInfo(name = "is_original") val isOriginal: Boolean,
  @ColumnInfo(name = "volume") val volume: String?,
  @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
  @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String?,
  @ColumnInfo(name = "relative_path") val relativePath: String?,
  @ColumnInfo(name = "display_name") val displayName: String?,
  @ColumnInfo(name = "stamp_size") val stampSize: Long,
  @ColumnInfo(name = "stamp_modified_at") val stampModifiedAt: Long,
  @ColumnInfo(name = "stamp_generation") val stampGeneration: Long?,
  /** La data non e' una lettura dei metadati: mancano (META non ancora passata) o sono una stima (nome o data del file). */
  @ColumnInfo(name = "date_estimated") val dateEstimated: Boolean,
) {
  override fun toString(): String = "BackupCandidateRow($kind, $versionSize B)"
}

/** Una copia sul NAS (`privateCopies`, `verifyCandidates`, `onlyCommonCopies`): posto, versione e timbro. */
data class NasCopyRow(
  @ColumnInfo(name = "location_id") val locationId: Long,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "share_name") val shareName: String?,
  @ColumnInfo(name = "nas_path") val nasPath: String?,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long,
  @ColumnInfo(name = "file_id") val fileId: Long?,
  /** Lo SHA-256 della versione, se noto (`media_version.full_hash`). */
  @ColumnInfo(name = "full_hash") val fullHash: String?,
  /** Quando la copia e' stata riletta con hash uguale (`media_location.verified_at`), se lo e'. */
  @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
  /** `qh1` della versione. */
  @ColumnInfo(name = "quick_hash") val quickHash: String?,
  /** Il giorno dello scatto del media (`media.local_day`), 0 = senza data. */
  @ColumnInfo(name = "local_day") val localDay: Int,
) {
  override fun toString(): String = "NasCopyRow($size B)"
}

/** Un conteggio per codice (problemi del backup per codice). */
data class CodeCountRow(
  @ColumnInfo(name = "code") val code: String,
  @ColumnInfo(name = "count") val count: Int,
)

/** La riga del telefono di una versione, letta da `phoneRefOf`. */
data class PhonePlaceRow(
  @ColumnInfo(name = "volume") val volume: String,
  @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
  @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String,
) {
  override fun toString(): String = "PhonePlaceRow"
}

/** Un candidato alle copie simili (`SimilarQueries`): tutto cio' che serve al rilevatore per decidere cosa firmare e confrontare. */
data class SimilarCandidateRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "aspect_milli") val aspectMilli: Int,
  @ColumnInfo(name = "duration_ms") val durationMs: Long?,
  @ColumnInfo(name = "group_id") val groupId: String?,
  @ColumnInfo(name = "group_kind") val groupKind: String?,
  @ColumnInfo(name = "signature_algorithm") val signatureAlgorithm: Int?,
) {
  override fun toString(): String = "SimilarCandidateRow($kind)"
}

/** Un gruppo e il suo tipo (elenco dei gruppi di copie simili). */
data class GroupIdRow(@ColumnInfo(name = "group_id") val groupId: String)

/** P14 (P9, "Libera spazio"): un media con una copia sul telefono, nell'ordine della Libreria (`MediaCatalog.phoneMedia`). */
data class PhoneMediaRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "local_day") val localDay: Int,
) {
  override fun toString(): String = "PhoneMediaRow"
}
