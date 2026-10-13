package dev.pampa.mycloudgallery.core.nas

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.breaker.AttemptResult
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.AuthOutcome
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerKeys
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerRecord
import dev.pampa.mycloudgallery.core.nas.breaker.DataStoreBreakerStore
import dev.pampa.mycloudgallery.core.nas.breaker.PersistentAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L'interruttore di sicurezza (§3.3). Il test obbligatorio di P1: 10 coroutine e 2 lavori con la password
 * sbagliata producono **esattamente 1** `authenticate`, anche dopo il riavvio del processo (stesso file DataStore,
 * istanze nuove). Gira su thread veri (Dispatchers.Default/IO), non sul tempo virtuale.
 */
class AuthBreakerTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val realClock = MonotonicClock { System.nanoTime() / 1_000_000 }

  /** Un "processo": DataStore sul file, registro degli interruttori e due gestori indipendenti sullo stesso NAS. */
  private inner class Process(file: File, backend: InMemorySmbBackend) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = DataStoreBreakerStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    val first = Harness(scope, realClock, io = Dispatchers.IO, store = store, backend = backend)

    /** Un secondo gestore (un altro lavoro) che condivide solo il registro degli interruttori. */
    val second = SmbNasSessionManager(
      account = { first.account },
      routeGate = first.gate,
      credentials = first.credentials,
      breakers = first.breakers,
      scope = scope,
      transport = backend,
      clock = realClock,
      io = Dispatchers.IO,
      backoff = {},
    )

    suspend fun stop() = scope.coroutineContext.job.cancelAndJoin()
  }

  private suspend fun storm(process: Process): List<NasError?> = coroutineScope {
    val start = CompletableDeferred<Unit>()
    val coroutines = (1..10).map {
      async(Dispatchers.Default) {
        start.await()
        process.first.manager.withShare(SHARE, Purpose.INTERACTIVE) { }.errorOrNull()
      }
    }
    // I due "lavori" usano il NasFileSystem bloccante da thread di I/O, su un altro gestore.
    val jobs = (1..2).map {
      async(Dispatchers.IO) {
        start.await()
        try {
          process.second.fileSystem(SHARE).list(SharePath.of("foto"), 10)
          null
        } catch (e: NasIoException) {
          e.error
        }
      }
    }
    start.complete(Unit)
    (coroutines + jobs).awaitAll()
  }

  @Test
  fun dieciCoroutineEDueLavoriConLaPasswordSbagliataUnaSolaAutenticazioneAncheDopoIlRiavvio() = runBlocking {
    withTimeout(60_000) {
      val backend = Harness.defaultBackend()
      val file = File(tmp.root, "nas_auth_breaker.preferences_pb")

      val firstRun = Process(file, backend).apply { first.password = "password-sbagliata" }
      val results = storm(firstRun)
      assertThat(backend.authenticateCalls.get()).isEqualTo(1)
      assertThat(results.count { it == NasError.Auth(AuthBlockReason.AUTH_FAILED) }).isEqualTo(1)
      assertThat(results.filter { it != NasError.Auth(AuthBlockReason.AUTH_FAILED) }.toSet())
        .containsExactly(NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED))
      assertThat(firstRun.first.breaker.current()).isInstanceOf(BreakerState.Open::class.java)
      firstRun.stop()

      // "Riavvio": nuovo DataStore sullo stesso file, nuovi interruttori, nuovi gestori.
      val secondRun = Process(file, backend).apply { first.password = "password-sbagliata" }
      val again = storm(secondRun)
      assertThat(again.toSet()).containsExactly(NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED))
      assertThat(backend.authenticateCalls.get()).isEqualTo(1)
      assertThat(secondRun.first.manager.state.value).isEqualTo(NasConnState.AuthBlocked(AuthBlockReason.AUTH_FAILED))

      // Solo una password nuova salvata richiude l'interruttore: un tentativo, riuscito.
      secondRun.first.password = GOOD_PASSWORD
      secondRun.first.breaker.onNewPasswordSaved()
      assertThat(secondRun.first.breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION + 1))
      assertThat(storm(secondRun).toSet()).containsExactly(null)
      // Uno per gestore: i due gestori non condividono la connessione, solo l'interruttore.
      assertThat(backend.authenticateCalls.get()).isEqualTo(3)
      secondRun.stop()
    }
  }

  @Test
  fun gliErroriDiReteNonApronoLInterruttore() = runTest {
    val h = Harness(backgroundScope, virtualClock())
    h.backend.tcp = TcpBehaviour.TIMEOUT
    h.manager.withShare(SHARE, Purpose.INTERACTIVE) { }
    h.backend.tcp = TcpBehaviour.REFUSE
    h.manager.withShare(SHARE, Purpose.INTERACTIVE) { }
    h.backend.tcp = TcpBehaviour.ACCEPT
    h.backend.beforeOp = { op, _ -> if (op == SimOp.AUTHENTICATE) h.backend.dropAllConnections() }
    assertThat(h.manager.withShare(SHARE, Purpose.INTERACTIVE) { }.errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION))
    // Revisione 2, F9: la connessione caduta DURANTE l'autenticazione (dopo la risposta NTLM) puo' essere un rifiuto
    // nascosto: il tentativo resta "non confermato" finche' un login riuscito non lo toglie.
    assertThat(h.store.read(BreakerKeys.of(h.backend.serverGuid, USER)).unconfirmed).isEqualTo(1)
    h.backend.beforeOp = null
    assertThat(h.manager.withShare(SHARE, Purpose.INTERACTIVE) { }.errorOrNull()).isNull()
    assertThat(h.store.read(BreakerKeys.of(h.backend.serverGuid, USER)).unconfirmed).isEqualTo(0)
  }

  @Test
  fun unFallimentoDellaGenerazionePrecedenteNonRiapre() = runTest {
    val registry = AuthBreakerRegistry(InMemoryBreakerStore(), backgroundScope) { 7L }
    val breaker = registry.forKey("guid", "test")
    val result = breaker.attempt { _ ->
      beforeAuthenticate()
      // Mentre il vecchio tentativo e' in volo, l'utente salva una password nuova.
      breaker.onNewPasswordSaved()
      AuthOutcome.Rejected(AuthBlockReason.AUTH_FAILED)
    }
    assertThat(result).isInstanceOf(AttemptResult.Done::class.java)
    assertThat(breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION + 1))
  }

  @Test
  fun tentativiRimastiAMetaAprono() = runTest {
    val store = InMemoryBreakerStore()
    val registry = AuthBreakerRegistry(store, backgroundScope) { 7L }
    val breaker = registry.forKey("guid", "test")
    // Due processi morti durante l'autenticazione (traccia scritta, nessun verdetto).
    store.update(BreakerKeys.of("guid", "test")) { it.copy(unconfirmed = PersistentAuthCircuitBreaker.MAX_UNCONFIRMED) }
    var ran = false
    val result = breaker.attempt { _ ->
      ran = true
      AuthOutcome.Accepted(Unit)
    }
    assertThat(ran).isFalse()
    assertThat((result as AttemptResult.Blocked).reason).isEqualTo(AuthBlockReason.AUTH_FAILED)
    assertThat(breaker.current()).isEqualTo(BreakerState.Open(AuthBlockReason.AUTH_FAILED, 7L, BreakerRecord.FIRST_GENERATION))
  }

  /**
   * Revisione 2, F9: chi sta in mezzo puo' ritardare la risposta a SESSION_SETUP oltre il tempo massimo, cosi' ogni
   * rifiuto sembra un timeout. Un timeout dopo la traccia resta "non confermato": al massimo due tentativi, poi si apre.
   */
  @Test
  fun unTimeoutDuranteLAutenticazioneNonSiCancellaEDueDiFilaAprono() = runTest {
    val store = InMemoryBreakerStore()
    val registry = AuthBreakerRegistry(store, backgroundScope) { 7L }
    val breaker = registry.forKey("guid", "test")
    var reachedNas = 0
    repeat(5) {
      breaker.attempt<Unit> { _ ->
        beforeAuthenticate()
        reachedNas++
        AuthOutcome.Failed(NasError.Timeout)
      }
    }
    assertThat(reachedNas).isEqualTo(PersistentAuthCircuitBreaker.MAX_UNCONFIRMED)
    assertThat(breaker.current()).isEqualTo(BreakerState.Open(AuthBlockReason.AUTH_FAILED, 7L, BreakerRecord.FIRST_GENERATION))

    // Un timeout PRIMA dell'autenticazione (connessione, NEGOTIATE) non conta; un altro errore dopo la traccia si toglie.
    val other = registry.forKey("guid", "altro")
    repeat(5) { other.attempt<Unit> { _ -> AuthOutcome.Failed(NasError.Timeout) } }
    repeat(5) {
      other.attempt<Unit> { _ ->
        beforeAuthenticate()
        AuthOutcome.Failed(NasError.ServiceDown)
      }
    }
    assertThat(other.current()).isInstanceOf(BreakerState.Closed::class.java)
    assertThat(store.read(BreakerKeys.of("guid", "altro")).unconfirmed).isEqualTo(0)
  }

  @Test
  fun unTentativoAnnullatoNonLasciaTracce() = runTest {
    val store = InMemoryBreakerStore()
    val breaker = AuthBreakerRegistry(store, backgroundScope) { 7L }.forKey("guid", "test")
    val entered = CompletableDeferred<Unit>()
    val job = launch {
      breaker.attempt<Unit> { _ ->
        beforeAuthenticate()
        entered.complete(Unit)
        CompletableDeferred<Unit>().await()
        AuthOutcome.Accepted(Unit)
      }
    }
    entered.await()
    assertThat(store.read(BreakerKeys.of("guid", "test")).unconfirmed).isEqualTo(1)
    job.cancelAndJoin()
    assertThat(store.read(BreakerKeys.of("guid", "test")).unconfirmed).isEqualTo(0)
  }

  @Test
  fun limitatoreAlPiuOttoRichiesteInVolo() = runBlocking {
    withTimeout(60_000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val h = Harness(scope, realClock, io = Dispatchers.IO)
      h.manager.withShare(SHARE, Purpose.BACKGROUND) { }.value()
      repeat(20) { h.backend.put(SHARE, "foto/f$it.jpg", ByteArray(10)) }
      val inFlight = AtomicInteger(0)
      val peak = AtomicInteger(0)
      val release = CountDownLatch(1)
      h.backend.beforeOp = { op, _ ->
        if (op == SimOp.OPEN_READ) {
          val now = inFlight.incrementAndGet()
          peak.accumulateAndGet(now, ::maxOf)
          // Si trattiene finche' non sono dentro in 8 (o per 5 s): un nono non deve mai entrare.
          if (now >= 8) release.countDown()
          release.await(5, TimeUnit.SECONDS)
          inFlight.decrementAndGet()
        }
      }
      val reads = (0 until 20).map { i ->
        async(Dispatchers.IO) { h.manager.fileSystem(SHARE).read(SharePath.of("foto", "f$i.jpg"), 100) }
      }
      reads.awaitAll()
      assertThat(peak.get()).isEqualTo(8)
      assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
      scope.coroutineContext.job.cancelAndJoin()
    }
  }
}
