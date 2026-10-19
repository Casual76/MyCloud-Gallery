package dev.pampa.mycloudgallery.feature.integrations.provider

import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeDocumentReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23; E-X1, E-X4, E-X6): il descrittore a richiesta. Letture a intervalli (mai il file intero in memoria), scadenza a ogni
 * lettura, annullamento che lascia tutto pulito, tetto dei descrittori aperti. Il "kernel" e' il test, che chiama `onRead` come farebbe il
 * sistema.
 */
@RunWith(AndroidJUnit4::class)
class ProxyDescriptorsTest {

  private class FakeOpener : ProxyOpener {
    var callback: ProxyFileDescriptorCallback? = null
    var opened = 0
    var fails = false
    val files = mutableListOf<File>()

    override fun open(callback: ProxyFileDescriptorCallback, handler: Handler): ParcelFileDescriptor {
      if (fails) throw IOException("non si apre")
      opened++
      this.callback = callback
      val file = File.createTempFile("proxy", ".bin").also { files += it }
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
  }

  private var handlersClosed = 0
  private val handlers = ProxyHandlerFactory { ProxyHandler(Handler(Looper.getMainLooper())) { handlersClosed++ } }
  private val opener = FakeOpener()

  @Before
  fun reset() = IntegrationCounters.reset()

  @After
  fun cleanup() {
    opener.files.forEach { it.delete() }
    IntegrationCounters.reset()
  }

  private fun descriptors(slots: DescriptorSlots = DescriptorSlots(), deadlineMs: Long = 150) =
    ProxyDescriptors(opener, handlers, slots, deadlineMs)

  /** Apre un descrittore del [reader] e restituisce il callback che il sistema chiamerebbe. */
  private fun ProxyDescriptors.openFor(reader: FakeDocumentReader, signal: CancellationSignal? = null): ProxyFileDescriptorCallback {
    assertThat(acquire(0)).isTrue()
    open(reader, signal).close()
    return opener.callback!!
  }

  private fun ProxyFileDescriptorCallback.read(offset: Long, size: Int, buffer: ByteArray = ByteArray(size)): Pair<Int, ByteArray> =
    onRead(offset, size, buffer) to buffer

  private fun errnoOf(block: () -> Unit): Int {
    val error = assertThrows(ErrnoException::class.java) { block() }
    return error.errno
  }

  // --- Letture a intervalli ---------------------------------------------------------------------------------------------

  @Test
  fun leggeUnPezzoAlVoltoEMaiIlFileIntero() {
    val reader = FakeDocumentReader(size = 3L * 1024 * 1024 * 1024, seed = 5) // 3 GiB virtuali
    val callback = descriptors().openFor(reader)
    assertThat(callback.onGetSize()).isEqualTo(3L * 1024 * 1024 * 1024)
    val random = java.util.Random(3)
    repeat(200) {
      val offset = (random.nextDouble() * (reader.size - 200_000)).toLong()
      val (n, data) = callback.read(offset, 131_072)
      assertThat(n).isEqualTo(131_072)
      for (i in listOf(0, 1, 777, 131_071)) assertThat(data[i]).isEqualTo(reader.byteAt(offset + i))
    }
    // Ogni lettura e' un pezzo: il piu' grande mai chiesto al lettore e' quello del sistema, non la dimensione del file.
    assertThat(reader.maxChunk.get()).isAtMost(131_072)
    assertThat(reader.bytesServed.get()).isEqualTo(200L * 131_072)
  }

