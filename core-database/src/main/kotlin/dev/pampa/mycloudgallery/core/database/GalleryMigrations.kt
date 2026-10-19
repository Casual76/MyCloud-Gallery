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
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
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

  val ALL: Array<Migration> get() = arrayOf(migration1To2(), MIGRATION_2_3, MIGRATION_3_4)

  /** [zone]: il fuso del telefono adesso, per il giorno provvisorio dei media senza data letta. */
  fun migration1To2(zone: () -> ZoneId = ZoneId::systemDefault): Migration = Migration1To2(zone)

  /** v2 -> v3 (Fase 4): vedi [Migration2To3]. */
  val MIGRATION_2_3: Migration = Migration2To3

  /** v3 -> v4 (Fase 5): vedi [Migration3To4]. */
  val MIGRATION_3_4: Migration = Migration3To4
}

/**
 * v2 -> v3 (Fase 4, `docs/fase4-progetto.md` §1.3, §2.1-§2.3, §2.7; `docs/modello-dati.md` §11):
 * - `media_location.file_id` (il `FileId` SMB, E-N1) e l'indice (`account_id`, `share_key`, `last_seen_at`) per le
 *   operazioni su una share intera;
 * - `work_state`: l'indice (`stage`, `status`) diventa (`stage`, `status`, `updated_at`) (i fallimenti da riprovare escono
 *   in ordine senza ordinare in memoria);
 * - `media.cell_flags`: i bit di presenza `ON_PHONE`, `ON_NAS`, `SAFE` (D18) **calcolati qui** dai derivati gia' salvati
 *   (`presence`, `safety`) con le stesse regole del catalogo (`TimelineRules.presenceFlags`); nessun gruppo provvisorio
 *   esiste ancora, quindi nessun `TO_VERIFY`;
 * - le tabelle nuove: `nas_scan_state`, l'indice condiviso (pubblicazione: `index_publish_state`, `index_own_segment`,
 *   `index_outbox_record`, `index_outbox_blob`, `index_own_record`, `index_own_blob`; replica: `index_head`,
 *   `index_segment`, `index_record`, `replica_dirty`, `replica_pack`, `replica_blob`, `replica_share_access`,
 *   `replica_setting`, `replica_provisional`) e il diario della 4B (`nas_op`, `nas_op_item`, `nas_trash_entry`), vuote.
 *   P11 ha riconciliato le tabelle dell'indice con le porte di P3 e P4 prima che la v3 uscisse (nessun database v3 esiste
 *   sui dispositivi).
 *
 * Mai distruttiva: solo colonne, indici e tabelle in piu'. I codici scritti in SQL ('phone_only', 'safe', ...) sono quelli
 * della v2, fissati qui di proposito. Su API 29 (SQLite 3.22) niente `UPSERT`, `RETURNING`, `DROP COLUMN`.
 */
