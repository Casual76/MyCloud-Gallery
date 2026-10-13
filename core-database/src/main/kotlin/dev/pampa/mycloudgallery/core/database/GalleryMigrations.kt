package dev.pampa.mycloudgallery.core.database

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.domain.GroupMember
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import java.time.ZoneId

/**
 * Le migrazioni dello schema, dalla v1 in poi. Per aggiungerne una (vedi `docs/modello-dati.md`, §7):
 *
 * 1. alzare [GalleryDatabase.VERSION] e cambiare le entita' (il build esporta `schemas/.../N.json`);
 * 2. scrivere qui `Migration(N-1, N)` e aggiungerla ad [ALL];
 * 3. in `ExportedSchemaTest` aggiungere i dati di prova della versione N (`SchemaSeeds`): il test
 *    apre ogni schema esportato, ci mette quei dati, migra fino all'ultima versione, lascia a Room la
 *    validazione dello schema risultante e controlla che i dati ci siano ancora.
 *
 * Mai distruttive: si aggiungono colonne e tabelle, i dati restano.
 */
object GalleryMigrations {

  val ALL: Array<Migration> get() = arrayOf(migration1To2())

  /** [zone]: il fuso del telefono adesso, per il giorno provvisorio dei media senza data letta. */
  fun migration1To2(zone: () -> ZoneId = ZoneId::systemDefault): Migration = Migration1To2(zone)
}

/**
 * v1 -> v2 (Fase 3, `docs/fase3-progetto.md` §1.2):
 * - `media`: i campi derivati dell'indice della timeline (`local_day`, `aspect_milli`, `duration_ms`,
 *   `place_id`, `cell_flags`, `thumb_hash`), **calcolati qui** per i media esistenti con le stesse regole
 *   del catalogo (`TimelineRules`);
 * - `version_metadata`: `mirrored`, `traits`, `place_id`, `place_dataset`, `thumb_hash` (vuoti: li legge
 *   la fase META della Fase 3);
 * - `media_personal`: `favorite_changed_at`, `hidden_changed_at`, inizializzati con `updated_at`;
 * - tabelle nuove `media_store_cursor` e `catalog_meta`.
 *
 * I codici scritti in SQL ('meta', 'gave_up', 'none') sono quelli della v1, fissati qui di proposito.
 */
private class Migration1To2(private val zone: () -> ZoneId) : Migration(1, 2) {

