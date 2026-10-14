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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il sorvegliante a progresso (D3 della Fase 4) a tempo virtuale: la scadenza vale **per blocco** (15 s interattiva, 60 s
 * in background) e resta la soglia di 32 KiB/s per 20 s; nessun limite al totale finche' i byte arrivano. Un
 * trasferimento lungo che procede non si uccide, uno fermo si'.
 *
 * Le letture girano su thread di I/O veri e il tempo e' quello virtuale di `runTest`: i due mondi si toccano SOLO in
 * punti espliciti (latch, semafori, `waitUntil`), mai per caso, e ogni attesa ha un tetto in tempo reale
 * (`TestSupport.kt`): un test che non va avanti FALLISCE con un messaggio, non resta appeso.
 */
class TransferBudgetTest {

  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Anche se un test fallisce a meta', i thread di I/O ancora fermi nei ganci si sbloccano (interrotti). */
  @After
  fun tearDown() {
    io.cancel()
  }

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

  /**
   * Quattro flussi su thread veri e il tempo virtuale di `runTest`, che si toccano solo in punti espliciti: il tempo
   * avanza di 2 s ALLA VOLTA e solo quando tutte le letture in volo sono ferme al cancello (e quelle finite hanno
   * finito del tutto, anche le scritture di stato dopo la risposta: `activeOperations`). Cosi' ogni lettura nasce
   * nell'istante in cui il test la sta aspettando e aspetta esattamente 8 s virtuali (quattro in volo, un giro ogni
   * 2 s, il totale e' 32 s), qualunque sia la velocita' dei thread.
   *
   * Prima il tempo correva da solo mentre un thread appena sbloccato non aveva ancora finito: sotto carico bastavano
   * pochi millisecondi reali di ritardo perche' una lettura "durasse" 15 s virtuali, il sorvegliante la dichiarasse
   * scaduta e chiudesse la sessione; le letture sorelle si riconnettevano (altre letture al cancello che il test non
   * apriva piu') e il test restava appeso, col thread del test bloccato nell'attesa del risultato.
   */
  @Test
  fun readAllInterattivoLungoCheProcedeNonVieneUcciso() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    val streams = NasMediaLimits.READ_ALL_STREAMS
    val chunks = 16
    val content = Random(2).nextBytes(chunks * NasMediaLimits.READ_ALL_CHUNK_BYTES)
    h.backend.put(SHARE, "film.mp4", content)
    val entered = AtomicInteger(0)
    val gate = Semaphore(0, true)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.READ) {
        entered.incrementAndGet()
        // Il tetto vale anche qui: se il test fallisce prima di aprire il cancello il thread non resta fermo per sempre.
        check(gate.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) { "il cancello non si e' aperto entro $WAIT_SECONDS s" }
      }
    }
    val reader = h.manager.mediaReader()
    val result = io.async {
      val file = reader.open(SHARE, SharePath.of("film.mp4"), Purpose.INTERACTIVE).value()
      reader.readAll(file, { _, _ -> })
    }

    var opened = 0
    // `entered - opened` sono le letture ferme al cancello; `activeOperations` conta anche il readAll stesso (il +1) e
    // le letture non ancora chiuse del tutto: quando le due cose coincidono, nient'altro e' in corso.
    fun awaitAllParked() {
      val parked = minOf(streams, chunks - opened)
      waitUntil(
        what = "le $parked letture in volo ferme al cancello",
        describe = { "entrate=${entered.get()} aperte=$opened operazioni=${h.manager.activeOperations.value} readAllFinito=${result.isCompleted}" },
      ) { entered.get() - opened == parked && h.manager.activeOperations.value == parked + 1 }
    }

    repeat(chunks) {
      awaitAllParked()
      advanceTimeBy(2_000)
      runCurrent()
      gate.release()
      opened++
    }

    assertThat(result.awaitBlocking("il readAll lungo")).isEqualTo(NasResult.Ok(Sha256.of(content)))
    assertThat(testScheduler.currentTime).isEqualTo(chunks * 2_000L)
    // Nessuna lettura ripetuta, nessuna riconnessione, nessun blocco dichiarato dal sorvegliante.
    assertThat(h.backend.readCalls.get()).isEqualTo(chunks)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
    assertThat(h.manager.lastStallAt.value).isNull()
  }

  @Test
  fun readAllFermoScadeAQuindiciSecondiInInterattivo() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    h.backend.put(SHARE, "film.mp4", ByteArray(4 * NasMediaLimits.READ_ALL_CHUNK_BYTES))
    val reader = h.manager.mediaReader()
    val file = blockingIo("l'apertura del file") { reader.open(SHARE, SharePath.of("film.mp4"), Purpose.INTERACTIVE).value() }
    // Il tempo parte solo quando tutte e quattro le letture sono in volo: nascono tutte a t=0 e scadono tutte insieme.
    val reading = CountDownLatch(NasMediaLimits.READ_ALL_STREAMS)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) reading.countDown() }
    h.backend.stallReads = true
    val result = io.async { reader.readAll(file, { _, _ -> }) }
    reading.mustOpen("le quattro letture di readAll in volo")

    advanceTimeBy(14_500)
    runCurrent()
    assertThat(result.isActive).isTrue()

    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking("il readAll fermo").errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.backend.openConnections).isEqualTo(0)
    // Un solo collegamento in tutto: nessuna lettura sorella si e' riconnessa dopo la chiusura.
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
  }

  @Test
  fun readAllFermoInBackgroundSiFermaAVentiSecondiPerLaSoglia() = runTest {
    val h = Harness(backgroundScope, virtualClock(), io = Dispatchers.IO)
    h.backend.put(SHARE, "film.mp4", ByteArray(4 * NasMediaLimits.READ_ALL_CHUNK_BYTES))
    val reader = h.manager.mediaReader()
    val file = blockingIo("l'apertura del file") { reader.open(SHARE, SharePath.of("film.mp4"), Purpose.BACKGROUND).value() }
    val reading = CountDownLatch(NasMediaLimits.READ_ALL_STREAMS)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) reading.countDown() }
    h.backend.stallReads = true
    val result = io.async { reader.readAll(file, { _, _ -> }) }
    reading.mustOpen("le quattro letture di readAll in volo")

    // La scadenza per blocco in background e' 60 s, ma sotto 32 KiB/s per 20 s il trasferimento e' fermo (E-R14).
    advanceTimeBy(19_500)
    runCurrent()
    assertThat(result.isActive).isTrue()

    advanceTimeBy(1_000)
    runCurrent()
    assertThat(result.awaitBlocking("il readAll fermo").errorOrNull()).isEqualTo(NasError.Stalled)
    assertThat(h.manager.lastStallAt.value).isNotNull()
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
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
    val error = assertThrows(NasIoException::class.java) { runBlockingBounded("la lettura") { file.readRange(0L..9L) } }.error
    assertThat(error).isEqualTo(NasError.ServiceDown)
  }
}
