package dev.pampa.mycloudgallery.core.imaging.viewer

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.imaging.loader.BitmapPreparer
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Le immagini del viewer con un media del **NAS** (P8): la sequenza `Preview → Loading(progress) → Sharp` dall'HQ della
 * replica o dall'originale letto a intervalli, `Unavailable` con la dimensione (E-V2, E-L4), E-V1 (lenta, stessa geometria),
 * E-V5 (al massimo tre in volo, le altre annullate senza lasciare temporanei) ed E-V6 (sparito), e i limiti dello zoom prima
 * e dopo che l'originale e' sul telefono. NAS finto, tempo virtuale, decodifica vera (Robolectric nativo).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NasViewerImagesTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val budget = ImagingBudget.forMemoryClass(256)
  private val screen = IntSize(1080, 2400)

  private val nas = InMemoryNasMedia()
  private lateinit var reader: ScriptedNasReader
  private lateinit var store: NasOriginalStore
  private val locations = HashMap<Long, NasMediaLocation?>()
  private val replica = InMemoryReplica()
  private val consent = MobileDataConsent()
  private var metered = false

  private val original = TestImages.jpeg(TestImages.quadrants(64, 32))
  private val hq = TestImages.jpeg(TestImages.quadrants(48, 24))

  @Before
  fun setUp() {
    nas.addShare("test")
    reader = ScriptedNasReader(nas.reader)
    store = NasOriginalStore(folder.newFolder("viewer-nas"), maxBytes = 64L shl 20) { Long.MAX_VALUE }
  }

  private val previews = object : ThumbnailSource {
    override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = TestImages.quadrants(16, 8)
  }

  private fun TestScope.dispatchers(): DispatcherProvider {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    return object : DispatcherProvider {
      override val main = dispatcher
      override val io = dispatcher
      override val default = dispatcher
    }
  }

  private fun TestScope.origin() = NasImageOrigin(
    reader = reader,
    locator = { locations[it.locationId] },
    store = store,
    replica = replica,
    link = { metered },
    consent = consent,
    dispatchers = dispatchers(),
    // Robolectric non decodifica da un File (ImageDecoder.createSource(File)): sul telefono si, da provare sul campo (F10).
    bytesOf = { ByteArrayImageBytes(it.readBytes()) },
  )

  private fun TestScope.viewer(origin: ImageOrigin) =
    PipelineViewerImageSource(origin, previews, budget, dispatchers(), BitmapPreparer { })

  private fun contentKeyOf(id: Long) = ContentKey("%064x".format(id))

  private suspend fun add(id: Long, bytes: ByteArray = original, mime: String? = "image/jpeg", withHq: Boolean = false): MediaRef.Nas {
    val path = SharePath.of("Foto", "p$id.jpg")
    nas.putFile("test", path.value, NasContent.of(bytes))
    val stat = (nas.reader.stat("test", path, Purpose.INTERACTIVE) as NasResult.Ok).value!!
    locations[id] = NasMediaLocation("test", path, bytes.size.toLong(), stat.stamp, width = 4000, height = 3000)
    if (withHq) replica.putBlob(contentKeyOf(id), SegmentClass.HQ, hq)
    return MediaRef.Nas(locationId = id, contentKey = contentKeyOf(id).short, mime = mime)
  }

  private fun leftovers() = java.io.File(folder.root, "viewer-nas").listFiles()!!.filter { it.name.endsWith(".part") }

  // --- La sequenza ---------------------------------------------------------------------------------------------------

  @Test
  fun conLHqDellaReplicaLaNitidaArrivaSubitoSenzaRete_EV1_EL4() = runTest {
    val ref = add(1, withHq = true)
    reader.openError = NasError.Timeout
    val viewer = viewer(origin())

    val states = viewer.state(ref, screen).toList()

    assertThat(states.map { it::class.java.simpleName }).containsExactly("Preview", "Loading", "Sharp").inOrder()
    val sharp = states.last() as ViewerImageState.Sharp
    // L'HQ (48x24, 2:1): stessa proporzione dell'anteprima, quindi la sostituzione non sposta niente.
    assertThat(sharp.bitmap.width.toFloat() / sharp.bitmap.height).isWithin(0.01f).of(2f)
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun senzaHqLOriginaleArrivaConLAvanzamento() = runTest {
    val ref = add(2, bytes = TestImages.jpeg(TestImages.quadrants(256, 128), quality = 100))
    reader.chunkDelayMs = 40
    reader.readAllChunk = 4 * 1024
    val viewer = viewer(origin())

    val states = viewer.state(ref, screen).toList()

    val kinds = states.map { it::class.java.simpleName }
    assertThat(kinds.first()).isEqualTo("Preview")
    assertThat(kinds.last()).isEqualTo("Sharp")
    assertThat(kinds.drop(1).dropLast(1).all { it == "Loading" }).isTrue()
    val progress = states.filterIsInstance<ViewerImageState.Loading>().mapNotNull { it.progress }
    assertThat(progress).isNotEmpty()
    assertThat(progress).isInOrder()
    assertThat(progress.last()).isAtMost(1f)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun unaSorgenteLentaMostraSubitoLAnteprimaELoStatoDiCaricamento_EV1() = runTest {
    val ref = add(3)
    reader.openDelayMs = 3_000
    val viewer = viewer(origin())
    val seen = CopyOnWriteArrayList<ViewerImageState>()

    val job = launch { viewer.state(ref, screen).collect { seen += it } }
    runCurrent()
    assertThat(seen.map { it::class.java.simpleName }).containsExactly("Preview", "Loading").inOrder()
    advanceTimeBy(2_999)
    assertThat(seen).hasSize(2)
    advanceUntilIdle()
    job.join()

    assertThat(seen.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
  }

  // --- Irraggiungibile, dati mobili, sparito -------------------------------------------------------------------------

  @Test
  fun ilNasIrraggiungibileDaUnavailableConLaDimensione_EL4() = runTest {
    val ref = add(4)
    reader.openError = NasError.Timeout
    val viewer = viewer(origin())

    val states = viewer.state(ref, screen).toList()

    assertThat(states.map { it::class.java.simpleName }).containsExactly("Preview", "Loading", "Unavailable").inOrder()
    val unavailable = states.last() as ViewerImageState.Unavailable
    assertThat(unavailable.reason).isEqualTo(UnavailableReason.NAS_UNREACHABLE)
    assertThat(unavailable.sizeBytes).isEqualTo(original.size.toLong())
    assertThat(viewer.stats.value.unavailable).isEqualTo(1)
  }

  @Test
  fun idatiMobiliChiedonoLaConfermaEPoiLOriginaleArriva_EV2() = runTest {
    val ref = add(5)
    metered = true
    val viewer = viewer(origin())

    val blocked = viewer.state(ref, screen).toList().last() as ViewerImageState.Unavailable
    assertThat(blocked.reason).isEqualTo(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION)
    assertThat(blocked.sizeBytes).isEqualTo(original.size.toLong())
    assertThat(reader.opens.get()).isEqualTo(0)

    consent.confirm(ref)
    val states = viewer.state(ref, screen).toList()

    assertThat(states.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
    assertThat(reader.opens.get()).isEqualTo(1)
  }

  @Test
  fun unMediaSparitoESpaccatoComeNonTrovato_EV6() = runTest {
    val ref = add(6)
    locations[6] = null
    val viewer = viewer(origin())

    val last = viewer.state(ref, screen).toList().last()

    assertThat((last as ViewerImageState.Failed).code).isEqualTo(ImageFailure.NOT_FOUND)
  }

  @Test
  fun ilDiscoPienoEUnaFraseASeENonUnaRaggiungibilita() = runTest {
    val ref = add(7)
    store = NasOriginalStore(folder.newFolder("pieno"), maxBytes = 64L shl 20) { 100 }
    val viewer = viewer(origin())

    val last = viewer.state(ref, screen).toList().last()

    assertThat((last as ViewerImageState.Failed).code).isEqualTo(ImageFailure.STORAGE_FULL)
  }

  @Test
  fun unaSorgenteCheLanciaNonFaCadereIlViewer() = runTest {
    val broken = object : ImageOrigin {
      override suspend fun open(ref: MediaRef): OriginResult = error("catalogo non risponde")
    }
    val viewer = viewer(broken)

    val last = viewer.state(MediaRef.Nas(1, 1, "image/jpeg"), screen).toList().last()

    assertThat((last as ViewerImageState.Failed).code).isEqualTo(ImageFailure.DECODE_FAILED)
  }

  @Test
  fun unVideoDelNasNonApreMaiLOriginaleNemmenoConIDatiMobili() = runTest {
    val ref = add(8, mime = "video/mp4")
    metered = true
    val viewer = viewer(origin())

    val states = viewer.state(ref, screen).toList()

    assertThat(states.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  // --- E-V5: un fling su molti elementi lenti ------------------------------------------------------------------------

  @Test
  fun unFlingSuMoltiElementiLentiTienePiuDiTreLettureInVoloELeAltreLeAnnullaSenzaTemporanei_EV5() = runTest {
    val refs = (10L..19L).map { add(it) }
    reader.chunkDelayMs = 1_000
    reader.stallAfterBytes = 0
    val viewer = viewer(origin())
    val jobs = refs.map { ref -> launch { viewer.state(ref, screen).collect { } } }

    advanceTimeBy(5_000)
    runCurrent()
    // Solo tre pagine leggono davvero dal NAS: le altre aspettano uno slot.
    assertThat(viewer.stats.value.maxInFlight).isAtMost(3)
    assertThat(reader.openFiles.get()).isAtMost(3)

    // Il pager le lascia tutte indietro: le letture si annullano e non resta niente aperto ne' a meta'.
    jobs.forEach { it.cancel() }
    jobs.forEach { it.join() }

    assertThat(viewer.stats.value.cancelled).isEqualTo(10)
    assertThat(viewer.stats.value.inFlight).isEqualTo(0)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(store.count).isEqualTo(0)
  }

  // --- Lo zoom: prima con le misure del catalogo, poi con quelle dell'originale ---------------------------------------

  @Test
  fun lZoomUsaLeMisureDelCatalogoFinoAChelOriginaleNonESulTelefono() = runTest {
    val ref = add(20, withHq = true)
    val origin = origin()
    val viewer = viewer(origin)
    assertThat(viewer.zoomSpec(ref)).isNull()

    viewer.state(ref, screen).toList()

    // La nitida viene dall'HQ (48x24): le misure per lo zoom sono quelle del catalogo, senza tasselli.
    val provisional = viewer.zoomSpec(ref)!!
    assertThat(provisional.width).isEqualTo(4000)
    assertThat(provisional.height).isEqualTo(3000)
    assertThat(provisional.tiled).isFalse()

    // Lo zoom chiede l'originale: da ora le misure sono quelle vere del file (64x32).
    origin.fetch(ref).toList()
    val real = viewer.zoomSpec(ref)!!
    assertThat(real.width).isEqualTo(64)
    assertThat(real.height).isEqualTo(32)

    // E la nitida successiva viene dall'originale.
    val again = viewer.state(ref, screen).toList().last() as ViewerImageState.Sharp
    assertThat(again.bitmap.width).isEqualTo(64)
  }
}
