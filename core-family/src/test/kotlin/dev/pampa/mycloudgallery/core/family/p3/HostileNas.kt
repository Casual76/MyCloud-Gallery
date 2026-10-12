package dev.pampa.mycloudgallery.core.family.p3

import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasBrowser
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Una share **ostile** in memoria: la semantica di Samba che conta (nomi senza maiuscole, `publishExclusive`
 * che non sovrascrive, `read` con tetto, `list` con limite) piu' tutto cio' che puo' fare chi ha accesso SMB
 * in scrittura senza chiavi: scrivere e sostituire file, cancellarli, inondare una cartella, servire un file
 * troncato, mettere i propri nomi in cima all'elenco, far fallire le operazioni con un errore NAS.
 */
class HostileNas(override val shareName: String) : NasFileSystem {

  private val files = LinkedHashMap<String, Pair<SharePath, ByteArray>>()
  private val attackerKeys = HashSet<String>()
  private val truncated = HashMap<String, Int>()

  /** Se non null, ogni operazione lancia `NasIoException` con questo errore (come il `NasFileSystem` di core-nas). */
  var failWith: NasError? = null

  /** L'elenco mette per primi i nomi dell'attaccante: con un'inondazione oltre il tetto i nomi veri spariscono. */
  var adversarialListing: Boolean = false

  var reads: Int = 0
    private set
  var publishes: Int = 0
    private set

  private fun key(path: SharePath) = path.value.lowercase(Locale.ROOT)

  private fun failIfAsked() {
    failWith?.let { throw NasIoException(it) }
  }

  @Synchronized
  override fun list(dir: SharePath, limit: Int): NasListing {
    failIfAsked()
    val prefix = key(dir) + "/"
    val names = files.entries.filter { it.key.startsWith(prefix) }
      .map { it.value.first.components[dir.components.size] to (it.key in attackerKeys) }
      .distinctBy { it.first.lowercase(Locale.ROOT) }
      .let { all -> if (adversarialListing) all.sortedByDescending { it.second } else all.shuffled(java.util.Random(7)) }
      .map { it.first }
    return NasListing(names.take(limit), truncated = names.size > limit)
  }

  @Synchronized
  override fun read(path: SharePath, maxBytes: Int): NasRead {
    failIfAsked()
    reads++
    val bytes = files[key(path)]?.second ?: return NasRead.NotFound
    val served = truncated[key(path)]?.let { bytes.copyOf(minOf(it, bytes.size)) } ?: bytes
    return if (served.size > maxBytes) NasRead.TooLarge else NasRead.Data(served.copyOf())
  }

  @Synchronized
  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    failIfAsked()
    publishes++
    if (files.containsKey(key(path))) return PublishResult.ALREADY_EXISTS
    files[key(path)] = path to bytes.copyOf()
    return PublishResult.CREATED
  }

  // --- Porte di servizio: cio' che fa chi ha SMB in scrittura -------------------------------------------

  @Synchronized
  fun bytesAt(path: SharePath): ByteArray? = files[key(path)]?.second?.copyOf()

  @Synchronized
  fun exists(path: SharePath): Boolean = files.containsKey(key(path))

  /** Scrive o **sostituisce** un file. */
  @Synchronized
  fun attackerWrite(path: SharePath, bytes: ByteArray) {
    files[key(path)] = path to bytes.copyOf()
    attackerKeys += key(path)
    truncated.remove(key(path))
  }

  @Synchronized
  fun attackerDelete(path: SharePath) {
    files.remove(key(path))
    attackerKeys.remove(key(path))
    truncated.remove(key(path))
  }

  /** Riempie [dir] di [count] nomi qualunque. */
  @Synchronized
  fun attackerFlood(dir: SharePath, count: Int) {
    repeat(count) { attackerWrite(dir.child("x" + it.toString().padStart(6, '0')), ByteArray(1)) }
  }

  /** D'ora in poi [path] si legge troncato ai primi [bytes] byte (copia a meta', file rovinato). */
  @Synchronized
  fun attackerTruncate(path: SharePath, bytes: Int) {
    truncated[key(path)] = bytes
  }

  @Synchronized
  fun attackerRestore(path: SharePath) {
    truncated.remove(key(path))
  }

  @Synchronized
  fun paths(): List<SharePath> = files.values.map { it.first }
}

/** Le share ostili condivise da tutti i dispositivi di un test: lo stesso NAS visto da piu' telefoni. */
class HostileNasServer {
  private val shares = HashMap<String, HostileNas>()

  @Synchronized
  fun share(name: String): HostileNas = shares.getOrPut(name.lowercase(Locale.ROOT)) { HostileNas(name) }
}

/**
 * La sessione di UN dispositivo verso il [HostileNasServer]: ogni chiamata lavora sulla share vera; con
 * [error] impostato [withShare] non esegue il blocco (percorso negato, NAS spento...). Le invalidazioni
 * finiscono in [log] (per l'ordine dell'azzeramento).
 */
class HostileSessions(private val server: HostileNasServer, private val log: OperationLog? = null) : NasSessionManager {
  override val state = MutableStateFlow<NasConnState>(NasConnState.Idle)
  override val lastDiskTouchAt = MutableStateFlow<Long?>(null)

  var error: NasError? = null
  val calls: MutableList<Pair<String, Purpose>> = mutableListOf()

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> {
    calls += share to purpose
    error?.let { return NasResult.Err(it) }
    val fs = server.share(share)
    val scope = object : NasShareScope {
      override val shareName: String = share
      override val fileSystem: NasFileSystem = fs
      override val browser: NasBrowser = FakeNasBrowser(InMemoryNasFileSystem(share))
    }
    return NasResult.Ok(block(scope))
  }

  override fun fileSystem(share: String): NasFileSystem = server.share(share)

  override fun browser(share: String): NasBrowser = FakeNasBrowser(InMemoryNasFileSystem(share))

  override suspend fun invalidate(reason: InvalidateReason) {
    log?.record("sessions:invalidate:${reason.name}")
  }
}
