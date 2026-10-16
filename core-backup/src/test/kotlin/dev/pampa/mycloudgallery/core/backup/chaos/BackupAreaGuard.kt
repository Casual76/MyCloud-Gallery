package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

// La GUARDIA del runner del backup sul NAS vero (docs/fase5-progetto.md §5.3, §5.4; stessa della 4B: F4-D30, `ChaosAreaGuard` di core-nasops).
// Due livelli, entrambi PRIMA di qualunque chiamata SMB:
//   1. la configurazione (account e share del file delle credenziali) si valida prima di aprire un socket: le share ammesse sono l'INTERSEZIONE
//      fra quelle del file e l'elenco scritto nel codice (`TestShares`); `alessio`, `pampa`, `NAS`, `Public` sono rifiutate in ogni forma; un errore
//      nel file non allarga il recinto, fa fallire la prova;
//   2. ogni chiamata passa da un decoratore che controlla la share prima di chiamare il vero ([GuardedBackupOps], [GuardedReader]).
// Il recinto dei percorsi e' per costruzione: i percorsi sono `SharePath` (niente `..`, niente assoluti), e le primitive vere girano "radicate"
// in `mcg-it/<giro>/r<N>/` (`NasBackupOpsFactory.createForTestArea`): non esiste una chiamata che esca dall'area. Un errore di autenticazione
// ferma la prova SUBITO ([AreaAuthStop]) e non ne prova un altro: una password sbagliata ripetuta blocca l'account.

/** Un accesso fuori dall'area di prova: lanciata PRIMA della chiamata, mai dopo. */
class AreaViolation(message: String) : RuntimeException(message)

/**
 * Le chiavi del file delle credenziali che il runner usa (stesso file di `NasIntegration`, §5.4). Nessuna e' mai stampata.
 *
 * ```properties
 * user=test            password=...         share=test
 * share2=test-comune   host=192.168.178.58  guid=            (facoltative)
 * user2=test2          password2=...        privateShare2=test2
 * backup.rounds=400    backup.bigFileMb=3072                 (facoltative: le interruzioni del runner e il video grande di E-B1)
 * ```
 */
class BackupAreaCredentials(
  val host: String,
  val user: String,
  val password: CharArray,
  val share: String,
  val share2: String?,
  val guid: String?,
  val user2: String?,
  val password2: CharArray?,
  val privateShare2: String?,
  val rounds: Int?,
  val bigFileMb: Int?,
) {
  override fun toString(): String = "BackupAreaCredentials(nascoste)"

  companion object {
    const val DEFAULT_HOST = "192.168.178.58"

    /** Null se mancano le chiavi obbligatorie (`user`, `password`, `share`). I numeri malformati diventano `-1` (li rifiuta la guardia). */
    fun parse(properties: Properties): BackupAreaCredentials? {
      fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
      fun number(key: String): Int? = value(key)?.let { it.toIntOrNull() ?: -1 }
      val user = value("user") ?: return null
      val password = value("password") ?: return null
      val share = value("share") ?: return null
      return BackupAreaCredentials(
        host = value("host") ?: DEFAULT_HOST,
        user = user,
        password = password.toCharArray(),
        share = share,
        share2 = value("share2"),
        guid = value("guid"),
        user2 = value("user2"),
        password2 = value("password2")?.toCharArray(),
        privateShare2 = value("privateShare2"),
        rounds = number("backup.rounds"),
        bigFileMb = number("backup.bigFileMb"),
      )
    }
  }
}

