package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.OpTicket
import dev.pampa.mycloudgallery.core.nas.session.OperationMonitor
import dev.pampa.mycloudgallery.core.nas.session.TransferWatchdog
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Sorveglianza con il tempo virtuale: il gestore e il suo sorvegliante usano l'orologio di runTest, le operazioni
 * girano su thread di I/O veri. Il tempo virtuale avanza solo con advanceTimeBy, e solo DOPO che l'operazione e'
 * dentro il gancio (latch): il suo biglietto nasce sempre a t=0. Ogni attesa ha un tetto in tempo reale
 * (`TestSupport.kt`): un test che non va avanti FALLISCE con un messaggio, non resta appeso.
 */
class WatchdogTest {

  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Anche se un test fallisce a meta', i thread di I/O ancora fermi nei ganci si sbloccano (interrotti). */
  @After
  fun tearDown() {
    io.cancel()
  }

  private fun TestScope.harness(config: NasSessionConfig = NasSessionConfig()) =
    Harness(backgroundScope, virtualClock(), config, io = Dispatchers.IO)

  @Test
  fun watchdogPuroSuTempoVirtuale() {
    val clock = ManualMonotonicClock()
    val watchdog = TransferWatchdog(windowMs = 20_000, minBytesPerWindow = 32L * 1024 * 20, startMs = clock.nowMillis())
    // 64 KiB/s per un minuto: sano.
    repeat(60) {
      clock.advance(1000)
      watchdog.onBytes(64 * 1024, clock.nowMillis())
      assertThat(watchdog.isStalled(clock.nowMillis())).isFalse()
    }
    // Poi 1 KiB/s: dopo 20 s e' bloccato, non prima.
    repeat(19) {
      clock.advance(1000)
      watchdog.onBytes(1024, clock.nowMillis())
      assertThat(watchdog.isStalled(clock.nowMillis())).isFalse()
    }
    clock.advance(1000)
    watchdog.onBytes(1024, clock.nowMillis())
    assertThat(watchdog.isStalled(clock.nowMillis())).isTrue()
  }

  /**
   * Chiudere la connessione sveglia subito le letture sorelle ferme sullo stesso socket: se il loro biglietto non e'
   * ancora segnato, scambiano il blocco per una caduta da riprovare (`RECONNECT`) e ripartono su una connessione
   * nuova, che resta aperta dopo che il trasferimento ha gia' fallito con `Timeout`. Quindi il verdetto di TUTTI i
   * biglietti scaduti deve essere scritto prima della prima chiusura (la gara vera, col simulatore: cadeva a freddo e
   * sotto carico in `readAllFermoScadeAQuindiciSecondiInInterattivo`; qui l'ordine e' osservato direttamente).
   */
  @Test
  fun ilSorveglianteSegnaTuttiIBigliettiScadutiPrimaDiChiudereLaConnessione() = runTest {
    val clock = ManualMonotonicClock()
    val tickets = ArrayList<OpTicket>()
    val seenAtEachAbort = ArrayList<List<NasError?>>()
    val monitor = OperationMonitor(
      clock = clock,
      scope = backgroundScope,
      tick = 1.seconds,
      wakingThresholdMs = 2_000,
      onWaking = {},
      onAbort = { _, _ -> seenAtEachAbort += tickets.map { it.abortedBy } },
    )
    repeat(4) { tickets += OpTicket(owner = Any(), startedAt = 0, deadlineAt = 15_000, firstDiskTouch = false) }
    tickets.forEach(monitor::begin)

    clock.advance(15_000)
    monitor.inspect()

    assertThat(seenAtEachAbort).hasSize(4)
    seenAtEachAbort.forEach { assertThat(it).containsExactly(NasError.Timeout, NasError.Timeout, NasError.Timeout, NasError.Timeout) }
  }

