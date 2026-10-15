package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

// La GUARDIA del runner sul NAS vero (docs/fase4-progetto.md §5.4, D30): stesse regole di `TestAreaGuard` di core-nas, riscritte qui perche'
// quella sta nei test di core-nas e questo modulo non la vede. Due livelli, entrambi PRIMA di qualunque chiamata SMB:
//   1. la configurazione (account e share del file delle credenziali) si valida prima di aprire un socket: le share ammesse sono
//      l'INTERSEZIONE fra quelle del file e l'elenco scritto nel codice (`TestShares`); `alessio`, `pampa`, `NAS`, `Public` sono rifiutate in ogni
//      forma; un errore nel file non allarga il recinto, fa fallire la prova;
//   2. ogni chiamata passa da un decoratore che controlla la share prima di chiamare il vero ([GuardedReader], [GuardedFileOps]).
// Il recinto dei percorsi e' per costruzione: i percorsi sono `SharePath` (niente `..`, niente percorsi assoluti), e le primitive vere
// girano "radicate" in `mcg-it/<giro>/` (`root`, come `RootedNasFileSystem`): non esiste una chiamata che esca dall'area.

/** Un accesso fuori dall'area di prova: lanciata PRIMA della chiamata, mai dopo. */
class AreaViolation(message: String) : RuntimeException(message)

/**
 * Le chiavi del file delle credenziali che il runner usa (stesso file di `NasIntegration`, §5.4). Nessuna e' mai stampata.
 *
 * ```properties
 * user=test            password=...         share=test
 * share2=test-comune   host=192.168.178.58  guid=            (facoltative)
 * user2=test2          password2=...        privateShare2=test2
 * chaos.rounds=400     chaos.bigFileMb=200                   (facoltative: le interruzioni del runner e il file grande)
 * ```
 */
class ChaosCredentials(
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
  override fun toString(): String = "ChaosCredentials(nascoste)"

  companion object {
    const val DEFAULT_HOST = "192.168.178.58"

    /** Null se mancano le chiavi obbligatorie (`user`, `password`, `share`). I numeri malformati diventano `-1` (li rifiuta la guardia). */
    fun parse(properties: Properties): ChaosCredentials? {
      fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
      fun number(key: String): Int? = value(key)?.let { it.toIntOrNull() ?: -1 }
      val user = value("user") ?: return null
      val password = value("password") ?: return null
      val share = value("share") ?: return null
      return ChaosCredentials(
        host = value("host") ?: DEFAULT_HOST,
        user = user,
        password = password.toCharArray(),
        share = share,
        share2 = value("share2"),
        guid = value("guid"),
        user2 = value("user2"),
        password2 = value("password2")?.toCharArray(),
        privateShare2 = value("privateShare2"),
        rounds = number("chaos.rounds"),
        bigFileMb = number("chaos.bigFileMb"),
      )
    }
  }
}

/** Il recinto di un giro del runner. Si costruisce solo con [of], che valida la configurazione. */
class ChaosAreaGuard private constructor(
  val runId: String,
  /** Le share ammesse, in minuscolo: intersezione fra il file e `TestShares.SHARES`. */
  val shares: Set<String>,
  val accounts: Set<String>,
) {
  /** La radice del giro: `mcg-it/<giro>`. Tutto cio' che il runner scrive sul NAS vero sta li' sotto. */
  val root: SharePath = SharePath.of(ROOT, runId)

  /** La radice del giro [round]: `mcg-it/<giro>/r<round>` (ogni giro del runner ha la sua cartella dell'app). */
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
    fun of(credentials: ChaosCredentials, runId: String): ChaosAreaGuard {
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
      credentials.rounds?.let { if (it !in 0..100_000) throw AreaViolation("chaos.rounds non valido") }
      credentials.bigFileMb?.let { if (it !in 1..4096) throw AreaViolation("chaos.bigFileMb non valido") }
      NasAddress.parseOrNull(credentials.host) ?: throw AreaViolation("host non e' un IP letterale")
      val shares = named.map { it.lowercase(Locale.ROOT) }.filter { it in TestShares.SHARES }.toSet()
      return ChaosAreaGuard(runId, shares, accounts.map { it.lowercase(Locale.ROOT) }.toSet())
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

/** La lettura dietro la guardia: la share si controlla PRIMA della chiamata. */
class GuardedReader(
  private val inner: NasMediaReader,
  private val guard: ChaosAreaGuard,
  private val counter: DelegateCounter = DelegateCounter(),
) : NasMediaReader {

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> {
    guard.checkShare(share)
    counter.calls.incrementAndGet()
    return inner.list(share, dir, purpose, pageSize, onPage)
  }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> {
    guard.checkShare(share)
    counter.calls.incrementAndGet()
    return inner.stat(share, path, purpose)
  }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    guard.checkShare(share)
    counter.calls.incrementAndGet()
    return inner.open(share, path, purpose)
  }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    counter.calls.incrementAndGet()
    return inner.readAll(file, sink, progress)
  }
}

