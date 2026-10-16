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
 * servono solo a chi guarda il diario, nessuna decisione li usa.
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
      require(before.temp == null || before.temp == item.temp) { "Il temporaneo di un elemento non cambia una volta scritto." }
      check(UploadStateMachine.canTransition(before.flow, before.state, item.state)) {
        "Transizione non ammessa: ${before.state.code} -> ${item.state.code} (${before.flow.code})."
      }
      dao.update(item.toEntity(createdAt = stored.createdAt, updatedAt = clock.nasNowMs() ?: stored.updatedAt))
    }
  }

  override suspend fun openFor(versionId: VersionId): BackupItem? = dao.openForVersion(versionId.value).firstOrNull()?.toItem()

  override suspend fun citedTemps(): Set<SharePath> = dao.citedTemps().mapTo(LinkedHashSet()) { SharePath.parse(it) }

  override suspend fun counts(): BackupDiaryCounts = database.transaction {
    val byState = dao.countsByState().mapNotNull { row -> STATES[row.status]?.let { it to row.count } }.toMap()
    BackupDiaryCounts(byState, dao.openBytes())
  }

  private companion object {
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
      )
    }

    fun nasStamp(size: Long?, modifiedAt: Long?, fileId: Long?): NasStamp? =
      if (size != null && modifiedAt != null) NasStamp(size, modifiedAt, fileId) else null

    fun fileStamp(size: Long?, modifiedAt: Long?, generation: Long?): FileStamp? =
      if (size != null && modifiedAt != null) FileStamp(size, modifiedAt, generation) else null
  }
}
