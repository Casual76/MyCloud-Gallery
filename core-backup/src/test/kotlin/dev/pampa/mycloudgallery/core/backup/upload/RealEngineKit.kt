package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Il banco del P0 ([BackupTestKit]: NAS finto, telefono finto, diario, catalogo, cancello, orologi) con il **motore vero** al posto di
 * quello di riferimento. Il kit resta lo stesso (e lo stesso verificatore degli invarianti del §5.4): cambia solo chi guida.
 */
internal class RealEngineKit(
  val kit: BackupTestKit = BackupTestKit(),
  val parallel: Int = 2,
  /** Le misure del motore: di default quelle piccole del kit (punti di ripresa e guasti con file da decine di KiB). */
  val config: UploadConfig = UploadConfig(
    chunkBytes = kit.chunkBytes,
    checkpointBytes = kit.checkpointBytes,
    parallel = parallel,
    lockedFirstWaitMs = 10,
    lockedRetries = 3,
  ),
  var indexing: UploadIndexing = UploadIndexing.NONE,
  var foreground: () -> Boolean = { false },
  var lowPriority: () -> CoroutineContext = { EmptyCoroutineContext },
  /** La porta della deduplica: di default quella del banco ([RereadingCopyFinder]), al posto di quella di P3. */
  var finder: (BackupCatalog) -> ExistingCopyFinder = { RereadingCopyFinder(it, kit.nas.reader, kit.clock) },
  /** Le primitive e la lettura del NAS: di default quelle del NAS finto del kit (il simulatore SMB le sostituisce). */
  var ops: NasBackupOps = kit.ops,
  var reader: NasMediaReader = kit.nas.reader,
  /** Un decoratore del telefono (letture che si sospendono, contatori). */
  var phone: (PhoneBytes) -> PhoneBytes = { it },
  /** Il piano (per le prove di proprieta' sulle destinazioni). */
  var planner: BackupPlanner = kit.planner,
) {
  /** Le attese del motore (E-N13): fanno avanzare l'orologio finto. */
  val sleeps: MutableList<Long> = ArrayList()

  /** Chiamato dopo ogni attesa (un file che si sblocca, un tempo che passa). */
  var sleepHook: () -> Unit = {}

  /** Il motore del "processo" attuale (nuovo a ogni [restart]). */
  var engine: SafeUploadEngine = newEngine()
    private set

  fun newEngine(store: BackupStore = kit.store, catalog: BackupCatalog = kit.catalog): SafeUploadEngine = SafeUploadEngine(
    deviceId = kit.deviceId,
    share = kit.share,
    ops = ops,
    reader = reader,
    store = store,
    catalog = catalog,
    phone = phone(kit.phone.port),
    gate = kit.gate,
    clock = kit.clock,
    planner = planner,
    folder = { kit.folder },
    existing = finder(catalog),
    facts = KitFacts(kit.catalog),
    indexing = indexing,
    config = config,
    foreground = { foreground() },
    lowPriority = { lowPriority() },
    sleep = { ms ->
      sleeps += ms
      kit.clock.advance(ms)
      sleepHook()
    },
  )

  /** La morte del processo: si ricrea solo cio' che sta in memoria. */
  fun restart() {
    engine = newEngine()
  }

  /** Un motore che "muore" alle scritture del diario e del catalogo ([BackupDeath]). */
  fun dyingEngine(death: BackupDeath): SafeUploadEngine = newEngine(DyingBackupStore(kit.store, death), DyingBackupCatalog(kit.catalog, death))

  /** Un giro delle foto e uno dei video (D11). */
  suspend fun runAll(engine: SafeUploadEngine = this.engine, budget: Duration = 1.hours): List<BackupRunResult> = listOf(
    engine.run(BackupKind.PHOTOS, budget, BackupTrigger.SCHEDULED),
    engine.run(BackupKind.VIDEOS, budget, BackupTrigger.SCHEDULED),
  )

  /** Giri finche' il diario non ha piu' niente da fare (al massimo [maxRounds]). */
  suspend fun resumeUntilQuiet(maxRounds: Int = 12) {
    repeat(maxRounds) {
      val results = runAll()
      val open = kit.store.all().count { !it.state.terminal }
      if (open == 0 && results.all { it.stoppedBy == RunStop.COMPLETE }) return
    }
  }
}

/** Il tipo e il MIME dal catalogo finto (la porta che P7 lega a `MediaCatalog`). */
internal class KitFacts(private val catalog: InMemoryBackupCatalog) : UploadFacts {
  override suspend fun describe(
    mediaId: dev.pampa.mycloudgallery.core.model.MediaId,
    versionId: dev.pampa.mycloudgallery.core.model.VersionId,
  ): UploadDescription? = catalog.media(mediaId)?.takeIf { it.versionId == versionId }?.let { UploadDescription(it.kind, null) }
}

/**
 * La deduplica del banco (D8), al posto di quella di P3 che arriva in parallelo: le copie private con la stessa dimensione (e `qh1`) dal
 * catalogo, ognuna **riletta per intero** dal NAS; SHA-256 uguale = trovata. Conta le chiamate (E-B5 "tramite la porta").
 */
internal class RereadingCopyFinder(
  private val catalog: BackupCatalog,
  private val reader: NasMediaReader,
  private val clock: BackupClock,
) : ExistingCopyFinder {
  val calls = AtomicInteger()

  override suspend fun find(candidate: BackupCandidate, phoneSha256: Sha256, privateShare: String): ExistingCopyResult {
    calls.incrementAndGet()
    for (copy in catalog.privateCopies(privateShare, candidate.size, candidate.quickHash, LIMIT)) {
      if (!copy.share.equals(privateShare, ignoreCase = true)) continue
      val file = when (val opened = reader.open(copy.share, copy.path, Purpose.BACKGROUND)) {
        is NasResult.Err -> if (opened.error == NasError.NotFound) continue else return ExistingCopyResult.Interrupted(UploadNames.problemFor(opened.error))
        is NasResult.Ok -> opened.value
      }
      val read = file.use { reader.readAll(it, { _, _ -> }, null) }
      when (read) {
        is NasResult.Err -> if (read.error == NasError.NotFound) continue else return ExistingCopyResult.Interrupted(UploadNames.problemFor(read.error))
        is NasResult.Ok -> if (read.value == phoneSha256 && file.stamp.size == candidate.size) {
          val at = clock.nasNowMs() ?: return ExistingCopyResult.Interrupted(null)
          return ExistingCopyResult.Found(VerifiedCopy(candidate.mediaId, candidate.versionId, copy.share, copy.path, file.stamp, at))
        }
      }
    }
    return ExistingCopyResult.None
  }

  private companion object {
    const val LIMIT = 20
  }
}
