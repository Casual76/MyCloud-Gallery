package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseCodes
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionCommonProbe
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionFsKit
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionObserver
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasConfig
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasWiring
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * IL SIMULATORE SMB DEL BANCO (600 giri a ogni `check.ps1`): `InMemorySmbBackend` di core-nas (semantica Samba) con la **sessione di produzione**
 * (`SmbNasSessionManager`, una per dispositivo, con il suo trasporto che colpisce), le **vere** primitive del NAS (sotto `FacesNasWiring`) e il **vero**
 * `NasFacesEraser` per la cancellazione; il pubblicatore, la replica e le prenotazioni dei volti passano da `NasSessionManager.fileSystem(share)` come li legherà
 * `:app` (P13). I colpi stanno nel trasporto ([FacesFaultingTransport]), mai nel codice di produzione.
 */
internal class SmbChaosNas(
  private val inj: ChaosInjector,
  private val logs: ChaosLogs,
  private val world: ChaosWorld,
  override val privateShare: String = "test",
  override val commonShare: String = "test-comune",
) : ChaosNas {
  override val name: String = "simulatore SMB (sessione di produzione, primitive vere del NAS e vero NasFacesEraser)"
  override val real: Boolean = false
  override var frozen: Boolean = false
  override var recycleBin: Boolean = false
  override var maxFilesPerRound: Int = 1_000

  val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    listOf(privateShare, commonShare).forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
    nasTime = { world.clock.nasMs }
  }

  /** Le identita' dei file che il catalogo vede come media (per la regola "mai un media"). */
  private val media: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())

  private class Conn(val manager: SmbNasSessionManager, val transport: FacesFaultingTransport, val scope: CoroutineScope)

  private val connections = HashMap<Int, Conn>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val watcher: SmbNasSessionManager = manager(backend, "oracolo")

  init {
    backend.beforeOp = { op, path ->
      // vfs_recycle: un file che si cancella finisce nel cestino con il suo percorso (solo nelle aree private).
      val clean = path.replace('\\', '/')
      if (recycleBin && op == SimOp.DELETE_ON_CLOSE && clean.isNotEmpty() && !clean.startsWith("$BIN/")) {
        backend.bytes(privateShare, clean)?.let { backend.put(privateShare, "$BIN/$clean", it) }
      }
    }
  }

  private fun manager(transport: SmbTransport, deviceId: String): SmbNasSessionManager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "faces", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
    credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope, wallClock = { WALL_CLOCK }),
    scope = scope,
    transport = transport,
    clock = MonotonicClock { world.clock.monotonicMs },
    io = Dispatchers.Unconfined,
    deviceId = { deviceId },
    backoff = { },
    shareGuard = ShareFence,
  )

  override fun binCopies(): Int = if (recycleBin || backend.exists(privateShare, BIN)) walk(privateShare, BIN).size else 0

  override fun emptyRecycleBin() {
    walk(privateShare, BIN).forEach { backend.delete(privateShare, it.value) }
  }

  override fun kit(device: ChaosDevice): ChaosKit {
    connections.remove(device.index)?.let { runCatching { it.transport.cutAll() } }
    val transport = FacesFaultingTransport(backend, inj, device.index) { device.online }
    val manager = manager(transport, device.id)
    connections[device.index] = Conn(manager, transport, scope)
    val kit = sessionFsKit(
      manager, privateShare, device.id, { commonShare }, device.identity, device.publishSwitch, world.clock,
      FacesWriteGate { if (frozen) FacesWriteBlock.FROZEN else null }, logs.written, logs.attempts,
      { path -> world.writeAttempt(device, path) },
    )
    return ChaosKit(kit.remote, kit.leases)
  }

  override fun eraseOps(device: ChaosDevice, facts: FacesAccountFacts): FacesEraseOps {
    val conn = connections.getValue(device.index)
    val services = FacesNasWiring.build(
      conn.manager,
      FacesNasConfig(
        deviceId = device.id,
        accountId = "test",
        facts = SmbEraseFacts(facts),
        privateShare = { privateShare },
        commonShare = { commonShare },
        gate = { if (frozen) FacesEraseStop.FROZEN else null },
        clocks = object : OpClocks {
          override fun monotonicMs(): Long = world.clock.monotonicMs

          override fun nasNowMs(): Long? = world.clock.nasMs
        },
        registry = { world.family.state },
        publishedSegments = { g -> device.local.publishStore.load(g).diary },
        catalogPresence = CatalogPresence { _, ids -> ids.filter { it in media }.associateWith { listOf(SharePath.of("Foto", "altrove.jpg")) } },
        maxFilesPerRound = maxFilesPerRound,
      ),
    )
    return SmbEraseOps(services.eraser, privateShare)
  }

  override fun sever(device: Int) {
    connections[device]?.let { runCatching { it.transport.cutAll() } }
  }

  override fun paths(): List<SharePath> = walk(privateShare, "")

  override fun bytes(path: SharePath): ByteArray? = backend.bytes(privateShare, path.value)

  override fun commonPaths(): List<SharePath> = walk(commonShare, "")

  override fun plant(path: SharePath, bytes: ByteArray) {
    backend.put(privateShare, path.value, bytes)
    if (path.value.startsWith("Foto/")) backend.fileId(privateShare, path.value)?.let { media += it }
  }

  override fun removeAsUser(path: SharePath) {
    backend.delete(privateShare, path.value)
  }

  override fun commonProbe(device: ChaosDevice): Pair<Boolean, Int> =
    sessionCommonProbe(watcher, commonShare, device.id, { backend.shareOps.get() }) { remote -> remote.generations() is RemoteResult.Failed }

  override fun observer(device: ChaosDevice): NasFacesRemote = sessionObserver(watcher, privateShare, device.id) { commonShare }

  override fun pauseFaults(): AutoCloseable = AutoCloseable { }

  override fun close() {
    connections.values.forEach { runCatching { it.transport.cutAll() } }
    connections.clear()
    runCatching { runBlocking { watcher.invalidate(dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason.USER_REQUEST) } }
    scope.cancel()
  }

  /** Tutti i file sotto [dir] della share [share] (l'oracolo del simulatore: legge lo stato del server, mai una richiesta). */
  private fun walk(share: String, dir: String): List<SharePath> {
    val out = ArrayList<SharePath>()
    fun visit(current: String) {
      for (child in backend.children(share, current)) {
        val path = if (current.isEmpty()) child else "$current/$child"
        if (backend.bytes(share, path) != null) out += SharePath.parse(path) else visit(path)
      }
    }
    if (dir.isEmpty() || backend.exists(share, dir)) visit(dir)
    return out
  }

  companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
    const val HOST = "192.168.178.58"
    const val WALL_CLOCK = 1_791_763_200_000L

    /** Il cestino di rete delle aree private (`vfs_recycle`), il primo nome dell'elenco di core-nasops. */
    const val BIN = ".recycle"
  }
}

/** Il simulatore SMB (nessuna rete). */
internal object SmbTarget : ChaosTargetFactory {
  override val name: String = "simulatore SMB (sessione di produzione, primitive vere del NAS e vero NasFacesEraser)"
  override val capabilities: Capabilities = Capabilities.SIMULATOR

  override fun create(world: ChaosWorld): ChaosNas = SmbChaosNas(world.inj, world.logs, world)
}
