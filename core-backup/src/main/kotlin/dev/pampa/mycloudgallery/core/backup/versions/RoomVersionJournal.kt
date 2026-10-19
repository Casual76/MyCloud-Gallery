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
 * temporaneo e punto di ripresa, impronte, timbri del temporaneo e del finale, tentativi); `guard_path` e il timbro `guard_*` sono la
 * versione da riportare di uno scambio (in `Versioni/`). Cio' che lo schema v4 non ha (l'attuale da spostare, il nome in `Versioni/` e il
 * timbro dopo l'archiviazione, la destinazione, la ragione di un'attesa) sta in una **nota** nella colonna di testo `problem`, con un prefisso
 * che nessun codice di problema ha ([NOTE_PREFIX]): il diario del backup della 5A (`RoomBackupStore`) legge un codice che non conosce come
 * "nessun problema", per costruzione. E' una soluzione **provvisoria**, senza migrazione; le colonne vere sono una richiesta per lo schema
 * (vedi `docs/decisioni.md`, "Fase 5B: P8").
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
        extras = Extras(target = job.target, current = job.current, stagedSha = (job.source as? VersionSource.Staged)?.sha256),
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

  override suspend fun inShare(share: String): List<VersionJob> {
    // Nessuna query del DAO per tipo e share (P1): gli id dei lavori delle versioni si leggono direttamente, poi le righe dal DAO.
    val ids = ArrayList<Long>()
    database.openHelper.readableDatabase.query(SHARE_SQL, arrayOf(share)).use { cursor ->
      while (cursor.moveToNext()) ids += cursor.getLong(0)
    }
    return ids.mapNotNull { dao.get(it)?.toJob() }
  }

  override suspend fun citedTemps(): Set<SharePath> = open(OPEN_SCAN).mapNotNullTo(LinkedHashSet()) { it.temp }

  // --- Righe ------------------------------------------------------------------------------------------------------------------

  /** Cio' che lo schema v4 non ha, nella nota della colonna `problem`. */
  private data class Extras(
    val target: VersionTarget = VersionTarget.USUAL_PATH,
    val current: CurrentFile? = null,
    val archivePath: SharePath? = null,
    val archiveAttempt: Int = 0,
    val archivedStamp: NasStamp? = null,
    val hold: VersionHold? = null,
    val problem: BackupProblemCode? = null,
    val sourceLocation: LocationId? = null,
    val stagedSha: Sha256? = null,
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
      sourceLocation = (job.source as? VersionSource.InVersions)?.locationId,
      stagedSha = (job.source as? VersionSource.Staged)?.sha256,
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
      problem = encode(extras),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
  }

  /** La riga come lavoro delle versioni, o null se e' un elemento del caricamento della 5A (o una riga che questa versione non sa leggere). */
  private fun BackupItemEntity.toJob(): VersionJob? {
    val kind = KINDS[kind] ?: return null
    val state = STATES[state] ?: return null
    val extras = problem?.let { decode(it) } ?: return null
    val volume = phoneVolume
    val msId = phoneMediaStoreId
    val msVersion = phoneMediaStoreVersion
    val gPath = guardPath
    val source: VersionSource = when {
      volume != null && msId != null && msVersion != null ->
        VersionSource.Phone(PhoneRef(volume, msId, msVersion), FileStamp(expectedSize ?: return null, expectedModifiedAt ?: return null, expectedGeneration))
      kind == UploadKind.SWAP && gPath != null ->
        VersionSource.InVersions(SharePath.parse(gPath), nasStamp(guardSize, guardModifiedAt, guardFileId) ?: return null, extras.sourceLocation)
      else -> VersionSource.Staged(extras.stagedSha ?: return null)
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
    /** Il prefisso della nota: nessun codice di `BackupProblemCode` comincia cosi'. */
    const val NOTE_PREFIX: String = "mcg-versioni-1;"

    /** Quanti elementi aperti si leggono al massimo per trovare i lavori delle versioni (gli aperti della 5A sono di piu'). */
    private const val OPEN_SCAN: Int = 100_000

    private const val SHARE_SQL: String =
      "SELECT item_id FROM backup_item WHERE kind IN ('version', 'swap') AND share = ? COLLATE NOCASE ORDER BY item_id"

    private val KINDS: Map<String, UploadKind> = listOf(UploadKind.VERSION, UploadKind.SWAP).associateBy { it.code }
    private val STATES: Map<String, UploadState> = UploadState.entries.associateBy { it.code }
    private val TARGETS: Map<String, VersionTarget> = VersionTarget.entries.associateBy { it.code }
    private val HOLDS: Map<String, VersionHold> = VersionHold.entries.associateBy { it.code }
    private val PROBLEMS: Map<String, BackupProblemCode> = BackupProblemCode.entries.associateBy { it.code }

    private fun nasStamp(size: Long?, modifiedAt: Long?, fileId: Long?): NasStamp? =
      if (size != null && modifiedAt != null) NasStamp(size, modifiedAt, fileId) else null

    /** I tre caratteri della nota (`%`, `;`, `=`) si scrivono come `%25`, `%3B`, `%3D`; tutto il resto resta com'e'. */
    private fun esc(value: String): String = value.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")

    private fun unesc(value: String): String = value.replace("%3D", "=").replace("%3B", ";").replace("%25", "%")

    private fun encode(e: Extras): String {
      val out = LinkedHashMap<String, String>()
      out["t"] = e.target.code
      e.current?.let { c ->
        out["cv"] = c.versionId.value
        c.locationId?.let { out["cl"] = it.value.toString() }
        out["cs"] = c.stamp.size.toString()
        out["cm"] = c.stamp.modifiedAtMillis.toString()
        c.stamp.fileId?.let { out["ci"] = it.toString() }
        out["co"] = if (c.original) "1" else "0"
      }
      e.archivePath?.let { out["ap"] = it.value }
      if (e.archiveAttempt != 0) out["aa"] = e.archiveAttempt.toString()
      e.archivedStamp?.let { s ->
        out["as"] = s.size.toString()
        out["am"] = s.modifiedAtMillis.toString()
        s.fileId?.let { out["ai"] = it.toString() }
      }
      e.hold?.let { out["h"] = it.code }
      e.problem?.let { out["p"] = it.code }
      e.sourceLocation?.let { out["sl"] = it.value.toString() }
      e.stagedSha?.let { out["ss"] = it.hex }
      return NOTE_PREFIX + out.entries.joinToString(";") { "${it.key}=${esc(it.value)}" }
    }

    /** La nota, o null se la colonna non e' una nota delle versioni. */
    private fun decode(text: String): Extras? {
      if (!text.startsWith(NOTE_PREFIX)) return null
      val map = text.removePrefix(NOTE_PREFIX).split(';').filter { it.isNotEmpty() }.associate { pair ->
        val at = pair.indexOf('=')
        pair.substring(0, at) to unesc(pair.substring(at + 1))
      }
      val current = map["cv"]?.let { v ->
        CurrentFile(
          versionId = VersionId(v),
          locationId = map["cl"]?.let { LocationId(it.toLong()) },
          stamp = NasStamp(map.getValue("cs").toLong(), map.getValue("cm").toLong(), map["ci"]?.toLong()),
          original = map["co"] == "1",
        )
      }
      return Extras(
        target = TARGETS[map["t"]] ?: VersionTarget.USUAL_PATH,
        current = current,
        archivePath = map["ap"]?.let { SharePath.parse(it) },
        archiveAttempt = map["aa"]?.toInt() ?: 0,
        archivedStamp = map["as"]?.let { NasStamp(it.toLong(), map.getValue("am").toLong(), map["ai"]?.toLong()) },
        hold = map["h"]?.let { HOLDS[it] },
        problem = map["p"]?.let { PROBLEMS[it] },
        sourceLocation = map["sl"]?.let { LocationId(it.toLong()) },
        stagedSha = map["ss"]?.let { Sha256(it) },
      )
    }
  }
}
