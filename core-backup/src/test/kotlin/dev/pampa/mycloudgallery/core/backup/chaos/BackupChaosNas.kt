package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

// Il NAS visto dal banco: tre incarnazioni dietro la stessa interfaccia ([NasSide]). Il banco non sa se sotto c'e' il NAS finto in memoria
// (semantica del contratto, veloce), il simulatore SMB di core-nas con la sessione di produzione e le primitive vere (handle, `FILE_CREATE`,
// rinomina senza sostituire, fine file sullo stesso handle) o l'area di prova del NAS vero (BackupAreaRunner.kt). Il codice sotto prova non
// vede mai un [NasSide]: vede [ops] e [reader] avvolti dai decoratori dei colpi.

/** Cio' che un NAS sa fare oltre a cio' che fa ogni NAS. */
class NasCaps(
  /** Il tempo puo' passare (il NAS vero no: sul NAS vero non si aspettano due giorni). */
  val advanceTime: Boolean,
  /** Si puo' togliere un file dal di fuori (la pulizia dei propri file, il PC): il NAS vero no, il banco non puo' eliminare nulla. */
  val housekeeping: Boolean,
  /** 5B: il PC puo' modificare sul posto un file (byte nuovi, stesso `fileId`): il NAS vero no (il banco non sostituisce niente). */
  val pcModify: Boolean = housekeeping,
)

interface NasSide : AutoCloseable {
  /** L'area privata e l'area comune di prova, in quest'ordine. */
  val shares: List<String>

  /** La lettura e le primitive del backup SENZA colpi: per osservare, verificare e fare le azioni "da fuori". Il codice sotto prova le riceve avvolte. */
  val reader: NasMediaReader
  val ops: NasBackupOps
  val caps: NasCaps

  /** Il "PC" (o un altro dispositivo) scrive un file intero: nessun colpo. */
  suspend fun pcPut(share: String, path: String, bytes: ByteArray, modifiedAt: Long)

  /** La pulizia dei propri file toglie un file (solo con [NasCaps.housekeeping]). */
  suspend fun remove(share: String, path: String)

  /**
   * 5B: il "PC" modifica sul posto un file che c'e' (byte nuovi e data nuova, o la stessa: il marciume di un bit; stesso `fileId`), solo con
   * [NasCaps.pcModify]. Nessun colpo.
   */
  suspend fun pcModify(share: String, path: String, bytes: ByteArray, modifiedAt: Long): Unit =
    throw UnsupportedOperationException("questo NAS non lascia modificare un file dal di fuori")

  /** Fa passare [ms] (solo con [NasCaps.advanceTime]); l'orologio del banco lo sposta il mondo. */
  fun advanceTime(ms: Long)

  /** La connessione e' caduta davvero (colpo `DROP`/`STALL`): la chiamata dopo si riconnette. */
  suspend fun connectionLost()

  /**
   * Il processo e' morto: il sistema chiude i socket del processo morto e il NAS chiude i suoi handle (le scritture a meta', l'apertura di cui
   * la risposta e' andata persa). Il processo nuovo parte con una connessione nuova (la lezione della prova F16 della 4B, docs/prova-500.md §6bis).
   */
  suspend fun processDied() = connectionLost()

  /** Un contenuto virtuale grande che il NAS finto non deve tenere in memoria. */
  fun registerKnown(content: NasContent) = Unit

  /** Una riga per il rapporto (mai nomi di file ne' credenziali). */
  fun describe(): String
}

/** Il NAS finto in memoria (`InMemoryNasMedia`): semantica Samba del contratto, nessun handle, nessuna rete. */
class FakeNasSide(private val clock: FakeBackupClock, deviceId: String, override val shares: List<String> = listOf(PRIVATE, COMMON)) : NasSide {
  /** Il NAS finto: lo vedono anche i test (contenuti, file bloccati). */
  val nas: InMemoryNasMedia = InMemoryNasMedia(nasNow = { clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW }).also { n -> shares.forEach(n::addShare) }

  override val reader: NasMediaReader = nas.reader
  override val ops: NasBackupOps = nas.backupOps(deviceId)
  override val caps: NasCaps = NasCaps(advanceTime = true, housekeeping = true)

