package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import dev.pampa.mycloudgallery.core.database.entity.BackupCandidateRow
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.database.entity.BackupProblemEntity
import dev.pampa.mycloudgallery.core.database.entity.CodeCountRow
import dev.pampa.mycloudgallery.core.database.entity.EditSessionEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceItemEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceRunEntity
import dev.pampa.mycloudgallery.core.database.entity.GroupIdRow
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.database.entity.IncomingQueueRow
import dev.pampa.mycloudgallery.core.database.entity.MetadataCorrectionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasCopyMismatchEntity
import dev.pampa.mycloudgallery.core.database.entity.NasCopyRow
import dev.pampa.mycloudgallery.core.database.entity.PendingTaskEntity
import dev.pampa.mycloudgallery.core.database.entity.PhoneMediaRow
import dev.pampa.mycloudgallery.core.database.entity.PhonePlaceRow
import dev.pampa.mycloudgallery.core.database.entity.SimilarCandidateRow
import dev.pampa.mycloudgallery.core.database.entity.StatusCount
import dev.pampa.mycloudgallery.core.database.entity.VisualSignatureEntity
import kotlinx.coroutines.flow.Flow

// I DAO dello schema v4 (Fase 5, docs/modello-dati.md §12). Semplici di proposito: righe dentro e fuori, nessuna regola. Le regole
// stanno in chi li usa (P2 il diario, P3 le verifiche, P8-P14 la 5B); il grafo dei media, "al sicuro" e i gruppi li scrive sempre e
// solo `MediaCatalog`. Le query che scorrono tante righe hanno il testo in una costante pubblica o in `BackupQueries`: i test ne
// guardano il piano (`EXPLAIN QUERY PLAN`: indice usato, nessun `USE TEMP B-TREE`).

/**
 * Il diario del backup (`backup_item`): righe dentro e fuori, sul modello di `NasOpsDao`. **Ogni passo si scrive prima di farlo**
 * (D3): chi esegue un passo chiama [update] e solo dopo il NAS. [insert] e [update] tengono coerente `is_open` con lo stato; le
 * versioni "grezze" non vanno chiamate da fuori.
 */
@Dao
interface BackupDiaryDao {

  @Insert
  suspend fun insertRaw(item: BackupItemEntity): Long

  @Update
  suspend fun updateRaw(item: BackupItemEntity)

  /** Un elemento nuovo (`PLANNED`): numerato da SQLite, `is_open` secondo lo stato. */
  suspend fun insert(item: BackupItemEntity): Long = insertRaw(item.copy(isOpen = BackupItemEntity.isOpenState(item.state)))

  /** Riscrive un elemento (il passo si scrive **prima** di farlo): `is_open` secondo lo stato. */
  suspend fun update(item: BackupItemEntity) = updateRaw(item.copy(isOpen = BackupItemEntity.isOpenState(item.state)))

  @Query("SELECT * FROM backup_item WHERE item_id = :itemId")
  suspend fun get(itemId: Long): BackupItemEntity?

  /** Gli elementi da riprendere (aperti), dal piu' vecchio: dall'indice (`is_open`, `item_id`), senza scorrere i terminali. */
  @Query(ITEMS_TO_RESUME_SQL)
  suspend fun itemsToResume(limit: Int): List<BackupItemEntity>

  /** Gli elementi aperti di una versione (idempotenza di `create`, `openFor`): al massimo uno per tipo. */
  @Query(OPEN_FOR_VERSION_SQL)
  suspend fun openForVersion(versionId: String): List<BackupItemEntity>

  /** Gli elementi di un media, terminali compresi, dal piu' vecchio (riga "Backup" del viewer). */
  @Query("SELECT * FROM backup_item WHERE media_id = :mediaId ORDER BY item_id")
  suspend fun forMedia(mediaId: String): List<BackupItemEntity>