/** Il recinto di un giro del runner. Si costruisce solo con [of], che valida la configurazione. */
class BackupAreaGuard private constructor(
  val runId: String,
  /** Le share ammesse, in minuscolo: intersezione fra il file e `TestShares.SHARES`. */
  val shares: Set<String>,
  val accounts: Set<String>,
) {
  /** La radice del giro: `mcg-it/<giro>`. Tutto cio' che il runner scrive sul NAS vero sta li' sotto. */
  val root: SharePath = SharePath.of(ROOT, runId)

  /** La radice del giro [round]: `mcg-it/<giro>/r<round>` (ogni giro del runner ha la sua cartella, con la sua `.mycloudgallery/`). */
  fun roundRoot(round: Int): SharePath = root.child("r$round")

  fun checkAccount(user: String) {
    if (TestShares.fold(user.trim()) in FORBIDDEN_ACCOUNTS || user.lowercase(Locale.ROOT) !in accounts) throw AreaViolation("account non di prova")
  }

  fun checkShare(share: String): String {
    if (!TestShares.isTestShare(share) || share.lowercase(Locale.ROOT) !in shares) throw AreaViolation("share fuori dall'area di prova")
    return share
  }

  companion object {
    const val ROOT = "mcg-it"

    /** `aaaammgg-hhmmss-<8 esadecimali>`: il nome di un giro (la data e' solo un nome). */
    val RUN_ID = Regex("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$")

    /** Mai questi account, in nessuna forma (NFKC, maiuscole). */
    val FORBIDDEN_ACCOUNTS: Set<String> = setOf("admin", "alessio", "pampa", "root", "administrator")

    /** La guardia per [credentials], PRIMA di qualunque connessione: se qualcosa non va lancia [AreaViolation] e la prova fallisce subito. */
    fun of(credentials: BackupAreaCredentials, runId: String): BackupAreaGuard {
      require(RUN_ID.matches(runId)) { "giro non valido" }
      val accounts = listOfNotNull(credentials.user, credentials.user2)
      for (account in accounts) {
        if (TestShares.fold(account.trim()) in FORBIDDEN_ACCOUNTS || !TestShares.isTestAccount(account)) {
          throw AreaViolation("il file delle credenziali nomina un account che non e' di prova")
        }
      }
      if (credentials.password2 != null && credentials.user2 == null) throw AreaViolation("password2 senza user2")
      val named = listOfNotNull(credentials.share, credentials.share2, credentials.privateShare2)
      for (share in named) {
        if (!TestShares.isTestShare(share)) throw AreaViolation("il file delle credenziali nomina una share che non e' di prova")
      }
      credentials.rounds?.let { if (it !in 0..100_000) throw AreaViolation("backup.rounds non valido") }
      credentials.bigFileMb?.let { if (it !in 1..4096) throw AreaViolation("backup.bigFileMb non valido") }
      NasAddress.parseOrNull(credentials.host) ?: throw AreaViolation("host non e' un IP letterale")
      val shares = named.map { it.lowercase(Locale.ROOT) }.filter { it in TestShares.SHARES }.toSet()
      return BackupAreaGuard(runId, shares, accounts.map { it.lowercase(Locale.ROOT) }.toSet())
    }

    /** Un giro nuovo: data e ora (solo un nome) piu' otto cifre esadecimali casuali. */
    fun newRunId(nowUtc: java.time.ZonedDateTime, random: java.util.Random): String =
      java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(nowUtc) + "-" +
        ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
  }
}

/** Quante chiamate sono arrivate al vero attraverso i decoratori: l'autotest le vuole a zero per ogni rifiuto. */
class DelegateCounter {
  val calls = AtomicInteger(0)
}

/** Un errore di autenticazione fermerebbe la prova: l'account di prova non va mai riprovato dopo un rifiuto (E-R8, E-S12). */
private fun <T> NasResult<T>.stopOnAuth(): NasResult<T> {
  if (this is NasResult.Err) {
    when (error) {
      is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing ->
        throw AreaAuthStop("errore di autenticazione sul NAS di prova (${error::class.simpleName}): prova fermata subito, nessun nuovo tentativo")
      else -> Unit
    }
  }
  return this
}

/**
 * La lettura dietro la guardia: la share (e, per la sola lettura della verifica dal telefono, il percorso) si controllano PRIMA della
 * chiamata. [readRoots] = le cartelle che si possono leggere (il lettore non radicato della verifica); null = nessun limite di percorso (il
 * lettore radicato del runner non puo' uscire dalla sua radice comunque).
 */
