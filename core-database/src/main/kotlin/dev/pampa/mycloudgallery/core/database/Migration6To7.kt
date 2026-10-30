package dev.pampa.mycloudgallery.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 -> v7 (Fase 8, correzioni dopo le revisioni, pacchetto D, R2): la cronologia delle ricerche ricorda **le persone** dei chip "Persona" da cui ogni ricerca
 * e' nata. `search_history.persons`: gli id delle persone (UUID canonici separati da una virgola; vuota per le ricerche senza persone). Servono perche'
 * "Rinomina", "Dimentica", "Nascondi persona" e "Disattiva e cancella" tolgano la ricerca anche quando il nome di prima non si ritrova piu'.
 *
 * Mai distruttiva: una colonna in piu' con il suo DEFAULT. Le ricerche che c'erano restano com'erano, senza persone (di quelle il nome si riconosce ancora dal
 * testo e dalle etichette, come prima). Su API 29 (SQLite 3.22) niente `DROP COLUMN`.
 */
internal object Migration6To7 : Migration(6, 7) {

  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `search_history` ADD COLUMN `persons` TEXT NOT NULL DEFAULT ''")
  }
}