  /** D33: i temporanei citati dagli elementi non finiti (la pulizia dei propri file non li tocca). */
  @Query(CITED_TEMPS_SQL)
  suspend fun citedTemps(): List<String>

  /** Quanti elementi per stato (pagina del backup, Diagnostica): legge solo l'indice di `state`. */
  @Query(COUNTS_BY_STATE_SQL)
  suspend fun countsByState(): List<StatusCount>

  /** I byte degli elementi aperti. */
  @Query("SELECT COALESCE(SUM(size), 0) FROM backup_item WHERE is_open = 1")
  suspend fun openBytes(): Long

  /**
   * 5B (P14): gli elementi di questi tipi in una share, terminali compresi, dal piu' vecchio (la franchigia del congelamento legge tutte le
   * versioni e gli scambi della share, D32). La share si confronta senza maiuscole, come Samba.
   */
  @Query("SELECT * FROM backup_item WHERE kind IN (:kinds) AND share = :share COLLATE NOCASE ORDER BY item_id")
  suspend fun ofKindsInShare(kinds: List<String>, share: String): List<BackupItemEntity>

  /** 5B (P14): gli elementi aperti di un tipo, dal piu' vecchio (i lavori delle versioni, gli arrivi). */
  @Query("SELECT * FROM backup_item WHERE kind = :kind AND is_open = 1 ORDER BY item_id LIMIT :limit")
  suspend fun openOfKind(kind: String, limit: Int): List<BackupItemEntity>

  companion object {
    const val ITEMS_TO_RESUME_SQL: String = "SELECT * FROM backup_item WHERE is_open = 1 ORDER BY item_id LIMIT :limit"

    const val OPEN_FOR_VERSION_SQL: String = "SELECT * FROM backup_item WHERE version_id = :versionId AND is_open = 1 ORDER BY item_id"

    const val CITED_TEMPS_SQL: String = "SELECT temp_path FROM backup_item WHERE is_open = 1 AND temp_path IS NOT NULL"

    const val COUNTS_BY_STATE_SQL: String = "SELECT state AS status, COUNT(*) AS count FROM backup_item GROUP BY state"
  }
}

/** Il problema del backup di un media (`backup_problem`); le scritture passano da `MediaCatalog.markBackupProblem`. */
@Dao
interface BackupProblemDao {

  @Query("SELECT * FROM backup_problem WHERE media_id = :mediaId")
  suspend fun get(mediaId: String): BackupProblemEntity?

  @Upsert
  suspend fun upsert(row: BackupProblemEntity)

  @Query("DELETE FROM backup_problem WHERE media_id = :mediaId")
  suspend fun delete(mediaId: String)

  @Query("SELECT COUNT(*) FROM backup_problem")
  suspend fun count(): Int

  /**
   * I media con il problema [code] che hanno una delle posizioni [locationIds] nello stato [state] (al massimo
   * [TimelineIndexDao.MAX_IDS] posizioni per chiamata). Serve a una regola sola, `MediaCatalog`: la foto "persa prima del backup"
   * che la lettura del telefono rivede presente non e' piu' persa. Si parte dalle posizioni (chiave primaria) e si arriva al
   * problema per chiave primaria: un lotto della lettura costa una manciata di ricerche, anche con la tabella vuota. Un media con
   * due posizioni nell'elenco torna due volte (niente `DISTINCT`: eviterebbe un `USE TEMP B-TREE`, chi chiama deduplica).
   */
  @Query(WITH_CODE_AT_LOCATIONS_SQL)
  suspend fun mediaIdsWithCodeAt(code: String, state: String, locationIds: List<Long>): List<String>

  companion object {
    const val WITH_CODE_AT_LOCATIONS_SQL: String =
      "SELECT p.media_id FROM media_location l " +
        "CROSS JOIN media_version v ON v.version_id = l.version_id " +
        "CROSS JOIN backup_problem p ON p.media_id = v.media_id " +
        "WHERE l.location_id IN (:locationIds) AND l.state = :state AND p.code = :code"
  }
}

