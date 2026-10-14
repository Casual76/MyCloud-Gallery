package dev.pampa.mycloudgallery.core.index.publish

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Chi pubblica: la chiave di questo dispositivo, il suo account del NAS (per verificare le proprie teste) e il registro
 * accettato (lo fornisce :app da core-family; null = famiglia non pronta).
 */
class IndexIdentity(
  val signer: Signer,
  val nasAccount: String,
  private val registryProvider: suspend () -> RegistryState?,
) {
  val deviceId: String = TrustIds.deviceIdOf(signer.publicKey)

  suspend fun registry(): RegistryState? = registryProvider()

  /** Il registro solo se questo dispositivo ci puo' scrivere (approvato, E-S14). */
  suspend fun writableRegistry(): RegistryState? = registry()?.takeIf { it.canWrite(deviceId) }

  override fun toString(): String = "IndexIdentity"
}

/**
 * Le regole di scrittura nell'indice di una share, controllate **prima di firmare** ogni testa e prima di ogni
 * prenotazione: D31 qui dentro (anche se il cancello di :app sbagliasse), poi il cancello [gate] (congelamento D10,
 * salto dell'ora D32, area in sola lettura E-I19, emergenza).
 */
class IndexWriteRules(private val flags: FeatureFlags, private val gate: IndexWriteGate) {
  suspend fun blockFor(share: String): WriteBlock? {
    if (flags.indexPublishTestSharesOnly && !TestShares.isTestShare(share)) return WriteBlock.LOCAL_ONLY
    return gate.blockFor(share)
  }

  /**
   * Un blocco che non passa da solo (R2 della revisione 4A): D31 su una share vera ([WriteBlock.LOCAL_ONLY]) o un'area in
   * sola lettura per questo account ([WriteBlock.READ_ONLY_AREA], E-I19). Con questi niente va nell'outbox: la preparazione
   * resta utile in locale (replica) e non si accumula un arretrato da pubblicare mesi dopo. Gli altri blocchi (congelamento,
   * salto dell'ora, emergenza, fiducia) sono temporanei: l'outbox aspetta, con un tetto.
   */
  suspend fun permanentBlockFor(share: String): WriteBlock? {
    if (flags.indexPublishTestSharesOnly && !TestShares.isTestShare(share)) return WriteBlock.LOCAL_ONLY
    return gate.blockFor(share)?.takeIf { it == WriteBlock.READ_ONLY_AREA }
  }
}

/** Cio' che il catalogo locale sa di un file di una share: il timbro dell'ultimo elenco e l'impronta rapida letta qui. */
data class CatalogFile(val stamp: NasStamp, val quickHash: QuickHash?)

/**
 * Il catalogo locale per la regola M3 (C19): "un dispositivo pubblica nell'indice di una share solo dati ricavati da
 * file di quella share". P11 lo collega al catalogo (posizioni `Location.Nas` della share con timbro e `qh1`, schema v3
 * di P2). Il timbro da solo non basta: due file con lo stesso percorso in due share possono avere stessa dimensione e
 * stessa data; l'impronta rapida li distingue.
 */
fun interface ShareCatalog {
  /** Per ogni percorso di [share] che il catalogo conosce, cio' che ne sa; gli altri mancano dalla mappa. */
  suspend fun files(share: String, paths: Collection<SharePath>): Map<SharePath, CatalogFile>
}

/** Un catalogo in memoria (test, simulazioni): share → percorso → file. */
class MapShareCatalog : ShareCatalog {
  private val lock = Any()
  private val files = HashMap<String, HashMap<SharePath, CatalogFile>>()

  fun put(share: String, path: SharePath, stamp: NasStamp, quickHash: QuickHash?) {
    synchronized(lock) { files.getOrPut(share.lowercase(Locale.ROOT)) { HashMap() }[path] = CatalogFile(stamp, quickHash) }
  }

  fun remove(share: String, path: SharePath) {
    synchronized(lock) { files[share.lowercase(Locale.ROOT)]?.remove(path) }
  }

  override suspend fun files(share: String, paths: Collection<SharePath>): Map<SharePath, CatalogFile> = synchronized(lock) {
    val known = files[share.lowercase(Locale.ROOT)] ?: return@synchronized emptyMap()
    paths.mapNotNull { p -> known[p]?.let { p to it } }.toMap()
  }
}

/**
 * Un cambiamento che non viene da un file di quella share (regola M3) o che non si puo' pubblicare: errore di chi
 * chiama. Il messaggio e' fisso (mai percorsi o nomi, §4.3).
 */
class IndexProvenanceException(message: String) : IllegalArgumentException(message)

/** Un lucchetto per share: pubblicazione e compattazione della stessa share non si sovrappongono mai. */
class ShareLocks {
  private val lock = Any()
  private val mutexes = HashMap<String, Mutex>()

  suspend fun <T> withLock(share: String, block: suspend () -> T): T {
    val mutex = synchronized(lock) { mutexes.getOrPut(share.lowercase(Locale.ROOT)) { Mutex() } }
    return mutex.withLock { block() }
  }
}

/** Gli errori del NAS come [RemoteFailure] (nessun tipo di core-nas esce da core-index). */
internal object RemoteFailures {
  fun of(error: NasError): RemoteFailure = when (error) {
    NasError.PermissionDenied -> RemoteFailure.ACCESS_DENIED
    is NasError.ShareMissing -> RemoteFailure.SHARE_MISSING
    NasError.NasFull -> RemoteFailure.DISK_FULL
    NasError.QuotaExceeded -> RemoteFailure.QUOTA
    NasError.FileBusy -> RemoteFailure.BUSY
    NasError.Timeout, NasError.ServiceDown, NasError.SessionExpired, NasError.Stalled -> RemoteFailure.UNREACHABLE
    is NasError.NotAllowedHere, NasError.LocalNetworkBlocked, is NasError.AuthBlocked, NasError.CredentialsMissing,
    is NasError.Auth, NasError.IdentityMismatch, is NasError.InsecureSession -> RemoteFailure.NOT_ALLOWED
    // Exists, NotFound, Unexpected e gli errori che core-nas aggiungera': si riprova al giro dopo.
    else -> RemoteFailure.OTHER
  }

  /** Lo spazio e' finito: si pubblica per priorita' e il resto aspetta (E-I6). */
  fun isSpace(failure: RemoteFailure): Boolean = failure == RemoteFailure.DISK_FULL || failure == RemoteFailure.QUOTA
}

/** L'esito di una chiamata bloccante al NAS: il valore o il guasto. */
internal sealed interface Nas<out T> {
  class Ok<T>(val value: T) : Nas<T>

  class Failed(val failure: RemoteFailure) : Nas<Nothing>
}

/** Esegue [block] (bloccante, `NasFileSystem`) su [io]; un errore del NAS diventa [Nas.Failed], mai un'eccezione. */
internal suspend fun <T> nasCall(io: CoroutineDispatcher, block: () -> T): Nas<T> =
  try {
    Nas.Ok(withContext(io) { block() })
  } catch (e: NasIoException) {
    Nas.Failed(RemoteFailures.of(e.error))
  } catch (e: IOException) {
    Nas.Failed(RemoteFailure.UNREACHABLE)
  }