/** Le primitive di scrittura dietro la guardia. */
class GuardedFileOps(
  private val inner: NasFileOps,
  private val guard: ChaosAreaGuard,
  private val counter: DelegateCounter = DelegateCounter(),
) : NasFileOps {
  override val ownerDeviceId: String get() = inner.ownerDeviceId

  private fun pass(vararg shares: String) {
    shares.forEach { guard.checkShare(it) }
    counter.calls.incrementAndGet()
  }

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> {
    pass(share)
    return inner.mkdirs(share, dir, purpose)
  }

  override suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> {
    pass(share)
    return inner.rename(share, from, to, expect, purpose)
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
    return inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose)
  }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> {
    pass(share)
    return inner.openTemp(share, temp, purpose)
  }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
    pass(share)
    return inner.publishTemp(share, temp, final, purpose)
  }

  override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> {
    pass(share)
    return inner.trashMove(share, path, trashId, expect, purpose)
  }

  override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose): NasResult<DeleteOutcome> {
    pass(share)
    return inner.deleteIfUnchanged(share, path, size, purpose)
  }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
    pass(share)
    return inner.deleteOwnTemp(share, temp, purpose)
  }

  override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> {
    pass(share)
    return inner.rmdirIfEmpty(share, dir, purpose)
  }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> {
    pass(share)
    return inner.space(share, purpose)
  }
}

/**
 * La lettura "radicata" in [root]: ogni percorso (e la radice della share, `null`) si legge sotto `root/`. E' il lato lettura di
 * `RootedNasFileSystem`: sul NAS vero la cartella `.mycloudgallery/` del giro sta in `mcg-it/<giro>/rN/.mycloudgallery/`.
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

/** L'autotest della guardia (§5.4, punto 4): tutto cio' che deve essere rifiutato lo e' davvero, con zero chiamate al vero. */
object ChaosAreaSelfTest {

  class Result(val cases: Int, val failures: List<String>)

  /** [reader] e [fileOps] sono dietro la guardia [guard]; [counter] conta le chiamate arrivate al vero (deve restare 0). */
  suspend fun run(guard: ChaosAreaGuard, reader: NasMediaReader, fileOps: NasFileOps, counter: DelegateCounter): Result {
    val failures = ArrayList<String>()
    var cases = 0
    val before = counter.calls.get()

    fun mustRefuse(what: String, block: () -> Unit) {
      cases++
      try {
        block()
        failures += "accettato: $what"
      } catch (_: AreaViolation) {
        // atteso
      }
    }

    suspend fun mustRefuseAsync(what: String, block: suspend () -> Unit) {
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
      mustRefuseAsync("lettura nella share '$share'") { reader.stat(share, SharePath.of("x"), Purpose.BACKGROUND) }
      mustRefuseAsync("elenco della share '$share'") { reader.list(share, null, Purpose.BACKGROUND) { } }
      mustRefuseAsync("scrittura nella share '$share'") { fileOps.mkdirs(share, SharePath.of("x"), Purpose.BACKGROUND) }
      mustRefuseAsync("copia verso la share '$share'") {
        fileOps.copyToTemp("test", SharePath.of("x"), share, SharePath.of(".mycloudgallery", "tmp", "${fileOps.ownerDeviceId}-x"), null, Purpose.BACKGROUND)
      }
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