/** Le copie del NAS la cui rilettura non e' tornata (`nas_copy_mismatch`); le scritture passano da `MediaCatalog.markVerified`. */
@Dao
interface NasCopyMismatchDao {

  @Upsert
  suspend fun upsert(row: NasCopyMismatchEntity)

  @Query("DELETE FROM nas_copy_mismatch WHERE location_id = :locationId")
  suspend fun delete(locationId: Long)

  @Query("SELECT * FROM nas_copy_mismatch WHERE location_id = :locationId")
  suspend fun get(locationId: Long): NasCopyMismatchEntity?

  @Query("SELECT COUNT(*) FROM nas_copy_mismatch")
  suspend fun count(): Int
}

/** Le firme visive delle copie simili (`visual_signature`); le scritture passano da `MediaCatalog.saveSignature`. */
@Dao
interface VisualSignatureDao {

  @Upsert
  suspend fun upsert(row: VisualSignatureEntity)

  @Query("SELECT * FROM visual_signature WHERE version_id = :versionId")
  suspend fun get(versionId: String): VisualSignatureEntity?

  @Query("SELECT * FROM visual_signature WHERE version_id IN (:versionIds)")
  suspend fun forVersions(versionIds: List<String>): List<VisualSignatureEntity>

  @Query("SELECT COUNT(*) FROM visual_signature")
  suspend fun count(): Int
}

/**
 * Le query che costruiscono il testo al volo ([BackupQueries]) e quelle sulle copie simili. I risultati sono righe del catalogo
 * (`BackupRows.kt`): le traduce `MediaCatalog`.
 */
@Dao
interface BackupQueryDao {

  @RawQuery
  suspend fun candidates(query: SupportSQLiteQuery): List<BackupCandidateRow>

  @RawQuery
  suspend fun nasCopies(query: SupportSQLiteQuery): List<NasCopyRow>

  @RawQuery
  suspend fun statusCounts(query: SupportSQLiteQuery): List<StatusCount>

  @RawQuery
  suspend fun codeCounts(query: SupportSQLiteQuery): List<CodeCountRow>

  /** Una somma (una colonna, una riga): i byte in coda del backup ([BackupQueries.queuedBytes]). */
  @RawQuery
  suspend fun total(query: SupportSQLiteQuery): Long

  /** Le share con area privata dell'account (di norma una: `LocalDeviceProfile.privateShare`). */
  /**
   * P14 (P9, `FreeSpaceCatalog.phoneMedia`): i media con una copia **presente** sul telefono (presenza derivata `phone_only` o
   * `phone_and_nas`), dal piu' recente (`sort_time`, poi id, decrescenti), dopo il cursore: dall'indice (`sort_time`, `media_id`) della
   * timeline, nessun ordinamento in memoria. Tutti i media, propri e no: le regole di "Libera spazio" decidono.
   */
  @Query(
    "SELECT media_id, sort_time, local_day FROM media WHERE presence IN ('phone_only', 'phone_and_nas') " +
      "AND (sort_time < :sortTime OR (sort_time = :sortTime AND media_id < :mediaId)) ORDER BY sort_time DESC, media_id DESC LIMIT :limit",
  )
  suspend fun phoneMediaAfter(sortTime: Long, mediaId: String, limit: Int): List<PhoneMediaRow>

  /** P14: i media con piu' di una versione (dall'indice di `media_version.media_id`). */
  @Query("SELECT media_id FROM media_version GROUP BY media_id HAVING COUNT(*) > 1 ORDER BY media_id LIMIT :limit")
  suspend fun mediaWithVersions(limit: Int): List<String>

  /** La prima pagina di [phoneMediaAfter]. */
  @Query(
    "SELECT media_id, sort_time, local_day FROM media WHERE presence IN ('phone_only', 'phone_and_nas') " +
      "ORDER BY sort_time DESC, media_id DESC LIMIT :limit",
  )
  suspend fun phoneMediaFirst(limit: Int): List<PhoneMediaRow>

