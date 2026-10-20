package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupEngine
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import kotlin.time.Duration

/**
 * Le porte del motore del caricamento (§1.2, §1.4): le lega `:app` (P7) al catalogo (P1), al telefono (`MediaStoreSource`), al piano e
 * alle impostazioni (P4), alla deduplica (P3), al cancello (D12, congelamento, emergenza) e agli orologi. Nessun tipo del NAS: le
 * primitive le costruisce [BackupEngineFactory] dalla sessione.
 */
class BackupEnginePorts(
  val store: BackupStore,
  val catalog: BackupCatalog,
  val phone: PhoneBytes,
  val gate: BackupGate,
  val clock: BackupClock,
  val planner: BackupPlanner,
  /** La cartella del dispositivo (D7), null finche' il primo backup non l'ha scelta. */
  val folder: () -> DeviceFolder?,
  val existing: ExistingCopyFinder,
  val facts: UploadFacts = UploadFacts.UNKNOWN,
  val indexing: UploadIndexing = UploadIndexing.NONE,
  /** L'app e' in primo piano (lettura e preparazione a priorita' bassa). */
  val foreground: () -> Boolean = { false },
  /** 5B (P14, D24): i file in arrivo da caricare; null = gli elementi `INCOMING` aspettano. */
  val incoming: IncomingUploadPorts? = null,
)

/**
 * Il **punto d'ingresso** del caricamento vero (D2, §1.1): costruisce il motore dalle porte e dalla sessione del NAS, con le primitive in
 * sola aggiunta di `NasBackupOpsFactory`. Serve perche' `:app` non nomini mai le primitive del backup (il test di architettura vieta il
 * loro nome fuori da core-nas e core-backup): `:app` passa la sessione e riceve un [StagedBackupEngine].
 */
object BackupEngineFactory {

  /**
   * Il motore per il dispositivo [deviceId] che salva nell'area privata [privateShare] sopra [session]. Con una sessione che non e' SMB
   * (un fake, nessun NAS configurato) le primitive non scrivono niente: il motore che ne esce non fa niente (`NoBackupEngine`).
   */
  fun create(
    session: NasSessionManager,
    deviceId: String,
    privateShare: String,
    ports: BackupEnginePorts,
    config: UploadConfig = UploadConfig(),
  ): StagedBackupEngine {
    val ops = NasBackupOpsFactory.create(session, deviceId)
    if (ops.ownerDeviceId != deviceId) return ClosedEngine
    return SafeUploadEngine(
      deviceId = deviceId,
      share = privateShare,
      ops = ops,
      reader = session.mediaReader(),
      store = ports.store,
      catalog = ports.catalog,
      phone = ports.phone,
      gate = ports.gate,
      clock = ports.clock,
      planner = ports.planner,
      folder = ports.folder,
      existing = ports.existing,
      facts = ports.facts,
      indexing = ports.indexing,
      config = config,
      foreground = ports.foreground,
      incoming = ports.incoming,
    )
  }

  /** Nessun NAS su cui scrivere: niente si fa (il null-object del contratto con le tappe). */
  private object ClosedEngine : StagedBackupEngine, BackupEngine by NoBackupEngine {
    override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger, control: UploadControl): BackupRunResult =
      NoBackupEngine.run(kind, budget, trigger)

    override suspend fun resumeAll(budget: Duration, control: UploadControl): BackupRunResult = NoBackupEngine.resumeAll(budget)
  }
}
