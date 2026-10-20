package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario delle versioni su Room, **nella stessa tabella del diario del backup** (`backup_item`, schema v4), con i tipi `version` e `swap`
 * che P1 ha gia' previsto: cosi' i lavori aperti sono citati dalla pulizia dei propri file (D33, `BackupStore.citedTemps`), il caricamento
 * della 5A non crea una copia nuova di una versione che ha un lavoro aperto (`BackupStore.openFor`) e i conteggi della pagina del backup li
 * vedono. Le stesse verifiche del diario in memoria dei test: transizioni di `UploadStateMachine`, identita' e temporaneo che non cambiano,
 * cammino che non cambia, `create` idempotente per versione e tipo. Ogni scrittura e' una transazione; gli istanti sono l'ora del NAS.
 *
 * **Le colonne.** Quelle del caricamento valgono com'e' (telefono, timbro atteso, share, `planned_path` = solito percorso, nome finale,
 * temporaneo e punto di ripresa, impronte, timbri del temporaneo e del finale, tentativi, `problem` = il problema del lavoro); `guard_path` e
 * il timbro `guard_*` sono la versione da riportare di uno scambio (in `Versioni/`). Le colonne delle versioni (P14, schema v4 prima del
 * rilascio: niente migrazione) tengono il resto: la destinazione (`version_target`), l'attuale da spostare (`current_*`: se manca il cammino
 * e' `VERSION_DIRECT`), il nome in `Versioni/` con i tentativi e il timbro dopo l'archiviazione (`archive_*`, `archived_*`), la ragione di
 * un'attesa (`hold`), la posizione della versione da riportare (`source_location_id`) e lo SHA-256 di un file preparato (`staged_sha256`).
 * Prima del P14 queste stavano in una nota nella colonna `problem`.
 */
class RoomVersionJournal(private val database: GalleryDatabase, private val clock: BackupClock) : VersionJournal {

  private val dao get() = database.backupDiaryDao()

  override suspend fun create(job: NewVersionJob): VersionJob = database.transaction {
    dao.openForVersion(job.versionId.value).mapNotNull { it.toJob() }.firstOrNull { it.kind == job.kind } ?: run {
      val now = clock.nasNowMs() ?: 0L
      val entity = entityOf(
        id = 0,
        kind = job.kind,
        state = UploadState.PLANNED,
        mediaId = job.mediaId,
        versionId = job.versionId,
        size = job.size,
        share = job.share,
        usualPath = job.usualPath,
        source = job.source,
        extras = Extras(target = job.target, current = job.current),
        sha256 = job.sha256,
        createdAt = now,
        updatedAt = now,
      )
      val id = dao.insert(entity)
      checkNotNull(dao.get(id)?.toJob()) { "Lavoro appena scritto non riletto." }
    }
  }

  override suspend fun job(id: VersionJobId): VersionJob? = dao.get(id.value)?.toJob()

  override suspend fun open(limit: Int): List<VersionJob> {
    require(limit > 0) { "Limite non positivo." }
    return dao.itemsToResume(OPEN_SCAN).mapNotNull { it.toJob() }.take(limit)
  }

  override suspend fun update(job: VersionJob) {
    database.transaction {
      val stored = dao.get(job.id.value) ?: throw IllegalArgumentException("Lavoro sconosciuto.")
      val before = stored.toJob() ?: throw IllegalArgumentException("Non e' un lavoro delle versioni.")
      require(
        before.kind == job.kind && before.mediaId == job.mediaId && before.versionId == job.versionId && before.share == job.share &&
          before.usualPath == job.usualPath && before.size == job.size,
      ) { "L'identita' di un lavoro non cambia." }
      require(before.temp == null || before.temp == job.temp) { "Il temporaneo di un lavoro non cambia una volta scritto." }
      require(before.flow == job.flow) { "Il cammino di un lavoro non cambia." }
      require(before.source::class == job.source::class) { "La sorgente di un lavoro non cambia tipo." }
      check(UploadStateMachine.canTransition(before.flow, before.state, job.state)) {
        "Transizione non ammessa: ${before.state.code} -> ${job.state.code} (${before.flow.code})."
      }
      dao.update(entityOf(job, createdAt = stored.createdAt, updatedAt = clock.nasNowMs() ?: stored.updatedAt))
    }
  }

  /** Un elemento aperto qualunque per la versione: anche un caricamento della 5A (stessa tabella). */
  override suspend fun busy(versionId: VersionId): Boolean = dao.openForVersion(versionId.value).isNotEmpty()

  override suspend fun forMedia(mediaId: MediaId): List<VersionJob> = dao.forMedia(mediaId.value).mapNotNull { it.toJob() }

