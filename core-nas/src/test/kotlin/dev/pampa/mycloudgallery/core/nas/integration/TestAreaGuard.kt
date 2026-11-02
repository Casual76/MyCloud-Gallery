package dev.pampa.mycloudgallery.core.nas.integration

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

// La guardia dell'area di prova sul NAS vero (docs/fase4-progetto.md §5.4, D30). Due livelli, entrambi PRIMA di
// qualunque chiamata SMB:
// 1. la configurazione (account e share) si valida prima di aprire un socket: share ammesse = quelle del file ∩ elenco
//    scritto nel codice (TestShares), share di famiglia rifiutate in ogni forma; un errore nel file non allarga il
//    recinto, fa fallire la suite;
// 2. ogni percorso: a livello delle interfacce ([TestAreaMedia]) e a livello del trasporto ([TestAreaTransport]), che
//    vede anche i temporanei interni di core-nas. Solo `mcg-it/<giro>/` (senza distinguere le maiuscole, come Samba).
// Nessun test della Fase 4 usa smbj direttamente (IntegrationSourcesTest).

/** Un accesso fuori dall'area di prova: lanciata **prima** della chiamata, mai dopo. */
class TestAreaViolation(message: String) : RuntimeException(message)

/**
 * Cio' che il trasporto recintato ([TestAreaTransport]) chiede a una guardia, **prima** di ogni chiamata: account, share e percorso
 * con l'operazione. Due guardie: [TestAreaGuard] (solo `mcg-it/<giro>/`, D30) e, dalla Fase 10 P11 seconda parte, `FamilyAreaGuard`
 * (l'area della famiglia di prova e `test-comune/mcg-ostile/`, con il registro di cio' che la prova ha creato). [created] arriva
 * **dopo** una creazione, una cartella nuova o una rinomina riuscite: la guardia ne tiene il conto, se le serve.
 */
internal interface SmbAreaGuard {
  fun checkAccount(user: String)

  fun checkShare(share: String): String

  fun checkSmb(share: String, raw: String, op: SmbAccess)

  fun created(share: String, raw: String, op: SmbAccess) = Unit
}

/**
 * Il recinto di un giro della suite. Si costruisce solo con [of], che valida la configurazione; poi [checkShare],
 * [checkPath] (interfacce) e [checkSmb] (trasporto) rifiutano tutto cio' che non sta sotto `mcg-it/<giro>/`.
 */
