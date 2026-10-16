package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.GuardCandidate
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.MediaSafety
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.upload.UploadDescription
import dev.pampa.mycloudgallery.core.backup.upload.UploadFacts
import dev.pampa.mycloudgallery.core.backup.upload.UploadRecords
import dev.pampa.mycloudgallery.core.database.AttachedCopy
import dev.pampa.mycloudgallery.core.database.CatalogBackupCandidate
import dev.pampa.mycloudgallery.core.database.CatalogBackupKind
import dev.pampa.mycloudgallery.core.database.CatalogCandidateCursor
import dev.pampa.mycloudgallery.core.database.CatalogNasCopy
import dev.pampa.mycloudgallery.core.database.CatalogSource
import dev.pampa.mycloudgallery.core.database.CatalogVerifyScope
import dev.pampa.mycloudgallery.core.database.FullHashRecord
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.trust.data.GeoE7
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.math.roundToInt

// Le PORTE del backup sopra il catalogo (Fase 5A, P7; docs/fase5-progetto.md §1.2, §1.4): solo TRADUZIONI di tipi, campo per campo. Le
// decisioni restano nel catalogo (P1) e nei moduli del backup (P2, P3, P4): "al sicuro" nasce solo da `attachVerifiedCopies` e `markVerified`,
// cioe' da una rilettura fatta qui (F4-D8, L28). Nessun nome, percorso, share o id nei log.

/**
 * [BackupCatalog] sul catalogo vero (`MediaCatalog`). La tabella delle traduzioni e' quella del report di P1: `candidates` ←
 * `backupCandidates`, `attachVerifiedCopies` ← `attachCopies`, `markProblem(code?.code, code?.marksMedia)`, `onlyCommonCopies(ids?, limit)`.
 *
 * **`phoneRowOf` con la ricetta della porta** (F1 di docs/prova-500.md §B6): se il volume ha cambiato versione del MediaStore, l'elemento
 * ritrova la sua riga dopo la rilettura del telefono; `Absent` solo se la lettura completa del volume e' finita a una versione diversa e
 * la versione non ha piu' una riga presente. Senza questa traduzione il difetto F1 resterebbe in produzione (default prudente: aspetta).
 *
 * @param account l'account NAS di questo dispositivo, proprietario dei media e delle copie (`MediaCatalog.localAccount`).
 */