  @Test
  fun trasferimentoLentoMaSanoArriva() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    val content = ByteArray(2 * 1024 * 1024) { it.toByte() }
    h.backend.put(SHARE, "video.mp4", content)
    h.backend.maxBytesPerRead = 64 * 1024
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) clock.advance(1000) }
    val read = h.manager.fileSystem(SHARE).read(SharePath.of("video.mp4"), content.size)
    assertThat((read as dev.pampa.mycloudgallery.core.trust.nas.NasRead.Data).bytes).isEqualTo(content)
    assertThat(h.manager.lastStallAt.value).isNull()
  }

  @Test
  fun bloccoSilenziosoChiudeLaConnessioneDopoVentiSecondi() = runTest {
    val h = harness()
    h.backend.put(SHARE, "video.mp4", ByteArray(1000))
    val reading = CountDownLatch(1)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) reading.countDown() }
    h.backend.stallReads = true
    val result = io.async { runCatching { h.manager.fileSystem(SHARE).read(SharePath.of("video.mp4"), 10_000) } }
    reading.mustOpen("la lettura ferma")

    advanceTimeBy(19_500)
    runCurrent()
    assertThat(result.isActive).isTrue()
    assertThat(h.backend.openConnections).isEqualTo(1)

    advanceTimeBy(1_000)
    runCurrent()
    val error = (result.awaitBlocking("la lettura ferma").exceptionOrNull() as NasIoException).error
    assertThat(error).isEqualTo(NasError.Stalled)
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.manager.lastStallAt.value).isEqualTo(20_000L)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Unavailable(NasError.Stalled))

    // Ripresa piu' tardi: la prossima operazione riapre una connessione nuova.
    h.backend.stallReads = false
    h.backend.beforeOp = null
    blockingIo("l'elenco dopo il blocco") { h.manager.browser(SHARE).list(null, 10) }.value()
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)
  }

  @Test
  fun operazioneInterattivaFermaScadeAQuindiciSecondi() = runTest {
    val h = harness()
    h.backend.put(SHARE, "foto/a.jpg", ByteArray(10))
    blockingIo("il primo elenco") { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }.value()
    val entered = CountDownLatch(1)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.OPEN_DIRECTORY) {
        entered.countDown()
        // Nessuna risposta finche' la connessione e' aperta (un socket muto); col tetto, se nessuno la chiude mai.
        waitUntil("la connessione muta chiusa dal sorvegliante", describe = { "connessioni aperte=${h.backend.openConnections}" }) {
          h.backend.openConnections == 0
        }
      }
    }
    val result = io.async { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }
    entered.mustOpen("l'elenco fermo")
    advanceTimeBy(14_500)
    runCurrent()
    assertThat(result.isActive).isTrue()
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking("l'elenco fermo").errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.backend.openConnections).isEqualTo(0)
    // Nessun nuovo tentativo dopo un timeout: la connessione era sospetta, una sola connessione aperta in tutto.
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
  }

  @Test
  fun primaOperazioneSuiDischiLentaRisveglioDelNas() = runTest {
    val h = harness()
    val entered = CountDownLatch(1)
    val disksAwake = CountDownLatch(1)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.TREE_CONNECT) {
        entered.countDown()
        // I dischi "si svegliano" quando il test lo dice; col tetto, se il test cade prima il thread non resta fermo.
        check(disksAwake.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "i dischi non si sono svegliati entro $WAIT_SECONDS s" }
      }
    }
    val result = io.async { h.manager.withShare(SHARE, Purpose.INTERACTIVE) { } }
    entered.mustOpen("la TREE_CONNECT lenta")
    advanceTimeBy(1_500)
    runCurrent()
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(h.manager.state.value).isEqualTo(NasConnState.WakingUp(0L))

    // I dischi rispondono dopo 30 s (virtuali): la prima operazione ha 60 s, non 15.
    advanceTimeBy(28_000)
    runCurrent()
    assertThat(result.isActive).isTrue()
    disksAwake.countDown()
    assertThat(result.awaitBlocking("la prima operazione sui dischi").errorOrNull()).isNull()
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }

  @Test
  fun ilSorveglianteNonTieneOccupatoNienteSenzaOperazioni() = runTest {
    val h = harness()
    blockingIo("l'elenco") { h.manager.browser(SHARE).list(null, 10) }.value()
    // Dopo l'operazione il sorvegliante si ferma da solo: avanzare il tempo non tocca la sessione (fino all'inattivita').
    advanceTimeBy(120_000)
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(1)
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }
}
