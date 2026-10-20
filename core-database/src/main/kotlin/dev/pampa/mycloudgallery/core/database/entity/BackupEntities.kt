package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v4 (Fase 5, docs/fase5-progetto.md D27; docs/modello-dati.md §12): il diario del backup, il problema di un media, la firma
// visiva delle copie simili e le tabelle che la 5B usera' (attivita' in sospeso, sessioni di modifica, "Libera spazio", arrivi).
// Le tabelle della 5B nascono gia' qui, vuote: una sola migrazione sul campo per tutta la fase (come le tabelle della 4B nella v3).
//
// Regole comuni (come per le altre tabelle): enumerazioni con il loro codice stabile; istanti in millisecondi (ora di
// riferimento di chi scrive; quelli del diario e delle attivita' in ora del NAS, F4-D32); impronte in esadecimale minuscolo.
// **Nei log mai nomi, percorsi, share, id ne' impronte** (§4.3): i `toString` delle righe con dati dell'utente sono muti.
//
// Le chiavi esterne verso `media` e `media_version` sono differite come quelle del grafo e **senza cascata** dove la riga e' un
// dato dell'utente o un diario (nessuna riga si perde in silenzio: l'unione di due media e la rinomina di un'identita' le
// spostano, `MergeReferencesTest` lo controlla); con cascata dove la riga si ricalcola (firma visiva, problema di un media).

/**
 * Un elemento del diario del backup (`backup_item`): rispecchia `BackupItem` di `core-backup` campo per campo (core-database non
 * dipende da core-backup: P2 traduce nel suo `RoomBackupStore`). **Ogni valore di un passo si scrive qui prima della chiamata
 * al NAS** (D3); e' P2 che lo garantisce, la riga non decide nulla.
 *
 * - [state] e [kind]: codici di `UploadState` e `UploadKind`; [isOpen] vero se lo stato non e' terminale (`DONE`,
 *   `LINKED_EXISTING`, `GONE`, `FAILED`). **Lo tiene coerente il DAO** ([dev.pampa.mycloudgallery.core.database.dao.BackupDiaryDao]):
 *   serve a trovare gli elementi aperti con un indice (la tabella cresce di una riga per file, gli aperti sono pochi).
 * - telefono: la riga del MediaStore con cui e' stato pianificato ([phoneVolume], [phoneMediaStoreId], [phoneMediaStoreVersion]) e il
 *   timbro atteso ([expectedSize], [expectedModifiedAt], [expectedGeneration]); `NULL` per il guardiano.
 * - NAS: [share] e [plannedPath] (D6), [finalPath] ("nome (n)" dopo una collisione), [tempPath] (D33), [resumeAt] (D5: byte gia'
 *   sul NAS e controllati) con [resumeFileId] (R3 della revisione 5A: l'identita' del temporaneo a quel punto), i timbri del temporaneo
 *   verificato e del finale (dimensione, data, `FileId`), la sorgente del guardiano ([guardShare], [guardPath] e il suo timbro).
 * - [problem]: codice di `BackupProblemCode` registrato con un'uscita (`FAILED`, `GONE`).
 */
