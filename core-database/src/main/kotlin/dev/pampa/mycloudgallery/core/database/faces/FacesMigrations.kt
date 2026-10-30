package dev.pampa.mycloudgallery.core.database.faces

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Le migrazioni del database dei volti (mai distruttive: `FacesDatabase` non usa `fallbackToDestructiveMigration`). Per alzare la versione:
 * scrivere qui `Migration(N-1, N)`, aggiungerla ad [ALL], esportare lo schema N e provarla in `FacesSchemaTest` (database creato dallo schema
 * N-1 con dati, migrato e aperto con il codice di oggi).
 */
object FacesMigrations {

  val ALL: Array<Migration> get() = arrayOf(MIGRATION_1_2)

  /**
   * v1 -> v2 (correzioni della Fase 8, pacchetto "formato e replica dei volti"):
   * - R3: `people_record.via`, la provenienza di una voce (il firmatario del segmento che l'ha portata). Le righe di prima valgono come
   *   arrivate dal loro autore (`via` = `author`): con la recinzione D34 accesa esistono solo sulle share di prova;
   * - R5, R7: `replica_segment_state.attempts` e `retry_at`, le attese di un segmento assente, illeggibile o sbagliato (nessuna riga ne ha);
   * - R6: l'indice di `face_body` per autore (i tetti per dispositivo contano i suoi corpi a ogni giro).
   */
  val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE `people_record` ADD COLUMN `via` TEXT")
      db.execSQL("UPDATE `people_record` SET `via` = `author`")
      db.execSQL("ALTER TABLE `replica_segment_state` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE `replica_segment_state` ADD COLUMN `retry_at` INTEGER")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_body_author` ON `face_body` (`author`)")
    }
  }
}