internal class TestAreaGuard private constructor(
  val runId: String,
  /** Le share ammesse, in minuscolo: intersezione fra il file e `TestShares.SHARES`. */
  val shares: Set<String>,
  /** Gli account ammessi, in minuscolo. */
  val accounts: Set<String>,
) : SmbAreaGuard {
  /** La radice del giro vista dalle interfacce: `mcg-it/<giro>`. */
  val root: SharePath = SharePath.of(ROOT, runId)

  /** I giri vecchi (oltre 24 ore di ora del NAS) che la pulizia puo' togliere: solo nomi nel formato dei giri. */
  private val staleRuns: MutableSet<String> = Collections.synchronizedSet(HashSet())

  /** La cartella `mcg-it` l'ha creata questo giro (allora, se vuota, la toglie anche). */
  @Volatile var createdRoot: Boolean = false

  /** La pulizia finale ha il permesso di togliere `mcg-it` se e' vuota anche se c'era gia' ([allowRootRemoval]). */
  @Volatile private var rootRemovable: Boolean = false

  override fun checkAccount(user: String) {
    if (fold(user) in FORBIDDEN_ACCOUNTS || user.lowercase(Locale.ROOT) !in accounts) throw TestAreaViolation("account non di prova")
  }

  override fun checkShare(share: String): String {
    if (!TestShares.isTestShare(share) || share.lowercase(Locale.ROOT) !in shares) throw TestAreaViolation("share fuori dall'area di prova")
    return share
  }

  /** Un percorso delle interfacce (formato `SharePath`, `/`): solo sotto `mcg-it/<giro>/`. */
  fun checkPath(share: String, path: SharePath?) {
    checkShare(share)
    val parts = path?.components.orEmpty()
    if (!inRun(parts, runId) || parts.size < 3) throw TestAreaViolation("percorso fuori dall'area di prova")
  }

  /** Una cartella da elencare: `mcg-it/<giro>` o sotto. */
  fun checkDir(share: String, dir: SharePath?) {
    checkShare(share)
    if (!inRun(dir?.components.orEmpty(), runId)) throw TestAreaViolation("cartella fuori dall'area di prova")
  }

  /** Un percorso grezzo (stringa con `/` o `\`), per l'autotest dei percorsi che `SharePath` non sa nemmeno costruire. */
  fun checkRaw(share: String, raw: String) {
    checkShare(share)
    val parts = split(raw) ?: throw TestAreaViolation("percorso non valido")
    if (!inRun(parts, runId) || parts.size < 3) throw TestAreaViolation("percorso fuori dall'area di prova")
  }

  /**
   * Un percorso del trasporto (`\`). Ammessi: tutto sotto `mcg-it/<giro>` e la cartella stessa; `mcg-it` solo per
   * crearla, elencarla (pulizia dei giri vecchi), guardarla o toglierla se vuota e creata da noi (o dopo
   * [allowRootRemoval]); sotto un giro vecchio approvato ([approveStale]) solo elenco, stato e cancellazione.
   */
  override fun checkSmb(share: String, raw: String, op: SmbAccess) {
    checkShare(share)
    val parts = split(raw) ?: throw TestAreaViolation("percorso non valido")
    val ok = when {
      inRun(parts, runId) -> true
      parts.size == 1 && same(parts[0], ROOT) ->
        op == SmbAccess.MKDIR || op == SmbAccess.LIST || op == SmbAccess.STAT ||
          (op == SmbAccess.RMDIR && (createdRoot || rootRemovable))
      parts.size >= 2 && same(parts[0], ROOT) && parts[1].lowercase(Locale.ROOT) in staleRuns ->
        op == SmbAccess.LIST || op == SmbAccess.STAT || op == SmbAccess.DELETE || op == SmbAccess.RMDIR
      else -> false
    }
    if (!ok) throw TestAreaViolation("percorso fuori dall'area di prova ($op)")
  }

  /** La cartella `mcg-it` creata da questo giro: allora, se vuota, la pulizia la toglie. */
  override fun created(share: String, raw: String, op: SmbAccess) {
    if (op == SmbAccess.MKDIR && raw.equals(ROOT, ignoreCase = true)) createdRoot = true
  }

  /**
   * Da qui in poi la pulizia finale puo' togliere `mcg-it` (solo `RMDIR`, e solo se vuota: il NAS rifiuta altrimenti)
   * anche se l'ha creata un giro precedente rimasto a meta'. Non allarga nient'altro: su `mcg-it` restano vietati
   * cancellazione di file, creazione, lettura e rinomina.
   */
  fun allowRootRemoval() {
    rootRemovable = true
  }

  /** Un giro vecchio da pulire: solo un nome nel formato dei giri, mai il giro corrente. */
  fun approveStale(name: String) {
    require(RUN_ID.matches(name) && !same(name, runId)) { "non e' un giro vecchio" }
    staleRuns += name.lowercase(Locale.ROOT)
  }

  private fun inRun(parts: List<String>, run: String): Boolean =
    parts.size >= 2 && same(parts[0], ROOT) && same(parts[1], run)

  /** Come Samba: maiuscole e minuscole uguali, nient'altro (una forma "a larghezza piena" e' un'altra cartella). */
  private fun same(a: String, b: String): Boolean = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

  /** I componenti; null se c'e' un componente vuoto, `.` o `..` (nessuna normalizzazione che possa uscire). */
  private fun split(raw: String): List<String>? {
    if (raw.isEmpty()) return emptyList()
    val parts = raw.split('/', '\\')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 0x20 } }) return null
    return parts
  }

  companion object {
    const val ROOT = "mcg-it"

    /** `aaaammgg-hhmmss-<8 esadecimali>`: il nome di un giro (la data e' solo un nome; l'eta' la dice il NAS). */
    val RUN_ID = Regex("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$")

    /** Mai questi account, in nessuna forma (NFKC, maiuscole). */
    val FORBIDDEN_ACCOUNTS: Set<String> = setOf("admin", "alessio", "pampa", "root", "administrator")

    private fun fold(name: String): String = TestShares.fold(name.trim())

    /**
     * La guardia per [credentials], **prima di qualunque connessione**. Lancia [TestAreaViolation] (la suite fallisce
     * subito) se un account non e' di prova o una share del file non e' fra quelle scritte nel codice.
     */
    fun of(credentials: ItCredentials, runId: String): TestAreaGuard {
      require(RUN_ID.matches(runId)) { "giro non valido" }
      val accounts = listOfNotNull(credentials.user, credentials.user2)
      for (account in accounts) {
        if (fold(account) in FORBIDDEN_ACCOUNTS || !TestShares.isTestAccount(account)) {
          throw TestAreaViolation("il file delle credenziali nomina un account che non e' di prova")
        }
      }
      if (credentials.password2 != null && credentials.user2 == null) throw TestAreaViolation("password2 senza user2")
      val named = listOfNotNull(credentials.share, credentials.share2, credentials.privateShare2)
      for (share in named) {
        if (!TestShares.isTestShare(share)) throw TestAreaViolation("il file delle credenziali nomina una share che non e' di prova")
      }
      credentials.chaosRounds?.let { if (it !in 0..100_000) throw TestAreaViolation("chaos.rounds non valido") }
      credentials.chaosBigFileMb?.let { if (it !in 1..4096) throw TestAreaViolation("chaos.bigFileMb non valido") }
      NasAddress.parseOrNull(credentials.host) ?: throw TestAreaViolation("host non e' un IP letterale")
      val shares = named.map { it.lowercase(Locale.ROOT) }.filter { it in TestShares.SHARES }.toSet()
      return TestAreaGuard(runId, shares, accounts.map { it.lowercase(Locale.ROOT) }.toSet())
    }
  }
}

