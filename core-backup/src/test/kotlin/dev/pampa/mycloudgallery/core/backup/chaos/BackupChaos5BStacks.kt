package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.corrections.DefaultCorrections
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.free.SafeFreeSpace
import dev.pampa.mycloudgallery.core.backup.safety.BackupPrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.versions.DefaultVersionKeeper
import dev.pampa.mycloudgallery.core.backup.versions.NasVersionEngine
import dev.pampa.mycloudgallery.core.backup.versions.VersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.VersionConfig
import dev.pampa.mycloudgallery.core.backup.versions.VersionJournal
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

// I MOTORI della 5B che il banco prova (P13): le versioni ([NasVersionEngine] e il custode [DefaultVersionKeeper] di P8), le correzioni
// ([DefaultCorrections]), il guardiano ([BackupPrivateCopyGuardian] di P3) e "Libera spazio" ([SafeFreeSpace] di P9). Il banco non li conosce:
// riceve da un [FiveBStackFactory] i servizi costruiti sopra le porte gia' avvolte dai colpi, a ogni "processo" nuovo. Un altro stack, anche
// rotto apposta (BackupChaosMutants.kt), si prova cambiando solo la fabbrica.

/**
 * Tutto cio' che serve a costruire i servizi di un "processo": le porte avvolte dai decoratori dei colpi (al posto delle vere) e il resto del
 * mondo. Come [EngineEnv] per il caricamento.
 */
class FiveBEnv(
  val world: BackupChaosWorld,
  val fb: FiveBWorld,
  val ops: NasBackupOps,
  val reader: NasMediaReader,
  val journal: VersionJournal,
  val versionCatalog: VersionCatalog,
  val freeCatalog: FreeSpaceCatalog,
  val backupCatalog: BackupCatalog,
  val freeDiary: FreeSpaceDiary,
  val guardStore: BackupStore,
  val phone: PhoneBytes,
  val rewriter: PhoneRewriter,
  val clock: BackupClock,
  val boot: BootCounter,
  /** Scelte dei servizi per questo "processo", ripetibili dal seme. */
  val random: Random,
) {
  fun with(
    ops: NasBackupOps = this.ops,
    reader: NasMediaReader = this.reader,
    journal: VersionJournal = this.journal,
    versionCatalog: VersionCatalog = this.versionCatalog,
    freeCatalog: FreeSpaceCatalog = this.freeCatalog,
    backupCatalog: BackupCatalog = this.backupCatalog,
    freeDiary: FreeSpaceDiary = this.freeDiary,
    guardStore: BackupStore = this.guardStore,
    phone: PhoneBytes = this.phone,
    rewriter: PhoneRewriter = this.rewriter,
    clock: BackupClock = this.clock,
    boot: BootCounter = this.boot,
  ): FiveBEnv = FiveBEnv(world, fb, ops, reader, journal, versionCatalog, freeCatalog, backupCatalog, freeDiary, guardStore, phone, rewriter, clock, boot, random)
}

/** I servizi di un "processo". */
class FiveBServices(
  val versions: NasVersionEngine,
  val keeper: VersionKeeper,
  val corrections: Corrections,
  val guardian: PrivateCopyGuardian,
  val free: SafeFreeSpace,
)

interface FiveBStackFactory {
  val name: String

  fun create(env: FiveBEnv): FiveBServices
}

/** I servizi veri di P8 (versioni, custode, correzioni), P3 (guardiano) e P9 ("Libera spazio"), uno sull'altro come li lega `:app` (P14). */
object RealFiveBStack : FiveBStackFactory {
  override val name: String = "servizi veri della 5B (NasVersionEngine, DefaultVersionKeeper, DefaultCorrections, BackupPrivateCopyGuardian, SafeFreeSpace)"