  @Query("SELECT share_key FROM nas_share WHERE account_id = :account AND area = 'private' ORDER BY share_key")
  suspend fun privateShareKeys(account: String): List<String>

  /** La riga del telefono di una versione, valida per la versione attuale del MediaStore del volume (E-P22), la piu' vecchia. */
  @Query(PHONE_PLACE_SQL)
  suspend fun phonePlace(versionId: String): List<PhonePlaceRow>

  /** L'ultima verifica di una copia privata presente delle versioni di un media (riga "Backup" del viewer), null se mai verificate. */
  @Query(
    "SELECT MAX(l.verified_at) FROM media_version v JOIN media_location l ON l.version_id = v.version_id " +
      "JOIN nas_share s ON s.account_id = l.account_id AND s.share_key = l.share_key " +
      "WHERE v.media_id = :mediaId AND l.location_type = 'nas' AND l.state = 'present' AND l.verified_at IS NOT NULL " +
      "AND l.account_id = :account AND s.area = 'private'",
  )
  suspend fun lastPrivateVerification(mediaId: String, account: String): Long?

  companion object {
    const val PHONE_PLACE_SQL: String =
      "SELECT l.volume AS volume, l.media_store_id AS media_store_id, l.media_store_version AS media_store_version " +
        "FROM media_location l JOIN media_store_cursor c ON c.volume = l.volume AND c.ms_version = l.media_store_version " +
        "WHERE l.version_id = :versionId AND l.location_type = 'phone' AND l.state = 'present' AND l.media_store_id IS NOT NULL " +
        "ORDER BY l.location_id LIMIT 1"
  }
}

/**
 * Le copie simili (D36, §2.12): i candidati per `sort_time` (indice `sort_time`, `media_id`), mai ordinando in memoria. Il testo
 * delle query e' in costanti pubbliche per i test di piano.
 */
@Dao
interface SimilarDao {

  /**
   * Una pagina di candidati: i media di [fromMs] in poi (millisecondi, di norma un secondo intero) che hanno almeno un altro
   * candidato dello stesso tipo nello stesso secondo di scatto. In ordine di `sort_time`, poi di identita'.
   */
  @Query(RUNS_SQL)
  suspend fun runRows(fromMs: Long, limit: Int): List<SimilarCandidateRow>

  /** Tutti i candidati di un secondo ([fromMs] incluso, [toMs] escluso), per completare una pagina o rileggere un secondo. */
  @Query(SECOND_SQL)
  suspend fun secondRows(fromMs: Long, toMs: Long): List<SimilarCandidateRow>

  /** Quanti candidati con almeno un compagno non hanno ancora la firma dell'algoritmo [algorithm]. */
  @Query(UNSIGNED_COUNT_SQL)
  suspend fun unsignedCount(algorithm: Int): Int

  /** Gli identificativi dei gruppi di copie simili, in ordine, dopo [after] ("" per cominciare). */
  @Query("SELECT group_id FROM media_group WHERE kind = 'similar_copies' AND group_id > :after ORDER BY group_id LIMIT :limit")
  suspend fun groupIds(after: String, limit: Int): List<GroupIdRow>