@Entity(
  tableName = "backup_item",
  foreignKeys = [
    ForeignKey(entity = MediaEntity::class, parentColumns = ["media_id"], childColumns = ["media_id"], deferred = true),
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], deferred = true),
  ],
  indices = [
    // Gli elementi da riprendere (dal piu' vecchio) e i temporanei citati: solo gli aperti, senza scorrere i terminali.
    Index(value = ["is_open", "item_id"]),
    // `openFor(versionId)` e idempotenza di `create`; copre anche la chiave esterna.
    Index(value = ["version_id", "is_open"]),
    Index(value = ["media_id"]),
    // I conteggi per stato (pagina del backup, Diagnostica) leggono solo questo indice.
    Index(value = ["state"]),
    // 5B (P14): i lavori di un tipo (versioni e scambi di una share per la franchigia, D32; gli arrivi): la tabella cresce di una riga per file.
    Index(value = ["kind", "item_id"]),
  ],
)
data class BackupItemEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "item_id") val itemId: Long = 0,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "is_open") val isOpen: Boolean,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "size") val size: Long,
  // --- telefono (NULL per il guardiano) ---
  @ColumnInfo(name = "phone_volume") val phoneVolume: String?,
  @ColumnInfo(name = "phone_ms_id") val phoneMediaStoreId: Long?,
  @ColumnInfo(name = "phone_ms_version") val phoneMediaStoreVersion: String?,
  @ColumnInfo(name = "expected_size") val expectedSize: Long?,
  @ColumnInfo(name = "expected_modified_at") val expectedModifiedAt: Long?,
  @ColumnInfo(name = "expected_generation") val expectedGeneration: Long?,
  // --- destinazione sul NAS ---
  @ColumnInfo(name = "share") val share: String,
  @ColumnInfo(name = "planned_path") val plannedPath: String,
  @ColumnInfo(name = "final_path") val finalPath: String?,
  @ColumnInfo(name = "name_attempt") val nameAttempt: Int,
  @ColumnInfo(name = "temp_path") val tempPath: String?,
  @ColumnInfo(name = "resume_at") val resumeAt: Long,
  // R3 della revisione 5A: l'identita' (`FileId`) del temporaneo al punto di ripresa, letta sull'handle dello scrittore.
  @ColumnInfo(name = "resume_file_id") val resumeFileId: Long?,
  // --- impronte dei byte del telefono (o, per il guardiano, quelle attese della versione) ---
  @ColumnInfo(name = "sha256") val sha256: String?,
  @ColumnInfo(name = "quick_hash") val quickHash: String?,
  // --- timbro del temporaneo verificato e del finale pubblicato (NasStamp: dimensione, data, FileId) ---
  @ColumnInfo(name = "temp_size") val tempSize: Long?,
  @ColumnInfo(name = "temp_modified_at") val tempModifiedAt: Long?,
  @ColumnInfo(name = "temp_file_id") val tempFileId: Long?,
  @ColumnInfo(name = "final_size") val finalSize: Long?,
  @ColumnInfo(name = "final_modified_at") val finalModifiedAt: Long?,
  @ColumnInfo(name = "final_file_id") val finalFileId: Long?,
  @ColumnInfo(name = "verify_attempts") val verifyAttempts: Int,
  // --- guardiano (D9): la copia dell'area comune da cui si copia lato server ---
  @ColumnInfo(name = "guard_share") val guardShare: String?,
  @ColumnInfo(name = "guard_path") val guardPath: String?,
  @ColumnInfo(name = "guard_size") val guardSize: Long?,
  @ColumnInfo(name = "guard_modified_at") val guardModifiedAt: Long?,
  @ColumnInfo(name = "guard_file_id") val guardFileId: Long?,
  @ColumnInfo(name = "problem") val problem: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  // --- 5B (P8, colonne di P14): i lavori `version` e `swap` (D17, D18). NULL per gli elementi della 5A e per gli arrivi ---
  // Dove va la versione nuova (`VersionTarget`: solito percorso o `Versioni/`).
  @ColumnInfo(name = "version_target") val versionTarget: String? = null,
  // L'attuale da spostare in `Versioni/` (`CurrentFile`): versione, posizione nel catalogo, timbro (dimensione, data, `FileId`), se e' l'originale.
  // NULL = nessuna attuale da spostare (cammino `version_direct`, D17): il cammino del lavoro si legge da qui.
  @ColumnInfo(name = "current_version_id") val currentVersionId: String? = null,
  @ColumnInfo(name = "current_location_id") val currentLocationId: Long? = null,
  @ColumnInfo(name = "current_size") val currentSize: Long? = null,
  @ColumnInfo(name = "current_modified_at") val currentModifiedAt: Long? = null,
  @ColumnInfo(name = "current_file_id") val currentFileId: Long? = null,
  @ColumnInfo(name = "current_original") val currentOriginal: Boolean? = null,
  // Il nome in `Versioni/` (scritto prima della rinomina), i tentativi dopo un nome occupato e il timbro dopo l'archiviazione.
  @ColumnInfo(name = "archive_path") val archivePath: String? = null,
  @ColumnInfo(name = "archive_attempt") val archiveAttempt: Int = 0,
  @ColumnInfo(name = "archived_size") val archivedSize: Long? = null,
  @ColumnInfo(name = "archived_modified_at") val archivedModifiedAt: Long? = null,
  @ColumnInfo(name = "archived_file_id") val archivedFileId: Long? = null,
  // Perche' il lavoro aspetta o e' finito senza fare il suo (`VersionHold`: `source_changed`, `usual_path_taken`, `rolled_back`).
  @ColumnInfo(name = "hold") val hold: String? = null,
  // Lo scambio: la posizione nel catalogo della versione da riportare (il percorso e il timbro stanno in `guard_path`/`guard_*`).
  @ColumnInfo(name = "source_location_id") val sourceLocationId: Long? = null,
  // Un file preparato (correzione, editor esterno): il suo SHA-256.
  @ColumnInfo(name = "staged_sha256") val stagedSha256: String? = null,
) {
  // Percorsi, share, impronte: dati dell'utente, mai nei log.
  override fun toString(): String = "BackupItemEntity(#$itemId, $kind, $state)"

  companion object {
    /** Gli stati terminali di `UploadState` (codici fissati qui: la riga non vede `core-backup`). */
    val TERMINAL_STATES: Set<String> = setOf("done", "linked_existing", "gone", "failed")

    /** Se un elemento in questo stato e' ancora da riprendere. */
    fun isOpenState(state: String): Boolean = state !in TERMINAL_STATES
  }
}