private object Migration2To3 : Migration(2, 3) {

  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `media_location` ADD COLUMN `file_id` INTEGER")
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_media_location_account_id_share_key_last_seen_at` " +
        "ON `media_location` (`account_id`, `share_key`, `last_seen_at`)",
    )
    db.execSQL("DROP INDEX IF EXISTS `index_work_state_stage_status`")
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_work_state_stage_status_updated_at` ON `work_state` (`stage`, `status`, `updated_at`)",
    )
    CREATE_TABLES.forEach(db::execSQL)
    fillPresenceFlags(db)
  }

  /**
   * I bit di presenza per i media esistenti: un `UPDATE` per ogni combinazione di (`presence`, `safety`) che li accende,
   * con i valori calcolati da [TimelineRules.presenceFlags] (la stessa funzione del catalogo), cosi' non c'e' una seconda
   * copia della regola in SQL.
   */
  private fun fillPresenceFlags(db: SupportSQLiteDatabase) {
    db.execSQL("UPDATE `media` SET `cell_flags` = `cell_flags` & ?", arrayOf<Any?>(TimelineRules.PRESENCE_MASK.inv()))
    for (presence in Presence.entries) {
      for (safety in SafetyStatus.entries) {
        val bits = TimelineRules.presenceFlags(presence, safety, probableDuplicate = false)
        if (bits == 0) continue
        db.execSQL(
          "UPDATE `media` SET `cell_flags` = `cell_flags` | ? WHERE `presence` = ? AND `safety` = ?",
          arrayOf<Any?>(bits, presence.code, safety.code),
        )
      }
    }
  }

  // Copiate da schemas/.../3.json (createSql di Room): la validazione dello schema dopo la migrazione le confronta.
  private val CREATE_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `nas_scan_state` (`account_id` TEXT NOT NULL, `share_key` TEXT NOT NULL, " +
      "`share_name` TEXT NOT NULL, `epoch` INTEGER, `resume_after` TEXT, `last_full_scan_at` INTEGER, " +
      "`freeze_reason` TEXT, `freeze_since` INTEGER, `freeze_known_present` INTEGER, `freeze_vanished` INTEGER, " +
      "`freeze_changed` INTEGER, `freeze_not_media` INTEGER, `window_start` INTEGER, `window_known` INTEGER, " +
      "`window_vanished` INTEGER, `window_changed` INTEGER, `window_not_media` INTEGER, PRIMARY KEY(`account_id`, `share_key`))",
    "CREATE TABLE IF NOT EXISTS `nas_adopted_fingerprint` (`location_id` INTEGER NOT NULL, `quick_hash` TEXT NOT NULL, " +
      "PRIMARY KEY(`location_id`))",
    "CREATE TABLE IF NOT EXISTS `index_publish_state` (`share_key` TEXT NOT NULL, `share_name` TEXT NOT NULL, " +
      "`next_seq` INTEGER NOT NULL, `head_version` INTEGER NOT NULL DEFAULT 0, `head_hash` TEXT, " +
      "`head_published_at` INTEGER, PRIMARY KEY(`share_key`))",
    "CREATE TABLE IF NOT EXISTS `index_own_segment` (`share_key` TEXT NOT NULL, `segment_hash` TEXT NOT NULL, " +
      "`cls` INTEGER NOT NULL, `size` INTEGER NOT NULL, `entries` INTEGER NOT NULL, `listed` INTEGER NOT NULL, " +
      "PRIMARY KEY(`share_key`, `segment_hash`))",
    "CREATE INDEX IF NOT EXISTS `index_index_own_segment_share_key_listed` ON `index_own_segment` (`share_key`, " +
      "`listed`)",
    "CREATE TABLE IF NOT EXISTS `index_outbox_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
      "`share_key` TEXT NOT NULL, `seq` INTEGER NOT NULL, `path_key` TEXT NOT NULL, `content_key` TEXT, " +
      "`body` BLOB NOT NULL, `sunk` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_index_outbox_record_share_key_seq` ON `index_outbox_record` (`share_key`, " +
      "`seq`)",
    "CREATE INDEX IF NOT EXISTS `index_index_outbox_record_sunk` ON `index_outbox_record` (`sunk`)",
    "CREATE TABLE IF NOT EXISTS `index_outbox_blob` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
      "`share_key` TEXT NOT NULL, `cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, " +
      "`byte_length` INTEGER NOT NULL, `handle` TEXT NOT NULL, `sunk` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_index_outbox_blob_share_key` ON `index_outbox_blob` (`share_key`)",
    "CREATE INDEX IF NOT EXISTS `index_index_outbox_blob_sunk` ON `index_outbox_blob` (`sunk`)",
    "CREATE TABLE IF NOT EXISTS `index_own_record` (`share_key` TEXT NOT NULL, `path_key` TEXT NOT NULL, " +
      "`path` TEXT NOT NULL, `seq` INTEGER NOT NULL, `gone` INTEGER NOT NULL, `content_key` TEXT, " +
      "`segment_hash` TEXT NOT NULL, PRIMARY KEY(`share_key`, `path_key`))",
    "CREATE INDEX IF NOT EXISTS `index_index_own_record_share_key_segment_hash` ON `index_own_record` (`share_key`, " +
      "`segment_hash`)",
    "CREATE INDEX IF NOT EXISTS `index_index_own_record_share_key_content_key` ON `index_own_record` (`share_key`, " +
      "`content_key`)",
    "CREATE TABLE IF NOT EXISTS `index_own_blob` (`share_key` TEXT NOT NULL, `cls` INTEGER NOT NULL, " +
      "`content_key` TEXT NOT NULL, `segment_hash` TEXT NOT NULL, PRIMARY KEY(`share_key`, `cls`, `content_key`))",
    "CREATE INDEX IF NOT EXISTS `index_index_own_blob_share_key_segment_hash` ON `index_own_blob` (`share_key`, " +
      "`segment_hash`)",
    "CREATE TABLE IF NOT EXISTS `index_head` (`share_key` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
      "`version` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `registry_version` INTEGER NOT NULL, " +
      "`created_at` INTEGER NOT NULL, PRIMARY KEY(`share_key`, `device_id`))",
    "CREATE TABLE IF NOT EXISTS `index_segment` (`share_key` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
      "`segment_hash` TEXT NOT NULL, `cls` INTEGER NOT NULL, `size` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
      "`listed` INTEGER NOT NULL, `pack_id` INTEGER, `position` INTEGER NOT NULL, PRIMARY KEY(`share_key`, " +
      "`device_id`, `segment_hash`))",
    "CREATE INDEX IF NOT EXISTS `index_index_segment_state_cls` ON `index_segment` (`state`, `cls`)",
    "CREATE TABLE IF NOT EXISTS `index_record` (`share_key` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
      "`segment_hash` TEXT NOT NULL, `record_key` TEXT NOT NULL, `seq` INTEGER NOT NULL, `gone` INTEGER NOT NULL, " +
      "`content_key` TEXT, `prep` INTEGER NOT NULL DEFAULT 0, `body` BLOB NOT NULL, PRIMARY KEY(`share_key`, " +
      "`device_id`, `segment_hash`, `record_key`))",
    "CREATE INDEX IF NOT EXISTS `index_index_record_share_key_record_key` ON `index_record` (`share_key`, " +
      "`record_key`)",
    "CREATE TABLE IF NOT EXISTS `replica_dirty` (`share_key` TEXT NOT NULL, `record_key` TEXT NOT NULL, " +
      "PRIMARY KEY(`share_key`, `record_key`))",
    "CREATE TABLE IF NOT EXISTS `replica_pack` (`pack_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
      "`file_name` TEXT NOT NULL, `kind` TEXT NOT NULL, `cls` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, " +
      "`share_key` TEXT, `device_id` TEXT, `segment_hash` TEXT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_replica_pack_file_name` ON `replica_pack` (`file_name`)",
    "CREATE INDEX IF NOT EXISTS `index_replica_pack_share_key_device_id_segment_hash` ON `replica_pack` (`share_key`, " +
      "`device_id`, `segment_hash`)",
    "CREATE TABLE IF NOT EXISTS `replica_blob` (`content_key` TEXT NOT NULL, `cls` INTEGER NOT NULL, " +
      "`pack_id` INTEGER NOT NULL, `content_key_short` INTEGER NOT NULL, `byte_offset` INTEGER NOT NULL, " +
      "`byte_length` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `segment_hash` TEXT NOT NULL, " +
      "PRIMARY KEY(`content_key`, `cls`, `pack_id`), " +
      "FOREIGN KEY(`pack_id`) REFERENCES `replica_pack`(`pack_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_replica_blob_content_key_short_cls` ON `replica_blob` (`content_key_short`, " +
      "`cls`)",
    "CREATE INDEX IF NOT EXISTS `index_replica_blob_pack_id` ON `replica_blob` (`pack_id`)",
    "CREATE TABLE IF NOT EXISTS `replica_share_access` (`share_key` TEXT NOT NULL, `failure` TEXT NOT NULL, " +
      "`first_failure_day` INTEGER NOT NULL, `dropped` INTEGER NOT NULL, PRIMARY KEY(`share_key`))",
    "CREATE TABLE IF NOT EXISTS `replica_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    "CREATE TABLE IF NOT EXISTS `replica_provisional` (`content_key_short` INTEGER NOT NULL, " +
      "`content_key` TEXT NOT NULL, `jpeg` BLOB NOT NULL, PRIMARY KEY(`content_key_short`))",
    "CREATE TABLE IF NOT EXISTS `nas_op` (`op_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `state` TEXT NOT NULL, " +
      "`plan_hash` TEXT NOT NULL, `item_count` INTEGER NOT NULL, `total_bytes` INTEGER NOT NULL, `error_code` TEXT, " +
      "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`op_id`))",
    "CREATE INDEX IF NOT EXISTS `index_nas_op_state` ON `nas_op` (`state`)",
    "CREATE TABLE IF NOT EXISTS `nas_op_item` (`op_id` TEXT NOT NULL, `item_index` INTEGER NOT NULL, " +
      "`state` TEXT NOT NULL, `is_folder` INTEGER NOT NULL, `account_id` TEXT NOT NULL, `src_share` TEXT NOT NULL, " +
      "`src_path` TEXT NOT NULL, `dst_share` TEXT, `dst_path` TEXT, `temp_name` TEXT, `size` INTEGER, " +
      "`modified_at` INTEGER, `file_id` INTEGER, `sha256` TEXT, `trash_id` TEXT, " +
      "`attempts` INTEGER NOT NULL DEFAULT 0, `next_attempt_at` INTEGER, `error_code` TEXT, " +
      "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`op_id`, `item_index`), " +
      "FOREIGN KEY(`op_id`) REFERENCES `nas_op`(`op_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_nas_op_item_state_next_attempt_at` ON `nas_op_item` (`state`, " +
      "`next_attempt_at`)",
    "CREATE TABLE IF NOT EXISTS `nas_trash_entry` (`trash_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, " +
      "`share_key` TEXT NOT NULL, `share_name` TEXT NOT NULL, `original_path` TEXT NOT NULL, " +
      "`trashed_path` TEXT NOT NULL, `reason` TEXT NOT NULL, `is_folder` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
      "`file_count` INTEGER, `trashed_at` INTEGER NOT NULL, `state` TEXT NOT NULL, `op_id` TEXT, " +
      "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`trash_id`))",
    "CREATE INDEX IF NOT EXISTS `index_nas_trash_entry_state_trashed_at` ON `nas_trash_entry` (`state`, " +
      "`trashed_at`)",
  )
}