/** Che cosa fa una chiamata del trasporto su un percorso (per [TestAreaGuard.checkSmb]). */
internal enum class SmbAccess { OPEN_READ, CREATE, MKDIR, LIST, ADD_FILE, STAT, DELETE, RMDIR, RENAME, SPACE, CHANGE }

/**
 * Il trasporto SMB dietro la guardia: ogni account, share e percorso passa da [guard] **prima** della chiamata al
 * trasporto vero; [delegateCalls] conta le chiamate arrivate davvero (l'autotest le vuole a zero per ogni rifiuto).
 * Vede anche cio' che le interfacce non mostrano: temporanei, cartelle create al volo, pulizie.
 */
internal class TestAreaTransport(private val delegate: SmbTransport, private val guard: SmbAreaGuard) : SmbTransport {

  val delegateCalls = AtomicInteger(0)

  private fun <T> call(block: () -> T): T {
    delegateCalls.incrementAndGet()
    return block()
  }

  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection =
    Connection(call { delegate.connect(binding, address, connectTimeout) })

  /** La share [inner] (gia' connessa) dietro la guardia: per l'autotest, con una share finta che conta le chiamate. */
  fun wrap(share: String, inner: SmbShare): SmbShare {
    guard.checkShare(share)
    return Share(share, inner)
  }

  private inner class Connection(private val inner: SmbConnection) : SmbConnection {
    override val negotiation = inner.negotiation
    override val isConnected: Boolean get() = inner.isConnected

    override fun authenticate(user: String, password: CharArray): SmbSession {
      guard.checkAccount(user)
      return Session(call { inner.authenticate(user, password) })
    }

    override fun close() = inner.close()
  }

  private inner class Session(private val inner: SmbSession) : SmbSession {
    override val facts = inner.facts

    override fun connectShare(name: String): SmbShare {
      guard.checkShare(name)
      return Share(name, call { inner.connectShare(name) })
    }

    override fun close() = inner.close()
  }