/**
 * Il problema che il backup ha registrato per un media (`backup_problem`, D15): [code] e' un `BackupProblemCode`. Se [marksCell]
 * accende `CellFlags.BACKUP_PROBLEM` (derivato dal catalogo nella stessa transazione) e toglie il media dai candidati del backup
 * (un media con la verifica fallita tre volte non si ricarica all'infinito). **Si toglie da solo quando il media torna al sicuro**
 * (e `lost_before_backup` anche quando la foto ricompare sul telefono: `MediaCatalog.releaseRecoveredLostMedia`).
 * Una riga per media; con cascata perche' e' un'annotazione ricalcolabile (l'unione la sposta comunque sul sopravvissuto).
 */
@Entity(
  tableName = "backup_problem",
  foreignKeys = [
    ForeignKey(entity = MediaEntity::class, parentColumns = ["media_id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE, deferred = true),
  ],
)
data class BackupProblemEntity(
  @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "code") val code: String,
  @ColumnInfo(name = "marks_cell") val marksCell: Boolean,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  // Niente id di media nei log.
  override fun toString(): String = "BackupProblemEntity($code, marksCell=$marksCell)"
}

/**
 * Una copia sul NAS la cui rilettura **non e' tornata** (`nas_copy_mismatch`, E-B8, D14): i byte letti erano diversi da quelli
 * della versione. Ricorda il timbro che aveva ([size], [modifiedAt]): finche' il file e' lo stesso la verifica delle copie non
 * verificate ([dev.pampa.mycloudgallery.core.database.dao.BackupQueries.unverified]) non la rilegge a ogni giro; se il timbro cambia
 * (il file e' stato rifatto) la riga non vale piu' e la copia torna da verificare. Una riga per posizione; segue la posizione
 * (con cascata: se la posizione sparisce, sparisce anche la riga). La scrive e la toglie `MediaCatalog.markVerified`.
 */
@Entity(
  tableName = "nas_copy_mismatch",
  foreignKeys = [
    ForeignKey(entity = MediaLocationEntity::class, parentColumns = ["location_id"], childColumns = ["location_id"], onDelete = ForeignKey.CASCADE, deferred = true),
  ],
)
data class NasCopyMismatchEntity(
  @PrimaryKey @ColumnInfo(name = "location_id") val locationId: Long,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "modified_at") val modifiedAt: Long,
  @ColumnInfo(name = "found_at") val foundAt: Long,
)

/**
 * La firma visiva di una versione (`visual_signature`, D36, `VisualSignature` di core-model): solo **locale** (mai pubblicata sul
 * NAS), una per versione e per un solo algoritmo (una firma di un altro algoritmo si ricalcola e sostituisce questa). Le versioni
 * sono immutabili (i byte le definiscono): la firma non invecchia. Con cascata: si ricalcola.
 */
@Entity(
  tableName = "visual_signature",
  foreignKeys = [
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], onDelete = ForeignKey.CASCADE, deferred = true),
  ],
)
class VisualSignatureEntity(
  @PrimaryKey @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "algorithm") val algorithm: Int,
  @ColumnInfo(name = "frames") val frames: Int,
  @ColumnInfo(name = "data", typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
  @ColumnInfo(name = "signed_at") val signedAt: Long,
) {
  override fun toString(): String = "VisualSignatureEntity(v$algorithm, $frames fotogrammi, ${data.size} B)"
}

