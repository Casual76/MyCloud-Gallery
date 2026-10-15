package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
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
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.PhoneInputs
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasOpsDiagCodes
import dev.pampa.mycloudgallery.nas.NasOpsDiagnostics
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.work.NasOpsJobs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Organizzare il NAS con il grafo vero (P16, riga del §4.4: "sposta, elimina, ripristina, pulizia con ora finta", e i dati personali
 * fra due dispositivi dello stesso account), sopra il NAS finto di core-nas (`InMemorySmbBackend`, aree di prova `test` e
 * `test-comune`) e la famiglia vera della Fase 2. Le conferme nascono come nella UI: dal foglio (`ConfirmationSheet`) con un tocco.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class NasOrganizeIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()
  private val galleries = mutableListOf<GalleryGraph>()

  /** Il lavoro delle operazioni registrato (JobScheduler non c'e'): i test guardano che un'operazione lunga lo chieda. */
  private class RecordingJobs : NasOpsJobs {
    val userInitiated = AtomicInteger()
    val resumes = AtomicInteger()
    val cancelled = AtomicInteger()

    override fun userInitiated() {
      userInitiated.incrementAndGet()
    }

    override fun resumeLater(delayMs: Long) {
      resumes.incrementAndGet()
    }

    override fun cancelAll() {
      cancelled.incrementAndGet()
    }
  }

  private fun device(name: String): TestDevice = TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun tearDown() {
    galleries.forEach { runBlocking { it.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun galleryOf(device: TestDevice, account: String, jobs: NasOpsJobs = NasOpsJobs.NONE): GalleryGraph {
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
        nasJobs = jobs,
      ),
      setup = device.graph.setup,
      ownerAccount = { AccountId.of(account) },
      clock = { time.now() },
    ).also { galleries += it }
  }

  private fun jpeg(tone: Int, width: Int = 320, height: Int = 240): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(Color.rgb(tone, 255 - tone, (tone * 7) % 255))
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  /** Il NAS del dispositivo, partito, con la 4B legata. */
  private suspend fun startedNas(gallery: GalleryGraph): NasGraph {
    gallery.startWhenReady()
    withTimeout(TIMEOUT) { gallery.started.first { it } }
    val nas = checkNotNull(gallery.nas)
    assertThat(nas.start()).isTrue()
    assertWithMessage("la 4B e' legata al motore del NAS").that(nas.organizer.parts).isNotNull()
    return nas
  }

  /** Il piano di [request], confermato con un tocco sul foglio (come la UI) e avviato. */
  private suspend fun NasGraph.confirmAndStart(request: OpRequest, taps: Int = 1): OpId {
    val ops = organizer.operations
    val plan = (ops.plan(request) as? PlanOutcome.Ready)?.plan ?: error("piano rifiutato: ${ops.plan(request)}")
    val sheet = ConfirmationSheet(plan)
    var confirmation = sheet.tap(organizer.clocks.monotonicMs())
    repeat(taps - 1) {
      // R6 (revisione 4B): la conferma successiva e' un secondo gesto, non il secondo colpo di un doppio tocco.
      Thread.sleep(dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules.MIN_TAP_GAP_MS)
      confirmation = sheet.tap(organizer.clocks.monotonicMs())
    }
    val outcome = ops.start(plan, checkNotNull(confirmation))
    val id = when (outcome) {
      is StartOutcome.Started -> outcome.id
      is StartOutcome.AlreadyStarted -> outcome.id
      else -> error("operazione non partita: $outcome")
    }
    // L'operazione continua nello scope della galleria anche dopo la risposta: si aspetta che si chiuda.
    withTimeout(TIMEOUT) { ops.operations.first { list -> list.any { it.id == id && !it.active } } }
    val state = ops.operations.value.single { it.id == id }
    assertWithMessage("operazione $request").that(state).isInstanceOf(NasOperationState.Done::class.java)
    return id
  }

  @Test
  fun spostaEliminaRipristinaEPuliziaDelCestinoConOraFinta() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val photos = (1..3).associate { n -> "Foto/IMG_000$n.jpg" to jpeg(tone = n * 50) }
    val twoDaysAgo = time.now() - 2 * DAY
    photos.forEach { (path, bytes) -> backend.put(TestNas.COMMON, path, bytes, lastWriteMs = twoDaysAgo) }

    val jobs = RecordingJobs()
    val gallery = galleryOf(phone, TestNas.ADMIN_USER, jobs)
    val nas = startedNas(gallery)
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    withTimeout(TIMEOUT) { gallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }
    val organizer = nas.organizer

    // Cartelle NAS: l'elenco della cartella, dal NAS (sola lettura).
    val folder = withTimeout(TIMEOUT) {
      organizer.folders.observe(TestNas.COMMON, SharePath.of("Foto")).first { it is NasFolderState.Listing } as NasFolderState.Listing
    }
    assertThat(folder.entries.map { it.name }).containsExactly("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg")
    fun item(name: String) = folder.entries.single { it.name == name }.toItem(TestNas.COMMON)

    // 1. Sposta tra share (area comune -> area privata): copia lato server, verifica, sorgente nel cestino (D24, Q3). E' lunga: UIDT.
    organizer.run { nas.confirmAndStart(OpRequest.Move(listOf(item("IMG_0001.jpg")), TestNas.ADMIN_USER, null)) }
    assertThat(backend.bytes(TestNas.ADMIN_USER, "IMG_0001.jpg")).isEqualTo(photos.getValue("Foto/IMG_0001.jpg"))
    assertThat(backend.exists(TestNas.COMMON, "Foto/IMG_0001.jpg")).isFalse()
    assertWithMessage("uno spostamento tra share chiede il lavoro avviato dall'utente").that(jobs.userInitiated.get()).isEqualTo(1)

    // 2. Elimina (nel cestino del NAS per 30 giorni, D25).
    nas.confirmAndStart(OpRequest.Trash(listOf(item("IMG_0002.jpg"))))
    assertThat(backend.exists(TestNas.COMMON, "Foto/IMG_0002.jpg")).isFalse()
    assertWithMessage("tre file in una cartella sola non sono un'operazione lunga").that(jobs.userInitiated.get()).isEqualTo(1)

    // "Eliminati di recente del NAS": la sorgente dello spostamento e il file eliminato, con il record firmato e "elimina subito".
    organizer.trash.refresh()
    val trash = withTimeout(TIMEOUT) { organizer.trash.items.first { it is NasTrashState.Ready } as NasTrashState.Ready }
    assertThat(trash.items.map { it.reason }).containsExactly(TrashReason.MOVE, TrashReason.DELETE)
    assertThat(trash.items.all { it.deletableNow && !it.withoutValidRecord }).isTrue()

    // 3. Ripristina il file eliminato (al suo posto).
    val deleted = trash.items.single { it.reason == TrashReason.DELETE }
    val ref = TrashRestoreRef(deleted.share, deleted.ownerDeviceId, deleted.trashId, checkNotNull(deleted.originalPath), deleted.isFolder, deleted.size)
    nas.confirmAndStart(OpRequest.Restore(listOf(ref)))
    assertThat(backend.bytes(TestNas.COMMON, "Foto/IMG_0002.jpg")).isEqualTo(photos.getValue("Foto/IMG_0002.jpg"))

    // La Libreria dopo due verifiche (la copia nell'area privata e' un file nuovo: stabile se visto uguale a 2 minuti di ora del NAS,
    // E-N4): ancora tre media, nessuno perso.
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    time.advance(3 * MINUTE)
    val reports = checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    val settled = withTimeoutOrNull(30.seconds) { gallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }
    assertWithMessage("Libreria dopo lo spostamento: ${gallery.repository.view(LibraryFilter.ALL).first().size} media, verifica $reports")
      .that(settled).isNotNull()

    // La Diagnostica: solo numeri e chiavi dell'elenco chiuso, codici del catalogo.
    val diag = NasOpsDiagnostics(nas).collect()
    assertThat(diag.map { it.code }).contains(NasOpsDiagCodes.OPS_STATE)
    assertThat(diag.map { it.code }).contains(NasOpsDiagCodes.TRASH_STATE)
    assertThat(diag.flatMap { it.export.keys }.filterNot(ExportKeys::isAllowed)).isEmpty()
    assertThat(diag.map { it.code }.filterNot { it in DiagCatalog }).isEmpty()

    // Un finto cestino di un altro dispositivo, scritto "dal PC" (E-S11): la pulizia non lo tocca mai.
    val fakeOwner = TrustIds.random()
    val fake = ".mycloudgallery/trash/$fakeOwner/files/${TrustIds.random()}"
    backend.put(TestNas.COMMON, fake, ByteArray(100) { 7 }, lastWriteMs = time.now() - 60 * DAY)

    // 4. La pulizia con l'ora finta: dopo 31 giorni di ora del NAS si elimina solo la voce propria scaduta (la sorgente dello
    // spostamento), dal diario locale; il file ripristinato non e' piu' nel cestino.
    time.advance(31 * DAY)
    // Il salto di 31 giorni rispetto all'orologio del telefono puo' sospendere le pulizie (D32): il gesto dell'utente lo accetta.
    nas.acceptClockJump()
    var report = checkNotNull(organizer.purgeTrash(5.minutes))
    if (report.stoppedBy == PurgeStop.BLOCKED && nas.acceptClockJump()) report = checkNotNull(organizer.purgeTrash(5.minutes))
    assertWithMessage("pulizia: $report").that(report.purged).isEqualTo(1)
    assertThat(backend.exists(TestNas.COMMON, fake)).isTrue()
    assertThat(backend.bytes(TestNas.ADMIN_USER, "IMG_0001.jpg")).isNotNull()
    assertThat(backend.bytes(TestNas.COMMON, "Foto/IMG_0002.jpg")).isNotNull()
    assertThat(backend.bytes(TestNas.COMMON, "Foto/IMG_0003.jpg")).isNotNull()
    assertThat(NasOpsDiagnostics(nas).collect().map { it.code }).contains(NasOpsDiagCodes.TRASH_PURGE)

    // 5. L'azzeramento ferma tutto: niente piu' operazioni, i lavori in coda tolti.
    gallery.shutdown()
    assertThat(organizer.parts).isNull()
    assertThat(jobs.cancelled.get()).isAtLeast(1)
    assertThat(organizer.operations.plan(OpRequest.Trash(listOf(item("IMG_0003.jpg"))))).isInstanceOf(PlanOutcome.Rejected::class.java)
  }

  @Test
  fun preferitiENascostiViaggianoFraIDispositiviDelloStessoAccount() = runBlocking {
    // La famiglia: il telefono fonda con `test`, il tablet entra con lo STESSO account (area privata `test`).
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    val invite = phone.graph.registry.createInvite(InviteParams(TestNas.ADMIN_USER, "Tablet di prova", TestNas.ADMIN_USER, presetName = null))
    tablet.graph.start()
    val awaiting = tablet.graph.joinSameAccount(invite)
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    val request = phone.graph.registry.pendingRequests.value.single()
    phone.graph.registry.approve(request.id, ApprovalProof.TypedCode(awaiting.code))
    tablet.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tablet.graph.awaitSetup { it is SetupState.Ready }
    phone.graph.registry.refresh(Purpose.INTERACTIVE)

    val photos = (1..3).associate { n -> "Viaggi/IMG_00$n.jpg" to jpeg(tone = 30 + n * 60) }
    photos.forEach { (path, bytes) -> backend.put(TestNas.COMMON, path, bytes, lastWriteMs = time.now() - 2 * DAY) }

    val phoneGallery = galleryOf(phone, TestNas.ADMIN_USER)
    val phoneNas = startedNas(phoneGallery)
    checkNotNull(phoneNas.scan(ScanMode.Full, 5.minutes))
    val phoneView = withTimeout(TIMEOUT) { phoneGallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }
    val tabletGallery = galleryOf(tablet, TestNas.ADMIN_USER)
    val tabletNas = startedNas(tabletGallery)
    checkNotNull(tabletNas.scan(ScanMode.Full, 5.minutes))
    withTimeout(TIMEOUT) { tabletGallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size } }

    // Sul telefono: un preferito e un nascosto dalla Libreria (le azioni di sempre).
    val ids: List<MediaId> = (0 until phoneView.size).map { phoneView.snapshot.idAt(phoneView.snapshotIndex(it)) }
    phoneGallery.actions.setFavorite(setOf(ids[0]), true)
    phoneGallery.actions.setHidden(setOf(ids[1]), true)

    // Il telefono pubblica nella sua area privata (`test` e' una share di prova: D31 lo consente).
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val published = checkNotNull(phoneNas.organizer.syncPersonal(1.minutes))
    assertWithMessage("telefono: $published").that(published.published).isAtLeast(2)

    // Il tablet sincronizza e li vede: un preferito, un media in meno nella Libreria (nascosto, E-H4).
    tablet.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val applied = checkNotNull(tabletNas.organizer.syncPersonal(1.minutes))
    assertWithMessage("tablet: $applied").that(applied.applied).isAtLeast(2)
    assertThat(applied.rejected).isEmpty()
    withTimeout(TIMEOUT) { tabletGallery.repository.view(LibraryFilter.FAVORITES).first { it.size == 1 } }
    withTimeout(TIMEOUT) { tabletGallery.repository.view(LibraryFilter.ALL).first { it.size == photos.size - 1 } }

    // I dati personali stanno solo nell'area privata dell'account, mai in quella comune (C19, M7).
    assertThat(backend.children(TestNas.COMMON, ".mycloudgallery/devices").flatMap { dev ->
      backend.children(TestNas.COMMON, ".mycloudgallery/devices/$dev")
    }).doesNotContain("personal")
    assertThat(NasOpsDiagnostics(tabletNas).collect().map { it.code }).contains(NasOpsDiagCodes.PERSONAL_STATE)
  }

  /** Il secondo dispositivo dello stesso account: l'invito e' per `test`, la password e' quella di `test`. */
  private suspend fun AppGraph.joinSameAccount(invite: FamilyInvite): JoinStep.AwaitingApproval {
    awaitSetup { it == SetupState.NotConfigured }
    settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(joinFlow.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(joinFlow.authenticate(TestNas.ADMIN_PASSWORD.toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
    val awaiting = joinFlow.register("Tablet di prova") as JoinStep.AwaitingApproval
    coordinator.progress.await { it.current == OnboardingStep.ROLES }
    localSteps(until = OnboardingStep.SUMMARY)
    settle(coordinator.submit(StepInput.Finish))
    awaitSetup { it is SetupState.AwaitingApproval }
    return awaiting
  }

  private companion object {
    const val PRIMARY = "external_primary"
    const val DAY = 24L * 60 * 60 * 1000
    const val MINUTE = 60_000L
    val TIMEOUT = 2.minutes
  }
}
