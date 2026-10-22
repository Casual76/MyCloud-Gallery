package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// La faccia SMB del NAS del giro (600 giri, §5.5): il simulatore SMB di core-nas ([InMemorySmbBackend], semantica Samba) con la sessione
// di produzione ([SmbNasSessionManager]), la lettura vera (`SmbNasMediaReader`, che legge i modelli a blocchi da 1 MiB) e il file system
// vero (`SmbNasFileSystem`, che pubblica con un temporaneo e una rinomina senza sostituire) per la copia sul NAS. Stessa costruzione di
// `SimSmbTarget` della 4B (core-nasops/.../chaos/). I guasti sono quelli del `FaultPlan` del simulatore: la connessione cade prima o dopo
// che il server abbia fatto l'operazione, e la chiamata dopo si riconnette da sola.

class SmbNasSide(override val share: String, log: ReadLog, isModelFile: (SharePath) -> Boolean) : ChaosNasSide {
  private val monotonic = ManualMonotonicClock(START_MONOTONIC)
  private val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    addShare(share, mapOf(USER to SimAccess.READ_WRITE))
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val manager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "chaos", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
    credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = backend,
    clock = monotonic,
    io = Dispatchers.IO,
    deviceId = { "chaos-device" },
    backoff = { },
    shareGuard = ShareFence,
  )
  private val putPaths = LinkedHashSet<String>()
  private val reads = AtomicInteger()
  private val rawReader: NasMediaReader = manager.mediaReader()
  private var failShelf = false
  private var plan: FaultPlan? = null

  override val firedFaults: Int get() = plan?.fired?.size ?: 0

  override val reader: NasMediaReader = WatchedReader(rawReader, log, isModelFile)
  override val external: Set<String> get() = putPaths

  init {
    // La prima sessione (l'ora del NAS e la prova della password), come all'avvio dell'app.
    val opened = runBlocking { manager.withShare(share, Purpose.INTERACTIVE) { } }
    check(opened is NasResult.Ok) { "il simulatore non apre la sessione: $opened" }
  }

  override fun put(path: SharePath, bytes: ByteArray) {
    putPaths += path.value
    backend.put(share, path.value, bytes)
  }

  override fun change(path: SharePath, bytes: ByteArray) {
    backend.modify(share, path.value, NasContent.of(bytes))
  }

  override fun armDrops(seed: Long, percent: Int) {
    val p = percent / 200.0
    val reads = setOf(SimOp.READ, SimOp.OPEN_READ)
    plan = FaultPlan()
      .random(seed, p, FaultPhase.BEFORE, SimFault.DropConnection, reads)
      .random(seed + 1, p, FaultPhase.AFTER, SimFault.DropConnection, reads)
    backend.faults = plan
  }

  override fun armChangeAtRead(n: Int, path: SharePath, bytes: ByteArray) {
    backend.beforeOp = { op, _ ->
      if (op == SimOp.READ && reads.incrementAndGet() == n) backend.modify(share, path.value, NasContent.of(bytes))
    }
  }

  override fun heal() {
    backend.faults = null
    backend.beforeOp = null
  }

  override fun shelfWriter(device: String): ShelfWriter = SmbShelfWriter(device)

  override fun failNextShelfWrite() {
    failShelf = true
  }

  override suspend fun listAll(): Map<String, Long> = walkAll(rawReader, share)

  override suspend fun contentOf(path: SharePath): ByteArray? = readWhole(rawReader, share, path)

  override fun close() {
    try {
      runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    } finally {
      scope.cancel()
    }
  }

  /**
   * Lo scrittore dello scaffale sopra `SmbNasFileSystem` (vero): solo `devices/<io>/models/<sha256>`, scrittura esclusiva con temporaneo e
   * rinomina. Il byte-array e' quello del modello di prova (pochi MiB); lo scrittore a flusso e' un punto aperto di P3.
   */
  private inner class SmbShelfWriter(override val ownerDeviceId: String) : ShelfWriter {
    private val own = NasLayout.ROOT.child("devices").child(ownerDeviceId).child("models")

    override suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite {
      if (path.parent() != own || !HEX64.matches(path.name)) return ShelfWrite.Refused("outside_own_folder")
      if (failShelf) {
        failShelf = false
        // La scrittura cade a meta': nessun byte va sul server (il vero scrive in un temporaneo e non rinomina).
        return ShelfWrite.NetworkError
      }
      val bytes = ByteArray(source.size.toInt())
      var done = 0
      while (done < bytes.size) {
        val n = source.read(done.toLong(), bytes, done, minOf(bytes.size - done, 64 * 1024))
        if (n <= 0) return ShelfWrite.NetworkError
        done += n
      }
      return try {
        when (withContext(Dispatchers.IO) { manager.fileSystem(share).publishExclusive(path, bytes) }) {
          PublishResult.CREATED -> ShelfWrite.Written
          PublishResult.ALREADY_EXISTS -> ShelfWrite.Exists
        }
      } catch (e: NasIoException) {
        ShelfWrite.NetworkError
      }
    }

    override suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete = ShelfDelete.Failed
  }

  private companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
    val HEX64 = Regex("^[0-9a-f]{64}$")
  }
}
