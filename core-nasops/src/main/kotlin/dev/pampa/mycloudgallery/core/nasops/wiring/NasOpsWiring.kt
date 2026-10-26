package dev.pampa.mycloudgallery.core.nasops.wiring

import dev.pampa.mycloudgallery.core.index.compact.CompactOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nasops.clean.IndexMaintenance
import dev.pampa.mycloudgallery.core.nasops.clean.OwnFilesCleaner
import dev.pampa.mycloudgallery.core.nasops.contract.CitedTemps
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.engine.HeavyWorkPolicy
import dev.pampa.mycloudgallery.core.nasops.engine.KnownHashes
import dev.pampa.mycloudgallery.core.nasops.engine.MoveCompletions
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.engine.completionPortOf
import dev.pampa.mycloudgallery.core.nasops.folders.NasFolderService
import dev.pampa.mycloudgallery.core.nasops.noop.NoMoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.space.NasSpaceService
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecordSource
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecords
import dev.pampa.mycloudgallery.core.nasops.trash.TrashSigningIdentity
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

/**
 * Cio' che `:app` (P16) lega alle operazioni della 4B. Solo porte e valori: nessun tipo delle primitive di scrittura.
 *
 * @property deviceId l'id di questo dispositivo (proprietario dei temporanei e del cestino).
 * @property accountId l'account del NAS in uso (voci del cestino, dati personali nelle teste).
 * @property shares le share dell'utente (area privata e comune), per "Eliminati di recente".
 * @property gate congelamento (D10), salto dell'ora (D32), emergenza (D31): lo compone `:app`.
 * @property registry il registro accettato (verifica dei record del cestino e dei propri file firmati).
 * @property signing famiglia e chiave del dispositivo per firmare i record del cestino (null = non ancora).
 * @property compact `Compactor.compactIfNeeded` costruito con `enabled = true` (la 4B accende la compattazione).
 * @property catalogPresence R3: cio' che il catalogo vede ancora presente (la pulizia non attraversa un collegamento).
 * @property trashRetentionMillis quanto resta una voce nel cestino: 30 giorni; solo la build debug lo accorcia (F20).
 * @property citedTemps Fase 5A, D33: i temporanei citati dal diario del backup (`BackupStore.citedTemps`, composto da `:app`, P7): la
 *   pulizia dei propri file li lascia come quelli delle operazioni.
 * @property moves Campo, gruppo A (D7): il catalogo collega la posizione nuova di uno spostamento tra share prima che la sorgente vada nel cestino.
 *   Fase 7 P4 (D29): diventa la porta di completamento (`asSink`), vincolante come [completions]: un errore ferma l'elemento finche' il catalogo
 *   non risponde.
 * @property completions Fase 7 (D29): la porta di completamento vera (`MediaCatalog.attachMoved`, P13). Al piu' una fra [moves] e
 *   [completions] (`completionPortOf`): il catalogo non si aggiorna mai due volte per lo stesso file.
 */
class NasOpsConfig(
  val deviceId: String,
  val accountId: String,
  val shares: () -> List<String>,
  val clocks: OpClocks,
  val gate: OpsGate,
  val access: ShareAccessView = ShareAccessView.ALL_WRITABLE,
  val registry: () -> RegistryState? = { null },
  val signing: () -> TrashSigningIdentity? = { null },
  val knownHashes: KnownHashes = KnownHashes.NONE,
  val heavyWork: HeavyWorkPolicy = HeavyWorkPolicy.ALWAYS,
  val largest: suspend (String) -> List<LargestFile> = { emptyList() },
  val indexBytes: suspend (String) -> Long = { 0L },
  val publishedSegments: suspend (String) -> Set<String> = { emptySet() },
  val currentSegments: suspend (String) -> Set<String> = { emptySet() },
  val authorName: (String) -> String? = { null },
  val folderFileCount: suspend (String, SharePath) -> Int? = { _, _ -> null },
  val compact: (suspend (String) -> CompactOutcome)? = null,
  val catalogPresence: CatalogPresence = CatalogPresence.NONE,
  val trashRetentionMillis: Long = TrustLimits.TRASH_RETENTION_MILLIS,
  val citedTemps: CitedTemps = CitedTemps.NONE,
  val moves: MoveCompletions = MoveCompletions.NONE,
  val completions: MoveCompletionSink = NoMoveCompletionSink,
) {
  init {
    // Fase 7 P4 (D29): una sola porta di completamento (lo stesso controllo di `completionPortOf`, prima di aprire qualunque cosa).
    completionPortOf(completions, moves)
  }

  override fun toString(): String = "NasOpsConfig"
}

/** Le operazioni della 4B pronte, legate fra loro (un solo lucchetto per le scritture distruttive di questo dispositivo). */
class NasOpsServices internal constructor(
  val operations: NasOperations,
  val trash: NasTrash,
  val space: NasSpace,
  val folders: NasFolders,
  val cleaning: OwnFilesCleaning,
  /** La manutenzione dell'indice (compattazione + pulizia dei propri file), se la compattazione e' legata. */
  val maintenance: IndexMaintenance?,
)

/**
 * L'unico posto in produzione dove una sessione del NAS diventa primitive di scrittura (`NasFileOpsFactory`, D2): `:app` passa la
 * sessione e il diario e riceve i servizi, **senza mai vedere le primitive** (il test di architettura vieta il loro nome fuori da
 * `core-nas` e `core-nasops`). Ogni servizio entra solo da una conferma dell'utente o dal diario locale.
 */
object NasOpsWiring {

  fun build(session: NasSessionManager, store: OpsStore, config: NasOpsConfig): NasOpsServices {
    val fileOps = NasFileOpsFactory.create(session, config.deviceId)
    val reader = session.mediaReader()
    val lock = NasOpsLock()
    val records = SignedTrashRecords(config.deviceId, config.signing, session::fileSystem)
    val operations = NasOpsEngine(
      deviceId = config.deviceId,
      accountId = config.accountId,
      fileOps = fileOps,
      reader = reader,
      store = store,
      clocks = config.clocks,
      gate = config.gate,
      access = config.access,
      records = records,
      hashes = config.knownHashes,
      heavy = config.heavyWork,
      lock = lock,
      presence = config.catalogPresence,
      completions = completionPortOf(config.completions, config.moves),
    )
    val trash = NasTrashService(
      deviceId = config.deviceId,
      accountId = config.accountId,
      shares = config.shares,
      store = store,
      fileOps = fileOps,
      reader = reader,
      clocks = config.clocks,
      gate = config.gate,
      access = config.access,
      remote = SignedTrashRecordSource(session::fileSystem, config.registry),
      lock = lock,
      authorName = config.authorName,
      presence = config.catalogPresence,
      retentionMillis = config.trashRetentionMillis,
    )
    val space = NasSpaceService(fileOps, config.clocks, store, config.largest, config.indexBytes, trash)
    val folders = NasFolderService(reader, config.clocks, config.access, config.folderFileCount)
    val cleaning = OwnFilesCleaner(
      deviceId = config.deviceId,
      accountId = config.accountId,
      fileOps = fileOps,
      reader = reader,
      store = store,
      clocks = config.clocks,
      gate = config.gate,
      lock = lock,
      registry = config.registry,
      publishedSegments = config.publishedSegments,
      currentSegments = config.currentSegments,
      otherCited = config.citedTemps,
    )
    val maintenance = config.compact?.let { IndexMaintenance(it, cleaning, config.gate, config.clocks) }
    return NasOpsServices(operations, trash, space, folders, cleaning, maintenance)
  }
}
