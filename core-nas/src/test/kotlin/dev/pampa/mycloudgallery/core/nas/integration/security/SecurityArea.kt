package dev.pampa.mycloudgallery.core.nas.integration.security

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.fs.FsOps
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaGuard
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaSelfTest
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaTransport
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// L'AREA delle prove di sicurezza dal PC (Fase 10 P11; docs/fase10-progetto.md §2.11, D36; C447): il banco esistente (`NasIntegration.kt`,
// `TestAreaGuard`, `TestAreaTransport`, `TestAreaSelfTest`) con il codice vero dell'app come libreria JVM. Tutto passa dal trasporto
// recintato: solo le share di prova e solo `mcg-it/<giro>/`; la pulizia toglie **solo** il giro di questa prova (non i giri di altri) e
// `mcg-it` solo se l'ha creata questa prova ed e' vuota. Al primo errore di autenticazione la prova si ferma: nessun nuovo tentativo.

/** Un login rifiutato: la prova si ferma qui (nessun nuovo tentativo, ne' di questo account ne' dell'altro). */
internal class AuthStop(val account: String) : RuntimeException("AUTH_FAILED con l'account di prova $account: prova fermata, nessun nuovo tentativo")

/** Il NAS non risponde (o la share non si apre): la prova non parte, il resoconto lo dice. */
internal class NasUnavailable(val code: String) : RuntimeException("NAS non raggiungibile o share non aperta: $code")