  companion object {
    private const val COLUMNS: String =
      "m.media_id AS media_id, m.current_version_id AS version_id, m.kind AS kind, m.sort_time AS sort_time, " +
        "m.aspect_milli AS aspect_milli, m.duration_ms AS duration_ms, m.group_id AS group_id, g.kind AS group_kind, " +
        "s.algorithm AS signature_algorithm"

    private const val FROM: String =
      "FROM media m JOIN version_metadata md ON md.version_id = m.current_version_id " +
        "LEFT JOIN media_group g ON g.group_id = m.group_id LEFT JOIN visual_signature s ON s.version_id = m.current_version_id"

    /**
     * Candidato: visibile da qualche parte, ora di scatto **letta dai metadati** (EXIF o contenitore: mai il nome o la data del
     * file, mai una stima) e non in una raffica, in una coppia RAW+JPEG o in un `PROBABLE_DUPLICATE` (§2.12 condizioni 2 e 5).
     */
    private const val ELIGIBLE: String =
      "m.presence != 'none' AND md.taken_at_utc IS NOT NULL AND md.taken_source IN ('exif_offset', 'exif_local', 'video_utc') " +
        "AND (g.kind IS NULL OR g.kind = 'similar_copies')"

    /**
     * Esiste un altro candidato dello stesso tipo (foto con foto, video con video) nello stesso secondo: una scansione per
     * intervallo dell'indice (`sort_time`, `media_id`) per ogni riga, nessun ordinamento.
     */
    private const val HAS_PEER: String =
      "EXISTS (SELECT 1 FROM media o JOIN version_metadata od ON od.version_id = o.current_version_id " +
        "LEFT JOIN media_group og ON og.group_id = o.group_id " +
        "WHERE o.sort_time >= (m.sort_time / 1000) * 1000 AND o.sort_time < (m.sort_time / 1000) * 1000 + 1000 " +
        "AND o.media_id != m.media_id AND (o.kind = 'video') = (m.kind = 'video') AND o.presence != 'none' " +
        "AND od.taken_at_utc IS NOT NULL AND od.taken_source IN ('exif_offset', 'exif_local', 'video_utc') " +
        "AND (og.kind IS NULL OR og.kind = 'similar_copies'))"

    const val RUNS_SQL: String =
      "SELECT $COLUMNS $FROM WHERE m.sort_time >= :fromMs AND $ELIGIBLE AND $HAS_PEER ORDER BY m.sort_time, m.media_id LIMIT :limit"

    const val SECOND_SQL: String =
      "SELECT $COLUMNS $FROM WHERE m.sort_time >= :fromMs AND m.sort_time < :toMs AND $ELIGIBLE ORDER BY m.sort_time, m.media_id"

    const val UNSIGNED_COUNT_SQL: String =
      "SELECT COUNT(*) FROM media m JOIN version_metadata md ON md.version_id = m.current_version_id " +
        "LEFT JOIN media_group g ON g.group_id = m.group_id " +
        "LEFT JOIN visual_signature s ON s.version_id = m.current_version_id AND s.algorithm = :algorithm " +
        "WHERE s.version_id IS NULL AND $ELIGIBLE AND $HAS_PEER"
  }
}

// --- 5B: tabelle vuote nella v4, DAO semplici (D27). Le regole sono di P8-P14. ------------------------------------------------

/** Le attivita' in sospeso (`pending_task`, D20): righe dentro e fuori. */
@Dao
interface PendingTaskDao {

  @Insert
  suspend fun insert(task: PendingTaskEntity): Long

  @Update
  suspend fun update(task: PendingTaskEntity)

  @Query("SELECT * FROM pending_task WHERE task_id = :taskId")
  suspend fun get(taskId: Long): PendingTaskEntity?

  /** Le attivita' in uno stato, dalla piu' vecchia ("Da decidere"): dall'indice (`state`, `created_at`). */
  @Query("SELECT * FROM pending_task WHERE state = :state ORDER BY created_at, task_id")
  suspend fun inState(state: String): List<PendingTaskEntity>
}

/** Le sessioni di "Modifica con…" (`edit_session`, D25): righe dentro e fuori. */
@Dao
interface EditSessionDao {

  @Insert
  suspend fun insert(session: EditSessionEntity): Long

  @Update
  suspend fun update(session: EditSessionEntity)

  @Query("SELECT * FROM edit_session WHERE session_id = :sessionId")
  suspend fun get(sessionId: Long): EditSessionEntity?