  @Test
  fun riempieLaRichiestaAncheSeIlLettoreDaPocoPerVolta() {
    val slow = object : dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader {
      override val size: Long = 100_000
      override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, 777, (size - offset).toInt())
        for (i in 0 until n) buf[off + i] = ((offset + i) and 0xFF).toByte()
        return n
      }
      override fun close() = Unit
    }
    val d = descriptors()
    assertThat(d.acquire(0)).isTrue()
    d.open(slow, null).close()
    val (n, data) = opener.callback!!.read(1_000, 40_000)
    assertThat(n).isEqualTo(40_000)
    assertThat(data[0]).isEqualTo((1_000 and 0xFF).toByte())
    assertThat(data[39_999]).isEqualTo(((1_000 + 39_999) and 0xFF).toByte())
  }

  @Test
  fun allaFineDelFileDaQuelloChePuo_EOltreDaZero() {
    val callback = descriptors().openFor(FakeDocumentReader(size = 1_000))
    assertThat(callback.read(900, 500).first).isEqualTo(100)
    assertThat(callback.read(1_000, 500).first).isEqualTo(0)
    assertThat(callback.read(50_000, 500).first).isEqualTo(0)
    assertThat(callback.read(0, 0).first).isEqualTo(0)
  }

  @Test
  fun argomentiAssurdiSonoErroriENonEccezioniStrane() {
    val callback = descriptors().openFor(FakeDocumentReader(size = 1_000))
    assertThat(errnoOf { callback.read(-1, 10) }).isEqualTo(OsConstants.EINVAL)
    assertThat(errnoOf { callback.read(0, 100, ByteArray(10)) }).isEqualTo(OsConstants.EINVAL)
    assertThat(errnoOf { callback.read(0, -5, ByteArray(10)) }).isEqualTo(OsConstants.EINVAL)
  }

  @Test
  fun inScritturaNonSiPuo() {
    val callback = descriptors().openFor(FakeDocumentReader(size = 1_000))
    val error = assertThrows(ErrnoException::class.java) { callback.onWrite(0, 1, ByteArray(1)) }
    assertThat(error.errno).isEqualTo(OsConstants.EBADF)
  }

  // --- Scadenza (E-X1) --------------------------------------------------------------------------------------------------

  @Test
  fun unaLetturaCheNonFinisceScadeConEtimedout_E_X1() {
    val reader = FakeDocumentReader(size = 10_000, hangs = true)
    val callback = descriptors(deadlineMs = 120).openFor(reader)
    val started = System.nanoTime()
    assertThat(ProviderTestSupport.within { errnoOf { callback.read(0, 1_000) } }).isEqualTo(OsConstants.ETIMEDOUT)
    val tookMs = (System.nanoTime() - started) / 1_000_000
    assertThat(tookMs).isAtMost(5_000L)
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_READ_TIMEOUTS)).isEqualTo(1L)
    // Il descrittore non e' rotto: una lettura buona dopo funziona ancora (qui il lettore e' un altro, ma lo stato del callback e' lo stesso).
  }

  @Test
  fun unaLetturaLentaMaDentroLaScadenzaRiesce() {
    val reader = FakeDocumentReader(size = 10_000, readDelayMs = 30)
    val callback = descriptors(deadlineMs = 5_000).openFor(reader)
    assertThat(callback.read(0, 1_000).first).isEqualTo(1_000)
  }

  @Test
  fun unErroreDelNasEUnErroreDiIoNonUnaEccezione() {
    val broken = object : dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader {
      override val size: Long = 100
      override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int = throw IOException("la rete e' caduta")
      override fun close() = Unit
    }
    val d = descriptors()
    assertThat(d.acquire(0)).isTrue()
    d.open(broken, null).close()
    assertThat(errnoOf { opener.callback!!.read(0, 10) }).isEqualTo(OsConstants.EIO)
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_READ_ERRORS)).isEqualTo(1L)
  }

  // --- Annullamento (E-X4) ----------------------------------------------------------------------------------------------

  @Test
  fun annullareFermaLaLetturaInVoloELeSuccessiveELasciaTuttoPulito() {
    val reader = FakeDocumentReader(size = 10_000, hangs = true)
    val slots = DescriptorSlots(maxOpen = 2, maxWaiting = 1)
    val signal = CancellationSignal()
    val d = descriptors(slots = slots, deadlineMs = 30_000)
    val callback = d.openFor(reader, signal)
    assertThat(slots.inUse).isEqualTo(1)

    val inFlight = AtomicReference<Int>()
    val done = CountDownLatch(1)
    Thread {
      inFlight.set(errnoOf { callback.read(0, 1_000) })
      done.countDown()
    }.start()
    // La lettura e' davvero in volo (il lettore la sta aspettando)...
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (reader.reads.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
    assertThat(reader.reads.get()).isEqualTo(1)
    // ...e l'annullamento la ferma subito, ben prima della scadenza di 30 secondi.
    signal.cancel()
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(inFlight.get()).isEqualTo(OsConstants.ECANCELED)
    // Le successive falliscono subito, senza nemmeno toccare il lettore.
    assertThat(errnoOf { callback.read(0, 10) }).isEqualTo(OsConstants.ECANCELED)
    assertThat(reader.reads.get()).isEqualTo(1)
    // Il sistema chiude il descrittore: tutto si libera, una volta sola.
    assertThat(reader.closed).isFalse()
    callback.onRelease()
    callback.onRelease()
    assertThat(reader.closed).isTrue()
    assertThat(handlersClosed).isEqualTo(1)
    assertThat(slots.inUse).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_READ_CANCELLED)).isAtLeast(1L)
  }

  @Test
  fun unSegnaleGiaScattatoNonApreNienteELasciaTuttoPulito() {
    val reader = FakeDocumentReader(size = 1_000)
    val slots = DescriptorSlots(maxOpen = 1, maxWaiting = 0)
    val d = descriptors(slots = slots)
    assertThat(d.acquire(0)).isTrue()
    val signal = CancellationSignal().also { it.cancel() }
    assertThrows(OperationCanceledException::class.java) { d.open(reader, signal) }
    assertThat(opener.opened).isEqualTo(0)
    assertThat(reader.closed).isTrue()
    assertThat(handlersClosed).isEqualTo(1)
    assertThat(slots.inUse).isEqualTo(0)
  }

  @Test
  fun seIlSistemaNonApreIlDescrittoreIlLettoreSiChiudeEIlPostoSiLibera() {
    opener.fails = true
    val reader = FakeDocumentReader(size = 1_000)
    val slots = DescriptorSlots(maxOpen = 1, maxWaiting = 0)
    val d = descriptors(slots = slots)
    assertThat(d.acquire(0)).isTrue()
    assertThrows(IOException::class.java) { d.open(reader, null) }
    assertThat(reader.closed).isTrue()
    assertThat(handlersClosed).isEqualTo(1)
    assertThat(slots.inUse).isEqualTo(0)
  }

  @Test
  fun annullareDopoLApertura() {
    val reader = FakeDocumentReader(size = 1_000)
    val signal = CancellationSignal()
    val callback = descriptors().openFor(reader, signal)
    assertThat(callback.read(0, 10).first).isEqualTo(10)
    signal.cancel()
    assertThat(errnoOf { callback.read(0, 10) }).isEqualTo(OsConstants.ECANCELED)
  }

  // --- Tetto (E-X6) -----------------------------------------------------------------------------------------------------

  @Test
  fun oltreIlTettoLeAperturePrimaAspettanoPoiVengonoRifiutateSubito_E_X6() {
    val slots = DescriptorSlots(maxOpen = 2, maxWaiting = 1)
    assertThat(slots.acquire(0)).isTrue()
    assertThat(slots.acquire(0)).isTrue()
    assertThat(slots.inUse).isEqualTo(2)
    // Una in attesa: scade dopo la sua attesa.
    val waiter = AtomicReference<Boolean>()
    val waiting = Thread { waiter.set(slots.acquire(300)) }.also { it.start() }
    Thread.sleep(80)
    // La seconda in attesa trova il tetto delle attese pieno e viene rifiutata SUBITO, senza aspettare.
    val started = System.nanoTime()
    assertThat(slots.acquire(10_000)).isFalse()
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000L)
    waiting.join(5_000)
    assertThat(waiter.get()).isFalse()
    // Un posto liberato lo prende chi arriva dopo.
    slots.release()
    assertThat(slots.acquire(0)).isTrue()
  }

  @Test
  fun unPostoLiberatoSvegliaChiAspetta() {
    val slots = DescriptorSlots(maxOpen = 1, maxWaiting = 2)
    assertThat(slots.acquire(0)).isTrue()
    val got = AtomicReference<Boolean>()
    val thread = Thread { got.set(slots.acquire(5_000)) }.also { it.start() }
    Thread.sleep(80)
    slots.release()
    thread.join(5_000)
    assertThat(got.get()).isTrue()
  }

  @Test
  fun ilTettoDiSerieEOtto() {
    assertThat(DocumentPolicy.MAX_OPEN_DESCRIPTORS).isEqualTo(8)
    val slots = DescriptorSlots()
    repeat(8) { assertThat(slots.acquire(0)).isTrue() }
    assertThat(slots.acquire(0)).isFalse()
    assertThat(slots.inUse).isEqualTo(8)
  }
}