internal class SecurityArea private constructor(
  val credentials: ItCredentials,
  val guard: TestAreaGuard,
  val transport: TestAreaTransport,
  private val binding: SocketBinding,
  private val guid: String?,
  private val clock: MonotonicClock,
) : AutoCloseable {

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, binding) }
  private val breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope)

  /** Il primo account (`test`): scrive le prove. */
  val manager: SmbNasSessionManager = newManager(credentials.user, credentials.password, "s1")

  /** Il secondo account (`test2`), se il file delle credenziali lo ha. */
  val manager2: SmbNasSessionManager? = credentials.user2?.let { user -> credentials.password2?.let { newManager(user, it, "s2") } }

  val share: String get() = credentials.share
  val commonShare: String? get() = credentials.share2

  /** `mcg-it/<giro>/<parti>`. */
  fun path(vararg parts: String): SharePath = SharePath.of(*(guard.root.components + parts).toTypedArray())

  private fun newManager(user: String, password: CharArray, slot: String): SmbNasSessionManager {
    val account = NasAccount(NasAddress(credentials.host), user, slot, guid)
    return SmbNasSessionManager(
      account = { account },
      routeGate = gate,
      credentials = { CredentialLoad.Found(NasCredentials(user, password.copyOf())) },
      breakers = breakers,
      scope = scope,
      transport = transport,
      clock = clock,
      config = NasSessionConfig(),
      deviceId = { "itsec" + guard.runId.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT).take(21).padEnd(21, '0') },
      shareGuard = ShareFence,
    )
  }

  /** Una sola verifica per account, prima di tutto: un `AUTH_FAILED` ferma la prova ([AuthStop]). */
  suspend fun verify(on: SmbNasSessionManager, user: String, shareName: String) {
    val result = on.withShare(shareName, Purpose.INTERACTIVE) { }
    if (result is NasResult.Err) {
      if (result.error is NasError.Auth) throw AuthStop(user)
      throw NasUnavailable(result.error.toString())
    }
  }

  /** Un'operazione con [on] sulla share; un errore del NAS diventa un `AssertionError` con il codice (mai credenziali). */
  suspend fun <T> op(on: SmbNasSessionManager, shareName: String, block: (OpContext) -> T): T =
    when (val r = on.runOp(shareName, Purpose.BACKGROUND, idempotent = false, touchesDisk = true, op = block)) {
      is NasResult.Ok -> r.value
      is NasResult.Err -> {
        if (r.error is NasError.Auth) throw AuthStop(if (on === manager) credentials.user else credentials.user2.orEmpty())
        throw AssertionError("operazione nell'area di prova fallita su $shareName: ${r.error}")
      }
    }

  /** Scrive [bytes] in [path] (sotto `mcg-it/<giro>/`), creando le cartelle. */
  suspend fun write(on: SmbNasSessionManager, shareName: String, path: SharePath, bytes: ByteArray) = op(on, shareName) { ctx ->
    FsOps.mkdirs(ctx, path.parent())
    ctx.share.createExclusive(path.smb()).use { file ->
      var offset = 0
      while (offset < bytes.size) offset += file.write(bytes, offset.toLong(), offset, minOf(MIB, bytes.size - offset))
      file.flush()
    }
  }

  /** Molti file piccoli in poche operazioni (lotti di [BATCH]). */
  suspend fun writeAll(on: SmbNasSessionManager, shareName: String, files: List<Pair<SharePath, ByteArray>>) {
    for (batch in files.chunked(BATCH)) {
      op(on, shareName) { ctx ->
        for ((path, bytes) in batch) {
          FsOps.mkdirs(ctx, path.parent())
          ctx.share.createExclusive(path.smb()).use { file ->
            var offset = 0
            while (offset < bytes.size) offset += file.write(bytes, offset.toLong(), offset, bytes.size - offset)
            file.flush()
          }
        }
      }
    }
  }

  /** Legge un file intero (al piu' [max] byte) con [on]; null se l'account non lo puo' leggere o non c'e' (il codice in [denials]). */
  suspend fun read(on: SmbNasSessionManager, shareName: String, path: SharePath, max: Long = 64L * MIB, denials: MutableList<String>? = null): ByteArray? {
    val reader = on.mediaReader()
    val file = when (val opened = reader.open(shareName, path, Purpose.BACKGROUND)) {
      is NasResult.Ok -> opened.value
      is NasResult.Err -> {
        denials?.add(opened.error.toString())
        return null
      }
    }
    return file.use { if (it.size <= 0) ByteArray(0) else it.readRange(0..minOf(it.size, max) - 1) }
  }

  /** Le voci di [dir] (nomi, dimensioni, date, `fileId`); null se l'account non la puo' elencare (il codice in [denials]). */
  suspend fun list(on: SmbNasSessionManager, shareName: String, dir: SharePath, denials: MutableList<String>? = null): List<NasEntry>? {
    val out = ArrayList<NasEntry>()
    return when (val r = on.mediaReader().list(shareName, dir, Purpose.BACKGROUND) { page -> out += page }) {
      is NasResult.Ok -> out
      is NasResult.Err -> {
        denials?.add(r.error.toString())
        null
      }
    }
  }

  /** Tutti i file sotto [dir], ricorsivamente: (percorso, voce). */
  suspend fun walk(on: SmbNasSessionManager, shareName: String, dir: SharePath, denials: MutableList<String>? = null, depth: Int = 0): List<Pair<SharePath, NasEntry>> {
    val entries = list(on, shareName, dir, denials) ?: return emptyList()
    val out = ArrayList<Pair<SharePath, NasEntry>>()
    for (e in entries) {
      val child = dir.child(e.name)
      if (e.isDirectory) {
        if (depth < 12) out += walk(on, shareName, child, denials, depth + 1)
      } else {
        out += child to e
      }
    }
    return out
  }

  /**
   * Toglie il giro di questa prova (`mcg-it/<giro>`) da ogni share di prova, con ogni account (cio' che uno non puo' togliere lo toglie
   * l'altro), e `mcg-it` solo se creata da questa prova e vuota. Restituisce i residui (vuoto = pulito).
   */
  suspend fun cleanup(): List<String> {
    val accounts = listOfNotNull(manager, manager2)
    for (shareName in guard.shares) for (on in accounts) deleteTree(on, shareName, guard.root)
    val residue = ArrayList<String>()
    for (shareName in guard.shares) {
      var seen = false
      for (on in accounts) {
        val listing = list(on, shareName, guard.root, ArrayList()) ?: continue
        seen = true
        if (listing.isNotEmpty()) residue += "$shareName: ${listing.size} voci restano sotto il giro"
      }
      if (seen) residue += "$shareName: la cartella del giro resta"
    }
    if (guard.createdRoot) {
      for (shareName in guard.shares) for (on in accounts) {
        try {
          op(on, shareName) { ctx -> ctx.share.removeEmptyDirectory(TestAreaGuard.ROOT) }
        } catch (_: AssertionError) {
          // non vuota (altri giri) o non di questo account: resta
        }
      }
    }
    return residue
  }

  private suspend fun deleteTree(on: SmbNasSessionManager, shareName: String, dir: SharePath) {
    val entries = list(on, shareName, dir, ArrayList()) ?: return
    for (batch in entries.filter { !it.isDirectory }.chunked(BATCH)) {
      op(on, shareName) { ctx -> batch.forEach { e -> quietly { ctx.share.deleteFile(dir.child(e.name).smb()) } } }
    }
    for (sub in entries.filter { it.isDirectory }) deleteTree(on, shareName, dir.child(sub.name))
    op(on, shareName) { ctx -> quietly { ctx.share.removeEmptyDirectory(dir.smb()) } }
  }

  private inline fun quietly(block: () -> Unit) {
    try {
      block()
    } catch (_: SmbStatusException) {
      // ACCESS_DENIED o gia' tolto: ci pensa l'altro account, e la verifica lo dice
    }
  }

  override fun close() {
    kotlinx.coroutines.runBlocking {
      manager2?.invalidate(InvalidateReason.USER_REQUEST)
      manager.invalidate(InvalidateReason.USER_REQUEST)
    }
    scope.cancel()
  }

  companion object {
    const val MIB = 1024 * 1024
    const val BATCH = 50

    fun newRunId(): String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(ZonedDateTime.now(ZoneOffset.UTC)) +
      "-" + ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /**
     * Il GUID del NAS dalla NEGOTIATE, senza autenticare, come l'onboarding e la suite (passa comunque dal trasporto recintato e dalla
     * guardia validata). Null se il NAS non risponde.
     */
    suspend fun negotiateGuid(credentials: ItCredentials, inner: SmbTransport, binding: SocketBinding): String? {
      val guard = TestAreaGuard.of(credentials, newRunId())
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, binding) }
        val tester = SmbConnectionTester(gate, AuthBreakerRegistry(InMemoryBreakerStore(), scope), TestAreaTransport(inner, guard), MonotonicClock { System.nanoTime() / 1_000_000 })
        return tester.run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE)).negotiation?.serverGuid
      } finally {
        scope.cancel()
      }
    }

    /**
     * L'area, **prima di qualunque socket**: la guardia valida il file delle credenziali (account e share di prova) e l'autotest deve
     * rifiutare ogni caso vietato con zero chiamate. [inner] e' il trasporto vero (smbj) o il NAS simulato.
     */
    fun open(credentials: ItCredentials, inner: SmbTransport, binding: SocketBinding, guid: String?, runId: String = newRunId(), clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 }): SecurityArea {
      val guard = TestAreaGuard.of(credentials, runId)
      val self = TestAreaSelfTest.run(guard)
      check(self.failures.isEmpty()) { "autotest della guardia fallito: ${self.failures}" }
      return SecurityArea(credentials, guard, TestAreaTransport(inner, guard), binding, guid, clock)
    }
  }
}
