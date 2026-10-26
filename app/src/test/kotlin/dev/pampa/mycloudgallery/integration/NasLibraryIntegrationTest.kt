package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginResult
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagnostics
import dev.pampa.mycloudgallery.core.mediastore.meta.DefaultMetadataPass
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.sync.DeferredFullHashes
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.PhoneInputs
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
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
 * Il NAS nella Libreria con il grafo vero (P11, riga del §4.4: "scansione → Libreria → preparazione → secondo dispositivo
 * che sincronizza → viewer"), sopra il NAS finto di core-nas (`InMemorySmbBackend`, utenti `test` e `test2`, aree di prova)
 * e la famiglia vera della Fase 2:
 *
 * 1. il telefono (`test`, admin) fonda la famiglia, il tablet (`test2`) entra e l'admin lo approva;
 * 2. sul NAS arrivano delle foto nell'area comune (`test-comune`);
 * 3. il telefono scansiona: i media compaiono nella **Libreria** con la data e il badge "solo NAS", e un riferimento al NAS;
 * 4. il telefono **prepara** (miniature S e M, analisi, HQ) e **pubblica** l'indice: `test-comune` e' una share di prova,
 *    quindi D31 lo consente;
 * 5. il tablet sincronizza l'indice e scansiona: vede **le miniature senza averle preparate** (nessun giro di preparazione,
 *    outbox vuota);
 * 6. il **viewer** del tablet apre la foto del NAS.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class NasLibraryIntegrationTest {

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

  /** La galleria di un dispositivo come in produzione, con il NAS (`AppGraph.nasInputs`) e il MediaStore finto vuoto. */
  private fun galleryOf(device: TestDevice, account: String): GalleryGraph {
    val store = FakeMediaStore().apply { addVolume(PRIMARY) }
    val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))
    fun phone(inputs: PhoneInputs): PhoneLibrary {
      val reads = RoomPhoneCatalogReads(inputs.database)
      val stats = PhoneLibraryStats()
      val meta = DefaultMetadataPass(store, store, access, inputs.catalog, reads, inputs.extractor, clock = inputs.clock, stats = stats)
      val sync = DefaultPhoneLibrarySync(store, access, inputs.catalog, reads, clock = inputs.clock, stats = stats, metadataPass = meta, priority = inputs.priority)
      return PhoneLibrary(
        sync = sync,
        access = access,
        metadataPass = meta,
        runner = DefaultPhoneSyncRunner(sync, meta, inputs.priority),
        stats = stats,
        diagnostics = PhoneLibraryDiagnostics(sync, access, stats) { inputs.catalog.mediaStoreCursors() },
        deferredHashes = DeferredFullHashes(store, access, inputs.catalog, inputs.database),
      )
    }
    return GalleryGraph(
      context = device.context,
      scope = device.scope,
      parts = GalleryParts(
        databaseName = null,
        replicaDir = File(device.context.noBackupFilesDir, "replica"),
        preferencesFile = File(device.context.noBackupFilesDir, "library.preferences_pb"),
        access = access,
        intents = FakePhoneMediaIntents(),
        recentlyDeleted = FakeRecentlyDeletedSource(),
        phone = ::phone,
        snapshotDelayMs = 100L,
        nas = device.graph.nasInputs,
        monotonicMs = { System.nanoTime() / 1_000_000 },
      ),
      setup = device.graph.setup,
      ownerAccount = { AccountId.of(account) },
      clock = { time.now() },
    ).also { galleries += it }
  }

  /** Una foto vera (JPEG) da [width] x [height], tinta unita di colore [tone]. */
  private fun jpeg(tone: Int, width: Int = 640, height: Int = 480): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(Color.rgb(tone, 255 - tone, (tone * 7) % 255))
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  @Test
  fun scansioneLibreriaPreparazioneSecondoDispositivoEViewer() = runBlocking {
    // 1. La famiglia: il telefono fonda (test), il tablet entra (test2) e viene approvato.
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    val invite = phone.graph.inviteMember()
    tablet.graph.start()
    val awaiting = tablet.graph.joinWith(invite)
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    val request = phone.graph.registry.pendingRequests.value.single()
    phone.graph.registry.approve(request.id, ApprovalProof.TypedCode(awaiting.code))
    // Il tablet, con la pagina d'attesa aperta, se ne accorge da solo (come nell'app).
    tablet.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tablet.graph.awaitSetup { it is SetupState.Ready }
    phone.graph.registry.refresh(Purpose.INTERACTIVE)

    // 2. Le foto nell'area comune, scritte dal PC due giorni fa (stabili per l'ora del NAS, E-N4).
    val photos = (1..4).associate { n -> "Foto/2024/IMG_000$n.jpg" to jpeg(tone = n * 40) }
    val twoDaysAgo = time.now() - 2 * DAY
    photos.forEach { (path, bytes) -> backend.put(TestNas.COMMON, path, bytes, lastWriteMs = twoDaysAgo) }

    // 3. Il telefono: galleria e NAS partono, la scansione porta i media nella Libreria (data, "solo NAS", riferimento).
    val phoneGallery = galleryOf(phone, TestNas.ADMIN_USER)
    phoneGallery.startWhenReady()
    withTimeout(TIMEOUT) { phoneGallery.started.first { it } }
    val phoneNas = checkNotNull(phoneGallery.nas)
    assertThat(phoneNas.start()).isTrue()
    val reports = checkNotNull(phoneNas.scan(ScanMode.Full, 5.minutes))
    assertThat(reports.first { it.files > 0 }.detected).isEqualTo(photos.size)
    assertThat(reports.all { it.complete }).isTrue()
    val view = withTimeout(TIMEOUT) { phoneGallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }
    val snapshot = view.snapshot as ColumnarTimelineSnapshot
    for (i in 0 until snapshot.size) {
      assertWithMessage("badge").that(snapshot.presenceAt(i)).isEqualTo(PresenceBadge.NAS_ONLY)
      assertWithMessage("riferimento al NAS").that(snapshot.thumbRefAt(i)).isInstanceOf(MediaRef.Nas::class.java)
      assertWithMessage("giorno").that(snapshot.localDayAt(i)).isGreaterThan(0)
    }
    // Il piede della Libreria: share di prova, quindi niente "solo locale" (D31 consente di pubblicare).
    assertThat(phoneNas.libraryStatus.value).isNotInstanceOf(LibraryNasStatus.Idle::class.java)
    assertThat(phoneNas.localOnly()).isFalse()

    // 4. Il telefono prepara e pubblica l'indice nell'area comune.
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val prepared = checkNotNull(phoneNas.prepare(5.minutes, PrepControl.ALWAYS))
    assertThat(prepared.prepared).isEqualTo(photos.size)
    phoneNas.flushOutbox()
    assertThat(backend.children(TestNas.COMMON, ".mycloudgallery/devices")).isNotEmpty()
    val refs = (0 until snapshot.size).map { snapshot.thumbRefAt(it) as MediaRef.Nas }
    refs.forEach { ref -> assertThat(phoneNas.blobs.locate(ref.contentKey, SegmentClass.THUMB_S)).isNotNull() }
    // Nessun media sul NAS e' stato toccato (D1): stessi byte di prima.
    photos.forEach { (path, bytes) -> assertThat(backend.bytes(TestNas.COMMON, path)).isEqualTo(bytes) }

    // 5. Il tablet: sincronizza l'indice e scansiona, vede le miniature senza preparare niente.
    val tabletGallery = galleryOf(tablet, TestNas.MEMBER_USER)
    tabletGallery.startWhenReady()
    withTimeout(TIMEOUT) { tabletGallery.started.first { it } }
    val tabletNas = checkNotNull(tabletGallery.nas)
    assertThat(tabletNas.start()).isTrue()
    val sync = checkNotNull(tabletNas.syncIndex(5.minutes))
    assertThat(sync.segmentsDownloaded).isGreaterThan(0)
    checkNotNull(tabletNas.scan(ScanMode.Full, 5.minutes))
    val tabletView = withTimeout(TIMEOUT) { tabletGallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }
    val tabletSnapshot = tabletView.snapshot as ColumnarTimelineSnapshot
    val tabletRefs = (0 until tabletSnapshot.size).map { tabletSnapshot.thumbRefAt(it) as MediaRef.Nas }
    assertThat(tabletRefs.map { it.contentKey }.toSet()).isEqualTo(refs.map { it.contentKey }.toSet())
    for (ref in tabletRefs) {
      assertThat(tabletNas.blobs.locate(ref.contentKey, SegmentClass.THUMB_S)).isNotNull()
      assertThat(tabletNas.thumbnails.load(ref, 200, CancellationSignal())).isNotNull()
    }
    assertThat(tabletNas.lastPrep.value).isNull()
    assertThat(tabletNas.runtime!!.publisher.pending.value.isEmpty).isTrue()

    // 6. Il viewer del tablet apre la foto del NAS (l'originale a intervalli: "Standard" non tiene l'HQ).
    val opened = tabletNas.imageOrigin.open(tabletRefs.first())
    assertThat(opened).isInstanceOf(OriginResult.Opened::class.java)

    // 7. Lo spegnimento (azzeramento, §1.4): il NAS si ferma con la galleria e non riparte da solo.
    tabletGallery.shutdown()
    assertThat(tabletNas.runtime).isNull()
    assertThat(tabletNas.blobs.locate(tabletRefs.first().contentKey, SegmentClass.THUMB_S)).isNull()
    assertThat(tabletNas.scan(ScanMode.Full, 5.minutes)).isNull()
    assertThat(tabletNas.start()).isFalse()
  }

  private companion object {
    const val PRIMARY = "external_primary"
    const val DAY = 24L * 60 * 60 * 1000
    val TIMEOUT = Attese.TETTO
  }
}