class CatalogBackupCatalog(
  private val catalog: MediaCatalog,
  private val account: () -> AccountId?,
) : BackupCatalog {

  // --- Piano (P4) ---------------------------------------------------------------------------------------------------------------

  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> {
    val sources = catalogSources(query.sources)
    if (sources.isEmpty()) return emptyList()
    val after = query.after?.let { CatalogCandidateCursor(it.sortTime, it.mediaId) }
    return catalog.backupCandidates(sources, query.kind.toCatalog(), after, query.limit).map { it.toCandidate() }
  }

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts {
    val chosen = catalogSources(sources)
    if (chosen.isEmpty()) return SafetyCounts.ZERO
    val counts = catalog.safetyCounts(chosen)
    val problems = HashMap<BackupProblemCode, Int>()
    for ((code, n) in counts.problems) problemOf(code)?.let { problems.merge(it, n, Int::plus) }
    return SafetyCounts(counts.safe, counts.unverified, counts.onlyCommonCopy, counts.noNasCopy, counts.lost, problems)
  }

  // --- Caricamento (P2) ---------------------------------------------------------------------------------------------------------

  override suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef? =
    catalog.phoneRefOf(mediaId, versionId)?.let { PhoneRef(it.volume, it.mediaStoreId, it.mediaStoreVersion) }

  override suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow {
    // Prima il cursore, poi la riga: se il cursore dice "riletto" e la riga manca DOPO, e' davvero assente (un'osservazione arrivata
    // fra le due letture la troverebbe presente, mai il contrario).
    val cursor = catalog.mediaStoreCursor(stale.volume)
    val reread = cursor != null &&
      cursor.msVersion != null && cursor.msVersion != stale.mediaStoreVersion &&
      cursor.scanEpoch == null && cursor.generation != null
    val ref = phoneRefOf(mediaId, versionId)
    return when {
      // Anche la stessa riga di prima: il catalogo non ha ancora visto il cambio di versione, l'elemento aspetta (lo decide il motore).
      ref != null -> PhoneRowNow.At(ref)
      reread -> PhoneRowNow.Absent
      else -> PhoneRowNow.Rereading
    }
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    when (catalog.recordFullHash(mediaId, versionId, sha256)) {
      FullHashRecord.RECORDED -> HashRecord.RECORDED
      FullHashRecord.SAME -> HashRecord.SAME
      FullHashRecord.CONFLICT -> HashRecord.CONFLICT
      FullHashRecord.UNKNOWN_VERSION -> HashRecord.UNKNOWN_VERSION
    }

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int {
    if (copies.isEmpty()) return 0
    val owner = account() ?: return 0
    val attached = copies.mapNotNull { copy ->
      val path = runCatching { NasPath.of(copy.path.value) }.getOrNull() ?: return@mapNotNull null
      AttachedCopy(
        mediaId = copy.mediaId,
        versionId = copy.versionId,
        location = Location.Nas(owner, copy.share, path, copy.stamp.fileId),
        stamp = FileStamp(copy.stamp.size, copy.stamp.modifiedAtMillis, null),
        verifiedAtMillis = copy.verifiedAtMillis,
      )
    }
    return catalog.attachCopies(attached)
  }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    catalog.markBackupProblem(mediaId, problem?.code, problem?.marksMedia ?: true)
  }

  // --- Deduplica, verifiche, guardiano (P3) -------------------------------------------------------------------------------------

  override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> =
    catalog.privateCopies(share, size, quickHash, limit).mapNotNull { it.toRef() }

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> {
    val catalogScope = when (scope) {
      VerifyScope.Unverified -> CatalogVerifyScope.Unverified
      VerifyScope.FullHash -> CatalogVerifyScope.FullHash
      VerifyScope.Rotation -> CatalogVerifyScope.Rotation
      is VerifyScope.OnTheSpot -> CatalogVerifyScope.OnTheSpot(scope.media)
    }
    return catalog.verifyCandidates(catalogScope, limit).mapNotNull { c ->
      val ref = c.copy.toRef() ?: return@mapNotNull null
      CopyToVerify(ref, c.size, c.expected, c.verifiedAtMillis)
    }
  }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    catalog.markVerified(location, readBack, atMillis)

  override suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate> {
    val ids = when (scope) {
      GuardScope.All -> null
      is GuardScope.Media -> scope.ids
    }
    return catalog.onlyCommonCopies(ids, limit).mapNotNull { c ->
      val ref = c.common.toRef() ?: return@mapNotNull null
      GuardCandidate(ref, c.size, c.fullHash, c.quickHash, c.localDay, c.fileName)
    }
  }

  // --- Stato (P5, P7) -----------------------------------------------------------------------------------------------------------

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? =
    catalog.mediaSafety(mediaId)?.let { MediaSafety(it.status, it.verifiedAtMillis, it.problem?.let(::problemOf)) }

  // --- Traduzioni ---------------------------------------------------------------------------------------------------------------

  private fun CatalogBackupCandidate.toCandidate(): BackupCandidate = BackupCandidate(
    mediaId = mediaId,
    versionId = versionId,
    phone = PhoneRef(phone.volume, phone.mediaStoreId, phone.mediaStoreVersion),
    stamp = stamp,
    size = size,
    kind = kind,
    source = source.toBackup(),
    localDay = localDay,
    dateEstimated = dateEstimated,
    fileName = fileName,
    sortTime = sortTime,
    quickHash = quickHash,
    original = original,
  )

  private fun CatalogNasCopy.toRef(): NasCopyRef? {
    val sharePath = runCatching { SharePath.parseMediaPath(path.value) }.getOrNull() ?: return null
    return NasCopyRef(locationId, mediaId, versionId, share, sharePath, stamp)
  }

  private fun BackupKind.toCatalog(): CatalogBackupKind = when (this) {
    BackupKind.PHOTOS -> CatalogBackupKind.PHOTOS
    BackupKind.VIDEOS -> CatalogBackupKind.VIDEOS
  }

  private fun CatalogSource.toBackup(): BackupSource = when (this) {
    CatalogSource.Camera -> BackupSource.Camera
    CatalogSource.Screenshots -> BackupSource.Screenshots
    is CatalogSource.Folder -> BackupSource.Folder(volume, relativePath)
  }

  companion object {
    /** Le sorgenti del backup come le chiede il catalogo. */
    fun catalogSources(sources: BackupSources): Set<CatalogSource> = sources.sources.mapTo(LinkedHashSet()) { source ->
      when (source) {
        BackupSource.Camera -> CatalogSource.Camera
        BackupSource.Screenshots -> CatalogSource.Screenshots
        is BackupSource.Folder -> CatalogSource.Folder(source.volume, source.relativePath)
      }
    }

    fun problemOf(code: String): BackupProblemCode? = BackupProblemCode.entries.firstOrNull { it.code == code }
  }
}

