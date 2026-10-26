package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

// L'AREA DI PROVA: tutto cio' che serve a far girare il banco della condivisione contro un NAS dietro la guardia: il trasporto vero (smbj, o il simulatore
// SMB per provare tutto senza rete), un mondo per giro in `mcg-it/<giro>/r<N>/`, l'oracolo, la pulizia, l'autotest della guardia. La prova vera sul NAS di
// casa e' `ShareNasIntegrationTest` (solo con l'interruttore e il file delle credenziali); `ShareAreaRunnerTest` fa girare lo stesso codice sul simulatore.

class ShareArea(
  val credentials: ShareAreaCredentials,
  val guard: ShareAreaGuard,
  private val inner: SmbTransport,
  private val binding: SocketBinding,
  private val guid: String,
  private val time: () -> ChaosTime,
  private val backoff: suspend (Duration) -> Unit,
  private val describe: String,
  private val cleanupRetries: Int = 6,
  private val cleanupPauseMs: Long = 20_000,
  firstRound: Int = 0,
) {
  /** "Il login e' stato rifiutato": il banco si ferma, nessun nuovo tentativo. */
  val latch = AreaAuthLatch()

  /** Le chiamate arrivate al trasporto vero attraverso la guardia, in tutto. */
  val counter = DelegateCounter()

  /** La pulizia di ogni mondo (uno per giro), nell'ordine. */
  val cleanups: MutableList<CleanupReport> = ArrayList()

  private var round = firstRound

  fun connection(): ChaosConnection = ChaosConnection(credentials.user, credentials.password.copyOf(), credentials.host, guid, binding)

  /** I NAS del banco: uno nuovo per giro, radicato in `mcg-it/<giro>/r<N>`, con l'oracolo accanto e la pulizia alla chiusura. */
  fun factory(): ChaosTargetFactory = object : ChaosTargetFactory {
    override val name: String = describe
    override val capabilities: Capabilities = Capabilities.REAL

    override fun create(completions: MoveCompletionSink, injector: ShareChaosInjector): ShareNasTarget {
      if (latch.failed) throw AreaAuthStop("login gia' rifiutato dal NAS: nessun nuovo tentativo")
      val root = guard.roundRoot(round++)
      val admin = AreaAdmin(
        transport = AreaTransport(inner, guard, root, latch, counter),
        connection = connection(),
        latch = latch,
        cleanupRetries = cleanupRetries,
        cleanupPauseMs = cleanupPauseMs,
        onCleaned = { synchronized(cleanups) { cleanups += it } },
      ).open()
      val faulting = FaultingTransport(AreaTransport(inner, guard, root, latch, counter), injector)
      return ChaosSmbTarget(describe, completions, injector, faulting, admin, time(), connection(), backoff)
    }
  }

  /** La pulizia finale: tutto cio' che sta sotto `mcg-it/<giro>` (anche cio' che un giro rosso ha lasciato) e poi la cartella del giro. */
  fun finish(): CleanupReport {
    val admin = AreaAdmin(
      transport = AreaTransport(inner, guard, guard.root, latch, counter),
      connection = connection(),
      latch = latch,
      cleanupRetries = cleanupRetries,
      cleanupPauseMs = cleanupPauseMs,
      cleanOnClose = false,
    ).open()
    return try {
      admin.clean()
    } finally {
      admin.close()
    }
  }
}

/** L'autotest della guardia (come `ChaosAreaSelfTest`): tutto cio' che deve essere rifiutato lo e' davvero, con ZERO chiamate al vero. */
object ShareAreaSelfTest {

  class Result(val cases: Int, val failures: List<String>)

  /** Un trasporto "vero" finto, che conta cio' che gli arriva e ricorda l'ultimo percorso, e un login che si puo' far rifiutare. */
  private class Dummy {
    val reached = AtomicInteger(0)
    val authAttempts = AtomicInteger(0)
    var lastPath: String? = null
    var rejectLogin = false

    val transport = object : SmbTransport {
      override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection = connection
    }

    private val connection = object : SmbConnection {
      override val negotiation: Negotiation get() = throw UnsupportedOperationException()
      override val isConnected: Boolean get() = true

      override fun authenticate(user: String, password: CharArray): SmbSession {
        authAttempts.incrementAndGet()
        if (rejectLogin) throw SmbStatusException(NtStatus.LOGON_FAILURE)
        return session
      }

      override fun close() = Unit
    }

    private val session = object : SmbSession {
      override val facts: SessionFacts get() = throw UnsupportedOperationException()

      override fun connectShare(name: String): SmbShare {
        reached.incrementAndGet()
        return share
      }

      override fun close() = Unit
    }