  override fun create(env: FiveBEnv): FiveBServices {
    val world = env.world
    val fb = env.fb
    val engine = NasVersionEngine(
      deviceId = world.deviceId,
      share = world.share,
      ops = env.ops,
      reader = env.reader,
      journal = env.journal,
      catalog = env.versionCatalog,
      phone = env.phone,
      staged = fb.staged,
      gate = world.gate,
      clock = env.clock,
      tasks = fb.tasks,
      config = VersionConfig(
        chunkBytes = world.chunkBytes,
        checkpointBytes = world.checkpointBytes,
        lockedFirstWaitMs = 10,
        lockedRetries = 3,
      ),
      zone = fb.zone,
      sleep = { ms -> world.clock.advance(ms) },
    )
    val keeper = DefaultVersionKeeper(
      share = world.share, catalog = env.versionCatalog, journal = env.journal, engine = engine, gate = world.gate, clock = env.clock,
      reader = env.reader, rewriter = env.rewriter, staged = fb.staged,
    )
    val corrections = DefaultCorrections(
      share = world.share, catalog = env.versionCatalog, engine = engine, journal = env.journal, gate = world.gate, clock = env.clock,
      phone = env.phone, reader = env.reader, writer = FakeMetadataWriter(mimeOf = { "image/jpeg" }),
      check = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }, rewriter = env.rewriter, staged = fb.staged, io = EmptyCoroutineContext,
    )
    val guardian = BackupPrivateCopyGuardian(
      deviceId = world.deviceId, ops = env.ops, reader = env.reader, store = env.guardStore, catalog = env.backupCatalog, phone = env.phone,
      gate = world.gate, clock = env.clock, privateShare = { world.share }, commonShare = { world.commonShare },
    )
    val me = AccountId(world.share)
    val free = SafeFreeSpace(
      catalog = env.freeCatalog,
      diary = env.freeDiary,
      backupStore = OpenJobsStore(env.guardStore, env.journal),
      versions = keeper,
      guardian = guardian,
      reader = env.reader,
      phone = env.phone,
      gate = world.gate,
      clock = env.clock,
      boot = env.boot,
      account = { me },
      privateShare = { world.share },
      areas = { NasAreaLookup.of(listOf(NasShare(me, world.share, NasArea.PRIVATE), NasShare(me, world.commonShare, NasArea.COMMON))) },
      fence = null,
      systemDialogs = { true },
      today = { TODAY },
      pageSize = 7,
    )
    return FiveBServices(engine, keeper, corrections, guardian, free)
  }

  /** Oggi per i filtri "ultimi 30 giorni" e "piu' vecchi di un anno" (dall'ora del NAS nella vita vera, qui fisso: lo stesso di P9). */
  val TODAY: LocalDate = LocalDate.of(2026, 10, 19)
}

/**
 * Il diario del backup come lo vede "Libera spazio": nella tabella vera (`backup_item`) stanno anche i lavori `VERSION` e `SWAP`, quindi un
 * lavoro delle versioni aperto vale come un elemento aperto per la sua versione. Il resto e' il diario del guardiano.
 */
class OpenJobsStore(private val inner: BackupStore, private val journal: VersionJournal) : BackupStore {
  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> = inner.create(items)

  override suspend fun item(id: BackupItemId): BackupItem? = inner.item(id)

  override suspend fun itemsToResume(limit: Int): List<BackupItem> = inner.itemsToResume(limit)

  override suspend fun update(item: BackupItem) = inner.update(item)

  override suspend fun openFor(versionId: VersionId): BackupItem? {
    inner.openFor(versionId)?.let { return it }
    val job = journal.open(Int.MAX_VALUE).firstOrNull { it.versionId == versionId } ?: return null
    return BackupItem(
      id = BackupItemId(job.id.value),
      kind = job.kind,
      state = job.state,
      mediaId = job.mediaId,
      versionId = job.versionId,
      size = job.size,
      phone = null,
      expected = null,
      share = job.share,
      planned = job.usualPath,
    )
  }

  override suspend fun citedTemps(): Set<SharePath> = inner.citedTemps() + journal.citedTemps()

  override suspend fun counts(): BackupDiaryCounts = inner.counts()
}
