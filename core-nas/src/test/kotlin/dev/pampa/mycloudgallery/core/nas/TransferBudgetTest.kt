package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il sorvegliante a progresso (D3 della Fase 4) a tempo virtuale: la scadenza vale **per blocco** (15 s interattiva, 60 s
 * in background) e resta la soglia di 32 KiB/s per 20 s; nessun limite al totale finche' i byte arrivano. Un
 * trasferimento lungo che procede non si uccide, uno fermo si'.
 */
class TransferBudgetTest {

  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun <T> Deferred<T>.awaitBlocking(): T = runBlocking { await() }

  private fun Semaphore.mustAcquire() = assertThat(tryAcquire(10, TimeUnit.SECONDS)).isTrue()

  @Test
  fun unaLetturaInterattivaLungaCheProcedeNonScade() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    val content = Random(1).nextBytes(2 * 1024 * 1024)
    h.backend.put(SHARE, "video.mp4", content)
    h.backend.maxBytesPerRead = 64 * 1024
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) clock.advance(1_000) }

    val read = h.manager.withShare(SHARE, Purpose.INTERACTIVE) { it.fileSystem.read(SharePath.of("video.mp4"), content.size) }.value()

    assertThat((read as NasRead.Data).bytes).isEqualTo(content)
    // 32 blocchi da un secondo: il doppio della scadenza interattiva, ma ogni blocco ci sta.
    assertThat(clock.nowMillis()).isAtLeast(32_000L)
  }

  @Test
  fun unBloccoArrivatoOltreLaScadenzaPerBloccoFallisce() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    h.backend.put(SHARE, "video.mp4", ByteArray(1024 * 1024))
    h.backend.maxBytesPerRead = 64 * 1024
    var reads = 0
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) clock.advance(if (++reads == 5) 16_000 else 1_000) }

    val result = h.manager.withShare(SHARE, Purpose.INTERACTIVE) { it.fileSystem.read(SharePath.of("video.mp4"), 1024 * 1024) }

    assertThat(result.errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.backend.openConnections).isEqualTo(0)
  }

  @Test
  fun readAllInterattivoLungoCheProcedeNonVieneUcciso() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    val chunks = 16
    val content = Random(2).nextBytes(chunks * NasMediaLimits.READ_ALL_CHUNK_BYTES)
    h.backend.put(SHARE, "film.mp4", content)
    val entered = Semaphore(0)
    val gate = Semaphore(0, true)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.READ) {
        entered.release()
        gate.acquire()
      }
    }
    val reader = h.manager.mediaReader()
    val result = io.async {
      val file = reader.open(SHARE, SharePath.of("film.mp4"), Purpose.INTERACTIVE).value()
      reader.readAll(file, { _, _ -> })
    }

    // Un blocco ogni 2 s (tempo virtuale), quattro in volo: ognuno aspetta al piu' 8 s; il totale e' 32 s.
    repeat(chunks) {
      entered.mustAcquire()
      advanceTimeBy(2_000)
      runCurrent()
      gate.release()
    }

    assertThat(result.awaitBlocking()).isEqualTo(NasResult.Ok(Sha256.of(content)))
    assertThat(testScheduler.currentTime).isAtLeast(32_000L)
    assertThat(h.manager.lastStallAt.value).isNull()
  }

  @Test
  fun readAllFermoScadeAQuindiciSecondiInInterattivo() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    h.backend.put(SHARE, "film.mp4", ByteArray(4 * NasMediaLimits.READ_ALL_CHUNK_BYTES))
    val reader = h.manager.mediaReader()
    val file = runBlocking(Dispatchers.IO) { reader.open(SHARE, SharePath.of("film.mp4"), Purpose.INTERACTIVE).value() }
    val reading = CountDownLatch(1)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) reading.countDown() }
    h.backend.stallReads = true
    val result = io.async { reader.readAll(file, { _, _ -> }) }
    assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue()

    advanceTimeBy(14_500)
    runCurrent()
    assertThat(result.isActive).isTrue()

    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking().errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.backend.openConnections).isEqualTo(0)
  }

  @Test
  fun readAllFermoInBackgroundSiFermaAVentiSecondiPerLaSoglia() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    h.backend.put(SHARE, "film.mp4", ByteArray(4 * NasMediaLimits.READ_ALL_CHUNK_BYTES))
    val reader = h.manager.mediaReader()
    val file = runBlocking(Dispatchers.IO) { reader.open(SHARE, SharePath.of("film.mp4"), Purpose.BACKGROUND).value() }
    val reading = CountDownLatch(1)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) reading.countDown() }
    h.backend.stallReads = true
    val result = io.async { reader.readAll(file, { _, _ -> }) }
    assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue()

    // La scadenza per blocco in background e' 60 s, ma sotto 32 KiB/s per 20 s il trasferimento e' fermo (E-R14).
    advanceTimeBy(19_500)
    runCurrent()
    assertThat(result.isActive).isTrue()

    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking().errorOrNull()).isEqualTo(NasError.Stalled)
    assertThat(h.manager.lastStallAt.value).isNotNull()
    assertThat(h.backend.openConnections).isEqualTo(0)
  }

  @Test
  fun unaCopiaLatoServerLungaNonScadeSulTotale() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    val size = 48L * 1024 * 1024
    h.backend.putContent(SHARE, "film.mkv", NasContent.virtual(size, seed = 9))
    h.backend.beforeOp = { op, _ -> if (op == SimOp.COPY_CHUNK) clock.advance(10_000) }

    val copied = SmbServerSideCopy(h.manager, Purpose.INTERACTIVE).copy(SHARE, SharePath.of("film.mkv"), SHARE, SharePath.of("copia.mkv"))

    // Tre richieste da 16 MiB, 10 s l'una: 30 s in tutto, ognuna sotto i 15 s.
    assertThat(copied).isEqualTo(NasResult.Ok(size))
    assertThat(clock.nowMillis()).isAtLeast(30_000L)
    assertThat(h.backend.bytes(SHARE, "copia.mkv")!!.size.toLong()).isEqualTo(size)
  }

  @Test
  fun unaLetturaDiUnFileApertoNonFaEccezioniFuoriDalContratto() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    h.backend.put(SHARE, "a.jpg", ByteArray(10))
    val file = h.manager.mediaReader().open(SHARE, SharePath.of("a.jpg"), Purpose.INTERACTIVE).value()
    h.backend.tcp = dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour.REFUSE
    h.backend.dropAllConnections()
    val error = assertThrows(NasIoException::class.java) { runBlocking { file.readRange(0L..9L) } }.error
    assertThat(error).isEqualTo(NasError.ServiceDown)
  }
}
