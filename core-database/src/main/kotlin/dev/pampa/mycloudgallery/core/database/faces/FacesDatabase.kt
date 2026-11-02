package dev.pampa.mycloudgallery.core.database.faces

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FaceGroupingDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesMetaDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesSyncDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesUnitDao
import dev.pampa.mycloudgallery.core.database.faces.dao.PeopleRecordDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceBodyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceUnsupportedEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesChunkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesMetaEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesUnitDoneEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupAssignmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupExemplarEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupPersonEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupStateEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PeopleRecordEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishDiaryEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishKeyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishSegmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishStateEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaDeviceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaRevokedEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentStateEntity
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.File

/**
 * Il database dei volti di UNA generazione (Fase 8, P6; `docs/fase8-progetto.md` D14, D15; `docs/modello-dati.md` §15): un file a parte
 * (`faces-<G>.db`), con un suo schema esportato in `schemas/dev.pampa.mycloudgallery.core.database.faces.FacesDatabase/`: **v1** della Fase 8,
 * **v2** delle correzioni della Fase 8 (provenienza delle voci `people`, attese dei segmenti della replica, indice dei corpi per autore:
 * [FacesMigrations]), **v3** del pacchetto P12 della Fase 10 (tolte le tabelle ridondanti `faces_head_mark` e `faces_outbox`, promessa 15: [FacesMigrations]).
 * **Non** e' il database della galleria (`GalleryDatabase` resta v6, nessuna tabella condivisa): si cancella come un file e si verifica che
 * non ci sia piu' ("Disattiva e cancella", D29), e chi non attiva Persone non lo crea nemmeno (nessuna migrazione sul campo).
 *
 * Vettori, landmark e nomi sono colonne **sigillate** (AES-256-GCM, chiave dati della generazione): questo livello non li interpreta e non
 * ha la chiave. Lo usa solo `core-faces/.../store/` (`Phase8ArchitectureTest`).
 *
 * **Mai** `fallbackToDestructiveMigration`: una migrazione mancante deve far fallire l'apertura, non cancellare nomi che (con la
 * recinzione, D34) esistono solo qui. Un database di una versione futura (app tornata indietro) non si apre e non si cancella.
 */
@Database(
  entities = [
    FaceBodyEntity::class,
    FaceEntity::class,
    FaceUnsupportedEntity::class,
    GroupStateEntity::class,
    GroupPersonEntity::class,
    GroupExemplarEntity::class,
    GroupAssignmentEntity::class,
    PeopleRecordEntity::class,
    FacesUnitDoneEntity::class,
    FacesMetaEntity::class,
    ReplicaDeviceEntity::class,
    ReplicaSegmentEntity::class,
    ReplicaSegmentStateEntity::class,
    ReplicaRevokedEntity::class,
    FacesChunkEntity::class,
    PublishStateEntity::class,
    PublishKeyEntity::class,
    PublishSegmentEntity::class,
    PublishDiaryEntity::class,
  ],
  version = FacesDatabase.VERSION,
  exportSchema = true,
)
abstract class FacesDatabase : RoomDatabase() {

  abstract fun bodyDao(): FaceBodyDao

  abstract fun groupingDao(): FaceGroupingDao

  abstract fun peopleDao(): PeopleRecordDao

  abstract fun unitDao(): FacesUnitDao

  abstract fun metaDao(): FacesMetaDao

  abstract fun syncDao(): FacesSyncDao

  companion object {
    /** Versione dello schema. Alzarla richiede una migrazione vera (mai distruttiva, in [FacesMigrations]) e il suo schema esportato. */
    const val VERSION: Int = 3

    /**
     * Il database nel file [file] (le cartelle che mancano si creano). Il giornale e' WAL: accanto al file ci sono `-wal` e `-shm`, che la
     * cancellazione toglie insieme (`FacesDatabaseFiles`). Non lo apre: la prima query (o [openNow]) lo fa.
     */
    fun build(context: Context, file: File): FacesDatabase {
      val parent = file.absoluteFile.parentFile ?: throw IllegalArgumentException("Il database dei volti vuole una cartella.")
      parent.mkdirs()
      return Room.databaseBuilder(DirectoryContext(context, parent), FacesDatabase::class.java, file.name)
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(*FacesMigrations.ALL)
        .build()
    }
  }

