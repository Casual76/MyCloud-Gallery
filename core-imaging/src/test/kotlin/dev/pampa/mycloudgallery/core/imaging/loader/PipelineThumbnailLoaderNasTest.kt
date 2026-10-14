package dev.pampa.mycloudgallery.core.imaging.loader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.nas.NasOriginalThumbnails
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.center
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.key
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.near
import dev.pampa.mycloudgallery.core.imaging.nas.NasThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.scheduler.RetryPolicy
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Il caricatore con le miniature del NAS (P7): "non adesso" che non e' un fallimento, miniature provvisorie sostituite
 * senza lampeggi quando la replica riceve quelle preparate, una cella che si aggiorna sola, e la visibilita' che arriva
 * alla sorgente. Con il tempo virtuale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineThumbnailLoaderNasTest {

  private val budget = ImagingBudget.forMemoryClass(256).copy(thumbnailConcurrency = 3)

  private fun request(n: Int, bucket: ThumbBucket = ThumbBucket.M, mime: String? = "image/jpeg"): ThumbRequest = ThumbRequest(
    ThumbKey(MediaId("00000000-0000-4000-8000-%012d".format(n)), versionKey = n.toLong(), bucket = bucket),
    NasTestSupport.ref(n, mime = mime),
  )

  private fun TestScope.dispatchers(): DispatcherProvider {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    return object : DispatcherProvider {
      override val main = dispatcher
      override val io = dispatcher
      override val default = dispatcher
    }
  }

  private fun TestScope.loader(source: ThumbnailSource, retry: RetryPolicy = RetryPolicy()): PipelineThumbnailLoader {
    val dispatchers = dispatchers()
    return PipelineThumbnailLoader(
      source = source,
      budget = budget,
      dispatchers = dispatchers,
      scope = CoroutineScope(SupervisorJob() + dispatchers.default),
      preparer = BitmapPreparer { },
      clockMs = { currentTime },
      retry = retry,
    )
  }

  /** L'originale del NAS: blu, e conta quante volte lo si legge. */
  private class Originals(var available: Boolean = true) : NasOriginalThumbnails {
    val calls = AtomicInteger()

    override suspend fun thumbnail(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? {
      calls.incrementAndGet()
      return if (available) ThumbResult.Loaded(NasTestSupport.solid(Color.BLUE, 8, 6), provisional = true) else null
    }
  }

  private fun TestScope.nasSource(replica: InMemoryReplica, originals: NasOriginalThumbnails = Originals()) =
    NasThumbnailSource(replica, replica, originals, NasTestSupport.ColorByFirstByte(), StandardTestDispatcher(testScheduler))

  private fun Bitmap?.isColor(color: Int) = this != null && near(center(this), color)

  @Test
  fun unaProvvisoriaSiSostituisceConLaPreparataSenzaLampeggiare() = runTest {
    val replica = InMemoryReplica()
    val loader = loader(nasSource(replica))
    val req = request(1)
    val state = loader.image(req.key, req.ref)
    replica.put(key(1), byteArrayOf(3, 0, 0, 0)) // la miniatura EXIF, blu
    loader.window(visible = listOf(req), ahead = emptyList())
    advanceUntilIdle()
    assertThat(state.value!!.asAndroidBitmap().isColor(Color.BLUE)).isTrue()

    // Niente di meglio: refreshNas non fa niente e la miniatura resta.
    assertThat(loader.refreshNas()).isEqualTo(0)
    advanceUntilIdle()
    assertThat(state.value!!.asAndroidBitmap().isColor(Color.BLUE)).isTrue()

    // La replica riceve la miniatura preparata (rossa).
    replica.putBlob(key(1), SegmentClass.THUMB_M, byteArrayOf(1, 0, 0, 0))
    assertThat(loader.refreshNas()).isEqualTo(1)
    // Fra l'avviso e l'arrivo della nuova la cella continua a mostrare la vecchia: niente buchi.
    assertThat(state.value).isNotNull()
    assertThat(state.value!!.asAndroidBitmap().isColor(Color.BLUE)).isTrue()
    advanceUntilIdle()
    assertThat(state.value!!.asAndroidBitmap().isColor(Color.RED)).isTrue()
    // E la preparata e' definitiva: un altro giro non la rifa'.
    val started = loader.stats.value.started
    assertThat(loader.refreshNas()).isEqualTo(0)
    advanceUntilIdle()
    assertThat(loader.stats.value.started).isEqualTo(started)
    loader.close()
  }

  @Test
  fun unaRichiestaInArrivoNonLeggeLOriginale_epoiDiventaVisibile() = runTest {
    val replica = InMemoryReplica()
    val originals = Originals()
    val loader = loader(nasSource(replica, originals))
    val a = request(1)
    val b = request(2)
    val stateA = loader.image(a.key, a.ref)
    val stateB = loader.image(b.key, b.ref)

    // A e' visibile, B e' solo in arrivo: l'originale si legge per A e non per B.
    loader.window(visible = listOf(a), ahead = listOf(b))
    advanceUntilIdle()
    assertThat(stateA.value).isNotNull()
    assertThat(stateB.value).isNull()
    assertThat(originals.calls.get()).isEqualTo(1)
    // Non e' un errore: nessun fallimento contato, nessun tentativo consumato.
    assertThat(loader.stats.value.failed).isEqualTo(0)
    assertThat(loader.hasGivenUp(b.key)).isFalse()

    // La finestra scorre: B diventa visibile e a quel punto si legge.
    loader.window(visible = listOf(b), ahead = emptyList())
    advanceUntilIdle()
    assertThat(stateB.value).isNotNull()
    assertThat(originals.calls.get()).isEqualTo(2)
    loader.close()
  }

  @Test
  fun ilNonAdessoNonGiraAVuoto_riprovaSoloAlCambioDiFinestra() = runTest {
    val replica = InMemoryReplica()
    val originals = Originals()
    val loader = loader(nasSource(replica, originals))
    val ahead = (1..40).map { request(it) }
    loader.window(visible = emptyList(), ahead = ahead)
    advanceUntilIdle()
    // Ogni richiesta e' stata provata una volta e poi lasciata ferma: niente cicli, niente originali.
    assertThat(loader.stats.value.started).isEqualTo(40)
    assertThat(originals.calls.get()).isEqualTo(0)
    advanceTimeBy(120_000)
    assertThat(loader.stats.value.started).isEqualTo(40)
    assertThat(loader.stats.value.failed).isEqualTo(0)
    // Un cambio di finestra le rimette alla prova (potrebbero essere diventate visibili).
    loader.window(visible = emptyList(), ahead = ahead)
    advanceUntilIdle()
    assertThat(loader.stats.value.started).isEqualTo(80)
    assertThat(loader.stats.value.failed).isEqualTo(0)
    loader.close()
  }

  @Test
  fun unaCellaChePerdeLeTentativiRitentaQuandoLaReplicaHaQualcosa() = runTest {
    val replica = InMemoryReplica()
    // Un video non preparato: niente replica, niente EXIF, e i video non si leggono: fallisce.
    val loader = loader(nasSource(replica), retry = RetryPolicy(maxAttempts = 2, backoffMs = listOf(100L)))
    val video = request(9, mime = "video/mp4")
    val state = loader.image(video.key, video.ref)
    loader.window(visible = listOf(video), ahead = emptyList())
    advanceTimeBy(10_000)
    assertThat(state.value).isNull()
    assertThat(loader.hasGivenUp(video.key)).isTrue()
    val failedBefore = loader.stats.value.failed

    // Passa il tempo senza che succeda niente: non ritenta da solo.
    advanceTimeBy(600_000)
    assertThat(loader.stats.value.failed).isEqualTo(failedBefore)

    // La preparazione di un altro dispositivo arriva: la replica ha la copertina e refreshNas fa ritentare.
    replica.putBlob(key(9), SegmentClass.THUMB_M, byteArrayOf(2, 0, 0, 0))
    loader.refreshNas()
    advanceUntilIdle()
    assertThat(state.value!!.asAndroidBitmap().isColor(Color.GREEN)).isTrue()
    assertThat(loader.hasGivenUp(video.key)).isFalse()
    loader.close()
  }

  @Test
  fun lAltroNasTornaRaggiungibile_leCelleSenzaNienteRitentano() = runTest {
    val replica = InMemoryReplica()
    val originals = Originals(available = false)
    val loader = loader(nasSource(replica, originals), retry = RetryPolicy(maxAttempts = 2, backoffMs = listOf(100L)))
    val req = request(4)
    val state = loader.image(req.key, req.ref)
    loader.window(visible = listOf(req), ahead = emptyList())
    advanceTimeBy(10_000)
    assertThat(state.value).isNull()
    assertThat(loader.hasGivenUp(req.key)).isTrue()
    val reads = originals.calls.get()
    assertThat(reads).isEqualTo(2)
    // Il NAS risponde di nuovo: chi chiama (IndexSync che torna Idle/UpToDate) avvisa il caricatore.
    originals.available = true
    loader.refreshNas()
    advanceUntilIdle()
    assertThat(state.value).isNotNull()
    assertThat(originals.calls.get()).isEqualTo(reads + 1)
    loader.close()
  }

  // --- Ricomposizioni: una miniatura che arriva tocca una cella sola -------------------------------------------------------

  /** Una sorgente del NAS che risponde a ogni chiave solo quando il test la libera. */
  private class GatedSource : ThumbnailSource {
    private val gates = HashMap<Long, CompletableDeferred<Unit>>()

    @Synchronized
    private fun gate(key: Long) = gates.getOrPut(key) { CompletableDeferred() }

    fun release(n: Int) = gate(key(n).short).complete(Unit)

    override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = null

    override suspend fun loadResult(ref: MediaRef, px: Int, cancel: CancellationSignal, context: ThumbContext): ThumbResult? {
      gate((ref as MediaRef.Nas).contentKey).await()
      return ThumbResult.Loaded(NasTestSupport.solid(Color.RED, 8, 6))
    }
  }

  @Test
  fun unaMiniaturaCheArrivaAggiornaUnaCellaSola() = runTest {
    val source = GatedSource()
    val loader = loader(source)
    val visible = (1..300).map { request(it) }
    val states = visible.map { loader.image(it.key, it.ref) }
    loader.window(visible = visible, ahead = emptyList())
    runCurrent()
    // Le prime richieste in volo sono al tetto di concorrenza: nessuna miniatura e' ancora arrivata.
    assertThat(states.count { it.value != null }).isEqualTo(0)
    assertThat(loader.stats.value.inFlight).isEqualTo(budget.thumbnailConcurrency)

    // Ne arriva una: cambia lo stato di quella cella e di nessun'altra.
    source.release(1)
    runCurrent()
    assertThat(states.indices.filter { states[it].value != null }).containsExactly(0)
    // Ne arrivano altre due: due celle in piu', le altre ferme (le richieste partono nell'ordine della finestra).
    source.release(2)
    source.release(3)
    runCurrent()
    assertThat(states.indices.filter { states[it].value != null }).containsExactly(0, 1, 2)
    loader.close()
  }
}
