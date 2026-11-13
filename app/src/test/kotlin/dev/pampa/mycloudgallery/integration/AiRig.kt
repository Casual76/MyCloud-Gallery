package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiWiring
import dev.pampa.mycloudgallery.core.ai.compute.AiInputs
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.ReleasableTextReader
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeSubjectCutter
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.debugtools.DebugBackupFence
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * L'attrezzatura dei test d'integrazione della Fase 6 (P11b): la famiglia di prova sul NAS finto, il telefono (galleria vera sopra il MediaStore
 * finto, con i byte delle foto), `AiWiring.build` con i motori finti (immagini, query, lettore del testo, ritagliatore) e il "Salva come nuova
 * foto" su uno scrittore finto. Come `SearchChainIntegrationTest`, ma con il telefono: stesso grafo, stessa galleria, stessi adattatori di produzione.
 */
internal class AiRig(private val app: Application, private val root: File) {
  var imageOverride: dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder? = null

  /** XS-7: il verdetto delle regole e gli eventi che lo cambiano, scelti dalla prova (di serie quelli del motore vero). */
  var decideOverride: (suspend (dev.pampa.mycloudgallery.core.rules.WorkRequest) -> dev.pampa.mycloudgallery.core.rules.RuleDecision?)? = null
  var triggersOverride: kotlinx.coroutines.flow.Flow<Unit>? = null
  val time = TestTime()
  val backend = TestNas.backend(time)

  /** Un ritagliatore al posto di quello finto (R20: uno che esaurisce la memoria); si imposta prima di montare il dispositivo. */
  var cutterOverride: dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter? = null

  /** L'anello della Diagnostica di tutti i dispositivi: i test guardano quali codici ci sono finiti. */
  val ring = RingDiagnosticsLog()
  private val devices = mutableListOf<TestDevice>()
  private val galleries = mutableListOf<GalleryGraph>()

  /** Un dispositivo montato: grafo, galleria, la Fase 6 e i motori finti (con il MediaStore e lo scrittore dei ritagli). */
  class Mounted(
    val test: TestDevice,
    val gallery: GalleryGraph,
    val stack: AiStack,
    val reader: FakeTextReader,
    val store: FakeMediaStore,
    /** Gli scrittori di `Ritagli/` creati dall'app (uno per cartella: normale e recintata). */
    val cutouts: List<FakePhoneMediaWriter>,
    val cutter: FakeSubjectCutter,
  )

  fun device(name: String): TestDevice = TestDevice(app, File(root, name), TestPlatform(time, backend), diagnostics = ring).also { devices += it }

  /** Gli ingressi finti dei motori: l'anteprima com'e', il testo sempre lo "scontrino" (il lettore finto lo conosce). */
  object Inputs : AiInputs {
    override fun image(analysis: ByteArray, recipe: AiRecipe): ModelImage = AiTestInputs.imageOf(analysis)

    override fun frames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage> = frames.map { AiTestInputs.imageOf(it.jpeg) }

    override fun ocr(bytes: ByteArray, source: OcrSource): OcrImage = AiTestInputs.ocr(RECEIPT, source)
  }

  private class Releasable(private val inner: FakeTextReader) : ReleasableTextReader {
    override suspend fun read(image: OcrImage): OcrResult = inner.read(image)

    override fun release() = Unit
  }

  /** Il dispositivo con la galleria (`NasGalleries`) e la Fase 6 montata come in `AiModule`, con i motori finti. */
  fun mount(
    test: TestDevice,
    account: String,
    store: FakeMediaStore = NasGalleries.newStore(),
    fence: BackupFence? = DebugBackupFence,
    readerLatencyMs: Long = 0,
    /** Fase 8 (P13): la ricerca per persona (il ponte di `FacesModule`); di serie nessuna. */
    people: dev.pampa.mycloudgallery.core.search.contract.PeopleLookup = dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup,
    /** Lo stato della famiglia che la galleria vede (di serie quello del dispositivo); le prove di un processo appena nato lo avvolgono in un `ColdStartSetup`. */
    setup: dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository = test.graph.setup,
    accessOverride: dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader? = null,
  ): Mounted {
    val gallery = NasGalleries.galleryOf(test, account, time, ai = { test.graph.aiHooks }, store = store, setup = setup, accessOverride = accessOverride).also { galleries += it }
    val reader = FakeTextReader(latencyMs = readerLatencyMs)
    val writers = mutableListOf<FakePhoneMediaWriter>()
    val cutter = FakeSubjectCutter()
    val stack = AiWiring.build(
      context = test.context,
      graph = test.graph,
      gallery = gallery,
      imageEmbedder = { imageOverride ?: FakeImageEmbedder() },
      queryEmbedder = { FakeTextEmbedder() },
      reader = { state -> GatedTextReader(state) { Releasable(reader) } },
      fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      inputs = Inputs,
      modelReady = { true },
      fence = fence,
      cutter = { cutterOverride ?: cutter },
      cutoutWriter = { path -> FakePhoneMediaWriter(path).also { writers += it } },
      people = people,
      decide = decideOverride,
      decisionTriggers = triggersOverride,
    )
    reader.readingHere = { stack.textSwitch.state.value.activeHere }
    reader.fixture(RECEIPT, OcrResult(listOf(OcrLine(ShortBox(1000, 2000, 60000, 9000), 0, listOf(WordSpan(0, 30000), WordSpan(32000, 65535)), "Pizzeria Mario")), dense = false))
    test.graph.aiHooks = stack.ai.hooks
    return Mounted(test, gallery, stack, reader, store, writers, cutter)
  }