  override suspend fun pcPut(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    nas.putFile(share, path, NasContent.of(bytes), modifiedAt = modifiedAt)
  }

  override suspend fun remove(share: String, path: String) {
    nas.delete(share, path)
  }

  override suspend fun pcModify(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    nas.modify(share, path, NasContent.of(bytes), modifiedAt = modifiedAt)
  }

  override fun advanceTime(ms: Long) = Unit

  override suspend fun connectionLost() = Unit

  override fun registerKnown(content: NasContent) = nas.registerKnownContent(content)

  override fun describe(): String = "NAS finto in memoria, share ${shares.joinToString()}"

  override fun close() = Unit

  companion object {
    const val PRIVATE = "test"
    const val COMMON = "test-comune"
  }
}

/**
 * Il simulatore SMB di core-nas ([InMemorySmbBackend]) dietro la sessione di produzione ([SmbNasSessionManager]) con le primitive vere del
 * backup (`NasBackupOpsFactory` → `SmbNasBackupOps` → le primitive SMB della 4B) e la lettura vera (`readAll` a 4 flussi). Un simulatore
 * nuovo a ogni giro. Il tempo e' virtuale: l'ora del NAS e l'orologio della sessione avanzano insieme.
 */
class SimSmbNasSide(private val clock: FakeBackupClock, deviceId: String) : NasSide {
  override val shares: List<String> = listOf(FakeNasSide.PRIVATE, FakeNasSide.COMMON)

  private val monotonic = ManualMonotonicClock(START_MONOTONIC)
  private var offset = 0L

  val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    shares.forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
    val base = nasTime()
    nasTime = { base + offset }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val manager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "slot-prova", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("wifi-casa")) },
    credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope, wallClock = { WALL_CLOCK }),
    scope = scope,
    transport = backend,
    clock = monotonic,
    io = Dispatchers.Unconfined,
    deviceId = { deviceId },
    backoff = { },
    shareGuard = null,
  )

  override val reader: NasMediaReader = manager.mediaReader()
  override val ops: NasBackupOps = NasBackupOpsFactory.create(manager, deviceId)
  override val caps: NasCaps = NasCaps(advanceTime = true, housekeeping = true)

  override suspend fun pcPut(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    backend.put(share, path, bytes, lastWriteMs = modifiedAt)
  }

  override suspend fun remove(share: String, path: String) {
    backend.delete(share, path)
  }

  override suspend fun pcModify(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    backend.put(share, path, bytes, lastWriteMs = modifiedAt)
  }

  override fun advanceTime(ms: Long) {
    offset += ms
    monotonic.advance(ms)
  }

  override suspend fun connectionLost() {
    backend.dropAllConnections()
    manager.invalidate(InvalidateReason.SUSPECT_CONNECTION)
  }

  override fun describe(): String = "simulatore SMB di core-nas con la sessione e le primitive del backup vere, share ${shares.joinToString()}"

  override fun close() {
    try {
      runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    } finally {
      scope.cancel()
    }
  }

  /** I file della share nel simulatore, per i test (percorso → byte). */
  fun filePaths(share: String, dir: String = ""): List<String> = backend.children(share, dir).flatMap { name ->
    val path = if (dir.isEmpty()) name else "$dir/$name"
    if (backend.bytes(share, path) != null) listOf(path) else filePaths(share, path)
  }

  companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova-lunga"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
    const val WALL_CLOCK = 1_791_763_200_000L
  }
}

// =============================================================================================================================
// La scansione completa del NAS (la rilettura finale, senza cache)
// =============================================================================================================================

/** Da dove si scansiona: una share dalla radice o da una cartella (`dir` null = la radice). */
class ScanRoot(val share: String, val dir: SharePath? = null)

/** L'esito di una scansione: i file, i problemi di lettura (invariante 11) e quanto e' stato riletto. */
class ScanResult(
  val files: List<NasFileFact>,
  val problems: List<String>,
  val filesHashed: Int,
  val bytesHashed: Long,
  val cacheHits: Int,
)

