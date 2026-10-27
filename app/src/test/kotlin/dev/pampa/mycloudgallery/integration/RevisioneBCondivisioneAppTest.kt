package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.albums.AppAlbums
import dev.pampa.mycloudgallery.albums.AppSharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.work.BackupStages
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/**
 * Fase 7, revisione B (flusso della condivisione) **sul grafo vero dell'app** e sul NAS finto (simulatore SMB con gli account di prova): ogni prova
 * riproduce un difetto di `docs/review/funzionale-7.md` o di `docs/review/7-fase7.md` ed e' rossa senza la sua correzione.
 * - SP-1: un media solo sul telefono arriva nella cartella dell'album (prima il cancello del backup lo fermava: "scritture ferme", poi "non partito").
 * - SG-1: con la release e un account di prova il foglio non promette il caricamento ne' la copia del guardiano.
 * - SP-2: "In arrivo" mentre lo spostamento e' in viaggio.
 * - R3: a "Gli altri guardano" il familiare non manda nel cestino (ne' sposta) il file di un elemento del creatore; il creatore si'.
 * - SC-1: "Smetti di condividere" lascia subito l'album personale al creatore.
 * - SC-2: una modifica in coda si pubblica dopo la morte del processo, senza il giro dell'indice.
 * - SH-3: "N file nuovi nella cartella" conta i file del PC, non quelli tolti dall'album.
 * - SH-4: "Da condividere" di uno smart condiviso.
 * - SG-2: un giro degli album che non puo' andare avanti e' "finito" (non si ripete a vuoto).
 * - I3: la seconda chiave (la conferma di core-nasops) solo per un trasferimento che il diario conosce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RevisioneBCondivisioneAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val rigs = mutableListOf<BackupRig>()
  private val graphs = mutableListOf<AlbumsGraph>()
  private val devices = mutableListOf<TestDevice>()
  private val today = Today { LocalDate.of(2026, 10, 27) }
  private val facadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    facadeScope.cancel()
    graphs.forEach { runBlocking { runCatching { it.stop() } } }
    rigs.forEach { rig ->
      runBlocking { runCatching { rig.gallery.shutdown() } }
      runCatching { rig.close() }
    }
    devices.forEach { runCatching { it.close() } }
  }

  private fun device(name: String, flags: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)) =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags).also { devices += it }

  /** Il telefono di `test` fonda la famiglia. */
  private suspend fun phoneOnly(flags: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)): TestDevice {
    val phone = device("telefono", flags)
    phone.graph.start()
    phone.graph.foundFamily()
    return phone
  }

  /** Il telefono di `test` fonda la famiglia, il tablet di `test2` entra con l'invito ed e' approvato. */
  private suspend fun family(): Pair<TestDevice, TestDevice> {
    val phone = phoneOnly()
    val tablet = device("tablet")
    val invite = phone.graph.inviteMember()
    tablet.graph.start()
    val awaiting = tablet.graph.joinWith(invite)
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    phone.graph.registry.approve(phone.graph.registry.pendingRequests.value.single().id, ApprovalProof.TypedCode(awaiting.code))
    tablet.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tablet.graph.awaitSetup { it is SetupState.Ready }
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    return phone to tablet
  }

  private suspend fun opened(device: TestDevice, account: String, rig: BackupRig = BackupRig(app, device, account)): Pair<BackupRig, AlbumsGraph> {
    rigs += rig
    rig.open()
    device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(device.context, device.graph, rig.gallery, search = null, today = today).also { graphs += it }
    checkNotNull(albums.ensureLive()) { "album non montati" }
    return rig to albums
  }

  private fun live(albums: AlbumsGraph): AlbumsLive = checkNotNull(albums.live.value)

  private suspend fun idsOf(rig: BackupRig, count: Int): List<MediaId> {
    val view = withTimeout(Attese.TETTO) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == count } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  /** Foto di prova nell'area privata di `test`, con byte diversi per ognuna. */
  private fun privatePhotos(vararg names: String): Map<String, ByteArray> = names.withIndex().associate { (n, name) ->
    "Foto/Prova/$name" to BackupRig.jpeg(tone = 30 + n * 40) + ByteArray(120 + 17 * n) { (n * 29 + it).toByte() }
  }.also { photos -> photos.forEach { (path, bytes) -> backend.put(TestNas.ADMIN_USER, path, bytes, lastWriteMs = time.now() - 2 * DAY) } }

  private fun confirmAndStart(albums: AlbumsGraph, plan: SharePlan): ShareStartOutcome.Started {
    val l = live(albums)
    val confirmation = checkNotNull(ShareConfirmationSheet(plan).tap(l.shared.ports.clocks.monotonicMs()))
    val started = runBlocking { l.shared.orchestrator.start(plan, confirmation) }
    assertWithMessage("avvio: $started").that(started).isInstanceOf(ShareStartOutcome.Started::class.java)
    return started as ShareStartOutcome.Started
  }

  /**
   * Il lavoro come lo fanno i lavori dell'app (qui non c'e' JobScheduler): le operazioni della 4B, gli arrivi del backup, la condivisione e il giro
   * degli album, con l'ora del NAS che passa quando le voci aspettano solo la testa. Fino al diario chiuso, o a [until].
   */
  private suspend fun drive(rig: BackupRig, device: TestDevice, albums: AlbumsGraph, until: suspend () -> Boolean = { live(albums).shared.diary.openOps().isEmpty() }): Boolean =
    withTimeoutOrNull(Attese.TETTO) {
      while (!until()) {
        runCatching { rig.nas.resumeOperations(30.seconds) }
        runCatching { BackupStages(device.graph, rig.backup).incoming() }
        runCatching { albums.shareRound(30.seconds) }
        runCatching { albums.syncRound(30.seconds) }
        val waiting = live(albums).shared.diary.openOps().flatMap { live(albums).shared.diary.items(it.id) }
        if (waiting.isNotEmpty() && waiting.all { it.state == ShareItemState.ENTRY_STAGED || it.state.terminal }) time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
        delay(150)
      }
      true
    } ?: false

  // --- SP-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sp1UnMediaSoloSulTelefonoArrivaNellaCartellaDellAlbum() = runBlocking<Unit> {
    val phone = phoneOnly()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER)
    val bytes = BackupRig.jpeg(tone = 90) + ByteArray(333) { (it * 7).toByte() }
    BackupRig.photo(rig.store, id = 41, bytes = bytes, takenAt = time.now() - 3 * DAY, name = "IMG_TELEFONO.jpg")
    val (_, albums) = opened(phone, TestNas.ADMIN_USER, rig)
    val ids = idsOf(rig, 1)
    val plan = (live(albums).shared.albums.create("Dal telefono", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    assertThat(plan.items.map { it.action }).containsExactly(ShareItemAction.UPLOAD)
    val started = confirmAndStart(albums, plan)
    val finished = drive(rig, phone, albums)
    val item = live(albums).shared.diary.items(started.op).single()
    assertWithMessage("il caricamento verso l'album finisce (elemento: ${item.state} ${item.failure} ${item.wait})").that(finished).isTrue()
    assertThat(item.state).isEqualTo(ShareItemState.DONE)
    // Il file e' nella cartella dell'album dell'area comune, con i byte del telefono; il media e' lo stesso (D29).
    assertThat(backend.bytes(TestNas.COMMON, "Album condivisi/Dal telefono/IMG_TELEFONO.jpg")).isEqualTo(bytes)
    assertThat(idsOf(rig, 1)).isEqualTo(ids)
  }

  // --- SG-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sg1InReleaseConUnAccountDiProvaIlFoglioNonPromette() = runBlocking<Unit> {
    // La release vera: R2 della 5A, un account di prova non fa scrivere il backup in nessuna share (ne' arrivi ne' guardiano); D30 lascia gli album
    // condivisi sulle share di prova.
    val phone = phoneOnly(FeatureFlags.RELEASE)
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER)
    BackupRig.photo(rig.store, id = 42, bytes = BackupRig.jpeg(tone = 70) + ByteArray(91) { it.toByte() }, takenAt = time.now() - 3 * DAY)
    privatePhotos("IMG_SOLO_NAS.jpg")
    val (_, albums) = opened(phone, TestNas.ADMIN_USER, rig)
    val ids = idsOf(rig, 2)
    val plan = (live(albums).shared.albums.create("Release", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    // Il media del telefono resta fuori con il suo motivo (prima: "si caricano dal telefono", poi fermo e "non partito").
    assertThat(plan.items.map { it.action }).containsExactly(ShareItemAction.MOVE_IN)
    assertThat(plan.excluded).containsEntry(ShareExclusion.UPLOAD_BLOCKED, 1)
    // Il media solo sul NAS (non e' sul telefono): la copia del guardiano non partirebbe, e il foglio lo dice.
    assertThat(plan.safeCopyBlocked).isEqualTo(1)
  }

  // --- SP-2 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sp2InArrivoMentreLoSpostamentoEInViaggio() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_A.jpg", "IMG_B.jpg")
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER)
    val ids = idsOf(rig, 2)
    val plan = (live(albums).shared.albums.create("In viaggio", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    // Lo spostamento e' chiesto alle operazioni della 4B ma non ancora fatto (qui le riprende solo `drive`): i due media sono "in arrivo".
    val facade = AppAlbums(albums, facadeScope)
    val incoming = withTimeout(Attese.TETTO) { facade.content(plan.album).first { (it?.incoming ?: 0) > 0 } }
    assertThat(incoming!!.incoming).isEqualTo(2)
    assertThat((incoming.album as Album.Shared).incoming).isEqualTo(2)
    // Arrivati e pubblicati, non lo sono piu'.
    assertThat(drive(rig, phone, albums)).isTrue()
    val after = withTimeout(Attese.TETTO) { facade.content(plan.album).first { it != null && it.incoming == 0 && it.items.size == 2 } }
    assertThat(after!!.incoming).isEqualTo(0)
  }

  // --- R3 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun r3AGliAltriGuardanoIlFamiliareNonEliminaNeSpostaIlFileDelCreatore() = runBlocking<Unit> {
    val (phone, tablet) = family()
    privatePhotos("IMG_C.jpg")
    val (phoneRig, phoneAlbums) = opened(phone, TestNas.ADMIN_USER)
    val (tabletRig, tabletAlbums) = opened(tablet, TestNas.MEMBER_USER)
    val ids = idsOf(phoneRig, 1)
    val plan = (live(phoneAlbums).shared.albums.create("Guardano", ShareLevel.VIEW, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(phoneAlbums, plan)
    assertThat(drive(phoneRig, phone, phoneAlbums)).isTrue()
    time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
    phoneAlbums.syncRound(1.minutes)
    // Il tablet di `test2` vede l'album e il suo elemento (il file nell'area comune).
    tablet.graph.trustGuard.preflight(Purpose.BACKGROUND)
    checkNotNull(tabletRig.nas.scan(ScanMode.Full, 5.minutes))
    tabletAlbums.syncRound(1.minutes)
    withTimeout(Attese.TETTO) { live(tabletAlbums).shared.albums.visible.first { list -> list.any { it.id == plan.album && it.count == 1 } } }
    val path = "Album condivisi/Guardano/IMG_C.jpg"

    // Il tablet: il piano di core-nasops lascia fuori il file (cestino, spostamento, rinomina), il menu del viewer non lo offre.
    val tabletItem = itemOf(tabletRig, TestNas.MEMBER_USER, path)
    val ops = tabletRig.nas.organizer.operations
    assertWithMessage("cestino dal tablet").that(ops.plan(OpRequest.Trash(listOf(tabletItem)))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    assertThat(ops.plan(OpRequest.Move(listOf(tabletItem), TestNas.MEMBER_USER, null))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    assertThat(ops.plan(OpRequest.Rename(tabletItem, "altro.jpg"))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    val folder = NasItem(TestNas.COMMON, SharePath.parse("Album condivisi/Guardano"), isFolder = true, size = 0, stamp = null)
    assertThat(ops.plan(OpRequest.Trash(listOf(folder)))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    val target = tabletRig.nas.organizer.mediaActions.target(MediaRef.Nas(locationOf(tabletRig, TestNas.MEMBER_USER, path), 0, null))
    assertThat(target?.albumProtected).isTrue()

    // Il creatore (tutte le celle della riga lo ammettono): il piano c'e'.
    val phoneItem = itemOf(phoneRig, TestNas.ADMIN_USER, path)
    assertThat(phoneRig.nas.organizer.operations.plan(OpRequest.Trash(listOf(phoneItem)))).isInstanceOf(PlanOutcome.Ready::class.java)
    assertThat(backend.exists(TestNas.COMMON, path)).isTrue()
  }

  private suspend fun locationOf(rig: BackupRig, account: String, path: String): Long {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    return checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(account), TestNas.COMMON, NasPath.of(path)))) { "posizione non nel catalogo" }.locationId.value
  }

  private suspend fun itemOf(rig: BackupRig, account: String, path: String): NasItem {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val info = checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(account), TestNas.COMMON, NasPath.of(path))))
    return NasItem(TestNas.COMMON, SharePath.parse(path), isFolder = false, size = info.stamp.size, stamp = info.stamp)
  }

  // --- SC-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sc1SmettiDiCondividereLasciaSubitoLAlbumPersonaleAlCreatore() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_D.jpg", "IMG_E.jpg")
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER)
    val ids = idsOf(rig, 2)
    val plan = (live(albums).shared.albums.create("Da chiudere", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    withTimeout(Attese.TETTO) { live(albums).shared.albums.visible.first { list -> list.any { it.id == plan.album && it.count == 2 } } }
    // "Smetti di condividere" dalla pagina, con la facciata che usa il foglio di chiusura. Dopo, nessun giro dell'indice.
    val shared = AppSharedAlbums(albums, facadeScope)
    assertThat(shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    val personal = shared.keptAfterClose(plan.album)
    assertWithMessage("l'album personale del creatore subito dopo la chiusura").that(personal).isNotNull()
    val list = withTimeout(Attese.TETTO) { live(albums).albums.all.first { all -> all.byId(personal!!) != null } }
    val album = checkNotNull(list.byId(personal!!))
    assertThat(album).isInstanceOf(Album.Manual::class.java)
    assertThat(album.count).isEqualTo(2)
  }

  // --- SC-2 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sc2UnaModificaInCodaSiPubblicaDopoLaMorteDelProcesso() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_F.jpg")
    val dbName = "revisione-b-sc2.db"
    app.deleteDatabase(dbName)
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER, BackupRig(app, phone, TestNas.ADMIN_USER, databaseName = dbName))
    val ids = idsOf(rig, 1)
    val plan = (live(albums).shared.albums.create("Prima", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
    // La rinomina va in coda (si pubblicherebbe dopo 30 s di calma); il processo muore prima.
    assertThat(live(albums).shared.albums.rename(plan.album, "Dopo")).isEqualTo(EditOutcome.Done)
    assertThat(stagedOf(live(albums), plan.album)).isNotEmpty()
    graphs.remove(albums)
    rigs.remove(rig)
    runCatching { albums.stop() }
    runCatching { rig.gallery.shutdown() }
    runCatching { rig.close() }
    devices.remove(phone)
    phone.close()
    time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
    // Il processo nuovo sulle stesse cartelle monta gli album e basta: nessun giro dell'indice, nessun lavoro della condivisione.
    val reborn = TestDevice(app, File(temp.root, "telefono"), phone.platform).also { devices += it }
    reborn.graph.start()
    reborn.graph.awaitSetup { it is SetupState.Ready }
    val rebornRig = BackupRig(app, reborn, TestNas.ADMIN_USER, databaseName = dbName, preferencesName = "library-2.preferences_pb")
    val (_, again) = opened(reborn, TestNas.ADMIN_USER, rebornRig)
    val published = withTimeoutOrNull(Attese.TETTO) {
      while (stagedOf(live(again), plan.album).isNotEmpty()) delay(200)
      true
    }
    assertWithMessage("la rinomina in coda pubblicata al montaggio, senza il giro dell'indice").that(published).isTrue()
  }

  private suspend fun stagedOf(live: AlbumsLive, album: AlbumId) = live.shared.publishStore.load(album)?.staged.orEmpty()

  // --- SH-3 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh3IFileNuoviNellaCartellaSonoSoloQuelliDelPc() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_G.jpg", "IMG_H.jpg")
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER)
    val ids = idsOf(rig, 2)
    val plan = (live(albums).shared.albums.create("Cartella", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    // Dal PC, un file nuovo nella cartella dell'album; dall'app, "Rimuovi dall'album" su un elemento (il suo file resta nella cartella, D39).
    // Un file fermo da un giorno (la scansione aspetta 2 minuti di calma prima di prendere un file appena scritto).
    backend.put(TestNas.COMMON, "Album condivisi/Cartella/DAL_PC.jpg", BackupRig.jpeg(tone = 200) + ByteArray(77) { 3 }, lastWriteMs = time.now() - DAY)
    assertThat(live(albums).shared.albums.remove(plan.album, listOf(ids[0]))).isEqualTo(EditOutcome.Done)
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    checkNotNull(rig.nas.scan(ScanMode.Full, 5.minutes))
    albums.syncRound(1.minutes)
    val shared = AppSharedAlbums(albums, facadeScope)
    val notices = withTimeout(Attese.TETTO) {
      shared.notices.first { list -> list.any { it is SharedAlbumNotice.FilesAddedFromPc && it.album == plan.album } }
    }
    val notice = notices.filterIsInstance<SharedAlbumNotice.FilesAddedFromPc>().single { it.album == plan.album }
    assertWithMessage("solo il file del PC, non quello tolto dall'album").that(notice.count).isEqualTo(1)
    assertThat(live(albums).shared.adoptions.pending(plan.album)).hasSize(1)
  }

  // --- SH-4 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh4LeFotoNuoveDelloSmartCondivisoVannoInDaCondividere() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_I.jpg", "IMG_J.jpg")
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER)
    val ids = idsOf(rig, 2)
    val l = live(albums)
    // Uno smart le cui regole da sole non prendono niente, con una foto aggiunta a mano; poi "Condividi" (D17: le regole restano mie).
    val rules = SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 2)))), null, null)
    val smart = (l.albums.create("Smart di prova", AlbumKind.SMART, rules) as CreateOutcome.Created).id
    assertThat(l.albums.add(smart, listOf(ids[0]))).isInstanceOf(AddOutcome.Added::class.java)
    withTimeout(Attese.TETTO) { l.albums.content(smart).first { (it?.items?.size ?: 0) == 1 } }
    val plan = (l.shared.orchestrator.plan(ShareRequest.FromPersonal(smart, ShareLevel.ADD)) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    // Una foto nuova entra nello smart: non si sposta da sola, sta in "Da condividere" dell'album condiviso (Q4 (a)).
    assertThat(l.albums.add(smart, listOf(ids[1]))).isInstanceOf(AddOutcome.Added::class.java)
    val facade = AppAlbums(albums, facadeScope)
    val content = checkNotNull(withTimeout(Attese.TETTO) { facade.content(plan.album).first { (it?.toShare?.size ?: 0) > 0 } })
    val toShare = checkNotNull(content.toShare)
    assertThat(toShare.size).isEqualTo(1)
    assertThat(toShare.snapshot.idAt(toShare.snapshotIndex(0))).isEqualTo(ids[1])
    assertThat((content.album as Album.Shared).toShare).isEqualTo(1)
    // Il file della foto nuova non si e' mosso: nella cartella privata ne resta uno, in quella dell'album uno.
    assertThat(backend.children(TestNas.ADMIN_USER, "Foto/Prova")).hasSize(1)
    assertThat(backend.children(TestNas.COMMON, "Album condivisi/Smart di prova")).hasSize(1)
  }

  // --- SG-2 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sg2UnGiroCheNonPuoAndareAvantiEFinito() = runBlocking<Unit> {
    val phone = phoneOnly()
    val (_, albums) = opened(phone, TestNas.ADMIN_USER)
    // Nessuna cartella dell'area comune si apre (la connessione cade a ogni elenco): l'elenco di `albums/` non si legge, niente letto ne' scartato.
    backend.faults = FaultPlan().random(7, 1.0, FaultPhase.BEFORE, SimFault.DropConnection, setOf(SimOp.OPEN_DIRECTORY))
    val done = try {
      albums.syncRound(30.seconds)
    } finally {
      backend.faults = null
    }
    val report = checkNotNull(albums.lastSync.value)
    assertThat(report.incomplete).isTrue()
    assertThat(report.contributionsRead).isEqualTo(0)
    // Prima il giro diceva "non finito" e la tappa lo ripeteva a vuoto fino al suo tetto; ora si riprova al giro dell'indice dopo.
    assertWithMessage("un giro che non puo' andare avanti e' finito per la tappa").that(done).isTrue()
  }

  // --- I3 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun i3UnaConfermaGiovaneNonBastaPerSpostareCioCheIlDiarioNonConosce() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_K.jpg", "IMG_L.jpg")
    val (rig, albums) = opened(phone, TestNas.ADMIN_USER)
    idsOf(rig, 2)
    // Le due foto hanno la stessa data e id casuali: l'ordine della Libreria non dice quale e' quale. L'album prende IMG_K per nome, IMG_L e' il
    // file estraneo (prima, una volta su due, l'album prendeva proprio IMG_L e la prova falliva).
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    suspend fun idOf(name: String): MediaId {
      val info = checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(TestNas.ADMIN_USER), TestNas.ADMIN_USER, NasPath.of("Foto/Prova/$name")))) { name }
      return checkNotNull(catalog.mediaIdOfLocation(info.locationId)) { name }
    }
    val ids = listOf(idOf("IMG_K.jpg"), idOf("IMG_L.jpg"))
    val l = live(albums)
    val other = NasItemRef(TestNas.ADMIN_USER, SharePath.parse("Foto/Prova/IMG_L.jpg"))
    val elsewhere = ShareFolder(TestNas.COMMON, SharePath.parse("Album condivisi/Altrove"))
    // Un piano vero confermato dal foglio e mai avviato: la conferma e' giovane e valida, ma nessuna operazione del diario la porta.
    val idle = (l.shared.albums.create("Mai partito", ShareLevel.ADD, ids.take(1)) as SharePlanOutcome.Ready).plan
    val loose = checkNotNull(ShareConfirmationSheet(idle).tap(l.shared.ports.clocks.monotonicMs()))
    assertThat(l.shared.ports.mover.move(TransferKey("op-finta:in"), listOf(other), elsewhere, emptySet(), loose))
      .isEqualTo(TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED))
    assertThat(l.shared.ports.uploader.upload(TransferKey("op-finta:up"), listOf(ids[1]), elsewhere, loose))
      .isEqualTo(TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED))
    // Un'operazione avviata: la sua conferma non sposta un file che non e' suo, con una chiave che il diario non ha.
    val plan = (l.shared.albums.create("Chiave", ShareLevel.ADD, ids.take(1)) as SharePlanOutcome.Ready).plan
    val confirmation = checkNotNull(ShareConfirmationSheet(plan).tap(l.shared.ports.clocks.monotonicMs()))
    val started = l.shared.orchestrator.start(plan, confirmation) as ShareStartOutcome.Started
    assertThat(l.shared.ports.mover.move(TransferKey("${started.op.value}:in:7"), listOf(other), elsewhere, emptySet(), confirmation))
      .isEqualTo(TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED))
    // L'operazione giusta finisce; il file estraneo e' dov'era e la cartella "Altrove" non esiste.
    assertThat(drive(rig, phone, albums)).isTrue()
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Foto/Prova/IMG_L.jpg")).isNotNull()
    assertThat(backend.exists(TestNas.COMMON, "Album condivisi/Altrove")).isFalse()
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
