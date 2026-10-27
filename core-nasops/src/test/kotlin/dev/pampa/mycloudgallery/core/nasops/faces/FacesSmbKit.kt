package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseLimits
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.testing.FakeFacesEraseFacts
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Il NAS delle prove della cancellazione dei volti (Fase 8, P8): il simulatore SMB di core-nas ([InMemorySmbBackend], semantica Samba) con la
 * sessione e le primitive VERE (`SmbNasSessionManager`, `SmbNasFileOps`, `SmbNasMediaReader`) e il [NasFacesEraser] vero sopra. In piu':
 * - il **registro di ogni richiesta** che puo' cambiare un file ([changes]: apertura per cambiare, cancellazione, rinomina, creazione), per la
 *   proprieta' "mai un percorso fuori da `faces/`";
 * - il **cestino di rete** emulato ([recycleBin]: un file tolto se ne va in `<cestino>/<percorso>`, come `vfs_recycle` di Samba, E-F34);
 * - i **guasti** del piano del simulatore ([failNext], [faults]);
 * - il catalogo finto dei media ([media]: le identita' che il catalogo vede, per la regola "mai un media").
 * Solo nomi inventati; nessun dato di persone.
 */
internal class FacesSmbWorld(
  val privateShare: String = "test",
  val commonShare: String = "test-comune",
  maxFilesPerRound: Int = FacesEraseLimits.MAX_FILES_PER_ROUND,
  val deviceId: String = TrustIds.random(),
) : AutoCloseable {
  val monotonic = ManualMonotonicClock(START_MONOTONIC)
  val faults = FaultPlan()
  val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    listOf(privateShare, commonShare).forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
    faults = this@FacesSmbWorld.faults
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val manager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "faces", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
    credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope, wallClock = { WALL_CLOCK }),
    scope = scope,
    transport = backend,
    clock = monotonic,
    io = Dispatchers.Unconfined,
    deviceId = { deviceId },
    backoff = { },
    shareGuard = ShareFence,
  )
  val reader = manager.mediaReader()
  val fileOps = NasFileOpsFactory.create(manager, deviceId)
  val facts = FakeFacesEraseFacts()

  /** Il cancello di D35: null = aperto. */
  @Volatile var blocked: FacesEraseStop? = null

  /** Le identita' dei file che il catalogo vede come media (per la regola "mai un media"). */
  val media: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())

  /** Il cestino di rete delle aree private (null = spento, come oggi sulle aree private). */
  @Volatile var recycleBin: String? = null

  /** Ogni richiesta che puo' cambiare un file: (operazione, percorso nella share). */
  val changes: MutableList<Pair<SimOp, String>> = Collections.synchronizedList(mutableListOf())

  val clocks = object : OpClocks {
    override fun monotonicMs(): Long = monotonic.nowMillis()

    override fun nasNowMs(): Long? = manager.nasClock.nowMillisOrNull()
  }

  val presence = CatalogPresence { _, ids -> ids.filter { it in media }.associateWith { listOf(SharePath.of("Foto", "altrove.jpg")) } }

  val eraser = NasFacesEraser(
    fileOps = fileOps,
    reader = reader,
    facts = facts,
    privateShare = { privateShare },
    commonShare = { commonShare },
    gate = { blocked },
    clocks = clocks,
    presence = presence,
    maxFilesPerRound = maxFilesPerRound,
  )

  private val placed = Collections.synchronizedSet(LinkedHashSet<Pair<String, SharePath>>())

  init {
    backend.beforeOp = { op, path ->
      if (op in CHANGING) changes += op to path.replace('\\', '/')
      // vfs_recycle: un file che si cancella finisce nel cestino con il suo percorso (solo nelle aree private).
      val bin = recycleBin
      val clean = path.replace('\\', '/')
      if (bin != null && op == SimOp.DELETE_ON_CLOSE && clean.isNotEmpty()) {
        backend.bytes(privateShare, clean)?.let { backend.put(privateShare, "$bin/$clean", it) }
      }
    }
    val opened = runBlocking { manager.withShare(privateShare, Purpose.INTERACTIVE) { } }
    check(opened is NasResult.Ok) { "il simulatore non apre la sessione: $opened" }
  }

  /** Mette un file (come chi ha la password: nessuna firma). Restituisce il suo `fileId`. */
  fun put(share: String, path: SharePath, bytes: ByteArray = ByteArray(16) { it.toByte() }, lastWriteMs: Long? = null): Long {
    if (lastWriteMs == null) backend.put(share, path.value, bytes) else backend.put(share, path.value, bytes, lastWriteMs)
    placed += share to path
    return checkNotNull(backend.fileId(share, path.value))
  }

  /** I file messi dalle prove ancora presenti nella share, in ordine. */
  fun files(share: String): List<SharePath> =
    placed.filter { it.first == share && backend.exists(share, it.second.value) }.map { it.second }.sortedBy { it.value }

  /** I file messi dalle prove che non ci sono piu' (tolti, o finiti nel cestino di rete). */
  fun removed(): List<Pair<String, SharePath>> = placed.filter { !backend.exists(it.first, it.second.value) }

  /** Le prossime [operations] richieste al server falliscono con un codice inatteso (niente fatto). */
  fun failNext(operations: Int) {
    repeat(operations) { faults.at(faults.operations + 1 + it, FaultPhase.BEFORE, SimFault.Status(STATUS_UNSUCCESSFUL)) }
  }

  override fun close() {
    try {
      runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    } finally {
      scope.cancel()
    }
  }

  companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
    const val STATUS_UNSUCCESSFUL = 0xC0000001L
    const val WALL_CLOCK = 1_791_763_200_000L

    /** Le richieste che possono cambiare un file o una cartella. */
    val CHANGING = setOf(SimOp.OPEN_CHANGE, SimOp.DELETE, SimOp.DELETE_ON_CLOSE, SimOp.RENAME, SimOp.CREATE, SimOp.MKDIR, SimOp.RMDIR, SimOp.OPEN_WRITE)
  }
}