class GuardedReader(
  private val inner: NasMediaReader,
  private val guard: BackupAreaGuard,
  private val counter: DelegateCounter = DelegateCounter(),
  private val readRoots: List<SharePath>? = null,
) : NasMediaReader {

  private fun pass(share: String, path: SharePath?) {
    guard.checkShare(share)
    if (readRoots != null) {
      if (path == null || readRoots.none { path.isWithin(it) }) throw AreaViolation("percorso fuori dall'area di prova")
    }
    counter.calls.incrementAndGet()
  }

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> {
    pass(share, dir)
    return inner.list(share, dir, purpose, pageSize, onPage).stopOnAuth()
  }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> {
    pass(share, path)
    return inner.stat(share, path, purpose).stopOnAuth()
  }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    pass(share, path)
    return inner.open(share, path, purpose).stopOnAuth()
  }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    counter.calls.incrementAndGet()
    return inner.readAll(file, sink, progress).stopOnAuth()
  }
}

/** Le primitive del backup dietro la guardia. */
class GuardedBackupOps(
  private val inner: NasBackupOps,
  private val guard: BackupAreaGuard,
  private val counter: DelegateCounter = DelegateCounter(),
) : NasBackupOps {
  override val ownerDeviceId: String get() = inner.ownerDeviceId

  private fun pass(vararg shares: String) {
    shares.forEach { guard.checkShare(it) }
    counter.calls.incrementAndGet()
  }

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> {
    pass(share)
    return inner.mkdirs(share, dir, purpose).stopOnAuth()
  }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> {
    pass(share)
    return when (val opened = inner.openTemp(share, temp, purpose).stopOnAuth()) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(GuardedWriter(opened.value))
    }
  }

  override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, purpose: Purpose): NasResult<ReopenOutcome> {
    pass(share)
    return when (val reopened = inner.reopenTemp(share, temp, keepBytes, purpose).stopOnAuth()) {
      is NasResult.Err -> reopened
      is NasResult.Ok -> when (val outcome = reopened.value) {
        is ReopenOutcome.Reopened -> NasResult.Ok(ReopenOutcome.Reopened(GuardedWriter(outcome.writer)))
        else -> reopened
      }
    }
  }

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> {
    pass(fromShare, toShare)
    return inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose).stopOnAuth()
  }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
    pass(share)
    return inner.publishTemp(share, temp, final, purpose).stopOnAuth()
  }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
    pass(share)
    return inner.deleteOwnTemp(share, temp, purpose).stopOnAuth()
  }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> {
    pass(share)
    return inner.space(share, purpose).stopOnAuth()
  }

  /** Lo scrittore dietro la guardia: un rifiuto d'autenticazione a meta' di un file ferma la prova come ogni altro. */
  private class GuardedWriter(private val inner: BackupTempWriter) : BackupTempWriter by inner {
    override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> = inner.write(offset, buf, off, len).stopOnAuth()

    override suspend fun checkpoint(): NasResult<Long> = inner.checkpoint().stopOnAuth()

    override suspend fun finish(expectedSize: Long): NasResult<TempFinish> = inner.finish(expectedSize).stopOnAuth()
  }
}

/**
 * La lettura "radicata" in [root]: ogni percorso (e la radice della share, `null`) si legge sotto `root/`. Sul NAS vero la cartella
 * `.mycloudgallery/` del giro sta in `mcg-it/<giro>/rN/.mycloudgallery/`.
 */
class RootedReader(private val inner: NasMediaReader, private val root: SharePath) : NasMediaReader {

  private fun at(path: SharePath?): SharePath = path?.let { SharePath.of(*(root.components + it.components).toTypedArray()) } ?: root

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> = inner.list(share, at(dir), purpose, pageSize, onPage)

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = inner.stat(share, at(path), purpose)

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = inner.open(share, at(path), purpose)

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
    inner.readAll(file, sink, progress)
}

/** Il NAS di un giro dietro la guardia: lettura e primitive passano dai controlli (e dai contatori) prima di arrivare al vero. */
class GuardedNasSide(
  private val inner: NasSide,
  private val guard: BackupAreaGuard,
  val counter: DelegateCounter = DelegateCounter(),
) : NasSide by inner {
  override val reader: NasMediaReader = GuardedReader(inner.reader, guard, counter)
  override val ops: NasBackupOps = GuardedBackupOps(inner.ops, guard, counter)

  override suspend fun pcPut(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    guard.checkShare(share)
    counter.calls.incrementAndGet()
    inner.pcPut(share, path, bytes, modifiedAt)
  }

  override suspend fun remove(share: String, path: String) {
    guard.checkShare(share)
    counter.calls.incrementAndGet()
    inner.remove(share, path)
  }
}

