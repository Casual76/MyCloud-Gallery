package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import dev.pampa.mycloudgallery.core.database.dao.AlbumDao
import dev.pampa.mycloudgallery.core.database.dao.EmbeddingDao
import dev.pampa.mycloudgallery.core.database.dao.LocationDao
import dev.pampa.mycloudgallery.core.database.dao.MediaDao
import dev.pampa.mycloudgallery.core.database.dao.MergeDao
import dev.pampa.mycloudgallery.core.database.dao.NasShareDao
import dev.pampa.mycloudgallery.core.database.dao.PersonalDao
import dev.pampa.mycloudgallery.core.database.dao.TimelineDao
import dev.pampa.mycloudgallery.core.database.dao.VersionDao
import dev.pampa.mycloudgallery.core.database.dao.WorkStateDao
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGroupEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasShareEntity
import dev.pampa.mycloudgallery.core.database.entity.VersionMetadataEntity
import dev.pampa.mycloudgallery.core.database.entity.WorkStateEntity

/**
 * La replica locale dell'indice (piano §0: ricerca istantanea e offline, avvio senza aspettare il NAS).
 *
 * Lo schema di ogni versione e' esportato in `core-database/schemas/` (sotto controllo di versione) e
 * `ExportedSchemaTest` verifica che coincida con le entita' e che ogni versione esportata si apra e
 * migri fino all'ultima senza perdere dati. Il modello e le sue scelte: `docs/modello-dati.md`.
 */
@Database(
  entities = [
    MediaEntity::class,
    MediaVersionEntity::class,
    MediaLocationEntity::class,
    VersionMetadataEntity::class,
    NasShareEntity::class,
    MediaGroupEntity::class,
    MediaAliasEntity::class,
    WorkStateEntity::class,
    EmbeddingEntity::class,
    MediaPersonalEntity::class,
    AlbumEntity::class,
    AlbumItemEntity::class,
  ],
  version = GalleryDatabase.VERSION,
  exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {

  abstract fun mediaDao(): MediaDao

  abstract fun versionDao(): VersionDao

  abstract fun locationDao(): LocationDao

  abstract fun nasShareDao(): NasShareDao

  abstract fun timelineDao(): TimelineDao

  abstract fun workStateDao(): WorkStateDao

  abstract fun embeddingDao(): EmbeddingDao

  abstract fun personalDao(): PersonalDao

  abstract fun albumDao(): AlbumDao

  abstract fun mergeDao(): MergeDao

  companion object {
    /** Nome del file del database nella cartella privata dell'app. */
    const val NAME: String = "gallery.db"

    /** Versione dello schema. Alzarla richiede una migrazione in [GalleryMigrations] (mai distruttiva). */
    const val VERSION: Int = 1

    /**
     * Il database con tutte le migrazioni. **Mai** `fallbackToDestructiveMigration`: una migrazione
     * mancante deve far fallire l'avvio (e i test), non cancellare album, preferiti e stati di lavoro.
     *
     * [name] `null` = database in memoria (test).
     */
    fun build(context: Context, name: String? = NAME): GalleryDatabase {
      val builder = if (name == null) {
        Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
      } else {
        Room.databaseBuilder(context, GalleryDatabase::class.java, name)
      }
      return builder.addMigrations(*GalleryMigrations.ALL).build()
    }
  }
}

/**
 * Le migrazioni dello schema, dalla v1 in poi. Per aggiungerne una (vedi `docs/modello-dati.md`, §7):
 *
 * 1. alzare [GalleryDatabase.VERSION] e cambiare le entita' (il build esporta `schemas/.../N.json`);
 * 2. scrivere qui `Migration(N-1, N)` e aggiungerla ad [ALL];
 * 3. in `ExportedSchemaTest` aggiungere i dati di prova della versione N-1 (`SchemaSeeds`): il test
 *    apre ogni schema esportato, ci mette quei dati, migra fino all'ultima versione, lascia a Room la
 *    validazione dello schema risultante e controlla che i dati ci siano ancora.
 */
object GalleryMigrations {
  val ALL: Array<Migration> = emptyArray()
}
