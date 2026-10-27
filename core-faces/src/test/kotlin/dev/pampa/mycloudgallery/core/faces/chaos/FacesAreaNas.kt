package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionCommonProbe
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionFsKit
import dev.pampa.mycloudgallery.core.faces.sync.chaos.sessionObserver
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasConfig
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasWiring
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * IL NAS DEL BANCO DAL PC (docs/prova-500.md Parte F, §F8): un mondo per giro in `mcg-it/<giro>/r<N>/` della share di prova `test`, dietro la guardia. Sotto
 * ci sono il NAS vero (smbj e il socket diretto del PC) o, per provare tutto senza rete, il simulatore SMB: stessa sessione di produzione, stesse
 * primitive vere e stesso `NasFacesEraser` del simulatore dei 600 giri, stessi colpi nel trasporto, e in piu' il trasporto recintato e l'oracolo ([admin]).
 * L'area comune `test-comune` non si scrive mai: l'oracolo la legge soltanto, e la prova "li' non si scrive niente" non manda nessuna richiesta.
 */
internal class FacesAreaNas(
  private val area: FacesArea,
  private val inj: ChaosInjector,
  private val logs: ChaosLogs,
  private val world: ChaosWorld,
  private val rooted: FacesAreaTransport,
  private val admin: FacesAreaAdmin,
) : ChaosNas {
  override val name: String = area.describe
  override val real: Boolean = true
  override val privateShare: String = "test"
  override val commonShare: String = "test-comune"
  override var frozen: Boolean = false

  // Il cestino di rete non c'e' sulle aree private di prova: le prove dello stato del cestino stanno sul simulatore.
  override var recycleBin: Boolean = false
  override var maxFilesPerRound: Int = 1_000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val media = HashSet<Long>()

  private class Conn(val manager: SmbNasSessionManager, val transport: FacesFaultingTransport)

  private val connections = HashMap<Int, Conn>()
  private val watcher: SmbNasSessionManager = manager(rooted, "oracolo")

  private fun manager(transport: SmbTransport, deviceId: String): SmbNasSessionManager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(area.credentials.host), area.credentials.user, "faces", area.guid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, area.binding) },
    credentials = { CredentialLoad.Found(NasCredentials(area.credentials.user, area.credentials.password.copyOf())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = transport,
    clock = area.monotonic(world),
    io = if (area.realTime) Dispatchers.IO else Dispatchers.Unconfined,
    deviceId = { deviceId },
    backoff = { if (area.realTime) delay(it) },
    shareGuard = ShareFence,
  )

  override fun binCopies(): Int = 0

  override fun emptyRecycleBin() = Unit

  override fun kit(device: ChaosDevice): ChaosKit {
    connections.remove(device.index)?.let { runCatching { it.transport.cutAll() } }
    val transport = FacesFaultingTransport(rooted, inj, device.index) { device.online }
    val manager = manager(transport, device.id)
    connections[device.index] = Conn(manager, transport)
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
          override fun monotonicMs(): Long = area.monotonic(world).nowMillis()

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

  override fun paths(): List<SharePath> = admin.files(privateShare).map { SharePath.parse(it) }

  override fun bytes(path: SharePath): ByteArray? = admin.bytes(privateShare, path.value)

  override fun commonPaths(): List<SharePath> = admin.files(commonShare).map { SharePath.parse(it) }

  override fun plant(path: SharePath, bytes: ByteArray) {
    admin.put(path.value, bytes)
  }

  override fun removeAsUser(path: SharePath) = admin.delete(path.value)

  override fun commonProbe(device: ChaosDevice): Pair<Boolean, Int> =
    sessionCommonProbe(watcher, commonShare, device.id, { area.counter.calls.get() }) { remote -> remote.generations() is RemoteResult.Failed }

  override fun observer(device: ChaosDevice): NasFacesRemote = sessionObserver(watcher, privateShare, device.id) { commonShare }

  override fun pauseFaults(): AutoCloseable = AutoCloseable { }

  override fun close() {
    connections.values.forEach { runCatching { it.transport.cutAll() } }
    connections.clear()
    runCatching { runBlocking { watcher.invalidate(InvalidateReason.USER_REQUEST) } }
    scope.cancel()
    // La pulizia del mondo del giro: l'oracolo toglie tutto cio' che sta sotto la sua radice e la radice stessa.
    runCatching { admin.close() }
  }
}

internal fun FacesArea.monotonic(world: ChaosWorld): MonotonicClock =
  if (realTime) MonotonicClock { System.nanoTime() / NANOS_PER_MILLI } else MonotonicClock { world.clock.monotonicMs }

private const val NANOS_PER_MILLI = 1_000_000L