// --- 5B: nascono vuote nella v4 (D27) ----------------------------------------------------------------------------------------

/**
 * Un'attivita' in sospeso (`pending_task`, D20, §2.6 "Da decidere"): per ora quella di un video modificato e salvato sopra
 * l'originale ("L'originale occupa X sul NAS: vuoi eliminarlo?"). [kind] e [state] sono codici stabili del tipo e dello stato
 * (aperta, tenuta, fatta); [versionId] e' la versione su cui si decide (l'originale); [bytes] quanto occupa; istanti in ora del NAS.
 */
@Entity(
  tableName = "pending_task",
  foreignKeys = [
    ForeignKey(entity = MediaEntity::class, parentColumns = ["media_id"], childColumns = ["media_id"], deferred = true),
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], deferred = true),
  ],
  indices = [Index(value = ["state", "created_at"]), Index(value = ["media_id"]), Index(value = ["version_id"])],
)
data class PendingTaskEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "task_id") val taskId: Long = 0,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "bytes") val bytes: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  override fun toString(): String = "PendingTaskEntity(#$taskId, $kind, $state, $bytes B)"
}

/**
 * Una sessione di "Modifica con…" (`edit_session`, D25, E-X3): il media, la versione di partenza, la riga e il timbro del file
 * all'inizio (per riconoscere la riga cambiata o una riga nuova con la stessa data di scatto nella stessa cartella), la copia in
 * cache per gli editor che la riscrivono ([cacheName], [cacheSha256]) e la finestra di 30 minuti ([startedAt], [expiresAt]: istanti,
 * non durate, perche' la sessione deve sopravvivere alla morte del processo mentre l'utente modifica in un'altra app).
 */
@Entity(
  tableName = "edit_session",
  foreignKeys = [
    ForeignKey(entity = MediaEntity::class, parentColumns = ["media_id"], childColumns = ["media_id"], deferred = true),
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], deferred = true),
  ],
  indices = [Index(value = ["state", "expires_at"]), Index(value = ["media_id"]), Index(value = ["version_id"])],
)
data class EditSessionEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "session_id") val sessionId: Long = 0,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "target") val target: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "phone_volume") val phoneVolume: String?,
  @ColumnInfo(name = "phone_ms_id") val phoneMediaStoreId: Long?,
  @ColumnInfo(name = "phone_ms_version") val phoneMediaStoreVersion: String?,
  @ColumnInfo(name = "start_size") val startSize: Long,
  @ColumnInfo(name = "start_modified_at") val startModifiedAt: Long,
  @ColumnInfo(name = "start_generation") val startGeneration: Long?,
  @ColumnInfo(name = "relative_path") val relativePath: String?,
  @ColumnInfo(name = "taken_at_utc") val takenAtUtc: Long?,
  @ColumnInfo(name = "cache_name") val cacheName: String?,
  @ColumnInfo(name = "cache_sha256") val cacheSha256: String?,
  @ColumnInfo(name = "started_at") val startedAt: Long,
  @ColumnInfo(name = "expires_at") val expiresAt: Long,
) {
  // La cartella del telefono e il nome in cache sono dati dell'utente.
  override fun toString(): String = "EditSessionEntity(#$sessionId, $target, $state)"
}

/**
 * Un giro di "Libera spazio" (`free_space_run`, D21, §2.5): il filtro, l'impronta del piano a cui la conferma e' legata
 * ([planHash]), il modo (cestino di Android o "Libera subito") e l'avanzamento a lotti da 2.000. **La verifica sul momento vale 6 ore
 * di orologio monotono e non sopravvive a un riavvio**: dal P14 il giro ne salva l'inizio monotono e il conteggio dei riavvii, cosi' dopo la
 * morte del processo (stesso avvio) riprende; dopo un riavvio e' scaduto e si rifa' dal piano.
 */
