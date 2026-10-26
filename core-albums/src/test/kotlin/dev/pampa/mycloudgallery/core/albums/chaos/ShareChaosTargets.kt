package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsConfig
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsWiring
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

// IL NAS SOTTO IL BANCO CON I COLPI. Il motore vero delle operazioni di core-nasops (`NasOpsEngine`, la porta di completamento, il cestino) sopra la
// sessione SMB di produzione (`SmbNasSessionManager`) e le primitive vere, costruito da `NasOpsWiring` come in :app: nessuna primitiva di scrittura si
// nomina qui (Phase4/Phase7ArchitectureTest). I colpi arrivano da due parti: il trasporto ([FaultingTransport]: ogni chiamata SMB del motore, prima o
// dopo) e il diario del motore ([DyingOpsStore]: la morte a ogni salvataggio). Il trasporto sotto e' il simulatore SMB di core-nas (600 giri) o smbj
// dietro la guardia (la prova dal PC sul NAS vero): il target non lo sa.

/**
 * Il "PC" accanto al NAS: chi mette, legge, cambia e toglie file **senza passare dal motore** e senza colpi (e' l'oracolo del verificatore e il
 * mondo che cambia sotto i piedi del motore). Percorsi relativi alla radice della share, con `/`.
 */
interface ChaosAdmin : AutoCloseable {
  fun put(share: String, path: String, bytes: ByteArray)

  fun bytes(share: String, path: String): ByteArray?

  /** Tutti i file (non le cartelle) della share, come percorsi, anche quelli dell'app. */
  fun files(share: String): List<String>

  fun modify(share: String, path: String, bytes: ByteArray)

  fun delete(share: String, path: String)

  fun lock(share: String, path: String)

  fun unlock(share: String, path: String)

  /** Lo spazio libero dell'account (null = quello di serie), se il NAS lo lascia cambiare. */
  fun setFreeBytes(bytes: Long?)

  /** Il processo del telefono e' morto: il NAS vede cadere le sue connessioni. */
  fun processDied() = Unit

  override fun close() = Unit
}

/** Come si arriva al NAS: l'utente di prova, il GUID, l'indirizzo e il socket. */
class ChaosConnection(val user: String, val password: CharArray, val host: String, val guid: String, val binding: SocketBinding) {
  override fun toString(): String = "ChaosConnection(nascosta)"
}

/** Il tempo sotto il banco: finto e manuale sul simulatore (`advance` fa passare i minuti), vero sul NAS (non si fanno passare i giorni). */
class ChaosTime(val monotonic: MonotonicClock, val advance: (Long) -> Unit, val manual: Boolean)

