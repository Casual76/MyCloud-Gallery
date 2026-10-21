package dev.pampa.mycloudgallery.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 -> v5 (Fase 6, `docs/fase6-progetto.md` D23, D25, D32, D37; `docs/modello-dati.md` §13): **solo tabelle nuove**, tutte vuote.
 *
 * - l'indice locale dei dati AI per chiave di contenuto: `ai_vector` (le righe del file dei vettori), `ai_text` con la tabella FTS4
 *   `ai_text_fts` (contenuto esterno e i suoi quattro trigger, quelli che Room crea) e `ai_vocabulary`;
 * - la cronologia della ricerca `search_history`;
 * - le tabelle `ai_*` dell'indice condiviso che P2 si aspetta: la pubblicazione (`ai_pending_publish`, `ai_own_head`,
 *   `ai_own_segment`, `ai_own_live`) e la replica (`ai_replica_head`, `ai_replica_segment`, `ai_replica_entry`,
 *   `ai_replica_applied`, `ai_replica_dirty`).
 *
 * Mai distruttiva: non tocca nessuna riga ne' colonna esistente. Il bit `CellFlags.DOCUMENT` non va calcolato qui: non c'e' ancora
 * nessun testo letto, ogni dispositivo lo ricava dai dati dell'indice (`MediaCatalog.setDerivedFlags`). La tabella `embedding` della
 * v1 resta com'e' (vuota e superata, D23). Su API 29 (SQLite 3.22) niente `UPSERT`, `RETURNING`, `DROP COLUMN`; FTS4 c'e' sempre.
 */
internal object Migration4To5 : Migration(4, 5) {

  override fun migrate(db: SupportSQLiteDatabase) {
    CREATE.forEach(db::execSQL)
  }

