package dev.pampa.mycloudgallery.core.nas.testing

import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.DirEntry
import dev.pampa.mycloudgallery.core.nas.contract.DirPage
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasHousekeeping
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

// I fake di core-nas (D6): in src/main per i test dei moduli a valle (core-family, feature-*). Mai nella
// release (tools/check.ps1). Il simulatore SMB con semantica Samba completa (InMemorySmbBackend dietro
// SmbTransport, §5.1) e' di P1 e vivra' accanto a questi.

/**
 * Una share in memoria con la semantica che conta per chi sta sopra `NasFileSystem`: nomi **senza
 * distinzione di maiuscole** (come Samba sul NAS), `publishExclusive` che non sovrascrive mai, `read` che non
 * restituisce oltre il tetto, `list` con limite e `truncated`. Le cartelle esistono implicitamente.
 *
 * Non e' il simulatore SMB di P1: non sa niente di sessioni, cifratura o errori di rete. Per quelli si
 * imposta [failWith] (ogni chiamata lancia `NasIoException`).
 */
class InMemoryNasFileSystem(override val shareName: String = "test-comune") : NasFileSystem {

  private val files = LinkedHashMap<String, Pair<SharePath, ByteArray>>()

  /** Se non null, ogni operazione fallisce con questo errore. */
  var failWith: NasError? = null

  /** Quante pubblicazioni sono state tentate (anche quelle rifiutate perche' il nome esisteva). */
  var publishAttempts: Int = 0
    private set

  private fun key(path: SharePath): String = path.value.lowercase(Locale.ROOT)

  private fun failIfRequested() {
    failWith?.let { throw NasIoException(it) }
  }

  @Synchronized
  override fun list(dir: SharePath, limit: Int): NasListing {
    failIfRequested()
    val prefix = key(dir) + "/"
    val names = LinkedHashSet<String>()
    for ((k, entry) in files) {
      if (!k.startsWith(prefix)) continue
      val rest = entry.first.components.drop(dir.components.size)
      if (rest.isNotEmpty()) names += rest.first()
    }
    val all = names.toList()
    return NasListing(all.take(limit), truncated = all.size > limit)
  }

  @Synchronized
  override fun read(path: SharePath, maxBytes: Int): NasRead {
    failIfRequested()
    val bytes = files[key(path)]?.second ?: return NasRead.NotFound
    return if (bytes.size > maxBytes) NasRead.TooLarge else NasRead.Data(bytes.copyOf())
  }

  @Synchronized
  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    failIfRequested()
    publishAttempts++
    if (files.containsKey(key(path))) return PublishResult.ALREADY_EXISTS
    files[key(path)] = path to bytes.copyOf()
    return PublishResult.CREATED
  }

  /** Per preparare i test: scrive (o sovrascrive) un file, come farebbe un PC o un attaccante sul NAS. */
  @Synchronized
  fun put(path: SharePath, bytes: ByteArray) {
    files[key(path)] = path to bytes.copyOf()
  }

  @Synchronized
  fun exists(path: SharePath): Boolean = files.containsKey(key(path))

  @Synchronized
  fun fileCount(): Int = files.size
}

/**
 * Sessione finta: ogni share e' un [InMemoryNasFileSystem]. Con [error] impostato, [withShare] non esegue il
 * blocco e restituisce l'errore (percorso negato, interruttore aperto...). Conta le chiamate, per verificare
 * che un lavoro non abbia toccato il NAS quando non doveva.
 */
class FakeNasSessionManager(initial: NasConnState = NasConnState.Idle) : NasSessionManager {

  override val state: MutableStateFlow<NasConnState> = MutableStateFlow(initial)
  override val lastDiskTouchAt: MutableStateFlow<Long?> = MutableStateFlow(null)

  private val shares = HashMap<String, InMemoryNasFileSystem>()

  /** Se non null, ogni [withShare] fallisce con questo errore senza eseguire il blocco. */
  var error: NasError? = null