/** Il NAS con i colpi: il motore vero sopra [faulting] (la sessione SMB), [admin] accanto. */
class ChaosSmbTarget(
  override val name: String,
  private val completions: MoveCompletionSink,
  private val injector: ShareChaosInjector,
  private val faulting: FaultingTransport,
  private val admin: ChaosAdmin,
  private val time: ChaosTime,
  private val connection: ChaosConnection,
  private val backoff: suspend (Duration) -> Unit,
  private val privateShare: String = ShareNasTarget.PRIVATE,
  private val commonShare: String = ShareNasTarget.COMMON,
) : ShareNasTarget {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val session = SmbNasSessionManager(
    account = { NasAccount(NasAddress(connection.host), connection.user, "banco", connection.guid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, connection.binding) },
    credentials = { CredentialLoad.Found(NasCredentials(connection.user, connection.password.copyOf())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = faulting,
    clock = time.monotonic,
    io = Dispatchers.IO,
    deviceId = { ShareNasTarget.DEVICE },
    backoff = backoff,
    shareGuard = ShareFence,
  )
  private val clocks = object : OpClocks {
    override fun monotonicMs(): Long = time.monotonic.nowMillis()

    override fun nasNowMs(): Long? = session.nasClock.nowMillisOrNull()
  }

  override val gates: MutableMap<String, GateStatus> = HashMap()

  /** Il diario letto dal banco (senza colpi); il motore scrive attraverso [DyingOpsStore]. */
  override val store: OpsStore = InMemoryOpsStore()

  private fun build(): NasOperations = NasOpsWiring.build(
    session, DyingOpsStore(store, injector),
    NasOpsConfig(
      deviceId = ShareNasTarget.DEVICE,
      accountId = connection.user,
      shares = { listOf(privateShare, commonShare) },
      clocks = clocks,
      gate = OpsGate { share -> gates[share] ?: GateStatus.OPEN },
      completions = completions,
    ),
  ).operations

  override var operations: NasOperations = build()
    private set

  init {
    // La prima ancora dell'ora del NAS (NEGOTIATE) e la prima prova della password, come all'avvio dell'app. UNA sola volta: se non riesce, il
    // banco si ferma (nessun nuovo tentativo).
    val opened = runBlocking { session.withShare(privateShare, Purpose.INTERACTIVE) { } }
    check(opened is NasResult.Ok) { "la sessione di prova non parte: ${(opened as NasResult.Err).error::class.simpleName}" }
  }

  /** La morte del processo: i socket cadono, il motore si ricostruisce sullo stesso diario. */
  override fun restart() {
    faulting.cutAll()
    admin.processDied()
    runBlocking { session.invalidate(InvalidateReason.SUSPECT_CONNECTION) }
    operations = build()
  }

  override fun put(share: String, path: String, bytes: ByteArray) = admin.put(share, path, bytes)

  override fun bytes(share: String, path: String): ByteArray? = admin.bytes(share, path)

  override fun files(share: String): List<String> = admin.files(share)

  /**
   * Il file come lo selezionerebbe l'utente. Un file che non c'e' e' `null`; un NAS che non risponde NON e' "il file non c'e'": il legame lo dice con
   * un'eccezione (la porta non e' disponibile adesso, si riprova), come dovra' fare quello di :app (P13).
   */
  override suspend fun item(share: String, path: SharePath): NasItem? {
    val entry = when (val result = session.mediaReader().stat(share, path, Purpose.INTERACTIVE)) {
      is NasResult.Ok -> result.value ?: return null
      is NasResult.Err -> if (result.error == NasError.NotFound) return null else throw IllegalStateException("NAS non raggiungibile adesso")
    }
    if (entry.isDirectory) return null
    return NasItem(share, path, isFolder = false, size = entry.size, stamp = entry.stamp)
  }

  override suspend fun start(plan: OpPlan): StartOutcome = operations.start(plan, TestConfirmations.forPlan(plan, clocks.monotonicMs()))

  override fun lock(share: String, path: String) = admin.lock(share, path)

  override fun unlock(share: String, path: String) = admin.unlock(share, path)

  override fun modify(share: String, path: String, bytes: ByteArray) = admin.modify(share, path, bytes)

  override fun delete(share: String, path: String) = admin.delete(share, path)

  override fun advance(ms: Long) = time.advance(ms)

  override fun setFreeBytes(bytes: Long?) = admin.setFreeBytes(bytes)

  override fun close() {
    try {
      faulting.cutAll()
      runBlocking { session.invalidate(InvalidateReason.USER_REQUEST) }
    } finally {
      scope.cancel()
      admin.close()
    }
  }
}

/** Il simulatore SMB come "PC" accanto al NAS (semantica Samba: nomi senza maiuscole, handle, rinomina senza sostituire). */
class SimAdmin(private val backend: InMemorySmbBackend) : ChaosAdmin {
  override fun put(share: String, path: String, bytes: ByteArray) = backend.put(share, path, bytes)

  override fun bytes(share: String, path: String): ByteArray? = backend.bytes(share, path)

  override fun files(share: String): List<String> = filesUnder(share, "")

  private fun filesUnder(share: String, dir: String): List<String> = backend.children(share, dir).flatMap { name ->
    val path = if (dir.isEmpty()) name else "$dir/$name"
    if (backend.bytes(share, path) != null) listOf(path) else filesUnder(share, path)
  }

  override fun modify(share: String, path: String, bytes: ByteArray) = backend.modify(share, path, NasContent.of(bytes))

  override fun delete(share: String, path: String) = backend.delete(share, path)

  override fun lock(share: String, path: String) = backend.lock(share, path)

  override fun unlock(share: String, path: String) = backend.unlock(share, path)

  override fun setFreeBytes(bytes: Long?) {
    backend.volumeFreeBytes = bytes ?: DEFAULT_FREE
  }

  override fun processDied() = backend.dropAllConnections()

  private companion object {
    const val DEFAULT_FREE = 2_420_000_000_000L
  }
}

/** Il banco sul simulatore SMB: [ChaosSmbTarget] con i colpi sul trasporto e sul diario. */
object ChaosSmbTargets {
  private const val USER = "test"
  private const val PASSWORD = "pw-del-banco"
  private const val HOST = "192.168.178.58"
  private const val START_MONOTONIC = 10_000_000L

  fun simulated(completions: MoveCompletionSink, injector: ShareChaosInjector): ChaosSmbTarget {
    val monotonic = ManualMonotonicClock(START_MONOTONIC)
    var offset = 0L
    val backend = InMemorySmbBackend().apply {
      addUser(USER, PASSWORD)
      listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON).forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
      val base = nasTime()
      nasTime = { base + offset }
    }
    val faulting = FaultingTransport(backend, injector)
    return ChaosSmbTarget(
      name = "simulatore SMB",
      completions = completions,
      injector = injector,
      faulting = faulting,
      admin = SimAdmin(backend),
      time = ChaosTime(monotonic, { ms -> offset += ms; monotonic.advance(ms) }, manual = true),
      connection = ChaosConnection(USER, PASSWORD.toCharArray(), HOST, backend.serverGuid, backend.binding("pc")),
      backoff = { },
    )
  }
}

/** Il NAS finto in memoria (`NasOpsTestKit` con il motore vero): i colpi cadono sulle porte, sulla porta di completamento e sul diario della condivisione. */
object FakeChaosTargets : ChaosTargetFactory {
  override val name: String = "NAS finto in memoria (motore vero di core-nasops)"
  override val capabilities: Capabilities = Capabilities.FAKE

  override fun create(completions: MoveCompletionSink, injector: ShareChaosInjector): ShareNasTarget =
    dev.pampa.mycloudgallery.core.albums.share.FakeNasTarget(completions)
}

/** Il simulatore SMB con la sessione e le primitive vere: in piu' ogni chiamata SMB del motore e ogni salvataggio del suo diario. */
object SimulatedChaosTargets : ChaosTargetFactory {
  override val name: String = "simulatore SMB (sessione di produzione e primitive vere)"
  override val capabilities: Capabilities = Capabilities.SIMULATED

  override fun create(completions: MoveCompletionSink, injector: ShareChaosInjector): ShareNasTarget = ChaosSmbTargets.simulated(completions, injector)
}