@Entity(tableName = "free_space_run", indices = [Index(value = ["state"])])
data class FreeSpaceRunEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "run_id") val runId: Long = 0,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "filter") val filter: String,
  @ColumnInfo(name = "exclude_recent_days") val excludeRecentDays: Int,
  @ColumnInfo(name = "plan_hash") val planHash: String,
  @ColumnInfo(name = "delete_mode") val deleteMode: String,
  @ColumnInfo(name = "item_count") val itemCount: Int,
  @ColumnInfo(name = "total_bytes") val totalBytes: Long,
  @ColumnInfo(name = "batches_total") val batchesTotal: Int,
  @ColumnInfo(name = "batches_done") val batchesDone: Int,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  // P14 (colonne facoltative di P9): da quando vale la verifica sul momento. L'istante e' dell'orologio **monotono** (vale solo nello stesso
  // avvio) e [bootCount] e' il conteggio dei riavvii di allora: un giro a meta' riprende dopo la morte del processo, mai dopo un riavvio.
  // NULL = validita' non nota (il giro e' scaduto: fallisce chiuso).
  @ColumnInfo(name = "valid_from_monotonic") val validFromMonotonic: Long? = null,
  @ColumnInfo(name = "boot_count") val bootCount: Long? = null,
  // Perche' il giro e' fermo (`FreeSpaceBlock`), se lo e'.
  @ColumnInfo(name = "blocked") val blocked: String? = null,
)

/**
 * Un elemento di un giro di "Libera spazio" (`free_space_item`): la copia del telefono da liberare ([phoneLocationId], senza
 * chiave esterna: la posizione sparisce proprio quando Android conferma, e questa riga "lo sa prima della lettura del telefono",
 * cosi' la cella resta come "solo NAS" invece di diventare "persa"), la copia privata verificata contro cui e' stata decisa
 * ([nasLocationId] e il suo timbro [verifiedSize]/[verifiedModifiedAt]/[verifiedFileId]: il controllo finale prima del dialogo
 * di sistema), il lotto e, se escluso, il motivo ([reason], codice stabile). [state] e' un codice stabile.
 */
@Entity(
  tableName = "free_space_item",
  primaryKeys = ["run_id", "item_index"],
  foreignKeys = [
    ForeignKey(entity = FreeSpaceRunEntity::class, parentColumns = ["run_id"], childColumns = ["run_id"], onDelete = ForeignKey.CASCADE, deferred = true),
    ForeignKey(entity = MediaEntity::class, parentColumns = ["media_id"], childColumns = ["media_id"], deferred = true),
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], deferred = true),
  ],
  indices = [
    Index(value = ["phone_location_id"]),
    Index(value = ["media_id"]),
    Index(value = ["version_id"]),
    Index(value = ["run_id", "batch_index"]),
    // P14: le righe consegnate ad Android e non risolte (`in_dialog`, `in_doubt`) di tutti i giri, senza scorrerli.
    Index(value = ["state"]),
  ],
)
data class FreeSpaceItemEntity(
  @ColumnInfo(name = "run_id") val runId: Long,
  @ColumnInfo(name = "item_index") val itemIndex: Int,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "phone_location_id") val phoneLocationId: Long,
  @ColumnInfo(name = "nas_location_id") val nasLocationId: Long?,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "verified_size") val verifiedSize: Long?,
  @ColumnInfo(name = "verified_modified_at") val verifiedModifiedAt: Long?,
  @ColumnInfo(name = "verified_file_id") val verifiedFileId: Long?,
  @ColumnInfo(name = "batch_index") val batchIndex: Int?,
  @ColumnInfo(name = "reason") val reason: String?,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  // P14 (colonne facoltative di P9): la riga del telefono con il suo timbro (il controllo finale prima del dialogo), il tipo, il segno "prima
  // il guardiano" e le copie rilette di tutte le versioni da proteggere ([checks], testo: una riga per copia con versione, posizione, share,
  // percorso e timbro). NULL = non salvate (un giro senza non va avanti dopo la morte del processo).
  @ColumnInfo(name = "phone_volume") val phoneVolume: String? = null,
  @ColumnInfo(name = "phone_ms_id") val phoneMediaStoreId: Long? = null,
  @ColumnInfo(name = "phone_ms_version") val phoneMediaStoreVersion: String? = null,
  @ColumnInfo(name = "phone_size") val phoneSize: Long? = null,
  @ColumnInfo(name = "phone_modified_at") val phoneModifiedAt: Long? = null,
  @ColumnInfo(name = "phone_generation") val phoneGeneration: Long? = null,
  @ColumnInfo(name = "mime") val mime: String? = null,
  @ColumnInfo(name = "needs_guardian") val needsGuardian: Boolean = false,
  @ColumnInfo(name = "checks") val checks: String? = null,
) {
  override fun toString(): String = "FreeSpaceItemEntity(run=$runId, #$itemIndex, $state, $size B)"
}

