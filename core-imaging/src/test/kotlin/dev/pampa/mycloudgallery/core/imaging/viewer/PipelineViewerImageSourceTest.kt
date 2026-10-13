package dev.pampa.mycloudgallery.core.imaging.viewer

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.CancellationSignal
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedKind
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.decode.ExifOrientation
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.loader.BitmapPreparer
import dev.pampa.mycloudgallery.core.imaging.testing.FakeImageOrigin
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Le immagini del viewer con sorgenti **lente e irraggiungibili finte** (E-V1, E-V2, E-V5), orientamenti, file rotti
 * (E-M10), animati (E-M11) e `zoomSpec`. Il tempo e' virtuale; le sole prove con thread veri sono quelle sul thread
 * di `prepareToDraw`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineViewerImageSourceTest {

  private val budget = ImagingBudget.forMemoryClass(256)
  private val screen = IntSize(1080, 2400)

  private fun ref(index: Int, mime: String? = "image/jpeg"): MediaRef.Phone = MediaRef.Phone("external_primary", index + 1L, mime)

  /** Un'anteprima 16x8 (stessa proporzione 2:1 dei JPEG di prova 32x16), per ogni riferimento. */
  private val previews = object : ThumbnailSource {
    override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? =
      TestImages.quadrants(16, 8)
  }

  private fun TestScope.testDispatchers(): DispatcherProvider {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    return object : DispatcherProvider {
      override val main = dispatcher
      override val io = dispatcher
      override val default = dispatcher
    }
  }

  private fun TestScope.viewer(
    origin: FakeImageOrigin,
    thumbs: ThumbnailSource = previews,
    preparer: BitmapPreparer = BitmapPreparer { },
  ) = PipelineViewerImageSource(origin, thumbs, budget, testDispatchers(), preparer)

  private val jpeg = TestImages.jpeg(TestImages.quadrants())

  // --- La sequenza -------------------------------------------------------------------------------------

  @Test
  fun anteprimaPoiCaricamentoPoiImmagineNitidaDritta() = runTest {
    val origin = FakeImageOrigin().apply { put(ref(0), jpeg) }
    val viewer = viewer(origin)
    val states = viewer.state(ref(0), screen).toList()
    assertThat(states.map { it::class.java.simpleName }).containsExactly("Preview", "Loading", "Sharp").inOrder()
    val sharp = states.last() as ViewerImageState.Sharp
    assertThat(sharp.bitmap.width).isEqualTo(32)
    assertThat(sharp.bitmap.height).isEqualTo(16)
    assertThat(sharp.hdr).isFalse()
    assertThat(sharp.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.SRGB))
    assertThat(viewer.stats.value.completed).isEqualTo(1)
    assertThat(viewer.stats.value.inFlight).isEqualTo(0)
  }

  @Test
  fun conGliOttoOrientamentiLImmagineNitidaEDrittaEHaLaProporzioneDellAnteprima() = runTest {
    for (orientation in 1..8) {
      val origin = FakeImageOrigin().apply { put(ref(0), TestImages.jpegWithOrientation(orientation)) }
      val viewer = viewer(origin, thumbs = object : ThumbnailSource {
        // Un'anteprima come la dà il MediaProvider: gia' dritta, con la proporzione dell'immagine vista.
        override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap =
          if (ExifOrientation.fromExif(orientation).swapsSides) TestImages.quadrants(8, 16) else TestImages.quadrants(16, 8)
      })
      val states = viewer.state(ref(0), screen).toList()
      val preview = (states.first() as ViewerImageState.Preview).bitmap
      val sharp = (states.last() as ViewerImageState.Sharp).bitmap
      assertThat(TestImages.checkOrientation(sharp.asAndroidBitmap(), orientation)).isNull()
      // E-V1: stessa geometria, quindi la sostituzione non sposta niente.
      assertThat(sharp.width.toFloat() / sharp.height).isWithin(0.01f).of(preview.width.toFloat() / preview.height)
    }
  }

  @Test
  fun sorgenteLentaAnteprimaSubitoESostituzioneSenzaSpostamento_EV1() = runTest {
    val origin = FakeImageOrigin().apply {
      put(ref(0), jpeg)
      latencyMs = 2_000
    }
    val viewer = viewer(origin)
    val seen = CopyOnWriteArrayList<Pair<Long, ViewerImageState>>()
    val job = launch { viewer.state(ref(0), screen).collect { seen += currentTime to it } }

    runCurrent()
    // Subito (tempo 0) ci sono l'anteprima e lo stato "in caricamento"; la versione nitida non c'e' ancora.
    assertThat(seen.map { it.first }).containsExactly(0L, 0L)
    assertThat(seen[0].second).isInstanceOf(ViewerImageState.Preview::class.java)
    assertThat(seen[1].second).isInstanceOf(ViewerImageState.Loading::class.java)
    advanceTimeBy(1_999)
    assertThat(seen).hasSize(2)

    advanceUntilIdle()
    assertThat(seen).hasSize(3)
    val (at, last) = seen.last()
    assertThat(at).isAtLeast(2_000L)
    val sharp = last as ViewerImageState.Sharp
    val preview = (seen.first().second as ViewerImageState.Preview).bitmap
    assertThat(sharp.bitmap.width.toFloat() / sharp.bitmap.height).isWithin(0.01f).of(preview.width.toFloat() / preview.height)
    job.cancel()
  }

  @Test
  fun sorgenteIrraggiungibileDaLaSpiegazioneEMantieneLAnteprima_EV1_EV2() = runTest {
    val origin = FakeImageOrigin().apply {
      put(ref(0), jpeg)
      makeUnreachable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, sizeBytes = 18_000_000)
    }
    val viewer = viewer(origin)
    val states = viewer.state(ref(0), screen).toList()
    assertThat(states.last()).isEqualTo(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 18_000_000))
    assertThat(states.first()).isInstanceOf(ViewerImageState.Preview::class.java)
    assertThat(states.filterIsInstance<ViewerImageState.Sharp>()).isEmpty()
    assertThat(viewer.stats.value.unavailable).isEqualTo(1)

    for (reason in UnavailableReason.entries) {
      origin.makeUnreachable(reason)
      assertThat(viewer.state(ref(0), screen).toList().last()).isEqualTo(ViewerImageState.Unavailable(reason, null))
    }
  }

  @Test
  fun unMediaMancanteEUnFileRottoDannoUnErroreStabileSenzaEccezioni_EM10() = runTest {
    val origin = FakeImageOrigin().apply {
      put(ref(1), ByteArray(0))
      put(ref(2), "questo non e' un'immagine".toByteArray())
      put(ref(3), jpeg.copyOf(10))
      put(ref(4), TestImages.jpeg(TestImages.quadrants(256, 256), quality = 100).let { it.copyOf(it.size / 3) })
    }
    val viewer = viewer(origin)
    suspend fun last(i: Int) = viewer.state(ref(i), screen).toList().last()

    assertThat(last(0)).isEqualTo(ViewerImageState.Failed(ImageFailure.NOT_FOUND))
    assertThat(last(1)).isEqualTo(ViewerImageState.Failed(ImageFailure.DECODE_FAILED))
    assertThat(last(2)).isEqualTo(ViewerImageState.Failed(ImageFailure.UNSUPPORTED_FORMAT))
    assertThat(last(3)).isEqualTo(ViewerImageState.Failed(ImageFailure.DECODE_FAILED))
    assertThat(last(4)).isEqualTo(ViewerImageState.Failed(ImageFailure.DECODE_FAILED))
    assertThat(viewer.stats.value.failed).isEqualTo(5)
    assertThat(viewer.stats.value.inFlight).isEqualTo(0)
    // Un file rotto non rovina il successivo.
    origin.put(ref(5), jpeg)
    assertThat(last(5)).isInstanceOf(ViewerImageState.Sharp::class.java)
  }

  @Test
  fun laMemoriaEsauritaDiventaUnErroreDelloStatoENonUnCrash() = runTest {
    val hungry = object : ImageBytes {
      override val sizeBytes: Long = 1
      override fun openStream(): InputStream = jpeg.inputStream()
      override fun decoderSource(): ImageDecoder.Source = throw OutOfMemoryError("finto")
    }
    val origin = object : ImageOrigin {
      override suspend fun open(ref: MediaRef): OriginResult = OriginResult.Opened(hungry)
    }
    val viewer = PipelineViewerImageSource(origin, previews, budget, testDispatchers(), BitmapPreparer { })
    assertThat(viewer.state(ref(0), screen).toList().last()).isEqualTo(ViewerImageState.Failed(ImageFailure.OUT_OF_MEMORY))
  }

  @Test
  fun unaSorgenteDiAnteprimeCheSiRompeNonFermaIlViewer() = runTest {
    val origin = FakeImageOrigin().apply { put(ref(0), jpeg) }
    val broken = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = error("provider in crisi")
    }
    val states = viewer(origin, thumbs = broken).state(ref(0), screen).toList()
    assertThat(states.map { it::class.java.simpleName }).containsExactly("Loading", "Sharp").inOrder()
  }

  @Test
  fun unVideoMostraIlFotogrammoMiniaturaEBastaSenzaAprireIFile() = runTest {
    val origin = FakeImageOrigin()
    val viewer = viewer(origin)
    val states = viewer.state(ref(0, mime = "video/mp4"), screen).toList()
    assertThat(states.map { it::class.java.simpleName }).containsExactly("Preview", "Loading", "Sharp").inOrder()
    assertThat(origin.openCalls).isEqualTo(0)
    // Senza miniatura un video non ha una "immagine nitida": lo dice, non finge.
    val none = viewer(origin, thumbs = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = null
    })
    assertThat(none.state(ref(0, mime = "video/mp4"), screen).toList().last())
      .isEqualTo(ViewerImageState.Failed(ImageFailure.UNSUPPORTED_FORMAT))
  }

  // --- E-V5: fling su elementi lenti -----------------------------------------------------------------------

  @Test
  fun flingSu50ElementiLentiAlMassimoTreDecodificheInVoloELeAltreAnnullate_EV5() = runTest {
    val origin = FakeImageOrigin().apply { latencyMs = 1_000 }
    val refs = (0 until 50).map { ref(it) }
    refs.forEach { origin.put(it, jpeg) }
    val viewer = viewer(origin)
    val pager = ArrayDeque<Job>()
    var worstInFlight = 0
    for (i in 0 until 50) {
      // Il pager tiene vive cinque pagine alla volta: ogni nuova pagina ne fa uscire una vecchia (che si annulla).
      pager += launch { viewer.state(refs[i], screen).collect { } }
      if (pager.size > 5) pager.removeFirst().cancel()
      advanceTimeBy(20)
      worstInFlight = maxOf(worstInFlight, viewer.stats.value.inFlight)
    }
    val duringFling = viewer.stats.value
    assertThat(worstInFlight).isAtMost(3)
    assertThat(duringFling.maxInFlight).isAtMost(3)
    assertThat(duringFling.cancelled).isAtLeast(45)
    // Lo scorrimento si ferma: le ultime cinque pagine finiscono, al massimo tre per volta.
    advanceUntilIdle()
    val end = viewer.stats.value
    assertThat(end.maxInFlight).isAtMost(3)
    assertThat(end.completed).isEqualTo(5)
    assertThat(end.cancelled).isEqualTo(45)
    assertThat(end.inFlight).isEqualTo(0)
    assertThat(end.waiting).isEqualTo(0)
    // Gli slot passano sempre alle pagine piu' nuove (quelle vecchie escono per prime): nessuna ne resta senza.
    assertThat(origin.openCalls).isAtLeast(5)
    pager.forEach { it.cancel() }
  }

  @Test
  fun unaPaginaCheEsceMentreAspettaLoSlotNonParteMai() = runTest {
    val origin = FakeImageOrigin().apply { latencyMs = 10_000 }
    (0 until 5).forEach { origin.put(ref(it), jpeg) }
    val viewer = viewer(origin)
    val jobs = (0 until 5).map { i -> launch { viewer.state(ref(i), screen).collect { } } }
    runCurrent()
    assertThat(viewer.stats.value.inFlight).isEqualTo(3)
    assertThat(viewer.stats.value.waiting).isEqualTo(2)
    // Le due in attesa escono: non hanno mai aperto il media.
    jobs[3].cancel()
    jobs[4].cancel()
    runCurrent()
    assertThat(viewer.stats.value.waiting).isEqualTo(0)
    assertThat(origin.openCalls).isEqualTo(3)
    // Se ne esce una in volo, lo slot torna disponibile.
    jobs[0].cancel()
    runCurrent()
    assertThat(viewer.stats.value.inFlight).isEqualTo(2)
    jobs.forEach { it.cancel() }
  }

  // --- Zoom e animati --------------------------------------------------------------------------------------

  @Test
  fun lePropozioniDelloZoomSonoQuelleDellImmagineDrittaEITasselliSiVedonoDalFormato() = runTest {
    val origin = FakeImageOrigin().apply {
      put(ref(0), TestImages.jpegWithOrientation(6, 64, 32))
      put(ref(1), TestImages.animatedGif())
      put(ref(2), "boh".toByteArray())
    }
    val viewer = viewer(origin)
    // Orientamento 6: l'immagine si vede 32x64.
    val spec = viewer.zoomSpec(ref(0))!!
    assertThat(spec.width).isEqualTo(32)
    assertThat(spec.height).isEqualTo(64)
    assertThat(spec.tiled).isTrue()
    // Un animato si anima, non si ingrandisce a tasselli.
    assertThat(viewer.zoomSpec(ref(1))!!.tiled).isFalse()
    // Non leggibile o sconosciuto: nessuna risposta, nessuna eccezione.
    assertThat(viewer.zoomSpec(ref(2))).isNull()
    assertThat(viewer.zoomSpec(ref(9))).isNull()
    assertThat(viewer.zoomSpec(MediaRef.Synthetic(1, 10, 10))).isNull()
  }

  @Test
  fun glianimatiSiRiconoscono() = runTest {
    val origin = FakeImageOrigin().apply {
      put(ref(0), TestImages.animatedGif())
      put(ref(1), jpeg)
    }
    val viewer = viewer(origin)
    val spec = viewer.animated(ref(0))!!
    assertThat(spec.kind).isEqualTo(AnimatedKind.GIF)
    assertThat(spec.width).isEqualTo(4)
    assertThat(spec.height).isEqualTo(2)
    assertThat(viewer.animated(ref(1))).isNull()
    assertThat(viewer.animated(ref(9))).isNull()
  }

  @Test
  fun unGifAnimatoDaUnAnimatedImageDrawableFermoNellaGrigliaEAnimatoNelViewer_EM11() = runTest {
    val big = TestImages.gif(40, 20, listOf(IntArray(800), IntArray(800) { 1 }))
    val origin = FakeImageOrigin().apply {
      put(ref(0), big)
      put(ref(1), jpeg)
    }
    val viewer = viewer(origin)
    val drawable = viewer.drawable(ref(0), maxSide = 1_000)
    assertThat(drawable).isInstanceOf(AnimatedImageDrawable::class.java)
    assertThat(drawable!!.intrinsicWidth).isEqualTo(40)
    // Un tetto sul lato lungo riduce i fotogrammi in memoria.
    val shrunk = viewer.drawable(ref(0), maxSide = 10)!!
    assertThat(maxOf(shrunk.intrinsicWidth, shrunk.intrinsicHeight)).isAtMost(10)
    // Un'immagine ferma o mancante non da un disegno animato.
    assertThat(viewer.drawable(ref(1), maxSide = 1_000)).isNull()
    assertThat(viewer.drawable(ref(7), maxSide = 1_000)).isNull()
    // La sequenza di stati di un GIF da' comunque la prima immagine ferma (anteprima del viewer).
    val states = viewer.state(ref(0), screen).toList()
    assertThat(states.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
  }

  // --- Thread --------------------------------------------------------------------------------------------

  /** Thread veri: la preparazione delle bitmap del viewer non gira mai sul thread principale. */
  @Test
  fun prepareToDrawDelViewerGiraFuoriDalThreadPrincipale() {
    val mainThread = Looper.getMainLooper().thread
    val prepared = CopyOnWriteArrayList<Thread>()
    val origin = FakeImageOrigin().apply { put(ref(0), jpeg) }
    val viewer = PipelineViewerImageSource(
      origin, previews, budget, DefaultDispatcherProvider, BitmapPreparer { prepared += Thread.currentThread() },
    )
    val states = runBlocking { viewer.state(ref(0), screen).toList() }
    assertThat(states.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
    // Anteprima e immagine nitida: due preparazioni, nessuna sul thread principale.
    assertThat(prepared).hasSize(2)
    assertThat(prepared.none { it === mainThread }).isTrue()
  }
}
