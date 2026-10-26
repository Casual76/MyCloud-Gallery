package dev.pampa.mycloudgallery.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 -> v6 (Fase 7, `docs/fase7-progetto.md` D34; `docs/modello-dati.md` §14): gli album smart e condivisi, la condivisione, i viaggi.
 *
 * - `album` (`kind`, `sort`, `frozen`, `shared_id`, `state`) e `album_item` (`origin`, `fp`, `added_by`): colonne in piu' con il loro
 *   DEFAULT. Gli album e gli elementi che c'erano sono **manuali** e personali (la v5 non aveva altro): `kind` `manual`, `sort`
 *   `newest` (il predefinito dei manuali, D21), `origin` `manual`, `state` NULL. **L'impronta `fp`** di ogni elemento si ricava qui
 *   dalla versione corrente del suo media (`<qh1>:<dimensione>`, la stessa che `fingerprintOfMedia` di core-personal da' alle voci);
 * - `media.origin_device` e `media.copy_areas`: derivati dalle posizioni, **calcolati qui** con le stesse regole del catalogo
 *   ([OriginDevice], [CopyAreas]: la prima parte in SQL, le cartelle `Backup/<dispositivo>/` con la stessa funzione Kotlin del
 *   catalogo), cosi' dopo la migrazione il primo ricalcolo non cambia niente e non annuncia niente (`ExportedSchemaTest`);
 * - gli indici dei candidati per giorno, luogo e dispositivo (su `media`) e quello dei doppioni (`media_location`: versione e tipo):
 *   si costruiscono **dopo** aver riempito i derivati;
 * - le tabelle nuove, vuote: `album_rule`, `album_exclusion`, `album_suggestion`, `shared_album`, `shared_album_entry`,
 *   `shared_album_head_mark`, `album_share_op`, `album_share_item`, `trip_proposal`, `home_estimate`.
 *
 * Mai distruttiva: solo colonne, indici e tabelle in piu', nessuna riga cambia se non nelle colonne nuove. I codici scritti in SQL
 * ('phone', 'nas', 'present', 'absent', 'private', 'common') sono quelli della v5, fissati qui di proposito. Su API 29 (SQLite 3.22) niente
 * `UPSERT`, `RETURNING`, `DROP COLUMN`.
 */
internal object Migration5To6 : Migration(5, 6) {

  override fun migrate(db: SupportSQLiteDatabase) {
    ADD_COLUMNS.forEach(db::execSQL)
    fillAlbumItemFingerprints(db)
    fillOriginDevice(db)
    fillCopyAreas(db)
    CREATE.forEach(db::execSQL)
    EXISTING_TABLE_INDICES.forEach(db::execSQL)
  }

  /** `fp` = `<qh1>:<dimensione>` della versione corrente (la stessa forma che usera' la proiezione degli album, P3). */
  private fun fillAlbumItemFingerprints(db: SupportSQLiteDatabase) {
    db.execSQL(
      "UPDATE `album_item` SET `fp` = (SELECT v.`quick_hash` || ':' || v.`size` FROM `media` m " +
        "CROSS JOIN `media_version` v ON v.`version_id` = m.`current_version_id` WHERE m.`media_id` = `album_item`.`media_id`)",
    )
  }

  /**
   * `origin_device`: `phone` per chi ha una copia non assente sul telefono (un `UPDATE` solo); poi, per gli altri, la cartella
   * `Backup/<dispositivo>/` di un'area privata, con [OriginDevice.ofNasKey]. Si leggono prima tutte le righe e poi si scrive: aggiornare
   * una tabella mentre un cursore la scorre non e' affidabile con le finestre dei cursori di Android.
   *
   * **Mai una sottoquery correlata fra `media` e le posizioni**: senza statistiche (sul telefono nessuno esegue `ANALYZE`) SQLite puo'
   * scegliere di scorrere le posizioni per ogni media (quadratico: 10.000 media erano minuti). Le sottoquery sono indipendenti dalla riga
   * che si aggiorna (si valutano una volta) e il loro ordine e' fisso con `CROSS JOIN`: dalle posizioni, giu' per chiave primaria.
   */
  private fun fillOriginDevice(db: SupportSQLiteDatabase) {
    db.execSQL(
      "UPDATE `media` SET `origin_device` = '${OriginDevice.PHONE}' WHERE `media_id` IN (SELECT v.`media_id` FROM `media_location` l " +
        "CROSS JOIN `media_version` v ON v.`version_id` = l.`version_id` WHERE l.`location_type` = 'phone' AND l.`state` != 'absent')",
    )
    val labels = HashMap<String, String>()
    db.query(BACKUP_KEYS).use { cursor ->
      while (cursor.moveToNext()) {
        val label = OriginDevice.ofNasKey(cursor.getString(1)) ?: continue
        val media = cursor.getString(0)
        val known = labels[media]
        if (known == null || label < known) labels[media] = label
      }
    }
    if (labels.isEmpty()) return
    val update = db.compileStatement("UPDATE `media` SET `origin_device` = ? WHERE `media_id` = ?")
    for ((media, label) in labels) {
      update.clearBindings()
      update.bindString(1, label)
      update.bindString(2, media)
      update.executeUpdateDelete()
    }
  }

  /** `copy_areas`: un `UPDATE` per area, su chi ha una copia **presente** sul NAS in una share di quell'area (o di area sconosciuta). */
  private fun fillCopyAreas(db: SupportSQLiteDatabase) {
    for ((area, bit) in listOf("private" to CopyAreas.PRIVATE, "common" to CopyAreas.COMMON)) {
      db.execSQL(
        "UPDATE `media` SET `copy_areas` = `copy_areas` | $bit WHERE `media_id` IN (SELECT v.`media_id` FROM `media_location` l " +
          "CROSS JOIN `media_version` v ON v.`version_id` = l.`version_id` CROSS JOIN `nas_share` s ON s.`account_id` = l.`account_id` " +
          "AND s.`share_key` = l.`share_key` WHERE l.`location_type` = 'nas' AND l.`state` = 'present' AND s.`area` = '$area')",
      )
    }
    db.execSQL(
      "UPDATE `media` SET `copy_areas` = `copy_areas` | ${CopyAreas.OTHER} WHERE `media_id` IN (SELECT v.`media_id` FROM `media_location` l " +
        "CROSS JOIN `media_version` v ON v.`version_id` = l.`version_id` WHERE l.`location_type` = 'nas' AND l.`state` = 'present' " +
        "AND NOT EXISTS (SELECT 1 FROM `nas_share` s WHERE s.`account_id` = l.`account_id` AND s.`share_key` = l.`share_key`))",
    )
  }

  /**
   * Le posizioni del NAS non assenti, in un'area privata, la cui chiave nomina una cartella `Backup` (un filtro grossolano in SQL; la
   * decisione e' di [OriginDevice.ofNasKey]), dei media che non hanno gia' una copia sul telefono.
   */
  private const val BACKUP_KEYS: String =
    "SELECT v.`media_id`, l.`location_key` FROM `media_location` l CROSS JOIN `media_version` v ON v.`version_id` = l.`version_id` " +
      "CROSS JOIN `nas_share` s ON s.`account_id` = l.`account_id` AND s.`share_key` = l.`share_key` " +
      "CROSS JOIN `media` m ON m.`media_id` = v.`media_id` " +
      "WHERE l.`location_type` = 'nas' AND l.`state` != 'absent' AND s.`area` = 'private' AND m.`origin_device` IS NULL " +
      "AND instr(l.`location_key`, ':backup/') > 0"

  private val ADD_COLUMNS = listOf(
    "ALTER TABLE `album` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'manual'",
    "ALTER TABLE `album` ADD COLUMN `sort` TEXT NOT NULL DEFAULT 'newest'",
    "ALTER TABLE `album` ADD COLUMN `frozen` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `album` ADD COLUMN `shared_id` TEXT",
    "ALTER TABLE `album` ADD COLUMN `state` TEXT",
    "ALTER TABLE `album_item` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'manual'",
    "ALTER TABLE `album_item` ADD COLUMN `fp` TEXT",
    "ALTER TABLE `album_item` ADD COLUMN `added_by` TEXT",
    "ALTER TABLE `media` ADD COLUMN `origin_device` TEXT",
    "ALTER TABLE `media` ADD COLUMN `copy_areas` INTEGER NOT NULL DEFAULT 0",
  )

  // Copiate da schemas/.../6.json (createSql di Room): la validazione dello schema dopo la migrazione le confronta, e
  // `ExportedSchemaTest` confronta le definizioni di sqlite_master con quelle che Room crea da zero.
  private val CREATE = listOf(
    "CREATE TABLE IF NOT EXISTS `album_rule` (`album_id` TEXT NOT NULL, `rule` TEXT NOT NULL, `position` INTEGER NOT " +
    "NULL DEFAULT 0, `value` TEXT NOT NULL DEFAULT '', `media_id` TEXT, PRIMARY KEY(`album_id`, `rule`, `position`), " +
    "FOREIGN KEY(`album_id`) REFERENCES `album`(`album_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE " +
    "INITIALLY DEFERRED, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO ACTION ON DELETE " +
    "CASCADE DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_album_rule_media_id` ON `album_rule` (`media_id`)",
    "CREATE TABLE IF NOT EXISTS `album_exclusion` (`album_id` TEXT NOT NULL, `media_id` TEXT NOT NULL, `fp` TEXT, " +
    "PRIMARY KEY(`album_id`, `media_id`), FOREIGN KEY(`album_id`) REFERENCES `album`(`album_id`) ON UPDATE NO ACTION " +
    "ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) ON " +
    "UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_album_exclusion_media_id` ON `album_exclusion` (`media_id`)",
    "CREATE TABLE IF NOT EXISTS `album_suggestion` (`album_id` TEXT NOT NULL, `media_id` TEXT NOT NULL, `reason` " +
    "TEXT NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`album_id`, `media_id`), FOREIGN KEY(`album_id`) " +
    "REFERENCES `album`(`album_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, FOREIGN " +
    "KEY(`media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY " +
    "DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_album_suggestion_media_id` ON `album_suggestion` (`media_id`)",
    "CREATE TABLE IF NOT EXISTS `shared_album` (`album_id` TEXT NOT NULL, `state` TEXT NOT NULL, `level` TEXT NOT " +
    "NULL, `creator` TEXT NOT NULL, `heir` TEXT, `folder` TEXT, `after_close` TEXT, `updated_at_nas` INTEGER NOT " +
    "NULL, `closed_at_nas` INTEGER, PRIMARY KEY(`album_id`))",
    "CREATE TABLE IF NOT EXISTS `shared_album_entry` (`album_id` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
    "`entry_key` TEXT NOT NULL, `field` TEXT NOT NULL, `at` INTEGER NOT NULL, `account` TEXT NOT NULL, `content_key` " +
    "TEXT, `tombstone` INTEGER NOT NULL DEFAULT 0, `body` BLOB NOT NULL, PRIMARY KEY(`album_id`, `device_id`, " +
    "`entry_key`))",
    "CREATE INDEX IF NOT EXISTS `index_shared_album_entry_album_id_content_key` ON `shared_album_entry` (`album_id`, " +
    "`content_key`)",
    "CREATE INDEX IF NOT EXISTS `index_shared_album_entry_album_id_field` ON `shared_album_entry` (`album_id`, " +
    "`field`)",
    "CREATE TABLE IF NOT EXISTS `shared_album_head_mark` (`album_id` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
    "`version` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `registry_version` INTEGER NOT NULL, `created_at` " +
    "INTEGER NOT NULL, PRIMARY KEY(`album_id`, `device_id`))",
    "CREATE TABLE IF NOT EXISTS `album_share_op` (`op_id` TEXT NOT NULL, `album_id` TEXT NOT NULL, `kind` TEXT NOT " +
    "NULL, `plan_hash` TEXT NOT NULL, `title` TEXT NOT NULL, `level` TEXT, `nonce_hex` TEXT, `folder_share` TEXT, " +
    "`folder_path` TEXT, `hidden` TEXT NOT NULL, `finished` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT " +
    "NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`op_id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_album_share_op_plan_hash` ON `album_share_op` (`plan_hash`)",
    "CREATE INDEX IF NOT EXISTS `index_album_share_op_finished` ON `album_share_op` (`finished`)",
    "CREATE TABLE IF NOT EXISTS `album_share_item` (`op_id` TEXT NOT NULL, `item_index` INTEGER NOT NULL, `media_id` " +
    "TEXT NOT NULL, `action` TEXT NOT NULL, `state` TEXT NOT NULL, `mine` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
    "`content_key` TEXT, `source_share` TEXT, `source_path` TEXT, `dest_share` TEXT, `dest_path` TEXT, " +
    "`transfer_key` TEXT, `ticket` TEXT, `stage_seq` INTEGER, `safe_copy_requested` INTEGER NOT NULL DEFAULT 0, " +
    "`failure` TEXT, `wait` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`op_id`, `item_index`), FOREIGN " +
    "KEY(`op_id`) REFERENCES `album_share_op`(`op_id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY " +
    "DEFERRED, FOREIGN KEY(`media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO ACTION ON DELETE NO ACTION " +
    "DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_album_share_item_media_id` ON `album_share_item` (`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_album_share_item_transfer_key` ON `album_share_item` (`transfer_key`)",
    "CREATE INDEX IF NOT EXISTS `index_album_share_item_state` ON `album_share_item` (`state`)",
    "CREATE TABLE IF NOT EXISTS `trip_proposal` (`trip_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `from_day` INTEGER " +
    "NOT NULL, `to_day` INTEGER NOT NULL, `label` TEXT NOT NULL, `media_count` INTEGER NOT NULL, `cover_media_id` " +
    "TEXT, `place_ids` TEXT NOT NULL DEFAULT '', `album_id` TEXT, `detected_at` INTEGER NOT NULL, PRIMARY " +
    "KEY(`trip_id`), FOREIGN KEY(`cover_media_id`) REFERENCES `media`(`media_id`) ON UPDATE NO ACTION ON DELETE SET " +
    "NULL DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX IF NOT EXISTS `index_trip_proposal_cover_media_id` ON `trip_proposal` (`cover_media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_trip_proposal_to_day` ON `trip_proposal` (`to_day`)",
    "CREATE TABLE IF NOT EXISTS `home_estimate` (`slot` INTEGER NOT NULL, `kind` TEXT NOT NULL, `latitude` REAL NOT " +
    "NULL, `longitude` REAL NOT NULL, `radius_km` REAL NOT NULL, `days` INTEGER NOT NULL, `total_days` INTEGER NOT " +
    "NULL, `estimated_at` INTEGER NOT NULL, PRIMARY KEY(`slot`))",
  )

  // Gli indici in piu' di `media` (candidati per giorno, luogo e dispositivo) e di `media_location` (doppioni), dopo aver riempito i derivati.
  private val EXISTING_TABLE_INDICES = listOf(
    "CREATE INDEX IF NOT EXISTS `index_media_location_version_id_location_type` ON `media_location` (`version_id`, `location_type`)",
    "CREATE INDEX IF NOT EXISTS `index_media_local_day_sort_time_media_id` ON `media` (`local_day`, `sort_time`, " +
    "`media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_media_place_id_local_day_sort_time_media_id` ON `media` (`place_id`, " +
    "`local_day`, `sort_time`, `media_id`)",
    "CREATE INDEX IF NOT EXISTS `index_media_origin_device_local_day_sort_time_media_id` ON `media` " +
    "(`origin_device`, `local_day`, `sort_time`, `media_id`)",
  )
}