  /** Il fondatore (`test`, Indicizzatore) con la famiglia pronta, ancora senza galleria. */
  suspend fun foundedDevice(): TestDevice {
    val test = device("telefono")
    test.graph.start()
    test.graph.foundFamily()
    return test
  }

  /** Il fondatore con la galleria montata e il NAS della Libreria avviato. */
  suspend fun founder(store: FakeMediaStore = NasGalleries.newStore(), fence: BackupFence? = DebugBackupFence, readerLatencyMs: Long = 0): Mounted =
    startMounted(mount(foundedDevice(), TestNas.ADMIN_USER, store, fence, readerLatencyMs))

  /** La galleria avviata, il NAS agganciato e la lettura del telefono fatta una volta. */
  suspend fun startMounted(device: Mounted): Mounted {
    device.gallery.startWhenReady()
    withTimeout(TIMEOUT) { device.gallery.started.first { it } }
    assertThat(checkNotNull(device.gallery.nas).start()).isTrue()
    device.gallery.core().phone.sync.syncNow(SyncReason.APP_START)
    return device
  }

  /** Il consenso della pagina "Lettura del testo": la lettura e' attiva su questo dispositivo. */
  suspend fun enableReading(device: Mounted) {
    device.stack.personal.search.await(TIMEOUT) { it != null }
    device.stack.textSwitch.enableForAccount(TextReadingConsent(1, 0L))
    device.stack.textSwitch.state.await(TIMEOUT) { it.activeHere }
  }

  /** Il tablet (`test2`, non indicizzatore, lettura spenta) entra nella famiglia del [founder] e viene approvato. */
  suspend fun joinTablet(founder: TestDevice): TestDevice {
    val tablet = device("tablet")
    val invite = founder.graph.inviteMember()
    tablet.graph.start()
    val awaiting = tablet.graph.joinWith(invite)
    founder.graph.registry.refresh(Purpose.INTERACTIVE)
    val request = founder.graph.registry.pendingRequests.value.single()
    founder.graph.registry.approve(request.id, ApprovalProof.TypedCode(awaiting.code))
    tablet.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tablet.graph.awaitSetup { it is SetupState.Ready }
    founder.graph.registry.refresh(Purpose.INTERACTIVE)
    return tablet
  }

  fun close() {
    galleries.forEach { runBlocking { it.shutdown() } }
    devices.forEach { it.close() }
  }

  companion object {
    const val RECEIPT = "scontrino"
    const val DAY = 24L * 60 * 60 * 1000
    val TIMEOUT = Attese.TETTO

    /** Una foto di prova (JPEG 640x480): tinta unita, diversa per ogni [tone]. */
    fun jpeg(tone: Int): ByteArray {
      val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).drawColor(Color.rgb(tone, 255 - tone, (tone * 7) % 255))
      return compress(bitmap)
    }

    /** Un soggetto: un rettangolo rosso (un quarto della foto) su sfondo bianco, per il ritaglio. */
    fun jpegWithSubject(): ByteArray {
      val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      canvas.drawRect(160f, 120f, 480f, 360f, Paint().apply { color = Color.rgb(200, 30, 30) })
      return compress(bitmap)
    }

    private fun compress(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
      out.toByteArray()
    }

    /** Una riga del MediaStore per una foto nella cartella [path], con i suoi byte. */
    fun photoRow(id: Long, path: String, name: String, bytes: ByteArray, time: TestTime): MediaStoreRow = MediaStoreRow(
      volume = "external_primary",
      id = id,
      type = RowType.IMAGE,
      displayName = name,
      relativePath = path,
      mime = "image/jpeg",
      size = bytes.size.toLong(),
      dateModifiedMs = time.now() - DAY,
      dateTakenMs = time.now() - DAY,
    )
  }
}