/** Un bersaglio dietro la guardia. */
class GuardedTarget(
  private val inner: BackupChaosTarget,
  private val guard: BackupAreaGuard,
  val counter: DelegateCounter = DelegateCounter(),
) : BackupChaosTarget {
  override val name: String = "${inner.name} (dietro la guardia, giro ${guard.runId.take(15)})"

  override suspend fun openWorld(round: Int): BackupChaosWorld {
    val world = inner.openWorld(round)
    return BackupChaosWorld(world.clock, GuardedNasSide(world.nas, guard, counter), world.deviceId, world.chunkBytes, world.checkpointBytes)
  }
}

/** L'autotest della guardia: tutto cio' che deve essere rifiutato lo e', con zero chiamate al vero. */
object BackupAreaSelfTest {

  class Result(val cases: Int, val failures: List<String>)

  /** [reader] e [ops] sono dietro la guardia [guard]; [counter] conta le chiamate arrivate al vero (deve restare 0). */
  suspend fun run(guard: BackupAreaGuard, reader: NasMediaReader, ops: NasBackupOps, counter: DelegateCounter): Result {
    val failures = ArrayList<String>()
    var cases = 0
    val before = counter.calls.get()
    val someTemp = dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths.tempFor(ops.ownerDeviceId, 1)

    suspend fun mustRefuse(what: String, block: suspend () -> Unit) {
      cases++
      try {
        block()
        failures += "accettato: $what"
      } catch (_: AreaViolation) {
        // atteso
      }
    }

    for (account in listOf("admin", "ALESSIO", "Pampa", "root", "Administrator", " admin ", "ａｄｍｉｎ", "test3")) {
      mustRefuse("account $account") { guard.checkAccount(account) }
    }
    for (share in listOf("alessio", "pampa", "NAS", "Public", "public", "ＮＡＳ", "test ", "ｔｅｓｔ", "test2x", "", "..", "mcg-it", "test/..")) {
      mustRefuse("share '$share'") { guard.checkShare(share) }
      mustRefuse("lettura nella share '$share'") { reader.stat(share, SharePath.of("x"), Purpose.BACKGROUND) }
      mustRefuse("elenco della share '$share'") { reader.list(share, null, Purpose.BACKGROUND) { } }
      mustRefuse("apertura nella share '$share'") { reader.open(share, SharePath.of("x"), Purpose.BACKGROUND) }
      mustRefuse("cartella nella share '$share'") { ops.mkdirs(share, SharePath.of("Backup"), Purpose.BACKGROUND) }
      mustRefuse("temporaneo nella share '$share'") { ops.openTemp(share, someTemp, Purpose.BACKGROUND) }
      mustRefuse("ripresa nella share '$share'") { ops.reopenTemp(share, someTemp, 1, Purpose.BACKGROUND) }
      mustRefuse("pubblicazione nella share '$share'") { ops.publishTemp(share, someTemp, SharePath.of("Backup", "x.jpg"), Purpose.BACKGROUND) }
      mustRefuse("cancellazione di un proprio temporaneo nella share '$share'") { ops.deleteOwnTemp(share, someTemp, Purpose.BACKGROUND) }
      mustRefuse("copia verso la share '$share'") { ops.copyToTemp("test", SharePath.of("x"), share, someTemp, null, Purpose.BACKGROUND) }
      mustRefuse("copia dalla share '$share'") { ops.copyToTemp(share, SharePath.of("x"), "test", someTemp, null, Purpose.BACKGROUND) }
      mustRefuse("spazio della share '$share'") { ops.space(share, Purpose.BACKGROUND) }
    }
    // I percorsi che escono non esistono nemmeno come SharePath: la costruzione li rifiuta, quindi non arrivano mai a una chiamata.
    for (raw in listOf("../x", "a/../../x", "/x", "\\\\server\\x", "a\\b", "x/./y", ".")) {
      cases++
      val parsed = runCatching { SharePath.parse(raw) }
      if (parsed.isSuccess) failures += "percorso accettato da SharePath: $raw"
    }
    cases++
    if (counter.calls.get() != before) failures += "il vero ha ricevuto ${counter.calls.get() - before} chiamate durante i rifiuti"
    return Result(cases, failures)
  }
}