  /** Le sessioni in uno stato che scadono entro [expiresBefore] (ora di riferimento), le piu' vicine alla scadenza prima. */
  @Query("SELECT * FROM edit_session WHERE state = :state AND expires_at <= :expiresBefore ORDER BY expires_at, session_id")
  suspend fun expiring(state: String, expiresBefore: Long): List<EditSessionEntity>

  @Query("SELECT * FROM edit_session WHERE state = :state ORDER BY expires_at, session_id")
  suspend fun inState(state: String): List<EditSessionEntity>
}

/** I giri di "Libera spazio" e i loro elementi (`free_space_run`, `free_space_item`, D21): righe dentro e fuori. */
@Dao
interface FreeSpaceDao {

  @Insert
  suspend fun insertRun(run: FreeSpaceRunEntity): Long

  @Update
  suspend fun updateRun(run: FreeSpaceRunEntity)

  @Query("SELECT * FROM free_space_run WHERE run_id = :runId")
  suspend fun run(runId: Long): FreeSpaceRunEntity?

  @Query("SELECT * FROM free_space_run WHERE state IN (:states) ORDER BY run_id")
  suspend fun runsInState(states: List<String>): List<FreeSpaceRunEntity>

  @Insert
  suspend fun insertItems(items: List<FreeSpaceItemEntity>)

  @Update
  suspend fun updateItem(item: FreeSpaceItemEntity)

  @Query("SELECT * FROM free_space_item WHERE run_id = :runId ORDER BY item_index")
  suspend fun items(runId: Long): List<FreeSpaceItemEntity>

  /**
   * LS-15: solo gli elementi del giro con questi indici, dalla chiave primaria (`run_id`, `item_index`): la scrittura del diario cambia pochi
   * elementi per volta e non deve rileggere tutto il giro (migliaia di righe) a ogni chiamata. Chi chiama passa al piu' 500 indici per volta
   * (SQLite su Android 10 ammette 999 variabili per istruzione).
   */
  @Query("SELECT * FROM free_space_item WHERE run_id = :runId AND item_index IN (:indexes) ORDER BY item_index")
  suspend fun itemsAt(runId: Long, indexes: List<Int>): List<FreeSpaceItemEntity>

  /** Gli elementi di un lotto, in ordine. */
  @Query("SELECT * FROM free_space_item WHERE run_id = :runId AND batch_index = :batchIndex ORDER BY item_index")
  suspend fun batch(runId: Long, batchIndex: Int): List<FreeSpaceItemEntity>

  /** Gli elementi che riguardano una posizione del telefono (la lettura del telefono "lo sa" prima di segnarla assente). */
  @Query("SELECT * FROM free_space_item WHERE phone_location_id = :locationId ORDER BY run_id DESC, item_index")
  suspend fun forPhoneLocation(locationId: Long): List<FreeSpaceItemEntity>

  /**
   * P14 (richiesta di P9): gli elementi in questi stati di **tutti** i giri, dall'indice di `state` (le righe consegnate ad Android e non
   * risolte, senza scorrere i giri).
   */
  @Query("SELECT * FROM free_space_item WHERE state IN (:states) ORDER BY run_id, item_index")
  suspend fun itemsInStates(states: List<String>): List<FreeSpaceItemEntity>

  /** P14: le posizioni del telefono degli elementi in questi stati (la lettura del telefono: liberata, nel dialogo, in dubbio = non persa). */
  @Query("SELECT DISTINCT phone_location_id FROM free_space_item WHERE state IN (:states)")
  suspend fun phoneLocationsInStates(states: List<String>): List<Long>
}

/** I file in arrivo da altre app (`incoming_item`, D24): righe dentro e fuori. */
@Dao
interface IncomingDao {

  @Insert
  suspend fun insert(item: IncomingItemEntity): Long

  @Update
  suspend fun update(item: IncomingItemEntity)

  @Query("SELECT * FROM incoming_item WHERE incoming_id = :incomingId")
  suspend fun get(incomingId: Long): IncomingItemEntity?