  /** P14: una query del DAO per tipo e share (dall'indice `kind`, `item_id`). */
  override suspend fun inShare(share: String): List<VersionJob> =
    dao.ofKindsInShare(KINDS.keys.toList(), share).mapNotNull { it.toJob() }

  /** R17: solo i lavori aggiornati da [sinceNasMs] in poi (la colonna `updated_at` e' l'ora del NAS dell'ultimo passo). */
  override suspend fun inShareSince(share: String, sinceNasMs: Long): List<VersionJob> =
    dao.ofKindsInShare(KINDS.keys.toList(), share).filter { it.updatedAt >= sinceNasMs }.mapNotNull { it.toJob() }

  override suspend fun citedTemps(): Set<SharePath> = open(OPEN_SCAN).mapNotNullTo(LinkedHashSet()) { it.temp }

  // --- Righe ------------------------------------------------------------------------------------------------------------------

  /** Le colonne delle versioni (P14). */
  private data class Extras(
    val target: VersionTarget = VersionTarget.USUAL_PATH,
    val current: CurrentFile? = null,
    val archivePath: SharePath? = null,
    val archiveAttempt: Int = 0,
    val archivedStamp: NasStamp? = null,
    val hold: VersionHold? = null,
    val problem: BackupProblemCode? = null,
  )