/**
 * Un file in arrivo da un'altra app (`incoming_item`, D24): copiato subito in `noBackupFilesDir/incoming/` con un nome derivato
 * dall'hash ([fileName]), tipo dai byte ([mime]), poi, scelta la cartella ([destShare], [destFolder]), caricato come elemento
 * `INCOMING` del diario ([backupItemId]). [stageId] raggruppa i flussi di una stessa condivisione. Nessun riferimento a `media`:
 * il file non e' ancora un media.
 */
@Entity(
  tableName = "incoming_item",
  foreignKeys = [
    ForeignKey(entity = BackupItemEntity::class, parentColumns = ["item_id"], childColumns = ["backup_item_id"], onDelete = ForeignKey.SET_NULL, deferred = true),
  ],
  indices = [Index(value = ["state", "created_at"]), Index(value = ["stage_id"]), Index(value = ["backup_item_id"])],
)
data class IncomingItemEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "incoming_id") val incomingId: Long = 0,
  @ColumnInfo(name = "stage_id") val stageId: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "file_name") val fileName: String,
  @ColumnInfo(name = "display_name") val displayName: String?,
  @ColumnInfo(name = "sha256") val sha256: String,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "mime") val mime: String,
  @ColumnInfo(name = "dest_share") val destShare: String?,
  @ColumnInfo(name = "dest_folder") val destFolder: String?,
  @ColumnInfo(name = "backup_item_id") val backupItemId: Long?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  // Nome del file, cartella scelta e share: dati dell'utente.
  override fun toString(): String = "IncomingItemEntity(#$incomingId, $state, $size B)"
}

/**
 * IN-7: un file in arrivo con lo stato e il problema del suo elemento del diario (`backup_item`, null se l'elemento non c'e' piu'). Le colonne sono
 * quelle di `IncomingDao.observeQueue`: serve a dire "in attesa" o "in caricamento" senza una seconda lettura per file.
 */
data class IncomingQueueRow(
  @Embedded val item: IncomingItemEntity,
  @ColumnInfo(name = "item_state") val itemState: String?,
  @ColumnInfo(name = "item_problem") val itemProblem: String?,
) {
  override fun toString(): String = "IncomingQueueRow(${item.state}, ${itemState ?: "senza elemento"})"
}

/**
 * Una correzione di data, luogo o rotazione **solo nell'app** (`metadata_correction`, Q6 (a), L40; P14): per i formati che non si scrivono nel
 * file (HEIC, RAW, video) la correzione resta nel catalogo e nell'indice, e **sopravvive alla rilettura dei metadati** (fase META): i valori
 * qui si applicano sopra quelli letti dal file ogni volta che una versione riceve i suoi metadati. Una riga per versione; i campi NULL non
 * correggono niente. Con cascata: segue la versione (un'unione la sposta sul sopravvissuto, `MergeReferencesTest`).
 *
 * - [takenAtUtc], [offsetMinutes]: la data di scatto corretta (l'istante e il fuso);
 * - [geo]: `set` (con [latitude], [longitude]) o `clear` (luogo tolto); NULL = luogo com'e';
 * - [orientationDegrees], [mirrored]: la rotazione corretta.
 */
@Entity(
  tableName = "metadata_correction",
  foreignKeys = [
    ForeignKey(entity = MediaVersionEntity::class, parentColumns = ["version_id"], childColumns = ["version_id"], onDelete = ForeignKey.CASCADE, deferred = true),
  ],
)
data class MetadataCorrectionEntity(
  @PrimaryKey @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "taken_at_utc") val takenAtUtc: Long?,
  @ColumnInfo(name = "offset_minutes") val offsetMinutes: Int?,
  @ColumnInfo(name = "geo") val geo: String?,
  @ColumnInfo(name = "latitude") val latitude: Double?,
  @ColumnInfo(name = "longitude") val longitude: Double?,
  @ColumnInfo(name = "orientation_degrees") val orientationDegrees: Int?,
  @ColumnInfo(name = "mirrored") val mirrored: Boolean?,
  @ColumnInfo(name = "corrected_at") val correctedAt: Long,
) {
  // Data e luogo dello scatto: dati dell'utente.
  override fun toString(): String = "MetadataCorrectionEntity"

  companion object {
    const val GEO_SET: String = "set"
    const val GEO_CLEAR: String = "clear"
  }
}