/**
 * v3 -> v4 (Fase 5, `docs/fase5-progetto.md` D27, `docs/modello-dati.md` §12):
 * - `media.backup_todo` (i candidati del backup) e il suo indice (`backup_todo`, `sort_time`, `media_id`): **calcolato qui** dai
 *   derivati gia' salvati (`presence`, `safety`) con la stessa regola del catalogo (`TimelineRules.backupTodo`), senza conoscere
 *   l'account del dispositivo (`safety` dice gia' se il media e' proprio) e senza problemi registrati (la tabella nasce vuota);
 * - l'indice (`account_id`, `share_key`, `verified_at`) di `media_location`: la riverifica a rotazione e le copie da verificare;
 * - le tabelle nuove, vuote: il diario del backup (`backup_item`), il problema di un media (`backup_problem`), le copie che non sono tornate (`nas_copy_mismatch`), la firma visiva
 *   delle copie simili (`visual_signature`) e quelle che la 5B usera' (`pending_task`, `edit_session`, `free_space_run`,
 *   `free_space_item`, `incoming_item`): una sola migrazione sul campo per tutta la fase.
 *
 * Nessun bit di `cell_flags` cambia: `BACKUP_PROBLEM` e `SIMILAR_COPIES` nascono accesi solo dal catalogo (nessun problema e nessun
 * gruppo di copie simili esistono ancora). Mai distruttiva: solo colonne, indici e tabelle in piu'. I codici scritti in SQL
 * ('phone_only', 'unverified', ...) sono quelli della v3, fissati qui di proposito. Su API 29 (SQLite 3.22) niente `UPSERT`,
 * `RETURNING`, `DROP COLUMN`.
 */
