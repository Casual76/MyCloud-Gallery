package dev.pampa.mycloudgallery.core.nas

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerStore
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

const val SHARE = "test-comune"
const val READ_ONLY_SHARE = "sola-lettura"
const val FORBIDDEN_SHARE = "privata"
const val USER = "test"
const val GOOD_PASSWORD = "password-giusta"
const val DEVICE = "abcdefghijklmnopqrstuvwxyz"
const val HOME_WIFI = "wifi-casa"

/** Il valore di un esito riuscito (fallisce il test altrimenti). */
fun <T> NasResult<T>.value(): T = when (this) {
  is NasResult.Ok -> value
  is NasResult.Err -> throw AssertionError("atteso Ok, arrivato $error")
}

/** L'orologio virtuale di runTest come orologio monotono. */
fun TestScope.virtualClock(): MonotonicClock = MonotonicClock { testScheduler.currentTime }

/**
 * Un NAS simulato, un cancello aperto sul Wi-Fi di casa, le credenziali giuste e il gestore della sessione.
 * Di default tutto gira sul thread del test (`Dispatchers.Unconfined`) e i nuovi tentativi non aspettano.
 */
class Harness(
  val scope: CoroutineScope,
  val clock: MonotonicClock,
  config: NasSessionConfig = NasSessionConfig(),
  io: CoroutineDispatcher = Dispatchers.Unconfined,
  val store: BreakerStore = InMemoryBreakerStore(),
  val backend: InMemorySmbBackend = defaultBackend(),
  shareGuard: ShareGuard? = null,
) {
  val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding(HOME_WIFI)) }

  @Volatile
  var account: NasAccount? = NasAccount(NasAddress("192.168.178.58", "MyCloudEX2Ultra"), USER, "slot-nas", backend.serverGuid)

  @Volatile
  var password: String = GOOD_PASSWORD

  val credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(account?.user ?: USER, password.toCharArray())) }
  val breakers = AuthBreakerRegistry(store, scope, wallClock = { WALL_CLOCK })
  val backoffs: MutableList<Duration> = CopyOnWriteArrayList()

  val manager = SmbNasSessionManager(
    account = { account },
    routeGate = gate,
    credentials = credentials,
    breakers = breakers,
    scope = scope,
    transport = backend,
    clock = clock,
    config = config,
    io = io,
    deviceId = { DEVICE },
    backoff = { backoffs += it },
    shareGuard = shareGuard,
  )

  val breaker get() = breakers.forAccount(checkNotNull(account))

  companion object {
    const val WALL_CLOCK = 1_791_763_200_000L

    fun defaultBackend(): InMemorySmbBackend = InMemorySmbBackend().apply {
      addUser(USER, GOOD_PASSWORD)
      addUser("altro", "altra-password")
      addShare(SHARE, mapOf(USER to SimAccess.READ_WRITE))
      addShare(READ_ONLY_SHARE, mapOf(USER to SimAccess.READ))
      addShare(FORBIDDEN_SHARE, mapOf("altro" to SimAccess.READ_WRITE))
    }
  }
}

// --- Attese con un tetto -------------------------------------------------------------------------------------------
//
// Ogni attesa dei test e' in TEMPO REALE e ha un tetto: sotto carico un thread puo' tardare secondi, mai minuti. Allo
// scadere il test FALLISCE dicendo cosa aspettava e dove stavano fermi i thread; non resta appeso. Mai un
// `runBlocking { deferred.await() }` nudo, un `Semaphore.acquire()` o un `CountDownLatch.await()` senza tempo: il
// timeout di runTest non scatta se il thread del test e' bloccato dentro, e un test appeso blocca check.ps1 per ore
// (il 14/10 un test di questo modulo e' rimasto fermo 67 minuti su un `runBlocking { await() }`).

/** Il tetto, in secondi reali, di ogni attesa dei test (le attese riuscite durano millisecondi). */
const val WAIT_SECONDS: Long = 30L

/** Il tetto delle attese contro il NAS vero (`-PnasIntegration`): reti lente e file da 16 MiB, ma mai appesi. */
const val INTEGRATION_WAIT_SECONDS: Long = 600L

/** Dove stanno fermi i thread che passano per il codice della galleria: da allegare al messaggio di un'attesa scaduta. */
fun threadDump(): String =
  Thread.getAllStackTraces().entries
    .filter { (_, frames) -> frames.any { it.className.startsWith("dev.pampa") } }
    .joinToString("\n") { (thread, frames) ->
      "  ${thread.name} [${thread.state}]\n" + frames.take(DUMP_FRAMES).joinToString("\n") { "      at $it" }
    }

private const val DUMP_FRAMES = 16

/** Come `runBlocking`, ma con un tetto in tempo reale: allo scadere `AssertionError` con [what] e lo stato dei thread. */
fun <T> runBlockingBounded(what: String = "il test", seconds: Long = WAIT_SECONDS, block: suspend CoroutineScope.() -> T): T =
  runBlocking {
    try {
      withTimeout(seconds.seconds) { block() }
    } catch (e: TimeoutCancellationException) {
      throw AssertionError("$what: nessuna risposta entro $seconds s (tempo reale).\n${threadDump()}", e)
    }
  }

/** Aspetta [this] tenendo fermo il thread del test, al massimo [seconds] (tempo reale). */
fun <T> Deferred<T>.awaitBlocking(what: String = "il risultato", seconds: Long = WAIT_SECONDS): T =
  runBlockingBounded(what, seconds) { await() }

/** Esegue [block] su I/O e aspetta tenendo fermo il thread del test (niente tempo virtuale che corre), con un tetto. */
fun <T> blockingIo(what: String = "l'operazione", seconds: Long = WAIT_SECONDS, block: suspend () -> T): T =
  runBlockingBounded(what, seconds) { withContext(Dispatchers.IO) { block() } }

/** Aspetta che il latch si apra, al massimo [seconds]; altrimenti il test fallisce dicendo cosa aspettava. */
fun CountDownLatch.mustOpen(what: String, seconds: Long = WAIT_SECONDS) {
  if (!await(seconds, TimeUnit.SECONDS)) throw AssertionError("$what: il latch non si e' aperto entro $seconds s.\n${threadDump()}")
}

/** Prende un permesso entro [seconds]; altrimenti il test fallisce dicendo cosa aspettava. */
fun Semaphore.mustAcquire(what: String, seconds: Long = WAIT_SECONDS) {
  if (!tryAcquire(seconds, TimeUnit.SECONDS)) throw AssertionError("$what: nessun permesso entro $seconds s.\n${threadDump()}")
}

/** Aspetta (in tempo reale, a scatti di un millisecondo) che [condition] sia vera, al massimo [seconds]. */
fun waitUntil(what: String, seconds: Long = WAIT_SECONDS, describe: () -> String = { "" }, condition: () -> Boolean) {
  val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
  while (!condition()) {
    if (System.nanoTime() > deadline) throw AssertionError("$what: ancora non vero dopo $seconds s. ${describe()}\n${threadDump()}")
    Thread.sleep(1)
  }
}
