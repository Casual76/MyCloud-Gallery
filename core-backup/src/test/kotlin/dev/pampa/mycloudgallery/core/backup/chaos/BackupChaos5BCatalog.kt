package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
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
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCursor
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceMedia
import dev.pampa.mycloudgallery.core.backup.versions.InMemoryVersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.NasVersionCopy
import dev.pampa.mycloudgallery.core.backup.versions.PhoneVersionCopy
import dev.pampa.mycloudgallery.core.backup.versions.VersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.VersionFacts
import dev.pampa.mycloudgallery.core.backup.versions.VersionedMedia
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Il catalogo del banco della 5B (P13): UNO SOLO per le tre porte che nella vita vera sono lo stesso `MediaCatalog` (P14 le lega tutte li'):
// le versioni ([VersionCatalog]), "Libera spazio" ([FreeSpaceCatalog]) e il guardiano ([BackupCatalog]). Sotto c'e' il catalogo delle versioni
// di P8 (`InMemoryVersionCatalog`, provato dai suoi test): questa classe gli aggiunge le due viste che non aveva e due cose che il catalogo
// vero fa e quello di P8 no: una copia del telefono che Android ha tolto (o che e' sparita) non e' piu' presente, e una rilettura che non torna
// toglie la verifica alla versione. Sopravvive alla "morte del processo": e' il database.
//
// Le decisioni di "al sicuro" nascono qui solo da una rilettura fatta dal motore (`attachVerifiedCopy`, `markVerified`): mai da un dato del
// NAS (E-S11). Tutto cio' che e' del banco (nomi, giorni, ordine) sta in [Meta].

/** I dati di un media che il catalogo delle versioni non tiene: nome, giorno, ordine nella Libreria, tipo, cartella sul telefono. */
class MediaMeta(
  val name: String,
  val localDay: Int?,
  val sortTime: Long,
  val mime: String?,
  val relativePath: String = "DCIM/Camera/",
)

class ChaosMediaCatalog(
  val privateShare: String,
  val commonShare: String,
) : VersionCatalog, FreeSpaceCatalog, BackupCatalog {

  /** Il catalogo delle versioni di P8: la preparazione e gli eventi "da fuori" (la lettura del telefono, la scansione) si fanno li'. */
  val inner = InMemoryVersionCatalog(mapOf(privateShare to NasArea.PRIVATE, commonShare to NasArea.COMMON))

  private val metas = HashMap<MediaId, MediaMeta>()

  /** Le copie del telefono che non ci sono piu': tolte da Android (il dialogo) o sparite. */
  private val absentPhone = LinkedHashSet<LocationId>()

  fun setMeta(mediaId: MediaId, meta: MediaMeta) {
    metas[mediaId] = meta
  }

  fun metaOf(mediaId: MediaId): MediaMeta? = metas[mediaId]

  fun allMedia(): List<MediaId> = inner.allMedia()

  /** La copia del telefono non c'e' piu' (cancellata dall'utente, E-B4): niente piu' "sul telefono". */
  fun phoneVanished(location: LocationId) {
    absentPhone += location
  }

  fun isPhoneAbsent(location: LocationId): Boolean = location in absentPhone

  // --- VersionCatalog -----------------------------------------------------------------------------------------------------------

  /** Il media com'e' adesso, con le copie del telefono che Android ha tolto segnate non presenti. */
  override suspend fun media(mediaId: MediaId): VersionedMedia? {
    val m = inner.media(mediaId) ?: return null
    if (absentPhone.isEmpty()) return m
    return VersionedMedia(
      mediaId = m.mediaId,
      mine = m.mine,
      kind = m.kind,
      mime = m.mime,
      currentVersionId = m.currentVersionId,
      versions = m.versions.map { v ->
        VersionFacts(
          versionId = v.versionId,
          kind = v.kind,
          parentId = v.parentId,
          size = v.size,
          fullHash = v.fullHash,
          quickHash = v.quickHash,
          nas = v.nas,
          phone = v.phone.map { p -> if (p.locationId in absentPhone) PhoneVersionCopy(p.locationId, p.ref, p.stamp, present = false) else p },
        )
      },
    )
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord = inner.recordFullHash(mediaId, versionId, sha256)

  override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean = inner.attachVerifiedCopy(copy)

  override suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp) =
    inner.relocate(locationId, share, path, stamp)

  override suspend fun restoreOriginal(mediaId: MediaId) = inner.restoreOriginal(mediaId)

  override suspend fun correct(
    mediaId: MediaId,
    fields: Set<CorrectionField>,
    size: Long,
    quickHash: QuickHash,
    sha256: Sha256,
  ): VersionId? = inner.correct(mediaId, fields, size, quickHash, sha256)

  override suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean =
    inner.correctInApp(mediaId, versionId, change)

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) = inner.markProblem(mediaId, problem)

  // --- FreeSpaceCatalog ---------------------------------------------------------------------------------------------------------

  override suspend fun phoneMedia(after: FreeSpaceCursor?, limit: Int): List<FreeSpaceMedia> =
    inner.allMedia().mapNotNull { freeMediaOf(it) }
      .filter { fm -> fm.media.locations.any { it.isPhone && it.isPresent } }
      .sortedWith(compareByDescending<FreeSpaceMedia> { it.sortTime }.thenByDescending { it.id })
      .filter { fm -> after == null || fm.sortTime < after.sortTime || (fm.sortTime == after.sortTime && fm.id < after.mediaId) }
      .take(limit)

  override suspend fun media(ids: Collection<MediaId>): List<FreeSpaceMedia> = ids.mapNotNull { freeMediaOf(it) }

  /**
   * L'esito della rilettura completa di una copia (come `MediaCatalog.markVerified`): lo SHA-256 letto dev'essere quello della versione.
   * Uguale = verificata adesso; diverso = la versione non e' piu' "al sicuro" (le sue copie perdono la verifica, E-B8).
   */
  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean {
    for (id in inner.allMedia()) {
      val media = inner.media(id) ?: continue
      for (version in media.versions) {
        val copy = version.nas.firstOrNull { it.locationId == location } ?: continue
        if (version.fullHash != null && version.fullHash == readBack) {
          return inner.attachVerifiedCopy(VerifiedCopy(id, version.versionId, copy.share, copy.path, copy.stamp, atMillis))
        }
        inner.unverify(id, version.versionId)
        return false
      }
    }
    return false
  }

  override suspend fun freedFromPhone(locations: List<LocationId>) {
    absentPhone += locations
  }

  // --- BackupCatalog (la parte che serve al guardiano; la 5B non pianifica caricamenti) ------------------------------------------

  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> = emptyList()

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts = SafetyCounts.ZERO

  override suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef? =
    media(mediaId)?.version(versionId)?.phone?.firstOrNull { it.present }?.ref

  override suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow =
    phoneRefOf(mediaId, versionId)?.let { PhoneRowNow.At(it) } ?: PhoneRowNow.Absent

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = copies.count { inner.attachVerifiedCopy(it) }

  override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> {
    if (!share.equals(privateShare, ignoreCase = true)) return emptyList()
    val out = ArrayList<NasCopyRef>()
    for (id in inner.allMedia()) {
      val media = inner.media(id) ?: continue
      for (v in media.versions) for (c in v.nas) {
        if (c.present && c.share.equals(privateShare, ignoreCase = true) && c.stamp.size == size) {
          out += NasCopyRef(c.locationId, id, v.versionId, c.share, c.path, c.stamp)
        }
      }
    }
    return out.take(limit)
  }

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> = emptyList()

  override suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate> {
    val out = ArrayList<GuardCandidate>()
    for (id in inner.allMedia()) {
      if (scope is GuardScope.Media && id !in scope.ids) continue
      val media = media(id) ?: continue
      if (!media.mine) continue
      val all = media.versions.flatMap { v -> v.nas.filter { it.present }.map { v to it } }
      if (all.any { it.second.share.equals(privateShare, ignoreCase = true) }) continue
      val (version, copy) = all.firstOrNull { it.second.share.equals(commonShare, ignoreCase = true) } ?: continue
      val meta = metas[id]
      out += GuardCandidate(
        NasCopyRef(copy.locationId, id, version.versionId, copy.share, copy.path, copy.stamp),
        version.size, version.fullHash, version.quickHash, meta?.localDay, meta?.name ?: copy.path.name,
      )
    }
    return out.take(limit)
  }

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? {
    val media = media(mediaId) ?: return null
    val problem = inner.problems[mediaId]
    if (!media.mine) return MediaSafety(SafetyStatus.NOT_MINE, null, problem)
    val own = media.versions.flatMap { v -> v.nas.filter { it.present && it.share.equals(privateShare, ignoreCase = true) }.map { v to it } }
    val verified = media.current.nas.filter { it.present && it.share.equals(privateShare, ignoreCase = true) && it.verifiedAtMillis != null }
    val status = when {
      verified.isNotEmpty() -> SafetyStatus.SAFE
      own.isNotEmpty() -> SafetyStatus.PRIVATE_COPY_UNVERIFIED
      media.versions.any { v -> v.nas.any { it.present } } -> SafetyStatus.ONLY_COMMON_COPY
      media.versions.any { v -> v.phone.any { it.present } } -> SafetyStatus.NO_NAS_COPY
      else -> SafetyStatus.LOST
    }
    return MediaSafety(status, verified.mapNotNull { it.verifiedAtMillis }.maxOrNull(), problem)
  }

  // --- Il grafo del catalogo vero (`core.model.Media`) per "Libera spazio" ----------------------------------------------------

  private suspend fun freeMediaOf(id: MediaId): FreeSpaceMedia? {
    val vm = media(id) ?: return null
    val meta = metas[id] ?: MediaMeta("sconosciuto.jpg", null, 0, vm.mime)
    val me = AccountId(privateShare)
    val versions = vm.versions.map { v ->
      MediaVersion(
        id = v.versionId,
        kind = v.kind,
        parentId = v.parentId,
        fingerprint = Fingerprint(v.size, v.quickHash ?: NO_QUICK, v.fullHash),
        createdAtMillis = 0,
        corrections = if (v.kind == VersionKind.CORRECTED) setOf(CorrectionField.DATE) else emptySet(),
      )
    }
    val locations = ArrayList<MediaLocation>()
    for (v in vm.versions) {
      for (p in v.phone) {
        locations += MediaLocation(
          id = p.locationId,
          versionId = v.versionId,
          location = Location.Phone(p.ref.volume, meta.relativePath, meta.name, p.ref.mediaStoreId, p.ref.mediaStoreVersion),
          stamp = p.stamp,
          state = if (p.present) LocationState.PRESENT else LocationState.ABSENT,
          absentSinceMillis = if (p.present) null else 0L,
          lastSeenAtMillis = 0,
        )
      }
      for (n in v.nas) {
        locations += MediaLocation(
          id = n.locationId,
          versionId = v.versionId,
          location = Location.Nas(me, n.share, NasPath.of(n.path.value), n.stamp.fileId),
          stamp = FileStamp(n.stamp.size, n.stamp.modifiedAtMillis),
          state = if (n.present) LocationState.PRESENT else LocationState.ABSENT,
          absentSinceMillis = if (n.present) null else 0L,
          verifiedAtMillis = n.verifiedAtMillis,
          lastSeenAtMillis = 0,
        )
      }
    }
    // Un posto contiene un solo file: se per un istante due posizioni stanno nello stesso posto (un giro fermato a meta' di uno scambio) vale
    // quella presente. Il verificatore (invariante 7) guarda se il catalogo ha davvero due posizioni nello stesso posto.
    val unique = locations.sortedByDescending { it.isPresent }.distinctBy { it.location.key }
    return FreeSpaceMedia(
      media = Media(
        id = id,
        kind = vm.kind,
        source = MediaSource.CAMERA,
        owner = if (vm.mine) me else null,
        createdAtMillis = 0,
        currentVersionId = vm.currentVersionId,
        versions = versions,
        locations = unique,
      ),
      localDay = meta.localDay,
      sortTime = meta.sortTime,
      problem = inner.problems[id],
      mime = vm.mime,
    )
  }

  private companion object {
    /** L'hash rapido non conta per "Libera spazio" (decide l'hash completo): uno qualunque, valido. */
    val NO_QUICK = QuickHash("0".repeat(64))
  }
}