private object Migration3To4 : Migration(3, 4) {

  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `media` ADD COLUMN `backup_todo` INTEGER NOT NULL DEFAULT 0")
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_media_backup_todo_sort_time_media_id` ON `media` (`backup_todo`, `sort_time`, `media_id`)",
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_media_location_account_id_share_key_verified_at` ON `media_location` " +
        "(`account_id`, `share_key`, `verified_at`)",
    )
    CREATE_TABLES.forEach(db::execSQL)
    fillBackupTodo(db)
  }

  /**
   * I candidati del backup dei media esistenti: un `UPDATE` per ogni combinazione di (`presence`, `safety`) che li accende, con
   * il valore calcolato da [TimelineRules.backupTodo] (la stessa funzione del catalogo), cosi' non c'e' una seconda copia della
   * regola in SQL. Nessun problema del backup esiste ancora.
   */
  private fun fillBackupTodo(db: SupportSQLiteDatabase) {
    for (presence in Presence.entries) {
      for (safety in SafetyStatus.entries) {
        if (!TimelineRules.backupTodo(presence, safety, markedProblem = false)) continue
        db.execSQL(
          "UPDATE `media` SET `backup_todo` = 1 WHERE `presence` = ? AND `safety` = ?",
          arrayOf<Any?>(presence.code, safety.code),
        )
      }
    }
  }

  // Copiate da schemas/.../4.json (createSql di Room): la validazione dello schema dopo la migrazione le confronta.
  private val CREATE_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `backup_item` (`item_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
      "`state` TEXT NOT NULL, `is_open` INTEGER NOT NULL, `media_id` TEXT NOT NULL, `version_id` TEXT NOT NULL, `size` " +
      "INTEGER NOT NULL, `phone_volume` TEXT, `phone_ms_id` INTEGER, `phone_ms_version` TEXT, `expected_size` INTEGER, " +
      "`expected_modified_at` INTEGER, `expected_generation` INTEGER, `share` TEXT NOT NULL, `planned_path` TEXT NOT NULL, " +
      "`final_path` TEXT, `name_attempt` INTEGER NOT NULL, `temp_path` TEXT, `resume_at` INTEGER NOT NULL, `resume_file_id` INTEGER, " +
      "`sha256` TEXT, `quick_hash` TEXT, `temp_size` INTEGER, `temp_modified_at` INTEGER, `temp_file_id` INTEGER, `final_size` INTEGER, " +
      "`final_modified_at` INTEGER, `final_file_id` INTEGER, `verify_attempts` INTEGER NOT NULL, `guard_share` TEXT, " +
      "`guard_path` TEXT, `guard_size` INTEGER, `guard_modified_at` INTEGER, `guard_file_id` INTEGER, `problem` TEXT, " +
      "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) " +
      "ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`version_id`) REFERENCES " +
      "`media_version`(`version_id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_backup_item_is_open_item_id` ON `backup_item` (`is_open`, `item_id`)",
    "CREATE INDEX IF NOT EXISTS `index_backup_item_version_id_is_open` ON `backup_item` (`version_id`, `is_open`)",
    "CREATE INDEX IF NOT EXISTS `index_backup_item_media_id` ON `backup_item` (`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_backup_item_state` ON `backup_item` (`state`)",
    "CREATE TABLE IF NOT EXISTS `backup_problem` (`media_id` TEXT NOT NULL, `code` TEXT NOT NULL, `marks_cell` INTEGER NOT " +
      "NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`media_id`), FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) " +
      "ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
    "CREATE TABLE IF NOT EXISTS `nas_copy_mismatch` (`location_id` INTEGER NOT NULL, `size` INTEGER NOT NULL, `modified_at` " +
      "INTEGER NOT NULL, `found_at` INTEGER NOT NULL, PRIMARY KEY(`location_id`), FOREIGN KEY(`location_id`) REFERENCES " +
      "`media_location`(`location_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
    "CREATE TABLE IF NOT EXISTS `visual_signature` (`version_id` TEXT NOT NULL, `algorithm` INTEGER NOT NULL, `frames` " +
      "INTEGER NOT NULL, `data` BLOB NOT NULL, `signed_at` INTEGER NOT NULL, PRIMARY KEY(`version_id`), FOREIGN " +
      "KEY(`version_id`) REFERENCES `media_version`(`version_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY " +
      "DEFERRED)",
    "CREATE TABLE IF NOT EXISTS `pending_task` (`task_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
      "`state` TEXT NOT NULL, `media_id` TEXT NOT NULL, `version_id` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `created_at` " +
      "INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO " +
      "ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`version_id`) REFERENCES " +
      "`media_version`(`version_id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_pending_task_state_created_at` ON `pending_task` (`state`, `created_at`)",
    "CREATE INDEX IF NOT EXISTS `index_pending_task_media_id` ON `pending_task` (`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_pending_task_version_id` ON `pending_task` (`version_id`)",
    "CREATE TABLE IF NOT EXISTS `edit_session` (`session_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` TEXT " +
      "NOT NULL, `version_id` TEXT NOT NULL, `target` TEXT NOT NULL, `state` TEXT NOT NULL, `phone_volume` TEXT, " +
      "`phone_ms_id` INTEGER, `phone_ms_version` TEXT, `start_size` INTEGER NOT NULL, `start_modified_at` INTEGER NOT NULL, " +
      "`start_generation` INTEGER, `relative_path` TEXT, `taken_at_utc` INTEGER, `cache_name` TEXT, `cache_sha256` TEXT, " +
      "`started_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) " +
      "ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`version_id`) REFERENCES " +
      "`media_version`(`version_id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_edit_session_state_expires_at` ON `edit_session` (`state`, `expires_at`)",
    "CREATE INDEX IF NOT EXISTS `index_edit_session_media_id` ON `edit_session` (`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_edit_session_version_id` ON `edit_session` (`version_id`)",
    "CREATE TABLE IF NOT EXISTS `free_space_run` (`run_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `state` TEXT NOT " +
      "NULL, `filter` TEXT NOT NULL, `exclude_recent_days` INTEGER NOT NULL, `plan_hash` TEXT NOT NULL, `delete_mode` TEXT " +
      "NOT NULL, `item_count` INTEGER NOT NULL, `total_bytes` INTEGER NOT NULL, `batches_total` INTEGER NOT NULL, " +
      "`batches_done` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_free_space_run_state` ON `free_space_run` (`state`)",
    "CREATE TABLE IF NOT EXISTS `free_space_item` (`run_id` INTEGER NOT NULL, `item_index` INTEGER NOT NULL, `state` TEXT " +
      "NOT NULL, `media_id` TEXT NOT NULL, `version_id` TEXT NOT NULL, `phone_location_id` INTEGER NOT NULL, " +
      "`nas_location_id` INTEGER, `size` INTEGER NOT NULL, `verified_size` INTEGER, `verified_modified_at` INTEGER, " +
      "`verified_file_id` INTEGER, `batch_index` INTEGER, `reason` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`run_id`, " +
      "`item_index`), FOREIGN KEY(`run_id`) REFERENCES `free_space_run`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE " +
      "DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO ACTION ON DELETE NO " +
      "ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`version_id`) REFERENCES `media_version`(`version_id`) ON UPDATE NO " +
      "ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_free_space_item_phone_location_id` ON `free_space_item` (`phone_location_id`)",
    "CREATE INDEX IF NOT EXISTS `index_free_space_item_media_id` ON `free_space_item` (`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_free_space_item_version_id` ON `free_space_item` (`version_id`)",
    "CREATE INDEX IF NOT EXISTS `index_free_space_item_run_id_batch_index` ON `free_space_item` (`run_id`, `batch_index`)",
    "CREATE TABLE IF NOT EXISTS `incoming_item` (`incoming_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stage_id` TEXT " +
      "NOT NULL, `state` TEXT NOT NULL, `file_name` TEXT NOT NULL, `display_name` TEXT, `sha256` TEXT NOT NULL, `size` " +
      "INTEGER NOT NULL, `mime` TEXT NOT NULL, `dest_share` TEXT, `dest_folder` TEXT, `backup_item_id` INTEGER, `created_at` " +
      "INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`backup_item_id`) REFERENCES `backup_item`(`item_id`) ON " +
      "UPDATE NO ACTION ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_incoming_item_state_created_at` ON `incoming_item` (`state`, `created_at`)",
    "CREATE INDEX IF NOT EXISTS `index_incoming_item_stage_id` ON `incoming_item` (`stage_id`)",
    "CREATE INDEX IF NOT EXISTS `index_incoming_item_backup_item_id` ON `incoming_item` (`backup_item_id`)",
  )
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
