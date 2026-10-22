package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
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
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * Il catalogo finto del backup (Fase 5A, P0): le stesse regole che P1 scrive in `MediaCatalog` e che contano per gli invarianti del
 * §5.4. "Al sicuro" (`SAFE`) se e solo se la versione ha una copia **nell'area privata** [privateShare] attaccata come **verificata**
 * (mai da un dato remoto); una copia verificata si attacca solo con l'hash completo noto e la dimensione giusta (come `attachCopy`).
 * I candidati sono i media propri con la riga del telefono, nelle sorgenti, non al sicuro e senza un problema che chiede l'utente,
 * per `sort_time` decrescente. Sopravvive alla "morte del processo": e' il database.
 */
class InMemoryBackupCatalog(val privateShare: String, val commonShare: String? = null) : BackupCatalog {

  /** Un media (una sola versione da proteggere: quanto basta alla 5A). */
  class Media(
    val mediaId: MediaId,
    val versionId: VersionId,
    val kind: MediaKind,
    val size: Long,
    val quickHash: QuickHash?,
    var phone: PhoneRef?,
    var phoneStamp: FileStamp?,
    val source: BackupSource,
    val localDay: Int?,
    val fileName: String,
    val sortTime: Long,
    var fullHash: Sha256?,
    val mine: Boolean = true,
    /**
     * Campo, gruppo A (D2, Q4 (b)): il media e' stato su questo telefono (come il catalogo vero: una posizione del telefono, anche assente, o un
     * caricamento dal telefono). Falso per un media messo sul NAS dal PC: il guardiano di sottofondo non lo vede.
     */
    val wasOnPhone: Boolean = true,
  ) {
    override fun toString(): String = "Media(${kind.code})"
  }

  /** Una copia sul NAS (posizione del catalogo). */
  class Copy(
    val locationId: LocationId,
    val mediaId: MediaId,
    val versionId: VersionId,
    val share: String,
    val path: SharePath,
    var stamp: NasStamp,
    var verifiedAtMillis: Long?,
  ) {
    override fun toString(): String = "Copy(${stamp.size} B, verified=${verifiedAtMillis != null})"
  }

  private val lock = Any()
  private val media = LinkedHashMap<MediaId, Media>()
  private val copies = ArrayList<Copy>()
  private val problems = HashMap<MediaId, BackupProblemCode>()
  private var nextLocation = 1L

  /** Quante volte e' stato chiamato [attachVerifiedCopies] (i lotti: un cambiamento dell'indice per lotto, §5.5). */
  @Volatile var attachBatches: Int = 0
    private set

  private fun key(share: String) = share.lowercase(Locale.ROOT)

  fun put(m: Media): Media = synchronized(lock) { m.also { media[it.mediaId] = it } }

  fun media(id: MediaId): Media? = synchronized(lock) { media[id] }

  fun allMedia(): List<Media> = synchronized(lock) { media.values.toList() }

  /** Una copia gia' nota al catalogo (dalla scansione del NAS): per la deduplica e le verifiche. */
  fun putCopy(mediaId: MediaId, versionId: VersionId, share: String, path: SharePath, stamp: NasStamp, verifiedAtMillis: Long? = null): Copy =
    synchronized(lock) {
      Copy(LocationId(nextLocation++), mediaId, versionId, share, path, stamp, verifiedAtMillis).also { copies += it }
    }

  fun copiesOf(mediaId: MediaId): List<Copy> = synchronized(lock) { copies.filter { it.mediaId == mediaId } }

  fun allCopies(): List<Copy> = synchronized(lock) { copies.toList() }

  fun problemOf(mediaId: MediaId): BackupProblemCode? = synchronized(lock) { problems[mediaId] }

  /** La lettura del telefono ha ritrovato la riga dopo una rinumerazione (E-P12). */
  fun relinkPhone(mediaId: MediaId, ref: PhoneRef?) {
    synchronized(lock) { media.getValue(mediaId).phone = ref }
  }