/** Il tipo e il MIME di una versione dal catalogo (il giro delle foto non riprende un video, D11; la preparazione sa che cosa decodificare). */
class CatalogUploadFacts(private val catalog: MediaCatalog) : UploadFacts {
  override suspend fun describe(mediaId: MediaId, versionId: VersionId): UploadDescription? {
    val media = catalog.media(mediaId) ?: return null
    val mime = catalog.versionMetadata(versionId)?.mimeType
    return UploadDescription(media.kind, mime)
  }
}

/**
 * Il record `meta` di base di una versione appena pubblicata, **dal catalogo locale** (D10: mai da un record remoto): timbro e impronte
 * sono quelli del file pubblicato (letti dal motore dopo la pubblicazione), il resto (tipo, data, dimensioni gia' orientate, luogo,
 * segnaposto) viene da cio' che il catalogo sa della versione. Null se la versione non c'e' piu' o ha un'altra dimensione.
 */
class CatalogUploadRecords(private val catalog: MediaCatalog) : UploadRecords {

  override suspend fun record(mediaId: MediaId, versionId: VersionId, stamp: NasStamp, quickHash: QuickHash, fullHash: Sha256): MetaFile? {
    val media = catalog.media(mediaId) ?: return null
    val version = media.versions.firstOrNull { it.id == versionId } ?: return null
    if (version.fingerprint.size != stamp.size) return null
    val meta = catalog.versionMetadata(versionId)
    val swap = meta != null && (meta.orientationDegrees == 90 || meta.orientationDegrees == 270)
    val width = (if (swap) meta?.height else meta?.width)?.takeIf { it > 0 }
    val height = (if (swap) meta?.width else meta?.height)?.takeIf { it > 0 }
    fun build(mime: String?) = MetaFile(
      stamp = stamp,
      quickHash = quickHash,
      fullHash = fullHash,
      mediaId = media.id,
      versionId = version.id,
      parentVersionId = version.parentId,
      versionKind = version.kind,
      kind = media.kind,
      source = media.source,
      mime = mime,
      capture = meta?.captureTime,
      width = width,
      height = height,
      durationMs = meta?.durationMillis?.takeIf { it >= 0 },
      traits = MediaTrait.toBits(meta?.traits.orEmpty()),
      geo = meta?.location?.let { GeoE7((it.latitude * 1e7).roundToInt(), (it.longitude * 1e7).roundToInt()) },
      thumbHash = meta?.thumbHash,
    )
    // Un MIME che il formato non ammette (stringa strana da un'estrazione) non deve fermare la pubblicazione dell'indice.
    return runCatching { build(meta?.mimeType) }.getOrElse { runCatching { build(null) }.getOrNull() }
  }
}
