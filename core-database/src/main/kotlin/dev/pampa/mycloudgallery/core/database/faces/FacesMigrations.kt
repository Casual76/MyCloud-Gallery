package dev.pampa.mycloudgallery.core.database.faces

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Le migrazioni del database dei volti (mai distruttive: `FacesDatabase` non usa `fallbackToDestructiveMigration`). Per alzare la versione:
 * scrivere qui `Migration(N-1, N)`, aggiungerla ad [ALL], esportare lo schema N e provarla in `FacesSchemaTest` (database creato dallo schema
 * N-1 con dati, migrato e aperto con il codice di oggi).
 */
object FacesMigrations {

  val ALL: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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

  /**
   * v2 -> v3 (Fase 10, P12, promessa 15 di `docs/fase10-progetto.md`): si tolgono `faces_head_mark` e `faces_outbox`, le due tabelle della P6
   * originale che nessun codice di produzione ha mai letto (l'anti-rollback delle teste e l'outbox vivono nello stato di P3: `replica_device.mark_*`,
   * le chiavi e il diario del pubblicatore). Niente altro cambia: le altre tabelle, con i loro dati, restano come sono (la migrazione NON e'
   * distruttiva per altro: `FacesSchemaTest` la prova con un database v2 pieno). Le due tabelle erano comunque vuote sul campo: le scriveva solo
   * codice che nessuno chiamava.
   */
  val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("DROP TABLE IF EXISTS `faces_head_mark`")
      db.execSQL("DROP TABLE IF EXISTS `faces_outbox`")
    }
  }
}