  /**
   * La lettura del telefono sta rileggendo il volume dopo un cambio di versione del MediaStore (E-P12) e non ha finito: come il catalogo
   * vero con la scansione completa in corso, [phoneRowOf] risponde [PhoneRowNow.Rereading] per le righe che non conosce (ancora). Falso
   * (di norma): una riga che il catalogo non conosce e' una riga che la lettura ha cercato e non ha trovato ([relinkPhone] con null).
   */
  @Volatile
  var phoneRereading: Boolean = false

  /** "Al sicuro": una copia privata verificata della versione da proteggere. */
  fun isSafe(mediaId: MediaId): Boolean = synchronized(lock) { isSafeLocked(mediaId) }

  private fun isSafeLocked(mediaId: MediaId): Boolean {
    val m = media[mediaId] ?: return false
    return copies.any { it.mediaId == mediaId && it.versionId == m.versionId && key(it.share) == key(privateShare) && it.verifiedAtMillis != null }
  }

  private fun statusLocked(m: Media): SafetyStatus = when {
    !m.mine -> SafetyStatus.NOT_MINE
    isSafeLocked(m.mediaId) -> SafetyStatus.SAFE
    copies.any { it.mediaId == m.mediaId && key(it.share) == key(privateShare) } -> SafetyStatus.PRIVATE_COPY_UNVERIFIED
    copies.any { it.mediaId == m.mediaId } -> SafetyStatus.ONLY_COMMON_COPY
    m.phone == null -> SafetyStatus.LOST
    else -> SafetyStatus.NO_NAS_COPY
  }

  // --- BackupCatalog ------------------------------------------------------------------------------------------------------------

  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> = synchronized(lock) {
    media.values.asSequence()
      .filter { it.mine && it.phone != null && it.phoneStamp != null && it.source in query.sources }
      .filter { (it.kind.isVideo) == (query.kind == dev.pampa.mycloudgallery.core.backup.contract.BackupKind.VIDEOS) }
      .filter { !isSafeLocked(it.mediaId) && problems[it.mediaId]?.marksMedia != true }
      .sortedWith(compareByDescending<Media> { it.sortTime }.thenByDescending { it.mediaId })
      .filter { m ->
        val after = query.after ?: return@filter true
        m.sortTime < after.sortTime || (m.sortTime == after.sortTime && m.mediaId < after.mediaId)
      }
      .take(query.limit)
      .map { m ->
        BackupCandidate(
          mediaId = m.mediaId, versionId = m.versionId, phone = m.phone!!, stamp = m.phoneStamp!!, size = m.size, kind = m.kind,
          source = m.source, localDay = m.localDay, dateEstimated = false, fileName = m.fileName, sortTime = m.sortTime,
          quickHash = m.quickHash,
        )
      }
      .toList()
  }

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts = synchronized(lock) {
    val statuses = media.values.filter { it.source in sources }.map { statusLocked(it) }
    SafetyCounts(
      safe = statuses.count { it == SafetyStatus.SAFE },
      unverified = statuses.count { it == SafetyStatus.PRIVATE_COPY_UNVERIFIED },
      onlyCommonCopy = statuses.count { it == SafetyStatus.ONLY_COMMON_COPY },
      noNasCopy = statuses.count { it == SafetyStatus.NO_NAS_COPY },
      lost = statuses.count { it == SafetyStatus.LOST },
      problems = problems.values.groupingBy { it }.eachCount(),
    )
  }

  override suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef? = synchronized(lock) {
    media[mediaId]?.takeIf { it.versionId == versionId }?.phone
  }