  /** Apre davvero il file (crea lo schema o valida quello che c'e'): lancia se e' danneggiato o di una versione futura. */
  fun openNow() {
    openHelper.writableDatabase
  }
}

/**
 * Un contesto che mette i database in [directory] invece che in `databases/`: Room vuole un nome e lo cerca con `getDatabasePath`. Cosi' il
 * file dei volti sta dove lo decide chi lo costruisce (`noBackupFilesDir`, fuori da qualunque backup) e lo stesso codice gira sulla JVM di
 * Windows nei test (un percorso assoluto come nome non vi funziona).
 */
private class DirectoryContext(base: Context, private val directory: File) : ContextWrapper(base) {

  override fun getDatabasePath(name: String): File = File(directory, name)

  override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

  override fun openOrCreateDatabase(
    name: String,
    mode: Int,
    factory: SQLiteDatabase.CursorFactory?,
    errorHandler: DatabaseErrorHandler?,
  ): SQLiteDatabase =
    SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)

  override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
}

/** Come si apre un database dei volti da un file: i test ne passano uno loro, l'app quello di [FacesDatabases.forContext]. */
fun interface FacesDatabaseOpener {
  fun open(file: File): FacesDatabase
}

object FacesDatabases {
  /** L'apertura vera (Room, WAL) con il contesto dell'app. */
  fun forContext(context: Context): FacesDatabaseOpener = FacesDatabaseOpener { file -> FacesDatabase.build(context, file) }
}

/**
 * I nomi dei file del database dei volti: `faces-<G>.db` e i suoi tre compagni di SQLite (`-wal`, `-shm`, `-journal`). La **cancellazione**
 * li toglie tutti (D29 passo 6): un compagno rimasto potrebbe tenere pagine del database.
 */
object FacesDatabaseFiles {
  const val PREFIX: String = "faces-"
  const val SUFFIX: String = ".db"
  private val COMPANIONS = listOf("-wal", "-shm", "-journal")

  fun nameOf(generation: FaceGeneration): String = PREFIX + generation.base32 + SUFFIX

  fun fileOf(directory: File, generation: FaceGeneration): File = File(directory, nameOf(generation))

  /** I file di un database: il principale per primo, poi i compagni (esistano o no). */
  fun allOf(main: File): List<File> = listOf(main) + COMPANIONS.map { File(main.path + it) }

  /** La generazione di un nome di file del database o di un suo compagno, o null se il nome non e' dei volti. */
  fun generationOf(fileName: String): FaceGeneration? {
    val base = COMPANIONS.fold(fileName) { name, suffix -> name.removeSuffix(suffix) }
    if (!base.startsWith(PREFIX) || !base.endsWith(SUFFIX)) return null
    return FaceGeneration.parse(base.removePrefix(PREFIX).removeSuffix(SUFFIX))
  }

  /**
   * La versione dello schema scritta nel file ([FacesDatabase.VERSION] per un database di oggi), o null se il file non c'e' o non si
   * legge (danneggiato). Serve a non cancellare mai un database di una versione futura (un'app tornata indietro): si guarda **prima** di
   * aprirlo con Room.
   */
  fun versionOf(file: File): Int? {
    if (!file.isFile) return null
    return try {
      // Un gestore che non fa niente: quello di serie, trovando il file danneggiato, lo CANCELLA, e qui si sta solo guardando.
      SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { it.version }
    } catch (_: android.database.sqlite.SQLiteException) {
      null
    }
  }

  /** Le generazioni di cui [directory] ha un file (anche solo un compagno), in ordine. */
  fun generationsIn(directory: File): List<FaceGeneration> =
    directory.listFiles().orEmpty().mapNotNull { generationOf(it.name) }.distinctBy { it.base32 }.sortedBy { it.base32 }
}