  private inner class Share(override val name: String, private val inner: SmbShare) : SmbShare {
    private fun check(path: String, op: SmbAccess) = guard.checkSmb(name, path, op)

    override fun openRead(path: String): SmbReadFile {
      check(path, SmbAccess.OPEN_READ)
      return call { inner.openRead(path) }
    }

    override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile {
      check(path, SmbAccess.CREATE)
      val file = WriteFile(call { inner.createExclusive(path, shareMode) })
      guard.created(name, path, SmbAccess.CREATE)
      return file
    }

    override fun mkdir(path: String) {
      check(path, SmbAccess.MKDIR)
      call { inner.mkdir(path) }
      guard.created(name, path, SmbAccess.MKDIR)
    }

    override fun openDirectory(path: String, access: DirAccess): SmbDirectory {
      check(path, if (access == DirAccess.LIST) SmbAccess.LIST else SmbAccess.ADD_FILE)
      return call { inner.openDirectory(path, access) }
    }

    override fun stat(path: String): SmbFileInfo {
      check(path, SmbAccess.STAT)
      return call { inner.stat(path) }
    }

    override fun removeEmptyDirectory(path: String) {
      check(path, SmbAccess.RMDIR)
      call { inner.removeEmptyDirectory(path) }
    }

    override fun deleteFile(path: String) {
      check(path, SmbAccess.DELETE)
      call { inner.deleteFile(path) }
    }

    override fun space(): SmbSpace = call { inner.space() }

    // Fase 4B: un file o una cartella esistenti aperti per cambiarli (rinomina o cancellazione sullo stesso handle). Il percorso
    // di partenza e quello d'arrivo passano entrambi dalla guardia prima della chiamata.
    override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle {
      check(path, SmbAccess.CHANGE)
      return ChangeHandle(path, call { inner.openForChange(path, exclusive) })
    }

    // Fase 5A (D5): la riapertura del proprio temporaneo del backup scrive come una creazione: stessa guardia di CREATE.
    override fun openExistingForWrite(path: String, shareMode: SmbOpenMode): SmbWriteFile {
      check(path, SmbAccess.CREATE)
      return WriteFile(call { inner.openExistingForWrite(path, shareMode) })
    }

    override fun close() = inner.close()

    // Fase 10 P11 (seconda parte): anche la cancellazione dallo handle passa dalla guardia, come DELETE sul percorso aperto (per
    // TestAreaGuard non cambia niente: CHANGE e DELETE valgono negli stessi posti).
    private inner class ChangeHandle(private val path: String, private val handle: SmbChangeHandle) : SmbChangeHandle by handle {
      override fun renameNoReplace(newPath: String) {
        check(newPath, SmbAccess.RENAME)
        call { handle.renameNoReplace(newPath) }
        guard.created(name, newPath, SmbAccess.RENAME)
      }

      override fun deleteOnClose() {
        check(path, SmbAccess.DELETE)
        call { handle.deleteOnClose() }
      }
    }

    private inner class WriteFile(private val file: SmbWriteFile) : SmbWriteFile by file {
      override fun renameNoReplace(newPath: String) {
        check(newPath, SmbAccess.RENAME)
        call { file.renameNoReplace(newPath) }
        guard.created(name, newPath, SmbAccess.RENAME)
      }

      override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = call { file.copyChunks(resumeKey, chunks) }

      // Le implementazioni predefinite dell'interfaccia si inoltrano esplicitamente (la delega non le copre).
      override val info: SmbFileInfo? get() = file.info

      override fun setLength(length: Long) = call { file.setLength(length) }
    }
  }
}

/**
 * La lettura dei media e la copia lato server dietro la guardia, al livello delle interfacce (§5.4, punto 2): share e
 * percorsi controllati **prima** di chiamare [reader] o [copy]. [delegateCalls] conta le chiamate passate.
 */
internal class TestAreaMedia(private val reader: NasMediaReader, private val copy: ServerCopy, private val guard: TestAreaGuard) {

  val delegateCalls = AtomicInteger(0)

  val mediaReader: NasMediaReader = object : NasMediaReader {
    override suspend fun list(
      share: String,
      dir: SharePath?,
      purpose: Purpose,
      pageSize: Int,
      onPage: suspend (List<NasEntry>) -> Unit,
    ): NasResult<ListOutcome> {
      guard.checkDir(share, dir)
      delegateCalls.incrementAndGet()
      return reader.list(share, dir, purpose, pageSize, onPage)
    }

    override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> {
      guard.checkPath(share, path)
      delegateCalls.incrementAndGet()
      return reader.stat(share, path, purpose)
    }

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
      guard.checkPath(share, path)
      delegateCalls.incrementAndGet()
      return reader.open(share, path, purpose)
    }

    override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
      delegateCalls.incrementAndGet()
      return reader.readAll(file, sink, progress)
    }
  }

  val serverCopy: ServerCopy = object : ServerCopy {
    override suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long> {
      guard.checkPath(fromShare, from)
      guard.checkPath(toShare, to)
      delegateCalls.incrementAndGet()
      return copy.copy(fromShare, from, toShare, to)
    }
  }
}