  override suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow = synchronized(lock) {
    val ref = media[mediaId]?.takeIf { it.versionId == versionId }?.phone
    when {
      ref != null -> PhoneRowNow.At(ref)
      phoneRereading -> PhoneRowNow.Rereading
      else -> PhoneRowNow.Absent
    }
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord = synchronized(lock) {
    val m = media[mediaId]?.takeIf { it.versionId == versionId } ?: return HashRecord.UNKNOWN_VERSION
    when (m.fullHash) {
      null -> {
        m.fullHash = sha256
        HashRecord.RECORDED
      }
      sha256 -> HashRecord.SAME
      else -> HashRecord.CONFLICT
    }
  }

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = synchronized(lock) {
    attachBatches++
    var attached = 0
    for (c in copies) {
      val m = media[c.mediaId]?.takeIf { it.versionId == c.versionId } ?: continue
      require(c.stamp.size == m.size) { "La copia ha una dimensione diversa dalla versione." }
      requireNotNull(m.fullHash) { "Si verifica solo contro un hash completo noto." }
      val existing = this.copies.firstOrNull { key(it.share) == key(c.share) && it.path == c.path }
      if (existing != null) {
        require(existing.mediaId == c.mediaId || existing.verifiedAtMillis == null) { "Una posizione verificata di un altro media." }
        this.copies.remove(existing)
      }
      this.copies += Copy(existing?.locationId ?: LocationId(nextLocation++), c.mediaId, c.versionId, c.share, c.path, c.stamp, c.verifiedAtMillis)
      attached++
    }
    attached
  }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    synchronized(lock) { if (problem == null) problems.remove(mediaId) else problems[mediaId] = problem }
  }

  override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> = synchronized(lock) {
    if (key(share) != key(privateShare)) return emptyList()
    copies.filter { key(it.share) == key(privateShare) && it.stamp.size == size }
      .filter { c -> quickHash == null || media[c.mediaId]?.quickHash.let { it == null || it == quickHash } }
      .take(limit)
      .map { NasCopyRef(it.locationId, it.mediaId, it.versionId, it.share, it.path, it.stamp) }
  }

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> = synchronized(lock) {
    val own = copies.filter { key(it.share) == key(privateShare) }
    val chosen = when (scope) {
      VerifyScope.Unverified -> own.filter { it.verifiedAtMillis == null }
      VerifyScope.FullHash -> own.filter { media[it.mediaId]?.fullHash == null }
      VerifyScope.Rotation -> own.filter { it.verifiedAtMillis != null }.sortedBy { it.verifiedAtMillis }
      is VerifyScope.OnTheSpot -> own.filter { it.mediaId in scope.media }
    }
    chosen.take(limit).map { c ->
      CopyToVerify(NasCopyRef(c.locationId, c.mediaId, c.versionId, c.share, c.path, c.stamp), c.stamp.size, media[c.mediaId]?.fullHash, c.verifiedAtMillis)
    }
  }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean = synchronized(lock) {
    val c = copies.firstOrNull { it.locationId == location } ?: return false
    val expected = media[c.mediaId]?.fullHash
    val ok = expected != null && expected == readBack
    c.verifiedAtMillis = if (ok) atMillis else null
    ok
  }

  override suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate> = synchronized(lock) {
    val common = commonShare ?: return emptyList()
    media.values.filter { m -> m.mine && (scope is GuardScope.Media && m.mediaId in scope.ids || scope is GuardScope.All && (m.wasOnPhone || m.phone != null)) }
      .filter { m -> statusLocked(m) == SafetyStatus.ONLY_COMMON_COPY }
      .mapNotNull { m ->
        val c = copies.firstOrNull { it.mediaId == m.mediaId && key(it.share) == key(common) } ?: return@mapNotNull null
        GuardCandidate(NasCopyRef(c.locationId, c.mediaId, c.versionId, c.share, c.path, c.stamp), m.size, m.fullHash, m.quickHash, m.localDay, m.fileName)
      }
      .take(limit)
  }

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? = synchronized(lock) {
    val m = media[mediaId] ?: return null
    val verified = copies.filter { it.mediaId == mediaId && it.verifiedAtMillis != null && key(it.share) == key(privateShare) }.mapNotNull { it.verifiedAtMillis }.maxOrNull()
    MediaSafety(statusLocked(m), verified, problems[mediaId])
  }
}
