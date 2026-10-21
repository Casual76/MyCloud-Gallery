package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.dao.AiIndexDao
import dev.pampa.mycloudgallery.core.database.dao.AiPublishDao
import dev.pampa.mycloudgallery.core.database.dao.AiReplicaDao
import dev.pampa.mycloudgallery.core.database.dao.AlbumDao
import dev.pampa.mycloudgallery.core.database.dao.BackupDiaryDao
import dev.pampa.mycloudgallery.core.database.dao.CellContentDao
import dev.pampa.mycloudgallery.core.database.dao.BackupProblemDao
import dev.pampa.mycloudgallery.core.database.dao.BackupQueryDao
import dev.pampa.mycloudgallery.core.database.dao.CatalogMetaDao
import dev.pampa.mycloudgallery.core.database.dao.EditSessionDao
import dev.pampa.mycloudgallery.core.database.dao.EmbeddingDao
import dev.pampa.mycloudgallery.core.database.dao.FreeSpaceDao
import dev.pampa.mycloudgallery.core.database.dao.IncomingDao
import dev.pampa.mycloudgallery.core.database.dao.IdentityDao
import dev.pampa.mycloudgallery.core.database.dao.IndexPublishDao
import dev.pampa.mycloudgallery.core.database.dao.LocationDao
import dev.pampa.mycloudgallery.core.database.dao.MediaDao
import dev.pampa.mycloudgallery.core.database.dao.MediaStoreCursorDao
import dev.pampa.mycloudgallery.core.database.dao.MergeDao
import dev.pampa.mycloudgallery.core.database.dao.MetadataCorrectionDao
import dev.pampa.mycloudgallery.core.database.dao.NasCopyMismatchDao
import dev.pampa.mycloudgallery.core.database.dao.NasOpsDao
import dev.pampa.mycloudgallery.core.database.dao.NasScanDao
import dev.pampa.mycloudgallery.core.database.dao.NasShareDao
import dev.pampa.mycloudgallery.core.database.dao.PendingTaskDao
import dev.pampa.mycloudgallery.core.database.dao.SearchHistoryDao
import dev.pampa.mycloudgallery.core.database.dao.PersonalDao
import dev.pampa.mycloudgallery.core.database.dao.ReplicaDao
import dev.pampa.mycloudgallery.core.database.dao.SimilarDao
import dev.pampa.mycloudgallery.core.database.dao.TimelineDao
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.dao.VersionDao
import dev.pampa.mycloudgallery.core.database.dao.VisualSignatureDao
import dev.pampa.mycloudgallery.core.database.dao.WorkStateDao
import dev.pampa.mycloudgallery.core.database.entity.AiOwnHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnLiveEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.AiPendingPublishEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaAppliedEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.AiTextEntity
import dev.pampa.mycloudgallery.core.database.entity.AiTextFtsEntity
import dev.pampa.mycloudgallery.core.database.entity.AiVectorEntity
import dev.pampa.mycloudgallery.core.database.entity.AiVocabularyEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.database.entity.BackupProblemEntity
import dev.pampa.mycloudgallery.core.database.entity.CatalogMetaEntity
import dev.pampa.mycloudgallery.core.database.entity.EditSessionEntity
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceItemEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceRunEntity
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.database.entity.MetadataCorrectionEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexPublishStateEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGroupEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaStoreCursorEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasAdoptedFingerprintEntity
import dev.pampa.mycloudgallery.core.database.entity.NasCopyMismatchEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpItemEntity
import dev.pampa.mycloudgallery.core.database.entity.NasScanStateEntity
import dev.pampa.mycloudgallery.core.database.entity.NasShareEntity
import dev.pampa.mycloudgallery.core.database.entity.NasTrashEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.PendingTaskEntity
import dev.pampa.mycloudgallery.core.database.entity.SearchHistoryEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaPackEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaProvisionalEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaSettingEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaShareAccessEntity
import dev.pampa.mycloudgallery.core.database.entity.VersionMetadataEntity
import dev.pampa.mycloudgallery.core.database.entity.VisualSignatureEntity
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
    // v2
    MediaStoreCursorEntity::class,
    CatalogMetaEntity::class,
    // v3 (Fase 4): scansione del NAS, indice condiviso (outbox e replica), diario delle operazioni sul NAS
    NasScanStateEntity::class,
    IndexPublishStateEntity::class,
    IndexOwnSegmentEntity::class,
    IndexOutboxRecordEntity::class,
    IndexOutboxBlobEntity::class,
    IndexOwnRecordEntity::class,
    IndexOwnBlobEntity::class,
    IndexHeadEntity::class,
    IndexSegmentEntity::class,
    IndexRecordEntity::class,
    ReplicaDirtyEntity::class,
    ReplicaPackEntity::class,
    ReplicaBlobEntity::class,
    ReplicaShareAccessEntity::class,
    ReplicaSettingEntity::class,
    ReplicaProvisionalEntity::class,
    NasOpEntity::class,
    NasOpItemEntity::class,
    NasTrashEntryEntity::class,
    // v3, revisione 4A (R6): impronte adottate dall'indice, non lette qui
    NasAdoptedFingerprintEntity::class,
    // v4 (Fase 5): il diario del backup, il problema di un media e la firma visiva delle copie simili (5A); le tabelle della 5B
    // nascono gia' qui, vuote (D27)
    BackupItemEntity::class,
    BackupProblemEntity::class,
    NasCopyMismatchEntity::class,
    VisualSignatureEntity::class,
    PendingTaskEntity::class,
    EditSessionEntity::class,
    FreeSpaceRunEntity::class,
    FreeSpaceItemEntity::class,
    IncomingItemEntity::class,
    // v4, P14 (prima del rilascio della v4): le correzioni solo nell'app (Q6 (a))
    MetadataCorrectionEntity::class,
    // v5 (Fase 6, D37): l'indice locale dei dati AI per chiave di contenuto (vettori, testo con FTS4, vocabolario), la cronologia della
    // ricerca e le tabelle `ai_*` dell'indice condiviso (pubblicazione e replica della testa `ai`). Nessuna punta a `media`.
    AiVectorEntity::class,
    AiTextEntity::class,
    AiTextFtsEntity::class,
    AiVocabularyEntity::class,
    SearchHistoryEntity::class,
    AiPendingPublishEntity::class,
    AiOwnHeadEntity::class,
    AiOwnSegmentEntity::class,
    AiOwnLiveEntity::class,
    AiReplicaHeadEntity::class,
    AiReplicaSegmentEntity::class,
    AiReplicaEntryEntity::class,
    AiReplicaAppliedEntity::class,
    AiReplicaDirtyEntity::class,
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

  abstract fun timelineIndexDao(): TimelineIndexDao

  abstract fun catalogMetaDao(): CatalogMetaDao

  abstract fun mediaStoreCursorDao(): MediaStoreCursorDao

  // --- v3 (Fase 4) ---

  abstract fun nasScanDao(): NasScanDao

  abstract fun indexPublishDao(): IndexPublishDao

  abstract fun replicaDao(): ReplicaDao

  abstract fun nasOpsDao(): NasOpsDao

  abstract fun identityDao(): IdentityDao

  // --- v4 (Fase 5) ---

  abstract fun backupDiaryDao(): BackupDiaryDao

  abstract fun backupProblemDao(): BackupProblemDao

  abstract fun nasCopyMismatchDao(): NasCopyMismatchDao

  abstract fun visualSignatureDao(): VisualSignatureDao

  abstract fun backupQueryDao(): BackupQueryDao

  abstract fun similarDao(): SimilarDao

  abstract fun pendingTaskDao(): PendingTaskDao

  abstract fun editSessionDao(): EditSessionDao

  abstract fun freeSpaceDao(): FreeSpaceDao

  abstract fun incomingDao(): IncomingDao

  abstract fun metadataCorrectionDao(): MetadataCorrectionDao

  // --- v5 (Fase 6) ---

  abstract fun cellContentDao(): CellContentDao

  abstract fun aiIndexDao(): AiIndexDao

  abstract fun searchHistoryDao(): SearchHistoryDao

  abstract fun aiPublishDao(): AiPublishDao

  abstract fun aiReplicaDao(): AiReplicaDao

  companion object {
    /** Nome del file del database nella cartella privata dell'app. */
    const val NAME: String = "gallery.db"

    /** Versione dello schema. Alzarla richiede una migrazione in [GalleryMigrations] (mai distruttiva). */
    const val VERSION: Int = 5

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
 * Una transazione sul database della galleria per chi non vede `room-ktx` (gli adattatori Room delle porte di core-index,
 * P11): [block] gira tutto o niente. **Non** per le scritture del grafo dei media, che passano sempre da `MediaCatalog`
 * (le sue transazioni annunciano i cambiamenti dopo il commit).
 */
suspend fun <T> GalleryDatabase.transaction(block: suspend () -> T): T = withTransaction(block)
