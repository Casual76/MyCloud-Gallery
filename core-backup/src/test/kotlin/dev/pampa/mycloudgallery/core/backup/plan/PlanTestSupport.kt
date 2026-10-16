package dev.pampa.mycloudgallery.core.backup.plan

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.MediaSafety
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupSettings
import dev.pampa.mycloudgallery.core.backup.testing.FakeExistingCopies
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.RuleSet
import dev.pampa.mycloudgallery.core.rules.estimate.BackupEstimator
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Gli attrezzi dei test del piano del backup (Fase 5A, P4): id stabili (per l'ordine), media nel catalogo in memoria di P0, una recinzione
// finta come quella di D13, un mondo con tutte le porte collegate.

const val PRIVATE = "test"
const val COMMON = "test-comune"

fun mid(n: Int): MediaId = MediaId("00000000-0000-4000-8000-%012d".format(n))

fun vid(n: Int): VersionId = VersionId("00000000-0000-4000-9000-%012d".format(n))

fun qh(c: Char): QuickHash = QuickHash(c.toString().repeat(64))

/** Un media del telefono nel catalogo in memoria: proprio, con la sua riga, non ancora sul NAS. */
fun phoneMedia(
  n: Int,
  kind: MediaKind = MediaKind.PHOTO,
  source: BackupSource = BackupSource.Camera,
  sortTime: Long = n.toLong(),
  size: Long = 4_000_000L,
  localDay: Int? = 20261012,
  name: String = "IMG_$n.jpg",
  quickHash: QuickHash? = null,
  mine: Boolean = true,
): InMemoryBackupCatalog.Media = InMemoryBackupCatalog.Media(
  mediaId = mid(n),
  versionId = vid(n),
  kind = kind,
  size = size,
  quickHash = quickHash,
  phone = if (mine) PhoneRef("external_primary", n + 1L, "v1") else null,
  phoneStamp = if (mine) FileStamp(size, 1) else null,
  source = source,
  localDay = localDay,
  fileName = name,
  sortTime = sortTime,
  fullHash = null,
  mine = mine,
)

/** Un candidato come lo darebbe il catalogo (per i test che non passano dal catalogo in memoria). */
fun candidate(
  n: Int,
  kind: MediaKind = MediaKind.PHOTO,
  source: BackupSource = BackupSource.Camera,
  sortTime: Long = n.toLong(),
  original: Boolean = true,
  size: Long = 4_000_000L,
  localDay: Int? = 20261012,
  name: String = "IMG_$n.jpg",
): BackupCandidate = BackupCandidate(
  mediaId = mid(n),
  versionId = vid(n * 10 + if (original) 0 else 1),
  phone = PhoneRef("external_primary", n + 1L, "v1"),
  stamp = FileStamp(size, 1),
  size = size,
  kind = kind,
  source = source,
  localDay = localDay,
  dateEstimated = false,
  fileName = name,
  sortTime = sortTime,
  quickHash = null,
  original = original,
)

/**
 * Un catalogo che da' i candidati di un elenco, a pagine con il cursore `(sort_time, media)` come quello vero, ma con l'originale
 * **dopo** la sua versione dentro lo stesso media: il piano deve rimetterli nell'ordine giusto.
 */
class ListCatalog(
  private val all: List<BackupCandidate>,
  /** I media la cui sola copia sul NAS e' nell'area comune (`ONLY_COMMON_COPY`): il catalogo li restituisce comunque come candidati. */
  private val onlyCommonCopy: Set<MediaId> = emptySet(),
  /** Altri media con la sola copia comune che il catalogo conta in `safetyCounts` ma **non** restituisce fra i candidati. */
  private val commonNotReturned: Int = 0,
) : BackupCatalog by NoBackupCatalog {
  var queries: Int = 0
    private set

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts =
    SafetyCounts.ZERO.copy(onlyCommonCopy = onlyCommonCopy.size + commonNotReturned)

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? =
    MediaSafety(if (mediaId in onlyCommonCopy) SafetyStatus.ONLY_COMMON_COPY else SafetyStatus.NO_NAS_COPY, null, null)

  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> {
    queries++
    return all.filter { it.backupKind == query.kind && it.source in query.sources }
      .sortedWith(compareByDescending<BackupCandidate> { it.sortTime }.thenByDescending { it.mediaId }.thenBy { it.original })
      .filter { c ->
        val after = query.after
        after == null || c.sortTime < after.sortTime || (c.sortTime == after.sortTime && c.mediaId < after.mediaId)
      }
      .take(query.limit)
  }
}

/** La recinzione di D13: con `test` e `test2` solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/`, e si scrive in `mcg-campo`. */
class ProvaFence : BackupFence {
  private fun fenced(account: String) = account == "test" || account == "test2"

  override fun allowsPhoneFolder(account: String, relativePath: String): Boolean =
    !fenced(account) || relativePath.startsWith("DCIM/mcg-prova/") || relativePath.startsWith("Pictures/mcg-prova/")

  override fun destinationRoot(account: String): SharePath? = if (fenced(account)) SharePath.of("mcg-campo") else null
}

/** Il mondo del piano: catalogo in memoria, impostazioni, confronto con il NAS, orologio e regole, collegati. */
class PlanWorld(
  sources: BackupSources = BackupSources.DEFAULT,
  rules: RuleSet = PresetCatalog.ruleSet(Preset.NIGHT),
  val privateShare: String? = PRIVATE,
  val fence: BackupFence? = null,
  val account: String? = "alessio",
  val estimator: BackupEstimator = BackupEstimator(),
  val pageSize: Int = DefaultBackupPlanner.DEFAULT_PAGE,
  val planned: MutableSet<VersionId> = HashSet(),
  similar: SimilarCopyLookup = SimilarCopyLookup.NONE,
  val conditions: PhoneConditions = PhoneConditions.UNKNOWN,
  catalog: BackupCatalog? = null,
) {
  val memory = InMemoryBackupCatalog(PRIVATE, COMMON)
  val catalog: BackupCatalog = catalog ?: memory
  val settings = FakeBackupSettings(sources, DeviceFolder("S25 di prova"))
  val existing = FakeExistingCopies()
  val clock = FakeBackupClock()
  var rules: RuleSet = rules
  var similarLookup: SimilarCopyLookup = similar

  var accountNow: String? = account

  val planner: DefaultBackupPlanner
    get() = DefaultBackupPlanner(
      catalog = this.catalog,
      settings = settings,
      existing = existing,
      rules = { this.rules },
      privateShare = { privateShare },
      clock = clock,
      census = CatalogExistingCopyCensus(this.catalog, SimilarCopyLookup { similarLookup.withSimilarOnNas(it) }),
      estimator = estimator,
      conditions = PhoneConditionsSource { conditions },
      fence = fence,
      account = { accountNow },
      alreadyPlanned = { it in planned },
      pageSize = pageSize,
    )

  fun add(vararg media: InMemoryBackupCatalog.Media) {
    media.forEach { memory.put(it) }
  }

  /** Una copia di [media] sul NAS (la scansione l'ha vista): nell'area privata [share] o in quella comune. */
  fun putCopy(media: InMemoryBackupCatalog.Media, share: String, path: String, size: Long = media.size) {
    memory.putCopy(media.mediaId, media.versionId, share, SharePath.parse(path), NasStamp(size, 1, 1))
  }

  /** Un file dell'area privata che il catalogo conosce ma che non e' un media di questo telefono (un vecchio backup, §2.11). */
  fun oldBackupFile(n: Int, size: Long, quickHash: QuickHash?, path: String = "Backup telefono 2023/IMG_$n.jpg") {
    val owner = phoneMedia(n, size = size, quickHash = quickHash, mine = false)
    memory.put(owner)
    memory.putCopy(owner.mediaId, owner.versionId, PRIVATE, SharePath.parse(path), NasStamp(size, 1, n.toLong()))
  }
}