  /** I file in uno stato, dal piu' vecchio: dall'indice (`state`, `created_at`). */
  @Query("SELECT * FROM incoming_item WHERE state = :state ORDER BY created_at, incoming_id")
  suspend fun inState(state: String): List<IncomingItemEntity>

  @Query("SELECT * FROM incoming_item WHERE stage_id = :stageId ORDER BY incoming_id")
  suspend fun ofStage(stageId: String): List<IncomingItemEntity>

  /** P14: tutti i file di uno stato con il loro elemento del diario (per la ripresa del caricamento). */
  @Query("SELECT * FROM incoming_item WHERE backup_item_id IS NOT NULL AND state = :state ORDER BY incoming_id")
  suspend fun linkedInState(state: String): List<IncomingItemEntity>

  /** P14: il file in arrivo che un elemento `INCOMING` del diario del backup carica (dall'indice di `backup_item_id`). */
  @Query("SELECT * FROM incoming_item WHERE backup_item_id = :backupItemId ORDER BY incoming_id")
  suspend fun forBackupItem(backupItemId: Long): List<IncomingItemEntity>

  /** P14: le condivisioni con almeno un file in uno di questi stati (la pulizia di `incoming/` all'avvio tiene queste). */
  @Query("SELECT DISTINCT stage_id FROM incoming_item WHERE state IN (:states)")
  suspend fun stagesInStates(states: List<String>): List<String>

  /**
   * IN-7 della verifica funzionale 5B ("Ricevuti in coda" della pagina Backup): i file in questi stati con lo stato e il problema del loro elemento del
   * diario, dal piu' vecchio. Un flusso: si rilegge quando cambia una riga di `incoming_item` **o** di `backup_item` (il caricamento che avanza non
   * cambia `incoming_item`). Dall'indice di `state` per `incoming_item` e di `backup_item_id` per la giunzione (una riga per file).
   */
  @Query(
    "SELECT i.*, b.state AS item_state, b.problem AS item_problem FROM incoming_item i " +
      "LEFT JOIN backup_item b ON b.item_id = i.backup_item_id WHERE i.state IN (:states) ORDER BY i.created_at, i.incoming_id",
  )
  fun observeQueue(states: List<String>): Flow<List<IncomingQueueRow>>

  /** IN-7: la stessa fotografia, una volta sola. */
  @Query(
    "SELECT i.*, b.state AS item_state, b.problem AS item_problem FROM incoming_item i " +
      "LEFT JOIN backup_item b ON b.item_id = i.backup_item_id WHERE i.incoming_id = :incomingId",
  )
  suspend fun queueRow(incomingId: Long): IncomingQueueRow?

  /** IN-7: quanti file sono in questi stati (il piede della Libreria e la notifica contano i problemi). Dall'indice di `state`. */
  @Query("SELECT COUNT(*) FROM incoming_item WHERE state IN (:states)")
  suspend fun countInStates(states: List<String>): Int

  /** IN-7: lo stesso conto come flusso (la notifica dei problemi lo segue). */
  @Query("SELECT COUNT(*) FROM incoming_item WHERE state IN (:states)")
  fun observeCountInStates(states: List<String>): Flow<Int>

  /** IN-7: "Scarta" toglie la riga dal diario degli arrivi (la copia locale la toglie chi chiama). Il NAS non c'entra. */
  @Query("DELETE FROM incoming_item WHERE incoming_id = :incomingId")
  suspend fun delete(incomingId: Long): Int
}

/** Le correzioni solo nell'app (`metadata_correction`, Q6 (a), P14): una riga per versione. */
@Dao
interface MetadataCorrectionDao {

  @Upsert
  suspend fun upsert(row: MetadataCorrectionEntity)

  @Query("SELECT * FROM metadata_correction WHERE version_id = :versionId")
  suspend fun get(versionId: String): MetadataCorrectionEntity?
}
