package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.corrections.DefaultCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoVersionKeeper
import dev.pampa.mycloudgallery.core.backup.upload.StagedBackupEngine
import dev.pampa.mycloudgallery.core.backup.upload.UploadControl
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.StateFlow

/**
 * Le porte delle versioni e delle correzioni (5B, P14): le lega `:app` al catalogo (`VersionCatalog` su `MediaCatalog`), al diario
 * (`RoomVersionJournal`), ai file preparati (`DirectoryStagedFiles`), alle attivita' in sospeso (`RoomPendingTasks`), al telefono e al suo
 * riscrittore (con la recinzione D13) e allo scrittore dei metadati. Nessun tipo del NAS: le primitive le costruisce [FiveBFactory].
 *
 * @property fenceRoot D13: con la recinzione di debug e un account di prova, la radice di prova (`mcg-campo`): solo i media sotto di lei hanno
 *   versioni. Null = nessuna recinzione.
 */
class FiveBPorts(
  val journal: VersionJournal,
  val catalog: VersionCatalog,
  val phone: PhoneBytes,
  val staged: StagedVersionFiles,
  val gate: BackupGate,
  val clock: BackupClock,
  val tasks: VideoOriginalTasks,
  val rewriter: PhoneRewriter,
  val writer: MetadataWriter,
  val check: WrittenFileCheck,
  val fenceRoot: SharePath? = null,
  val zone: () -> ZoneId = ZoneId::systemDefault,
)

/** I servizi delle versioni costruiti da [FiveBFactory]: il motore (null senza un NAS su cui scrivere), il custode e le correzioni. */
class FiveBServices(val engine: NasVersionEngine?, val keeper: VersionKeeper, val corrections: Corrections) {
  override fun toString(): String = "FiveBServices(motore=${engine != null})"
}

/**
 * Il **punto d'ingresso** delle versioni (5B, P14), come `BackupEngineFactory` per il caricamento e `BackupSafetyFactory` per il guardiano:
 * costruisce le primitive in sola aggiunta dalla sessione del NAS e consegna il motore delle versioni, il custode ("Ripristina originale") e le
 * correzioni. `:app` non nomina mai le primitive del backup (D2, `Phase5ArchitectureTest`): le uniche due rinomine automatiche di media
 * (`archiveVersion`, `restoreVersion`) restano dentro core-backup.
 */
object FiveBFactory {

  /**
   * I servizi per il dispositivo [deviceId] e l'area privata [privateShare] sopra [session]. Con una sessione che non e' SMB (un fake, nessun
   * NAS configurato) niente scrive: il motore manca e custode e correzioni sono i null-object (falliscono chiusi).
   */
  fun create(session: NasSessionManager, deviceId: String, privateShare: String, ports: FiveBPorts): FiveBServices {
    val ops = NasBackupOpsFactory.create(session, deviceId)
    if (ops.ownerDeviceId != deviceId) return FiveBServices(null, NoVersionKeeper, NoCorrections)
    val reader = session.mediaReader()
    val engine = NasVersionEngine(
      deviceId = deviceId,
      share = privateShare,
      ops = ops,
      reader = reader,
      journal = ports.journal,
      catalog = ports.catalog,
      phone = ports.phone,
      staged = ports.staged,
      gate = ports.gate,
      clock = ports.clock,
      tasks = ports.tasks,
      config = VersionConfig(fenceRoot = ports.fenceRoot),
      zone = ports.zone(),
    )
    val keeper = DefaultVersionKeeper(
      share = privateShare,
      catalog = ports.catalog,
      journal = ports.journal,
      engine = engine,
      gate = ports.gate,
      clock = ports.clock,
      reader = reader,
      rewriter = ports.rewriter,
      staged = ports.staged,
    )
    val corrections = DefaultCorrections(
      share = privateShare,
      catalog = ports.catalog,
      engine = engine,
      journal = ports.journal,
      gate = ports.gate,
      clock = ports.clock,
      phone = ports.phone,
      reader = reader,
      writer = ports.writer,
      check = ports.check,
      rewriter = ports.rewriter,
      staged = ports.staged,
    )
    return FiveBServices(engine, keeper, corrections)
  }
}

/**
 * Il caricamento della 5A **con le versioni della 5B nella stessa tappa** (P14; nota di P8): prima di ogni giro
 * [NasVersionEngine.planPhoneVersions] trasforma le versioni nuove del telefono che hanno gia' un solito percorso nell'area privata in lavori
 * `VERSION` (altrimenti la 5A caricherebbe una modifica come "nome (2)": il piano della 5A salta le versioni con un lavoro aperto), poi il
 * motore delle versioni porta avanti i suoi lavori, poi il caricamento. Un giro fermato da "Ferma", dal cancello, dal NAS o dal telefono non
 * passa alla parte dopo. I file in arrivo (`INCOMING`) sono solo del caricamento.
 */