  private fun entityOf(job: VersionJob, createdAt: Long, updatedAt: Long): BackupItemEntity = entityOf(
    id = job.id.value,
    kind = job.kind,
    state = job.state,
    mediaId = job.mediaId,
    versionId = job.versionId,
    size = job.size,
    share = job.share,
    usualPath = job.usualPath,
    source = job.source,
    extras = Extras(
      target = job.target,
      current = job.current,
      archivePath = job.archivePath,
      archiveAttempt = job.archiveAttempt,
      archivedStamp = job.archivedStamp,
      hold = job.hold,
      problem = job.problem,
    ),
    sha256 = job.sha256,
    quickHash = job.quickHash,
    finalPath = job.finalPath,
    nameAttempt = job.nameAttempt,
    temp = job.temp,
    resumeAt = job.resumeAt,
    resumeId = job.resumeId,
    tempStamp = job.tempStamp,
    finalStamp = job.finalStamp,
    verifyAttempts = job.verifyAttempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  @Suppress("LongParameterList")
  private fun entityOf(
    id: Long,
    kind: UploadKind,
    state: UploadState,
    mediaId: MediaId,
    versionId: VersionId,
    size: Long,
    share: String,
    usualPath: SharePath,
    source: VersionSource,
    extras: Extras,
    sha256: Sha256?,
    createdAt: Long,
    updatedAt: Long,
    quickHash: QuickHash? = null,
    finalPath: SharePath? = null,
    nameAttempt: Int = 0,
    temp: SharePath? = null,
    resumeAt: Long = 0,
    resumeId: Long? = null,
    tempStamp: NasStamp? = null,
    finalStamp: NasStamp? = null,
    verifyAttempts: Int = 0,
  ): BackupItemEntity {
    val phone = source as? VersionSource.Phone
    val back = source as? VersionSource.InVersions
    val current = extras.current
    return BackupItemEntity(
      itemId = id,
      kind = kind.code,
      state = state.code,
      isOpen = !state.terminal,
      mediaId = mediaId.value,
      versionId = versionId.value,
      size = size,
      phoneVolume = phone?.ref?.volume,
      phoneMediaStoreId = phone?.ref?.mediaStoreId,
      phoneMediaStoreVersion = phone?.ref?.mediaStoreVersion,
      expectedSize = phone?.expected?.size,
      expectedModifiedAt = phone?.expected?.modifiedAtMillis,
      expectedGeneration = phone?.expected?.generation,
      share = share,
      plannedPath = usualPath.value,
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
      guardShare = null,
      guardPath = back?.path?.value,
      guardSize = back?.stamp?.size,
      guardModifiedAt = back?.stamp?.modifiedAtMillis,
      guardFileId = back?.stamp?.fileId,
      problem = extras.problem?.code,
      createdAt = createdAt,
      updatedAt = updatedAt,
      versionTarget = extras.target.code,
      currentVersionId = current?.versionId?.value,
      currentLocationId = current?.locationId?.value,
      currentSize = current?.stamp?.size,
      currentModifiedAt = current?.stamp?.modifiedAtMillis,
      currentFileId = current?.stamp?.fileId,
      currentOriginal = current?.original,
      archivePath = extras.archivePath?.value,
      archiveAttempt = extras.archiveAttempt,
      archivedSize = extras.archivedStamp?.size,
      archivedModifiedAt = extras.archivedStamp?.modifiedAtMillis,
      archivedFileId = extras.archivedStamp?.fileId,
      hold = extras.hold?.code,
      sourceLocationId = back?.locationId?.value,
      stagedSha256 = (source as? VersionSource.Staged)?.sha256?.hex,
    )
  }

  /** La riga come lavoro delle versioni, o null se e' un elemento del caricamento della 5A (o una riga che questa versione non sa leggere). */
  private fun BackupItemEntity.toJob(): VersionJob? {
    val kind = KINDS[kind] ?: return null
    val state = STATES[state] ?: return null
    val extras = extrasOf(this)
    val volume = phoneVolume
    val msId = phoneMediaStoreId
    val msVersion = phoneMediaStoreVersion
    val gPath = guardPath
    val source: VersionSource = when {
      volume != null && msId != null && msVersion != null ->
        VersionSource.Phone(PhoneRef(volume, msId, msVersion), FileStamp(expectedSize ?: return null, expectedModifiedAt ?: return null, expectedGeneration))
      kind == UploadKind.SWAP && gPath != null ->
        VersionSource.InVersions(SharePath.parse(gPath), nasStamp(guardSize, guardModifiedAt, guardFileId) ?: return null, sourceLocationId?.let(::LocationId))
      else -> VersionSource.Staged(stagedSha256?.let(::Sha256) ?: return null)
    }
    return VersionJob(
      id = VersionJobId(itemId),
      kind = kind,
      state = state,
      mediaId = MediaId(mediaId),
      versionId = VersionId(versionId),
      size = size,
      share = share,
      usualPath = SharePath.parse(plannedPath),
      source = source,
      target = extras.target,
      current = extras.current,
      archivePath = extras.archivePath,
      archiveAttempt = extras.archiveAttempt,
      archivedStamp = extras.archivedStamp,
      temp = tempPath?.let { SharePath.parse(it) },
      resumeAt = resumeAt,
      resumeId = resumeFileId,
      sha256 = sha256?.let { Sha256(it) },
      quickHash = quickHash?.let { QuickHash(it) },
      tempStamp = nasStamp(tempSize, tempModifiedAt, tempFileId),
      finalPath = finalPath?.let { SharePath.parse(it) },
      nameAttempt = nameAttempt,
      finalStamp = nasStamp(finalSize, finalModifiedAt, finalFileId),
      verifyAttempts = verifyAttempts,
      hold = extras.hold,
      problem = extras.problem,
    )
  }

  internal companion object {
    /** Quanti elementi aperti si leggono al massimo per trovare i lavori delle versioni (gli aperti della 5A sono di piu'). */
    private const val OPEN_SCAN: Int = 100_000

    private val KINDS: Map<String, UploadKind> = listOf(UploadKind.VERSION, UploadKind.SWAP).associateBy { it.code }
    private val STATES: Map<String, UploadState> = UploadState.entries.associateBy { it.code }
    private val TARGETS: Map<String, VersionTarget> = VersionTarget.entries.associateBy { it.code }
    private val HOLDS: Map<String, VersionHold> = VersionHold.entries.associateBy { it.code }
    private val PROBLEMS: Map<String, BackupProblemCode> = BackupProblemCode.entries.associateBy { it.code }

    private fun nasStamp(size: Long?, modifiedAt: Long?, fileId: Long?): NasStamp? =
      if (size != null && modifiedAt != null) NasStamp(size, modifiedAt, fileId) else null

    /** Le colonne delle versioni di una riga (un codice che questa versione non conosce non vale: nessuna attesa, nessun problema). */
    private fun extrasOf(e: BackupItemEntity): Extras {
      val currentId = e.currentVersionId
      val currentStamp = nasStamp(e.currentSize, e.currentModifiedAt, e.currentFileId)
      return Extras(
        target = e.versionTarget?.let { TARGETS[it] } ?: VersionTarget.USUAL_PATH,
        current = if (currentId != null && currentStamp != null) {
          CurrentFile(VersionId(currentId), e.currentLocationId?.let(::LocationId), currentStamp, e.currentOriginal == true)
        } else {
          null
        },
        archivePath = e.archivePath?.let { SharePath.parse(it) },
        archiveAttempt = e.archiveAttempt,
        archivedStamp = nasStamp(e.archivedSize, e.archivedModifiedAt, e.archivedFileId),
        hold = e.hold?.let { HOLDS[it] },
        problem = e.problem?.let { PROBLEMS[it] },
      )
    }
  }
}
