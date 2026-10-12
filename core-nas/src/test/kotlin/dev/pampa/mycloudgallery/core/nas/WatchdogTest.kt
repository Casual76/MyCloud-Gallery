package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.TransferWatchdog
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sorveglianza con il tempo virtuale: il gestore e il suo sorvegliante usano l'orologio di runTest, le operazioni
 * girano su thread di I/O veri. Mentre un'operazione e' in corso il thread del test resta BLOCCATO (latch,
 * runBlocking): cosi' il tempo virtuale avanza solo con advanceTimeBy.
 */
class WatchdogTest {

  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun TestScope.harness(config: NasSessionConfig = NasSessionConfig()) =
    Harness(backgroundScope, virtualClock(), config, io = Dispatchers.IO)

  /** Esegue [block] su I/O e aspetta tenendo fermo il thread del test (niente tempo virtuale che corre). */
  private fun <T> blockingIo(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

  private fun <T> Deferred<T>.awaitBlocking(): T = runBlocking { await() }

  private fun CountDownLatch.mustOpen() = assertThat(await(10, TimeUnit.SECONDS)).isTrue()

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
    reading.mustOpen()

    advanceTimeBy(19_500)
    runCurrent()
    assertThat(result.isActive).isTrue()
    assertThat(h.backend.openConnections).isEqualTo(1)

    advanceTimeBy(1_000)
    runCurrent()
    val error = (result.awaitBlocking().exceptionOrNull() as NasIoException).error
    assertThat(error).isEqualTo(NasError.Stalled)
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.manager.lastStallAt.value).isEqualTo(20_000L)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Unavailable(NasError.Stalled))

    // Ripresa piu' tardi: la prossima operazione riapre una connessione nuova.
    h.backend.stallReads = false
    h.backend.beforeOp = null
    blockingIo { h.manager.browser(SHARE).list(null, 10) }.value()
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)
  }

  @Test
  fun operazioneInterattivaFermaScadeAQuindiciSecondi() = runTest {
    val h = harness()
    h.backend.put(SHARE, "foto/a.jpg", ByteArray(10))
    blockingIo { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }.value()
    val entered = CountDownLatch(1)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.OPEN_DIRECTORY) {
        entered.countDown()
        // Nessuna risposta finche' la connessione e' aperta (un socket muto).
        while (h.backend.openConnections > 0) Thread.sleep(5)
      }
    }
    val result = io.async { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }
    entered.mustOpen()
    advanceTimeBy(14_500)
    runCurrent()
    assertThat(result.isActive).isTrue()
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking().errorOrNull()).isEqualTo(NasError.Timeout)
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
        disksAwake.await(10, TimeUnit.SECONDS)
      }
    }
    val result = io.async { h.manager.withShare(SHARE, Purpose.INTERACTIVE) { } }
    entered.mustOpen()
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
    assertThat(result.awaitBlocking().errorOrNull()).isNull()
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }

  @Test
  fun ilSorveglianteNonTieneOccupatoNienteSenzaOperazioni() = runTest {
    val h = harness()
    blockingIo { h.manager.browser(SHARE).list(null, 10) }.value()
    // Dopo l'operazione il sorvegliante si ferma da solo: avanzare il tempo non tocca la sessione (fino all'inattivita').
    advanceTimeBy(120_000)
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(1)
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }
}