  val calls: MutableList<Pair<String, Purpose>> = mutableListOf()
  val invalidations: MutableList<InvalidateReason> = mutableListOf()

  @Synchronized
  fun share(name: String): InMemoryNasFileSystem = shares.getOrPut(name.lowercase(Locale.ROOT)) { InMemoryNasFileSystem(name) }

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> {
    calls += share to purpose
    error?.let { return NasResult.Err(it) }
    val fs = share(share)
    val scope = object : NasShareScope {
      override val shareName: String = share
      override val fileSystem: NasFileSystem = fs
      override val browser: NasBrowser = FakeNasBrowser(fs)
    }
    return NasResult.Ok(block(scope))
  }

  override fun fileSystem(share: String): NasFileSystem = share(share)

  override fun browser(share: String): NasBrowser = FakeNasBrowser(share(share))

  override suspend fun invalidate(reason: InvalidateReason) {
    invalidations += reason
    state.value = NasConnState.Idle
  }
}

/** Sfoglia un [InMemoryNasFileSystem]: le voci sono i nomi figli, cartelle se hanno figli a loro volta. */
class FakeNasBrowser(private val fs: InMemoryNasFileSystem) : NasBrowser {
  override suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> {
    fs.failWith?.let { return NasResult.Err(it) }
    if (dir == null) return NasResult.Ok(DirPage(emptyList(), truncated = false))
    val listing = fs.list(dir, limit)
    val entries = listing.names.map { name ->
      val child = dir.child(name)
      DirEntry(name, isDirectory = !fs.exists(child), size = 0L, modifiedAt = 0L)
    }
    return NasResult.Ok(DirPage(entries, listing.truncated))
  }
}

/** Interruttore finto: si apre con [open], si richiude con [onNewPasswordSaved] (generazione + 1). */
class FakeAuthCircuitBreaker(initial: BreakerState = BreakerState.Closed(generation = 1)) : AuthCircuitBreaker {
  override val state: MutableStateFlow<BreakerState> = MutableStateFlow(initial)

  fun open(reason: AuthBlockReason, at: Long = 0L) {
    state.value = BreakerState.Open(reason, at, state.value.generation)
  }

  override suspend fun onNewPasswordSaved() {
    state.value = BreakerState.Closed(state.value.generation + 1)
  }
}

/** Test di connessione finto: restituisce [report] e ricorda cosa gli e' stato chiesto (mai la password). */
class FakeConnectionTester(var report: ConnectionReport = ConnectionReport(emptyList(), negotiation = null)) : ConnectionTester {
  val targets: MutableList<TestTarget> = mutableListOf()

  /** Quante volte e' arrivata una credenziale: con l'interruttore aperto deve restare 0. */
  var credentialsSeen: Int = 0
    private set

  override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
    targets += target
    if (credentials != null) credentialsSeen++
    return report
  }
}

class FakeShareProbe(var access: Map<String, NasResult<ShareAccess>> = emptyMap()) : ShareProbe {
  override suspend fun probe(share: String): NasResult<ShareAccess> =
    access[share] ?: NasResult.Ok(ShareAccess.MISSING)
}

/** Sorgente di credenziali finta: [load] restituisce cio' che produce [next] (una nuova istanza ogni volta). */
class FakeNasCredentialSource(var next: () -> CredentialLoad = { CredentialLoad.Absent }) : NasCredentialSource {
  var loads: Int = 0
    private set

  override suspend fun load(slot: String): CredentialLoad {
    loads++
    return next()
  }
}

class FakeNasHousekeeping(var result: NasResult<Int> = NasResult.Ok(0)) : NasHousekeeping {
  val requests: MutableList<Pair<String, Duration>> = mutableListOf()

  override suspend fun purgeOwnStaleTemps(deviceId: String, olderThan: Duration): NasResult<Int> {
    requests += deviceId to olderThan
    return result
  }
}
