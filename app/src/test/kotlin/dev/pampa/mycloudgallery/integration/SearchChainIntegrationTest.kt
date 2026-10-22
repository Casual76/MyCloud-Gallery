package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiWiring
import dev.pampa.mycloudgallery.core.ai.compute.AiInputs
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.ReleasableTextReader
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La riga P11 del §4.4 di docs/fase6-progetto.md, la catena intera della Fase 6 sul NAS finto, sul telefono finto e con i motori finti:
 * **foto caricata → preparata → anteprima nel buffer → vettore e testo → pubblicati in una share di prova → un secondo dispositivo li
 * riceve → la ricerca la trova su entrambi**. Il secondo dispositivo e' un **tablet non indicizzatore** (E-Q8: stessi risultati) con la
 * **lettura del testo spenta**: cerca e legge il testo letto dal telefono senza mai chiamare il suo lettore (D42; il lettore finto fallisce
 * se chiamato, e quello vero non e' nemmeno costruito: `GatedTextReader` non crea il motore da spento).
 *
 * Solo i motori sono finti (`FakeImageEmbedder`, `FakeTextEmbedder`, `FakeTextReader`) e i loro ingressi (nessun `ImageDecoder`): grafo
 * dell'app, catalogo, preparazione, buffer, calcolo, pubblicatore e replica `ai`, adattatori Room, indice locale e motore di ricerca sono
 * quelli veri.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SearchChainIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()
  private val galleries = mutableListOf<GalleryGraph>()

  private fun device(name: String): TestDevice = TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun tearDown() {
    galleries.forEach { runBlocking { it.shutdown() } }
    devices.forEach { it.close() }
  }

  /** Gli ingressi finti dei motori: l'anteprima com'e' (il motore finto ne fa un concetto per impronta), il testo sempre lo "scontrino". */
  private object TestInputs : AiInputs {
    override fun image(analysis: ByteArray, recipe: AiRecipe): ModelImage = AiTestInputs.imageOf(analysis)

    override fun frames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage> = frames.map { AiTestInputs.imageOf(it.jpeg) }

    override fun ocr(bytes: ByteArray, source: OcrSource): OcrImage = AiTestInputs.ocr(RECEIPT, source)
  }

  private class Device(val test: TestDevice, val gallery: GalleryGraph, val stack: AiStack, val reader: FakeTextReader)

  /** Un dispositivo con la galleria (`NasGraphs` di produzione) e la Fase 6 montata come in `AiModule`, con i motori finti. */
  private fun mount(test: TestDevice, account: String): Device {
    val gallery = NasGalleries.galleryOf(test, account, time, ai = { test.graph.aiHooks }).also { galleries += it }
    val reader = FakeTextReader()
    val stack = AiWiring.build(
      context = test.context,
      graph = test.graph,
      gallery = gallery,
      imageEmbedder = { FakeImageEmbedder() },
      queryEmbedder = { FakeTextEmbedder() },
      reader = { state -> GatedTextReader(state) { Releasable(reader) } },
      fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      inputs = TestInputs,
      modelReady = { true },
    )
    reader.readingHere = { stack.textSwitch.state.value.activeHere }
    reader.fixture(RECEIPT, OcrResult(listOf(OcrLine(ShortBox(1000, 2000, 60000, 9000), 0, listOf(WordSpan(0, 30000), WordSpan(32000, 65535)), "Pizzeria Mario")), dense = false))
    test.graph.aiHooks = stack.ai.hooks
    return Device(test, gallery, stack, reader)
  }

  private class Releasable(private val inner: FakeTextReader) : ReleasableTextReader {
    override suspend fun read(image: OcrImage): OcrResult = inner.read(image)

    override fun release() = Unit
  }

  private fun jpeg(tone: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(Color.rgb(tone, 255 - tone, (tone * 7) % 255))
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  @Test
  fun fotoPreparataAnalizzataPubblicataRicevutaETrovataSuEntrambi() = runBlocking {
    // La famiglia: il telefono (test, Indicizzatore) fonda, il tablet (test2) entra e viene approvato.
    val phoneDevice = device("telefono")
    phoneDevice.graph.start()
    phoneDevice.graph.foundFamily()
    val tabletDevice = device("tablet")
    val invite = phoneDevice.graph.inviteMember()
    tabletDevice.graph.start()
    val awaiting = tabletDevice.graph.joinWith(invite)
    phoneDevice.graph.registry.refresh(Purpose.INTERACTIVE)
    val request = phoneDevice.graph.registry.pendingRequests.value.single()
    phoneDevice.graph.registry.approve(request.id, ApprovalProof.TypedCode(awaiting.code))
    tabletDevice.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tabletDevice.graph.awaitSetup { it is SetupState.Ready }
    phoneDevice.graph.registry.refresh(Purpose.INTERACTIVE)

    // Le foto nell'area comune (share di prova: F4-D31 consente di pubblicare).
    val photos = (1..3).associate { n -> "Foto/2024/IMG_000$n.jpg" to jpeg(tone = n * 50) }
    photos.forEach { (path, bytes) -> backend.put(TestNas.COMMON, path, bytes, lastWriteMs = time.now() - 2 * DAY) }

    // Il telefono: galleria, NAS, ruolo Indicizzatore, lettura del testo attiva (con il consenso della pagina).
    val phone = mount(phoneDevice, TestNas.ADMIN_USER)
    phone.gallery.startWhenReady()
    withTimeout(TIMEOUT) { phone.gallery.started.first { it } }
    val phoneNas = checkNotNull(phone.gallery.nas)
    assertThat(phoneNas.start()).isTrue()
    assertThat(phone.stack.ai.indexer.value).isTrue()
    phone.stack.personal.search.await(TIMEOUT) { it != null }
    phone.stack.textSwitch.enableForAccount(TextReadingConsent(1, 0L))
    phone.stack.textSwitch.state.await(TIMEOUT) { it.activeHere }

    // Scansione e preparazione: le anteprime di analisi vanno anche nel buffer dell'indicizzatore (D10).
    checkNotNull(phoneNas.scan(ScanMode.Full, 5.minutes))
    phoneDevice.graph.trustGuard.preflight(Purpose.BACKGROUND)
    assertThat(checkNotNull(phoneNas.prepare(5.minutes, PrepControl.ALWAYS)).prepared).isEqualTo(photos.size)
    phoneNas.flushOutbox()
    assertWithMessage("anteprime nel buffer").that(phone.stack.ai.buffer.keys()).hasSize(photos.size)

    // Il calcolo: vettore e testo, salvati e pubblicati nella testa `ai` dell'area comune; il buffer si svuota.
    val results = checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    assertThat(results.sumOf { it.second.computed }).isEqualTo(photos.size)
    assertThat(phone.reader.reads).isNotEmpty()
    assertThat(phone.stack.ai.buffer.keys()).isEmpty()
    assertThat(phone.stack.search.index.sizes).isEqualTo(photos.size to photos.size)
    assertThat(phone.stack.ai.publishStore.pending().waitingForShare).isEqualTo(0)

    // La ricerca sul telefono: il testo letto trova le tre foto (sicuri), "Trova simili" risponde dai vettori.
    assertThat(phone.stack.search.open()).isTrue()
    val phoneResults = phone.stack.search.engine.search(SearchRequest("pizzeria"))
    assertThat(phoneResults.confident.size).isEqualTo(photos.size)

    // Il tablet: non indicizzatore, lettura spenta. Sincronizza l'indice (teste `index` e poi `ai`) e trova le stesse foto.
    val tablet = mount(tabletDevice, TestNas.MEMBER_USER)
    tablet.gallery.startWhenReady()
    withTimeout(TIMEOUT) { tablet.gallery.started.first { it } }
    val tabletNas = checkNotNull(tablet.gallery.nas)
    assertThat(tabletNas.start()).isTrue()
    assertThat(tablet.stack.roles.setRoles(indexer = false, preparer = false)).isInstanceOf(RolesChange.Saved::class.java)
    assertThat(tablet.stack.ai.indexer.value).isFalse()
    assertThat(tablet.stack.textSwitch.state.value.activeHere).isFalse()
    checkNotNull(tabletNas.syncIndex(5.minutes))
    checkNotNull(tabletNas.scan(ScanMode.Full, 5.minutes))
    assertThat(tablet.stack.search.index.sizes).isEqualTo(photos.size to photos.size)
    assertThat(tablet.stack.search.open()).isTrue()
    withTimeout(TIMEOUT) { tablet.gallery.repository.snapshot.first { (it?.size ?: 0) == photos.size } }
    val tabletResults = tablet.stack.search.engine.search(SearchRequest("pizzeria"))
    assertWithMessage("E-Q8: il tablet trova cio' che trova il telefono").that(tabletResults.confident.size).isEqualTo(phoneResults.confident.size)

    // D42: il testo dal vivo vale anche sul tablet, dalla replica, senza lettura e senza ML Kit.
    val first = tabletResults.confident.snapshot.idAt(tabletResults.confident.snapshotIndex(0))
    val layout = checkNotNull(tablet.stack.search.mediaText.layout(first))
    assertThat(layout.lines.single().words).hasSize(2)
    assertWithMessage("il lettore del tablet non e' mai stato chiamato").that(tablet.reader.reads).isEmpty()
    assertThat(tablet.stack.ai.computeCounters()).isNull()

    // "Trova simili" sul tablet: i vettori ricevuti bastano.
    val similar = tablet.stack.search.engine.similar(first)
    assertThat(similar.chips).isNotEmpty()
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
    const val RECEIPT = "scontrino"
    val TIMEOUT = 2.minutes
  }
}
