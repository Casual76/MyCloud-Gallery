package dev.pampa.mycloudgallery.core.backup.store

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.GuardSource
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario del backup su Room (`backup_item`, schema v4 di P1; Fase 5A, P2): **le stesse verifiche** del fake in memoria
 * (`InMemoryBackupStore`), dentro una transazione per chiamata:
 * - rifiuta ogni transizione fuori da [UploadStateMachine] (`IllegalStateException`), un elemento sconosciuto, un cambio d'identita'
 *   (tipo, media, versione, share, destinazione prevista, dimensione) o di un temporaneo gia' scritto (`IllegalArgumentException`) e i
 *   tipi della 5B;
 * - `create` e' **idempotente per versione e tipo**: un elemento aperto per la stessa versione e lo stesso tipo si restituisce, mai un
 *   secondo (invariante 2 del §5.4), anche dentro lo stesso lotto.
 * Gli istanti `created_at`/`updated_at` sono l'ora del NAS ([BackupClock.nasNowMs]) quando c'e', mai l'orologio del telefono (D31):
 * servono a chi guarda il diario e (D-13) `updated_at` al motore, per sapere da quanto un elemento e' fermo su un temporaneo occupato
 * ([lastStepAt]; 0 = ora del NAS non nota: non si sa).
 */
class RoomBackupStore(private val database: GalleryDatabase, private val clock: BackupClock) : BackupStore {

  private val dao get() = database.backupDiaryDao()

  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> = database.transaction {
    items.map { new ->
      require(!new.kind.phase5B) { "Un elemento della 5B (${new.kind.code}) non si crea nella 5A." }
      dao.openForVersion(new.versionId.value).map { it.toItem() }.firstOrNull { it.kind == new.kind } ?: run {
        val entity = new.toEntity(clock.nasNowMs() ?: 0L)
        val id = dao.insert(entity)
        entity.copy(itemId = id, isOpen = true).toItem()
      }
    }
  }

  override suspend fun createIncoming(item: NewBackupItem): BackupItem = database.transaction {
    // Un file in arrivo da un'altra app (la copia locale, con il suo SHA-256) o, Fase 7 (E-SH1), un media del telefono verso la cartella di un album
    // condiviso (la riga del telefono e il suo timbro: l'impronta la legge il motore, come per una copia nuova).
    require(item.kind == UploadKind.INCOMING && ((item.phone == null && item.sha256 != null) || (item.phone != null && item.expected != null))) {
      "Un file in arrivo con il suo SHA-256, o un media del telefono con la sua riga."
    }
    // R14: lo stesso arrivo e' quello della stessa versione **nella stessa share e nella stessa cartella**; una destinazione diversa e' un elemento nuovo.
    dao.openForVersion(item.versionId.value).map { it.toItem() }.firstOrNull { it.kind == UploadKind.INCOMING && it.sameDestinationAs(item) } ?: run {
      val entity = item.toEntity(clock.nasNowMs() ?: 0L)
      val id = dao.insert(entity)
      entity.copy(itemId = id, isOpen = true).toItem()
    }
  }

  override suspend fun item(id: BackupItemId): BackupItem? = dao.get(id.value)?.toItem()

  override suspend fun itemsToResume(limit: Int): List<BackupItem> {
    require(limit > 0) { "Limite non positivo." }
    return dao.itemsToResume(limit).map { it.toItem() }
  }

  override suspend fun update(item: BackupItem) {
    database.transaction {
      val stored = dao.get(item.id.value) ?: throw IllegalArgumentException("Elemento del diario sconosciuto.")
      val before = stored.toItem()
      require(
        before.kind == item.kind && before.mediaId == item.mediaId && before.versionId == item.versionId &&
          before.share == item.share && before.planned == item.planned && before.size == item.size,
      ) { "L'identita' di un elemento del diario non cambia." }
      // D-13: il temporaneo non cambia una volta scritto, se non per passare a un tentativo dopo dello stesso elemento (il NAS teneva occupato il
      // vecchio): i tentativi di prima restano citati ([citedTemps]) finche' l'elemento e' aperto.
      require(before.temp == null || before.temp == item.temp || before.temp.isEarlierAttemptOf(item.temp)) {
        "Il temporaneo di un elemento non cambia una volta scritto."
      }
      check(UploadStateMachine.canTransition(before.flow, before.state, item.state)) {
        "Transizione non ammessa: ${before.state.code} -> ${item.state.code} (${before.flow.code})."
      }
      // Le colonne delle versioni (P14) le scrive solo il loro diario: qui restano quelle che c'erano.
      dao.update(item.toEntity(createdAt = stored.createdAt, updatedAt = clock.nasNowMs() ?: stored.updatedAt).withVersionColumnsOf(stored))
    }
  }