  override fun migrate(db: SupportSQLiteDatabase) {
    ADD_COLUMNS.forEach(db::execSQL)
    db.execSQL("UPDATE `media_personal` SET `favorite_changed_at` = `updated_at`, `hidden_changed_at` = `updated_at`")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `media_store_cursor` (`volume` TEXT NOT NULL, `ms_version` TEXT, `generation` INTEGER, " +
        "`deleted_generation` INTEGER, `scan_epoch` INTEGER, `scan_resume_after_id` INTEGER, `last_full_scan_at` INTEGER, " +
        "`last_reconcile_at` INTEGER, PRIMARY KEY(`volume`))",
    )
    db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_meta` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))")
    fillDerived(db)
  }

  private class Row(
    val mediaId: String,
    val kind: String,
    val sortTime: Long,
    val groupId: String?,
    val visible: Boolean,
    val versionKind: String?,
    val width: Int?,
    val height: Int?,
    val orientation: Int?,
    val durationMs: Long?,
    val takenAtUtc: Long?,
    val takenOffsetMinutes: Int?,
    val takenConfidence: String?,
    val takenSource: String?,
    val favorite: Boolean,
    val unreadable: Boolean,
    val groupCover: String?,
  )

  /**
   * I derivati dei media esistenti. Si leggono prima tutte le righe e poi si scrive: aggiornare una
   * tabella mentre un cursore la scorre non e' affidabile con le finestre dei cursori di Android.
   */
  private fun fillDerived(db: SupportSQLiteDatabase) {
    val rows = db.query(SELECT_ROWS).use { c -> buildList { while (c.moveToNext()) add(c.toRow()) } }

    val covers = rows.filter { it.groupId != null }.groupBy { it.groupId!! }.mapValues { (_, members) ->
      TimelineRules.groupCover(
        members.map { GroupMember(MediaId(it.mediaId), it.sortTime, it.visible) },
        members.first().groupCover?.let(::MediaId),
      )
    }

    rows.forEach { row ->
      val capture = row.captureTime()
      val metadata = runCatching {
        MediaMetadata(
          width = row.width,
          height = row.height,
          orientationDegrees = row.orientation ?: 0,
          durationMillis = row.durationMs,
          captureTime = capture,
        )
      }.getOrNull()
      val flags = TimelineRules.cellFlags(
        kind = enumByCodeOrNull<MediaKind>(row.kind) ?: MediaKind.PHOTO,
        currentVersionKind = enumByCodeOrNull<VersionKind>(row.versionKind) ?: VersionKind.ORIGINAL,
        metadata = metadata,
        favorite = row.favorite,
        unreadable = row.unreadable,
      )
      val cover = row.groupId?.let { covers[it] }
      db.execSQL(
        "UPDATE `media` SET `local_day` = ?, `aspect_milli` = ?, `duration_ms` = ?, `cell_flags` = ? WHERE `media_id` = ?",
        arrayOf<Any?>(
          capture?.let(TimelineRules::localDay) ?: TimelineRules.localDay(row.sortTime, zone()),
          TimelineRules.aspectMilli(metadata),
          row.durationMs,
          TimelineRules.withGroupRole(flags, inGroup = row.groupId != null, isCover = cover?.value == row.mediaId),
          row.mediaId,
        ),
      )
    }
  }

  private fun Row.captureTime(): CaptureTime? {
    val utc = takenAtUtc ?: return null
    val offset = takenOffsetMinutes ?: return null
    val confidence = enumByCodeOrNull<CaptureTimeConfidence>(takenConfidence) ?: return null
    val source = enumByCodeOrNull<CaptureTimeSource>(takenSource) ?: return null
    return runCatching { CaptureTime(utc, offset, confidence, source) }.getOrNull()
  }

  private fun Cursor.toRow(): Row = Row(
    mediaId = getString(0),
    kind = getString(1),
    sortTime = getLong(2),
    groupId = stringOrNull(3),
    visible = getString(4) != "none" && getInt(5) == 0,
    versionKind = stringOrNull(6),
    width = intOrNull(7),
    height = intOrNull(8),
    orientation = intOrNull(9),
    durationMs = longOrNull(10),
    takenAtUtc = longOrNull(11),
    takenOffsetMinutes = intOrNull(12),
    takenConfidence = stringOrNull(13),
    takenSource = stringOrNull(14),
    favorite = getInt(15) != 0,
    unreadable = getInt(16) > 0,
    groupCover = stringOrNull(17),
  )

  private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

  private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

  private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

  private companion object {
    val ADD_COLUMNS = listOf(
      "ALTER TABLE `media` ADD COLUMN `local_day` INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE `media` ADD COLUMN `aspect_milli` INTEGER NOT NULL DEFAULT 1000",
      "ALTER TABLE `media` ADD COLUMN `duration_ms` INTEGER",
      "ALTER TABLE `media` ADD COLUMN `place_id` INTEGER",
      "ALTER TABLE `media` ADD COLUMN `cell_flags` INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE `media` ADD COLUMN `thumb_hash` BLOB",
      "ALTER TABLE `version_metadata` ADD COLUMN `mirrored` INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE `version_metadata` ADD COLUMN `traits` INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE `version_metadata` ADD COLUMN `place_id` INTEGER",
      "ALTER TABLE `version_metadata` ADD COLUMN `place_dataset` INTEGER",
      "ALTER TABLE `version_metadata` ADD COLUMN `thumb_hash` BLOB",
      "ALTER TABLE `media_personal` ADD COLUMN `favorite_changed_at` INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE `media_personal` ADD COLUMN `hidden_changed_at` INTEGER NOT NULL DEFAULT 0",
    )

    const val SELECT_ROWS: String =
      "SELECT m.media_id, m.kind, m.sort_time, m.group_id, m.presence, COALESCE(p.hidden, 0), v.kind, " +
        "md.width, md.height, md.orientation, md.duration_ms, md.taken_at_utc, md.taken_offset_min, md.taken_confidence, " +
        "md.taken_source, COALESCE(p.favorite, 0), " +
        "(SELECT COUNT(*) FROM work_state w WHERE w.media_id = m.media_id AND w.stage = 'meta' AND w.status = 'gave_up'), " +
        "g.cover_media_id " +
        "FROM media m LEFT JOIN media_version v ON v.version_id = m.current_version_id " +
        "LEFT JOIN version_metadata md ON md.version_id = m.current_version_id " +
        "LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "LEFT JOIN media_group g ON g.group_id = m.group_id"
  }
}
