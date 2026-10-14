package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Color
import android.os.CancellationSignal
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.center
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.near
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.ref
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * L'ultimo gradino (D17): la miniatura dall'originale sul NAS. Sul NAS finto con byte veri, e con un NAS lento o fermo
 * per l'annullamento, la concorrenza e la chiusura dei file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NasMediaOriginalThumbnailsTest {

  private val nas = InMemoryNasMedia().also { it.addShare("test") }

  private class Lookup(private val known: Map<Long, NasFileLocation>) : NasLocationLookup {
    val calls = AtomicInteger()

    override suspend fun locate(locationId: Long): NasFileLocation? {
      calls.incrementAndGet()
      return known[locationId]
    }
  }

  private fun location(path: String) = NasFileLocation("test", SharePath.parse(path))

  private fun TestScope.originals(
    reader: NasMediaReader = nas.reader,
    lookup: NasLocationLookup,
    decoder: BlobDecoder = PlatformBlobDecoder,
    allowed: () -> Boolean = { true },
    maxBytes: Long = NasMediaOriginalThumbnails.DEFAULT_MAX_BYTES,
    concurrency: Int = 2,
  ) = NasMediaOriginalThumbnails(reader, lookup, decoder, allowed, maxBytes, concurrency, StandardTestDispatcher(testScheduler))

  private suspend fun NasMediaOriginalThumbnails.of(ref: MediaRef.Nas) = thumbnail(ref, 160, CancellationSignal())

  @Test
  fun leggeLOriginaleEneDaUnaMiniaturaProvvisoria() = runTest {
    nas.putFile("test", "Vacanze/IMG_1.jpg", NasContent.of(NasTestSupport.jpeg(Color.RED, 800, 600)))
    val o = originals(lookup = Lookup(mapOf(101L to location("Vacanze/IMG_1.jpg"))))
    val result = o.of(ref(1)) as ThumbResult.Loaded
    assertThat(result.provisional).isTrue()
    assertThat(maxOf(result.bitmap.width, result.bitmap.height)).isEqualTo(160)
    assertThat(near(center(result.bitmap), Color.RED)).isTrue()
    // Un solo file aperto e letto una volta: nient'altro dal NAS.
    assertThat(nas.opens.get()).isEqualTo(1)
    assertThat(nas.listCalls.get()).isEqualTo(0)
  }

  @Test
  fun tuttoCioCheNonSiPuoLeggereEUnFallimentoSenzaEccezioni() = runTest {
    nas.putFile("test", "a.jpg", NasContent.of(NasTestSupport.jpeg(Color.RED)))
    nas.putFile("test", "rotto.jpg", NasContent.of(ByteArray(2_000) { 7 }))
    nas.putFile("test", "grosso.jpg", NasContent.virtual(size = 50L * 1024 * 1024, seed = 1))
    nas.putFile("test", "vuoto.jpg", NasContent.of(ByteArray(0)))
    nas.putFile("test", "occupato.jpg", NasContent.of(NasTestSupport.jpeg(Color.RED)))
    nas.lock("test", "occupato.jpg")
    val o = originals(
      lookup = Lookup(
        mapOf(
          101L to location("a.jpg"), 102L to location("rotto.jpg"), 103L to location("grosso.jpg"), 104L to location("vuoto.jpg"),
          105L to location("occupato.jpg"), 107L to location("non-c-e.jpg"),
        ),
      ),
    )
    assertThat(o.of(ref(1))).isInstanceOf(ThumbResult.Loaded::class.java)
    // Non e' un'immagine, troppo grande (tetto 20 MiB: non si legge nemmeno), vuoto, aperto dal PC, sparito dal NAS.
    assertThat(o.of(ref(2))).isNull()
    val readBefore = nas.bytesRead.get()
    assertThat(o.of(ref(3))).isNull()
    assertThat(nas.bytesRead.get()).isEqualTo(readBefore)
    assertThat(o.of(ref(4))).isNull()
    assertThat(o.of(ref(5))).isNull()
    assertThat(o.of(ref(7))).isNull()
    // Una posizione che il catalogo non conosce piu' (azzerata): nessuna richiesta al NAS.
    val opens = nas.opens.get()
    assertThat(o.of(ref(9))).isNull()
    assertThat(nas.opens.get()).isEqualTo(opens)
  }

  @Test
  fun unNasChePermetteSoloErrori_nonLanciaMai() = runTest {
    nas.putFile("test", "a.jpg", NasContent.of(NasTestSupport.jpeg(Color.RED)))
    val o = originals(lookup = Lookup(mapOf(101L to location("a.jpg"))))
    for (error in listOf(NasError.ServiceDown, NasError.PermissionDenied, NasError.ShareMissing("test"), NasError.NotFound, NasError.Timeout, NasError.Stalled)) {
      nas.failWith = error
      assertThat(o.of(ref(1))).isNull()
    }
    nas.failWith = null
    assertThat(o.of(ref(1))).isNotNull()
  }

  @Test
  fun videoERawNonSiLeggonoMai_eLaReteLaDecideChiLaConosce() = runTest {
    nas.putFile("test", "v.mp4", NasContent.virtual(size = 10_000_000, seed = 2))
    val lookup = Lookup(mapOf(101L to location("v.mp4"), 102L to location("v.mp4")))
    var allowed = false
    val o = originals(lookup = lookup, allowed = { allowed })
    assertThat(o.of(ref(1, mime = "video/mp4"))).isNull()
    assertThat(o.of(ref(2, mime = "image/x-adobe-dng"))).isNull()
    assertThat(nas.opens.get()).isEqualTo(0)
    // Niente rete (dati mobili): non e' un errore, e' "non adesso" (D23), e non si apre niente.
    assertThat(o.of(ref(1, mime = "image/jpeg"))).isEqualTo(ThumbResult.Deferred)
    assertThat(nas.opens.get()).isEqualTo(0)
    assertThat(lookup.calls.get()).isEqualTo(0)
    allowed = true
    nas.putFile("test", "v.mp4", NasContent.of(NasTestSupport.jpeg(Color.GREEN)))
    assertThat(o.of(ref(1, mime = "image/jpeg"))).isInstanceOf(ThumbResult.Loaded::class.java)
  }

  @Test
  fun isDecodable_trePossibilita() {
    assertThat(NasMediaOriginalThumbnails.isDecodable("image/jpeg")).isTrue()
    assertThat(NasMediaOriginalThumbnails.isDecodable("IMAGE/HEIC")).isTrue()
    assertThat(NasMediaOriginalThumbnails.isDecodable(null)).isTrue()
    assertThat(NasMediaOriginalThumbnails.isDecodable("video/quicktime")).isFalse()
    assertThat(NasMediaOriginalThumbnails.isDecodable("image/x-adobe-dng")).isFalse()
    assertThat(NasMediaOriginalThumbnails.isDecodable("application/pdf")).isFalse()
  }

  // --- Annullamento e concorrenza, con un NAS lento ---------------------------------------------------------------------

  /** Un NAS i cui file si leggono lentamente (1 s per blocco); registra aperture, chiusure e letture insieme. */
  private class SlowNas(private val size: Long) : NasMediaReader by InMemoryNasMedia().reader {
    val opened = AtomicInteger()
    val closed = AtomicInteger()
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val reads = AtomicInteger()

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
      opened.incrementAndGet()
      return NasResult.Ok(
        object : NasRandomFile {
          override val size: Long = this@SlowNas.size
          override val stamp: NasStamp = NasStamp(size, 1L)

          override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
              reads.incrementAndGet()
              delay(1_000)
              return len
            } finally {
              inFlight.decrementAndGet()
            }
          }

          override suspend fun readRange(range: LongRange): ByteArray = ByteArray(0)

          override fun close() {
            closed.incrementAndGet()
          }
        },
      )
    }
  }

  @Test
  fun unaRichiestaAnnullataFermaLaLetturaEChiudeIlFile() = runTest {
    val slow = SlowNas(size = 5L * 1024 * 1024)
    val o = originals(reader = slow, lookup = Lookup(mapOf(101L to location("a.jpg"))))
    var thrown: Throwable? = null
    val job = launch {
      try {
        o.of(ref(1))
      } catch (e: CancellationException) {
        thrown = e
        throw e
      }
    }
    runCurrent()
    advanceTimeBy(2_500) // due blocchi su cinque letti
    assertThat(slow.reads.get()).isAtLeast(2)
    assertThat(slow.closed.get()).isEqualTo(0)
    job.cancel()
    advanceUntilIdle()
    // La richiesta e' uscita dalla finestra: niente piu' letture, il file e' chiuso (un handle aperto blocca il file sul NAS).
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
    assertThat(slow.closed.get()).isEqualTo(1)
    assertThat(slow.reads.get()).isLessThan(5)
    assertThat(slow.inFlight.get()).isEqualTo(0)
  }

  @Test
  fun unSegnaleScattatoFermaLaLettura() = runTest {
    val slow = SlowNas(size = 5L * 1024 * 1024)
    val o = originals(reader = slow, lookup = Lookup(mapOf(101L to location("a.jpg"))))
    val signal = CancellationSignal()
    var thrown: Throwable? = null
    launch {
      try {
        o.thumbnail(ref(1), 160, signal)
      } catch (e: CancellationException) {
        thrown = e
      }
    }
    runCurrent()
    advanceTimeBy(1_500)
    signal.cancel()
    advanceUntilIdle()
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
    assertThat(slow.closed.get()).isEqualTo(1)
    assertThat(slow.reads.get()).isAtMost(3)
  }

  @Test
  fun alMassimoDueOriginaliInsieme_gliAltriAspettanoIlLoroTurno() = runTest {
    val slow = SlowNas(size = 1024 * 1024)
    val lookup = Lookup((1L..6L).associate { 100L + it to location("f$it.jpg") })
    val o = originals(reader = slow, lookup = lookup, concurrency = 2, decoder = { _, _, _ -> null })
    val done = AtomicInteger()
    repeat(6) { n ->
      launch {
        o.of(ref(n + 1))
        done.incrementAndGet()
      }
    }
    runCurrent()
    advanceTimeBy(500)
    // Sei richieste, due file aperti: le altre quattro aspettano senza aver toccato il NAS.
    assertThat(slow.opened.get()).isEqualTo(2)
    advanceUntilIdle()
    assertThat(done.get()).isEqualTo(6)
    assertThat(slow.peak.get()).isEqualTo(2)
    assertThat(slow.opened.get()).isEqualTo(6)
    assertThat(slow.closed.get()).isEqualTo(6)
  }

  @Test
  fun unaRichiestaInCodaChePoiVieneAnnullataNonApreNulla() = runTest {
    val slow = SlowNas(size = 1024 * 1024)
    val lookup = Lookup((1L..3L).associate { 100L + it to location("f$it.jpg") })
    val o = originals(reader = slow, lookup = lookup, concurrency = 1, decoder = { _, _, _ -> null })
    val first = launch { o.of(ref(1)) }
    val second = launch { o.of(ref(2)) }
    runCurrent()
    advanceTimeBy(100)
    second.cancel()
    first.join()
    advanceUntilIdle()
    assertThat(slow.opened.get()).isEqualTo(1)
    assertThat(slow.closed.get()).isEqualTo(1)
  }
}