/**
 * Elenca tutta la parte del NAS che interessa (ogni share, ricorsivamente, compresa `.mycloudgallery/`) e rilegge **per intero** ogni file
 * con lo SHA-256 (`readAll`): e' l'osservazione indipendente dal motore, con il lettore SENZA colpi. Con [useCache] un file che non e'
 * cambiato (stesso percorso, dimensione, data e `fileId`) non si rilegge nelle verifiche intermedie (il NAS vero con file da centinaia di MB);
 * la scansione finale si chiede sempre con `allowCache = false`: l'invariante 5.
 */
class BackupNasScanner(
  private val reader: NasMediaReader,
  private val roots: List<ScanRoot>,
  private val useCache: Boolean = false,
) {
  companion object {
    /** Tutte le share per intero, dalla radice. */
    fun ofShares(reader: NasMediaReader, shares: List<String>, useCache: Boolean = false): BackupNasScanner =
      BackupNasScanner(reader, shares.map { ScanRoot(it) }, useCache)
  }

  private data class CacheKey(val share: String, val path: String, val size: Long, val modifiedAt: Long, val fileId: Long?)

  private val cache = HashMap<CacheKey, Sha256>()

  suspend fun scan(allowCache: Boolean = useCache): ScanResult {
    val files = ArrayList<NasFileFact>()
    val problems = ArrayList<String>()
    var hashed = 0
    var bytes = 0L
    var hits = 0
    for ((share, start) in roots.map { it.share to it.dir }) {
      val found = ArrayList<Pair<SharePath, NasEntry>>()
      walk(share, start, found, problems)
      for ((path, entry) in found) {
        val key = CacheKey(share.lowercase(Locale.ROOT), path.value.lowercase(Locale.ROOT), entry.size, entry.modifiedAt, entry.fileId)
        val cached = if (allowCache) cache[key] else null
        if (cached != null) {
          hits++
          files += NasFileFact(share, path.value, entry.size, entry.fileId, cached)
          continue
        }
        val sha = hash(share, path, problems) ?: continue
        hashed++
        bytes += entry.size
        if (useCache) cache[key] = sha
        files += NasFileFact(share, path.value, entry.size, entry.fileId, sha)
      }
    }
    return ScanResult(files, problems, hashed, bytes, hits)
  }

  private suspend fun walk(share: String, dir: SharePath?, out: MutableList<Pair<SharePath, NasEntry>>, problems: MutableList<String>) {
    val entries = ArrayList<NasEntry>()
    var attempt = 0
    while (true) {
      entries.clear()
      val listed = reader.list(share, dir, Purpose.BACKGROUND) { entries += it }
      if (listed is NasResult.Ok) break
      // Una cartella che non c'e' ancora (la radice del giro sul NAS vero) e' una cartella vuota.
      if ((listed as NasResult.Err).error == dev.pampa.mycloudgallery.core.nas.contract.NasError.NotFound) return
      if (++attempt >= Attempts.READ_ATTEMPTS) {
        problems += "elenco di una cartella non riuscito (${listed.error::class.simpleName})"
        return
      }
    }
    for (entry in entries) {
      val child = try {
        dir?.child(entry.name) ?: SharePath.of(entry.name)
      } catch (_: Exception) {
        problems += "un nome del NAS che SharePath rifiuta"
        continue
      }
      if (entry.isDirectory) walk(share, child, out, problems) else out += child to entry
    }
  }

  private suspend fun hash(share: String, path: SharePath, problems: MutableList<String>): Sha256? {
    var last: String? = null
    repeat(Attempts.READ_ATTEMPTS) {
      when (val opened = reader.open(share, path, Purpose.BACKGROUND)) {
        is NasResult.Err -> last = "apertura non riuscita (${opened.error::class.simpleName})"
        is NasResult.Ok -> {
          val read = opened.value.use { reader.readAll(it, { _, _ -> }, null) }
          when (read) {
            is NasResult.Ok -> return read.value
            is NasResult.Err -> last = "lettura non riuscita (${read.error::class.simpleName})"
          }
        }
      }
    }
    problems += "un file non si rilegge: $last"
    return null
  }

  private object Attempts {
    const val READ_ATTEMPTS = 3
  }
}