    private fun hit(path: String) {
      reached.incrementAndGet()
      lastPath = path
    }

    private val share = object : SmbShare {
      override val name: String get() = "dummy"

      override fun openRead(path: String): SmbReadFile = hit(path).let { throw UnsupportedOperationException() }

      override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile = hit(path).let { throw UnsupportedOperationException() }

      override fun mkdir(path: String) = hit(path)

      override fun openDirectory(path: String, access: DirAccess): SmbDirectory = hit(path).let { throw UnsupportedOperationException() }

      override fun stat(path: String): SmbFileInfo = hit(path).let { throw UnsupportedOperationException() }

      override fun removeEmptyDirectory(path: String) = hit(path)

      override fun deleteFile(path: String) = hit(path)

      override fun space(): SmbSpace = throw UnsupportedOperationException()

      override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = hit(path).let { throw UnsupportedOperationException() }

      override fun close() = Unit
    }
  }

  fun run(guard: ShareAreaGuard): Result {
    val failures = ArrayList<String>()
    var cases = 0
    val dummy = Dummy()
    val latch = AreaAuthLatch()
    val root = guard.roundRoot(0)
    val transport = AreaTransport(dummy.transport, guard, root, latch)
    val connection = transport.connect(DirectSocketBinding("autotest"), NasAddress("192.168.178.58"), Duration.ZERO)

    fun mustRefuse(what: String, block: () -> Unit) {
      cases++
      val before = dummy.reached.get() + dummy.authAttempts.get()
      try {
        block()
        failures += "accettato: $what"
      } catch (_: AreaViolation) {
        // atteso
      }
      if (dummy.reached.get() + dummy.authAttempts.get() != before) failures += "il vero ha ricevuto una chiamata per un rifiuto: $what"
    }

    for (account in listOf("admin", "ALESSIO", "Pampa", "root", "Administrator", " admin ", "ａｄｍｉｎ", "test2", "test3", "")) {
      mustRefuse("account '$account'") { guard.checkAccount(account) }
      mustRefuse("login con l'account '$account'") { connection.authenticate(account, "x".toCharArray()) }
    }
    for (share in listOf("alessio", "pampa", "NAS", "Public", "public", "ＮＡＳ", "test ", "ｔｅｓｔ", "test2", "TEST2", "test2x", "", "..", "mcg-it", "test/..", "test-comune2")) {
      mustRefuse("share '$share'") { guard.checkShare(share) }
    }
    // Le share di prova passano, e il percorso arriva radicato in `mcg-it/<giro>/r0`.
    val session = connection.authenticate("test", "x".toCharArray())
    for (share in listOf("test", "TEST", "test-comune", "Test-Comune")) {
      cases++
      runCatching { session.connectShare(share) }.onFailure { failures += "rifiutata una share di prova: $share" }
    }
    for (share in listOf("alessio", "NAS", "Public", "test2", "ＮＡＳ", "test ")) mustRefuse("tree connect verso '$share'") { session.connectShare(share) }
    val shared = session.connectShare("test")
    val expectedPrefix = root.components.joinToString("\\") + "\\"
    for (path in listOf("Foto/a.jpg", ".mycloudgallery/tmp/x", "a\\b\\c")) {
      cases++
      shared.mkdir(path)
      if (dummy.lastPath?.startsWith(expectedPrefix) != true) failures += "percorso non radicato: $path"
    }
    cases++
    shared.mkdir("")
    if (dummy.lastPath != root.components.joinToString("\\")) failures += "la radice del mondo non e' la radice"
    // I percorsi che escono non arrivano mai al vero.
    for (raw in listOf("../x", "a/../../x", "/x", "\\x", "\\\\server\\x", "C:\\x", "a/./b", ".", "..", "a//b", "a/", "a\\..\\..\\b", "x:y")) {
      mustRefuse("percorso '$raw' (stat)") { shared.stat(raw) }
      mustRefuse("percorso '$raw' (cancellazione)") { shared.deleteFile(raw) }
      mustRefuse("percorso '$raw' (cartella)") { shared.mkdir(raw) }
    }
    // Un login rifiutato non si ripete: il secondo tentativo non arriva al NAS.
    cases++
    dummy.rejectLogin = true
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    val attemptsAfterFirst = dummy.authAttempts.get()
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    if (!latch.failed) failures += "il login rifiutato non ha fermato il banco"
    if (dummy.authAttempts.get() != attemptsAfterFirst) failures += "dopo un login rifiutato sono arrivati altri tentativi al NAS"
    return Result(cases, failures)
  }
}