class VersionedBackupEngine(
  private val upload: StagedBackupEngine,
  private val versions: NasVersionEngine,
  private val planner: BackupPlanner,
  private val clock: BackupClock,
  private val planPage: Int = 500,
  /**
   * Candidati delle versioni fuori dalle sorgenti del backup (P14, nota di P8, E-E7): una copia scaricata con "Scarica sul telefono" in
   * `Pictures/MyCloud Gallery` e modificata in Galleria e' una versione di un media gia' sul NAS, ma la cartella non e' una sorgente e il piano
   * della 5A non la propone. Solo le versioni (`original = false`): un media nuovo fuori dalle sorgenti resta fuori. Solo dati locali.
   */
  private val extra: suspend (BackupKind) -> List<BackupCandidate> = { emptyList() },
) : StagedBackupEngine {

  override val status: StateFlow<BackupStatus> get() = upload.status

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult =
    run(kind, budget, trigger, UploadControl.FREE)

  override suspend fun resumeAll(budget: Duration): BackupRunResult = resumeAll(budget, UploadControl.FREE)

  override suspend fun stop(by: StopBy) {
    versions.stop()
    upload.stop(by)
  }

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger, control: UploadControl): BackupRunResult {
    if (trigger == BackupTrigger.INCOMING) return upload.run(kind, budget, trigger, control)
    val started = clock.monotonicMs()
    // Solo dati locali (E-S11): i candidati del piano della 5A di questo giro.
    val planned = try {
      versions.planPhoneVersions(planner.next(kind, planPage, trigger) + extra(kind).filter { !it.original })
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      // Il piano delle versioni non ferma il caricamento: la versione aspetta il giro dopo (la 5A non la carica: non ha un lavoro aperto,
      // ma il suo candidato resta tale e il piano delle versioni riprova). Solo il tipo dell'errore nel log.
      GalleryLog.w(TAG) { "piano delle versioni non riuscito (${e::class.java.simpleName})" }
      emptyList()
    }
    // VE-1: le versioni del tipo del giro, con le sue regole; "Proteggi subito" solo quelle che ha pianificato lui (la foto nuova non aspetta
    // l'arretrato, BK-1).
    val only = if (trigger == BackupTrigger.QUICK_NEW_PHOTOS) planned.mapTo(HashSet()) { it.id } else null
    val first = versions.run(budget, control, kinds = setOf(kind), only = only)
    if (first.stoppedBy !in GO_ON) return merge(first, null)
    val left = budget - (clock.monotonicMs() - started).milliseconds
    if (!left.isPositive()) return merge(first, null).copy(stoppedBy = RunStop.BUDGET)
    return merge(first, upload.run(kind, left, trigger, control))
  }

  override suspend fun resumeAll(budget: Duration, control: UploadControl): BackupRunResult {
    val started = clock.monotonicMs()
    val first = versions.run(budget, control)
    if (first.stoppedBy !in GO_ON) return merge(first, null)
    val left = budget - (clock.monotonicMs() - started).milliseconds
    if (!left.isPositive()) return merge(first, null).copy(stoppedBy = RunStop.BUDGET)
    return merge(first, upload.resumeAll(left, control))
  }

  private fun merge(v: VersionRunResult, u: BackupRunResult?): BackupRunResult {
    val stop = when {
      u == null -> v.stoppedBy
      v.stoppedBy == RunStop.BUDGET && u.stoppedBy == RunStop.COMPLETE -> RunStop.BUDGET
      // O-8: una versione che aspetta un file occupato sul NAS non rende "completo" il giro, anche se il caricamento ha finito.
      v.stoppedBy == RunStop.BUSY && u.stoppedBy == RunStop.COMPLETE -> RunStop.BUSY
      else -> u.stoppedBy
    }
    return BackupRunResult(
      done = v.done + (u?.done ?: 0),
      linked = u?.linked ?: 0,
      gone = v.gone + (u?.gone ?: 0),
      failed = v.failed + (u?.failed ?: 0),
      waiting = v.waiting + v.held + (u?.waiting ?: 0),
      bytesUploaded = v.bytesUploaded + (u?.bytesUploaded ?: 0),
      bytesReread = u?.bytesReread ?: 0,
      stoppedBy = stop,
      problem = u?.problem ?: v.problem,
    )
  }

  private companion object {
    const val TAG = "BackupVersioned"

    /**
     * Le versioni sono finite, hanno finito il loro tempo o aspettano un file occupato sul NAS (O-8): il caricamento va avanti (con il tempo che
     * resta), un file occupato non ferma gli altri.
     */
    val GO_ON: Set<RunStop> = setOf(RunStop.COMPLETE, RunStop.BUDGET, RunStop.BUSY)
  }
}
