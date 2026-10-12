package dev.pampa.mycloudgallery.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Segnaposto della Fase 1.1: serve a dimostrare che Room + KSP compilano e girano con la toolchain
 * dell'engine (AGP, Kotlin, KSP). Lo schema vero (media con identita' stabile, posizioni, versioni,
 * impronte) lo scrive la 1.3, che sostituisce queste tre classi e accende `exportSchema`.
 */
@Entity(tableName = "segnaposto")
data class SegnapostoEntity(
  @PrimaryKey val id: String,
)

@Dao
interface SegnapostoDao {

  @Insert
  suspend fun inserisci(entita: SegnapostoEntity)

  @Query("SELECT COUNT(*) FROM segnaposto")
  suspend fun conta(): Int
}

@Database(entities = [SegnapostoEntity::class], version = 1, exportSchema = false)
abstract class GalleryDatabase : RoomDatabase() {

  abstract fun segnapostoDao(): SegnapostoDao

  companion object {
    /** Nome del file del database nella cartella privata dell'app. */
    const val NAME: String = "gallery.db"
  }
}