  // Copiate da schemas/.../5.json (createSql di Room, con i nomi delle tabelle): la validazione dello schema dopo la migrazione le
  // confronta, e `ExportedSchemaTest` confronta le definizioni (compresi i trigger) con quelle che Room crea.
  private val CREATE = listOf(
    "CREATE TABLE IF NOT EXISTS `ai_vector` (`content_key` TEXT NOT NULL, `space` TEXT NOT NULL, `recipe` INTEGER NOT NULL, " +
      "`frame` INTEGER NOT NULL, `source` TEXT NOT NULL, `instant_ms` INTEGER, `byte_offset` INTEGER NOT NULL, " +
      "`byte_length` INTEGER NOT NULL, `crc` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
      "PRIMARY KEY(`content_key`, `space`, `recipe`, `frame`))",
    "CREATE INDEX IF NOT EXISTS `index_ai_vector_space_recipe` ON `ai_vector` (`space`, `recipe`)",
    "CREATE TABLE IF NOT EXISTS `ai_text` (`text_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `content_key` TEXT NOT NULL, " +
      "`recipe` INTEGER NOT NULL, `engine` TEXT NOT NULL, `source` TEXT NOT NULL, `text` TEXT NOT NULL, `lines` INTEGER NOT NULL, " +
      "`chars` INTEGER NOT NULL, `layout` BLOB, `created_at` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_text_content_key` ON `ai_text` (`content_key`)",
    "CREATE VIRTUAL TABLE IF NOT EXISTS `ai_text_fts` USING FTS4(`text` TEXT NOT NULL, content=`ai_text`)",
    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ai_text_fts_BEFORE_UPDATE BEFORE UPDATE ON `ai_text` BEGIN DELETE FROM " +
      "`ai_text_fts` WHERE `docid`=OLD.`rowid`; END",
    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ai_text_fts_BEFORE_DELETE BEFORE DELETE ON `ai_text` BEGIN DELETE FROM " +
      "`ai_text_fts` WHERE `docid`=OLD.`rowid`; END",
    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ai_text_fts_AFTER_UPDATE AFTER UPDATE ON `ai_text` BEGIN INSERT INTO " +
      "`ai_text_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ai_text_fts_AFTER_INSERT AFTER INSERT ON `ai_text` BEGIN INSERT INTO " +
      "`ai_text_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
    "CREATE TABLE IF NOT EXISTS `ai_vocabulary` (`word` TEXT NOT NULL, `docs` INTEGER NOT NULL, PRIMARY KEY(`word`))",
    "CREATE TABLE IF NOT EXISTS `search_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
      "`chips` TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_search_history_text` ON `search_history` (`text`)",
    "CREATE TABLE IF NOT EXISTS `ai_pending_publish` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `share` TEXT NOT NULL, " +
      "`state` TEXT NOT NULL, `cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, `recipe` INTEGER NOT NULL, " +
      "`body` BLOB NOT NULL, `digest` TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_ai_pending_publish_share_state_id` ON `ai_pending_publish` (`share`, `state`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_ai_pending_publish_share_cls_content_key` ON `ai_pending_publish` (`share`, `cls`, " +
      "`content_key`)",
    "CREATE TABLE IF NOT EXISTS `ai_own_head` (`share` TEXT NOT NULL, `version` INTEGER NOT NULL, `head_hash` TEXT NOT NULL, " +
      "`published_at` INTEGER NOT NULL, PRIMARY KEY(`share`))",
    "CREATE TABLE IF NOT EXISTS `ai_own_segment` (`share` TEXT NOT NULL, `hash` TEXT NOT NULL, `cls` INTEGER NOT NULL, " +
      "`size` INTEGER NOT NULL, `entries` INTEGER NOT NULL, PRIMARY KEY(`share`, `hash`))",
    "CREATE TABLE IF NOT EXISTS `ai_own_live` (`share` TEXT NOT NULL, `cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, " +
      "`recipe` INTEGER NOT NULL, `digest` TEXT NOT NULL, `segment` TEXT NOT NULL, PRIMARY KEY(`share`, `cls`, `content_key`))",
    "CREATE INDEX IF NOT EXISTS `index_ai_own_live_share_segment` ON `ai_own_live` (`share`, `segment`)",
    "CREATE TABLE IF NOT EXISTS `ai_replica_head` (`share` TEXT NOT NULL, `device` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
      "`content_hash` TEXT NOT NULL, `registry_version` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
      "PRIMARY KEY(`share`, `device`))",
    "CREATE TABLE IF NOT EXISTS `ai_replica_segment` (`share` TEXT NOT NULL, `device` TEXT NOT NULL, `hash` TEXT NOT NULL, " +
      "`cls` INTEGER NOT NULL, `size` INTEGER NOT NULL, `state` TEXT NOT NULL, `entries` INTEGER NOT NULL, " +
      "PRIMARY KEY(`share`, `device`, `hash`))",
    "CREATE TABLE IF NOT EXISTS `ai_replica_entry` (`share` TEXT NOT NULL, `device` TEXT NOT NULL, `segment` TEXT NOT NULL, " +
      "`cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, `recipe` INTEGER NOT NULL, `digest` TEXT NOT NULL, " +
      "`body` BLOB NOT NULL, PRIMARY KEY(`share`, `device`, `segment`, `cls`, `content_key`))",
    "CREATE INDEX IF NOT EXISTS `index_ai_replica_entry_share_cls_content_key` ON `ai_replica_entry` (`share`, `cls`, " +
      "`content_key`)",
    "CREATE TABLE IF NOT EXISTS `ai_replica_applied` (`share` TEXT NOT NULL, `cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, " +
      "`device` TEXT NOT NULL, `recipe` INTEGER NOT NULL, `digest` TEXT NOT NULL, PRIMARY KEY(`share`, `cls`, `content_key`))",
    "CREATE TABLE IF NOT EXISTS `ai_replica_dirty` (`share` TEXT NOT NULL, `cls` INTEGER NOT NULL, `content_key` TEXT NOT NULL, " +
      "PRIMARY KEY(`share`, `cls`, `content_key`))",
  )
}