  override suspend fun openFor(versionId: VersionId): BackupItem? =
    dao.openForVersion(versionId.value).map { it.toItem() }.firstOrNull { it.kind != UploadKind.INCOMING }

  override suspend fun citedTemps(): Set<SharePath> = dao.citedTemps().flatMapTo(LinkedHashSet()) { raw ->
    val temp = SharePath.parse(raw)
    // D-13: anche i temporanei dei tentativi di prima dello stesso elemento (il motore li toglie quando il NAS li lascia).
    listOf(temp) + NasBackupPaths.earlierAttempts(temp)
  }

  // D-13: 0 e' "ora del NAS non nota" (un elemento creato senza sessione): non "fermo da sempre", che farebbe ripartire subito da capo.
  override suspend fun lastStepAt(id: BackupItemId): Long? = dao.updatedAt(id.value)?.takeIf { it > 0 }

  override suspend fun counts(): BackupDiaryCounts = database.transaction {
    val byState = dao.countsByState().mapNotNull { row -> STATES[row.status]?.let { it to row.count } }.toMap()
    BackupDiaryCounts(byState, dao.openBytes())
  }

  private companion object {
    /** D-13: [this] e' il temporaneo di un tentativo di prima di [later] (stesso elemento, stessa cartella). */
    fun SharePath.isEarlierAttemptOf(later: SharePath?): Boolean = later != null && this in NasBackupPaths.earlierAttempts(later)

    /** R14: [this] va nella stessa share e nella stessa cartella di [other] (il nome del file puo' cambiare da un'app all'altra: la cartella no). */
    fun BackupItem.sameDestinationAs(other: NewBackupItem): Boolean =
      share.equals(other.share, ignoreCase = true) && planned.components.dropLast(1).map { it.lowercase() } == other.planned.components.dropLast(1).map { it.lowercase() }

    val KINDS: Map<String, UploadKind> = UploadKind.entries.associateBy { it.code }
    val STATES: Map<String, UploadState> = UploadState.entries.associateBy { it.code }
    val PROBLEMS: Map<String, BackupProblemCode> = BackupProblemCode.entries.associateBy { it.code }

    fun NewBackupItem.toEntity(now: Long): BackupItemEntity = BackupItemEntity(
      kind = kind.code,
      state = UploadState.PLANNED.code,
      isOpen = true,
      mediaId = mediaId.value,
      versionId = versionId.value,
      size = size,
      phoneVolume = phone?.volume,
      phoneMediaStoreId = phone?.mediaStoreId,
      phoneMediaStoreVersion = phone?.mediaStoreVersion,
      expectedSize = expected?.size,
      expectedModifiedAt = expected?.modifiedAtMillis,
      expectedGeneration = expected?.generation,
      share = share,
      plannedPath = planned.value,
      finalPath = null,
      nameAttempt = 0,
      tempPath = null,
      resumeAt = 0,
      resumeFileId = null,
      sha256 = sha256?.hex,
      quickHash = quickHash?.hex,
      tempSize = null,
      tempModifiedAt = null,
      tempFileId = null,
      finalSize = null,
      finalModifiedAt = null,
      finalFileId = null,
      verifyAttempts = 0,
      guardShare = guardSource?.share,
      guardPath = guardSource?.path?.value,
      guardSize = guardSource?.stamp?.size,
      guardModifiedAt = guardSource?.stamp?.modifiedAtMillis,
      guardFileId = guardSource?.stamp?.fileId,
      problem = null,
      createdAt = now,
      updatedAt = now,
    )

    fun BackupItem.toEntity(createdAt: Long, updatedAt: Long): BackupItemEntity = BackupItemEntity(
      itemId = id.value,
      kind = kind.code,
      state = state.code,
      isOpen = !state.terminal,
      mediaId = mediaId.value,
      versionId = versionId.value,
      size = size,
      phoneVolume = phone?.volume,
      phoneMediaStoreId = phone?.mediaStoreId,
      phoneMediaStoreVersion = phone?.mediaStoreVersion,
      expectedSize = expected?.size,
      expectedModifiedAt = expected?.modifiedAtMillis,
      expectedGeneration = expected?.generation,
      share = share,
      plannedPath = planned.value,
      finalPath = finalPath?.value,
      nameAttempt = nameAttempt,
      tempPath = temp?.value,
      resumeAt = resumeAt,
      resumeFileId = resumeId,
      sha256 = sha256?.hex,
      quickHash = quickHash?.hex,
      tempSize = tempStamp?.size,
      tempModifiedAt = tempStamp?.modifiedAtMillis,
      tempFileId = tempStamp?.fileId,
      finalSize = finalStamp?.size,
      finalModifiedAt = finalStamp?.modifiedAtMillis,
      finalFileId = finalStamp?.fileId,
      verifyAttempts = verifyAttempts,
      guardShare = guardSource?.share,
      guardPath = guardSource?.path?.value,
      guardSize = guardSource?.stamp?.size,
      guardModifiedAt = guardSource?.stamp?.modifiedAtMillis,
      guardFileId = guardSource?.stamp?.fileId,
      problem = problem?.code,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )

    fun BackupItemEntity.toItem(): BackupItem {
      // Copie locali: le proprieta' dell'entita' (altro modulo) non si possono restringere direttamente.
      val volume = phoneVolume
      val msId = phoneMediaStoreId
      val msVersion = phoneMediaStoreVersion
      val gShare = guardShare
      val gPath = guardPath
      return BackupItem(
        id = BackupItemId(itemId),
        kind = KINDS[kind] ?: error("Tipo di elemento sconosciuto nel diario."),
        state = STATES[state] ?: error("Stato di elemento sconosciuto nel diario."),
        mediaId = MediaId(mediaId),
        versionId = VersionId(versionId),
        size = size,
        phone = if (volume != null && msId != null && msVersion != null) PhoneRef(volume, msId, msVersion) else null,
        expected = fileStamp(expectedSize, expectedModifiedAt, expectedGeneration),
        share = share,
        planned = SharePath.parse(plannedPath),
        finalPath = finalPath?.let { SharePath.parse(it) },
        nameAttempt = nameAttempt,
        temp = tempPath?.let { SharePath.parse(it) },
        resumeAt = resumeAt,
        resumeId = resumeFileId,
        sha256 = sha256?.let { Sha256(it) },
        quickHash = quickHash?.let { QuickHash(it) },
        tempStamp = nasStamp(tempSize, tempModifiedAt, tempFileId),
        finalStamp = nasStamp(finalSize, finalModifiedAt, finalFileId),
        verifyAttempts = verifyAttempts,
        guardSource = if (gShare != null && gPath != null) {
          GuardSource(gShare, SharePath.parse(gPath), nasStamp(guardSize, guardModifiedAt, guardFileId))
        } else {
          null
        },
        // Un codice che questa versione non conosce (scritto da una piu' nuova) non e' un problema di questa.
        problem = problem?.let { PROBLEMS[it] },
        // P14: una versione senza attuale da spostare ha il cammino `VERSION_DIRECT` (colonna `current_version_id`).
        archivesCurrent = this.kind != UploadKind.VERSION.code || currentVersionId != null,
      )
    }

    fun BackupItemEntity.withVersionColumnsOf(stored: BackupItemEntity): BackupItemEntity = copy(
      versionTarget = stored.versionTarget,
      currentVersionId = stored.currentVersionId,
      currentLocationId = stored.currentLocationId,
      currentSize = stored.currentSize,
      currentModifiedAt = stored.currentModifiedAt,
      currentFileId = stored.currentFileId,
      currentOriginal = stored.currentOriginal,
      archivePath = stored.archivePath,
      archiveAttempt = stored.archiveAttempt,
      archivedSize = stored.archivedSize,
      archivedModifiedAt = stored.archivedModifiedAt,
      archivedFileId = stored.archivedFileId,
      hold = stored.hold,
      sourceLocationId = stored.sourceLocationId,
      stagedSha256 = stored.stagedSha256,
    )

    fun nasStamp(size: Long?, modifiedAt: Long?, fileId: Long?): NasStamp? =
      if (size != null && modifiedAt != null) NasStamp(size, modifiedAt, fileId) else null

    fun fileStamp(size: Long?, modifiedAt: Long?, generation: Long?): FileStamp? =
      if (size != null && modifiedAt != null) FileStamp(size, modifiedAt, generation) else null
  }
}
