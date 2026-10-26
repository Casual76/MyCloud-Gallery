package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.graph.AppLocalDataEraser
import dev.pampa.mycloudgallery.graph.GalleryStopper
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

/**
 * Fase 7, P13 ("Fatto quando" della riga P13 del §4.4): gli album condivisi **sul grafo vero dell'app** e sul NAS finto (simulatore SMB con gli
 * account di prova), con **due famiglie di dispositivi** (due account): il telefono di `test` crea un album condiviso con le sue foto dell'area
 * privata, le sposta nell'area comune con la conferma (orchestratore, operazioni della 4B, porta di completamento, diario su Room), la voce si
 * pubblica (testa firmata e segmenti in `.mycloudgallery/albums/`), e il tablet di `test2` la legge, la attesta sul suo catalogo e la vede con i
 * permessi del livello scelto. Poi un finto album ostile: nessuna operazione nel diario di core-nasops. Infine l'azzeramento a operazioni ferme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumsIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val rigs = mutableListOf<BackupRig>()
  private val graphs = mutableListOf<AlbumsGraph>()
  private val today = Today { LocalDate.of(2026, 10, 26) }

  @After
  fun tearDown() {
    graphs.forEach { runBlocking { it.stop() } }
    rigs.forEach { rig ->
      runBlocking { rig.gallery.shutdown() }
      rig.close()
    }
  }

  private fun device(name: String) = TestDevice(app, File(temp.root, name), TestPlatform(time, backend))

  /** Il telefono di `test` fonda la famiglia, il tablet di `test2` entra con l'invito ed e' approvato. */
  private suspend fun family(): Pair<TestDevice, TestDevice> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
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

  private suspend fun opened(device: TestDevice, account: String): Pair<BackupRig, AlbumsGraph> {
    val rig = BackupRig(app, device, account).also { rigs += it }
    rig.open()
    device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(device.context, device.graph, rig.gallery, search = null, today = today).also { graphs += it }
    checkNotNull(albums.ensureLive()) { "album non montati" }
    return rig to albums
  }

  private suspend fun idsOf(rig: BackupRig, count: Int): List<MediaId> {
    val view = withTimeout(TIMEOUT) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == count } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  private fun live(albums: AlbumsGraph): AlbumsLive = checkNotNull(albums.live.value)

  @Test
  fun unAlbumCreatoSulTelefonoDiTestSiSpostaSiPubblicaEIlTabletDiTest2LoVedeConIPermessi() = runBlocking<Unit> {
    val (phone, tablet) = family()
    // Byte diversi per ogni foto (il codificatore JPEG di Robolectric non dipende dai pixel): una coda diversa dopo la fine dell'immagine.
    val photos = (1..3).associate { n -> "Foto/Oslo/IMG_000$n.jpg" to BackupRig.jpeg(tone = 20 + n * 50) + ByteArray(100 * n) { (n * 31 + it).toByte() } }
    photos.forEach { (path, bytes) -> backend.put(TestNas.ADMIN_USER, path, bytes, lastWriteMs = time.now() - 2 * DAY) }
    val (phoneRig, phoneAlbums) = opened(phone, TestNas.ADMIN_USER)
    val (tabletRig, tabletAlbums) = opened(tablet, TestNas.MEMBER_USER)
    val ids = idsOf(phoneRig, photos.size)

    // 1. Il piano dal catalogo locale, il foglio, un tocco: la conferma della condivisione (C26, prima chiave).
    val outcome = live(phoneAlbums).shared.albums.create("Prova Oslo", ShareLevel.ADD, ids)
    val plan = (outcome as? SharePlanOutcome.Ready)?.plan ?: error("piano rifiutato: $outcome")
    assertThat(plan.items).hasSize(3)
    val confirmation = checkNotNull(ShareConfirmationSheet(plan).tap(live(phoneAlbums).shared.ports.clocks.monotonicMs()))
    val started = live(phoneAlbums).shared.orchestrator.start(plan, confirmation)
    assertWithMessage("avvio: $started").that(started).isInstanceOf(ShareStartOutcome.Started::class.java)

    // 2. Lo spostamento (operazioni della 4B), il completamento sul catalogo, la voce e la testa, il guardiano: giri finche' il diario e' chiuso.
    val done = kotlinx.coroutines.withTimeoutOrNull(2.minutes) {
      while (live(phoneAlbums).shared.diary.openOps().isNotEmpty()) {
        // Le operazioni della 4B le riprende il loro lavoro (qui non c'e' JobScheduler): come lo farebbe.
        phoneRig.nas.resumeOperations(30.seconds)
        phoneAlbums.syncRound(30.seconds)
        // Al piu' una testa ogni 5 minuti per album (D6, ora del NAS): quando i file sono arrivati e le voci aspettano solo la testa, l'ora finta
        // del NAS va avanti (prima no: un'operazione in corso vedrebbe un salto dell'ora).
        val waiting = live(phoneAlbums).shared.diary.openOps().flatMap { live(phoneAlbums).shared.diary.items(it.id) }
        if (waiting.isNotEmpty() && waiting.all { it.state == ShareItemState.ENTRY_STAGED || it.state.terminal }) {
          time.advance(dev.pampa.mycloudgallery.core.albums.contract.PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
        }
        kotlinx.coroutines.delay(200)
      }
    }
    if (done == null) {
      val op0 = live(phoneAlbums).shared.diary.openOps().first()
      println("DIARIO: " + live(phoneAlbums).shared.diary.items(op0.id).map { "${it.state} ${it.failure} ${it.wait} ${it.ticket?.value} ${it.stageSeq} ${it.safeCopyRequested}" })
      println("OPS: " + phoneRig.nas.organizer.opsStore.ops(OpState.entries.toSet()).map { "${it.state} ${it.errorCode} ${it.pauseReason}" })
      println("PUB: pending=${live(phoneAlbums).shared.publisher.pending.value} errori=${phoneAlbums.roundErrors} sync=${phoneAlbums.lastSync.value}")
      error("condivisione non finita")
    }
    val op = (started as ShareStartOutcome.Started).op
    val items = live(phoneAlbums).shared.diary.items(op)
    assertWithMessage("elementi: $items").that(items.map { it.state }.toSet()).containsExactly(ShareItemState.DONE)

    // Sul NAS: i file nella cartella dell'album dell'area comune, le sorgenti non piu' nell'area privata, la testa e i segmenti dell'album.
    val inAlbum = filesUnder(backend, TestNas.COMMON, "Album condivisi/Prova Oslo")
    assertThat(inAlbum.map { it.substringAfterLast('/') }).containsExactly("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg")
    photos.keys.forEach { assertWithMessage(it).that(backend.exists(TestNas.ADMIN_USER, it)).isFalse() }
    inAlbum.forEach { path -> assertThat(backend.bytes(TestNas.COMMON, path)).isEqualTo(photos.getValue("Foto/Oslo/${path.substringAfterLast('/')}")) }
    val albumFiles = filesUnder(backend, TestNas.COMMON, ".mycloudgallery/albums")
    assertWithMessage("file dell'album: $albumFiles").that(albumFiles.any { "/head/" in it }).isTrue()
    assertThat(albumFiles.any { "/seg/" in it }).isTrue()
    // Gli stessi media (D29): la Libreria ha gli stessi id di prima.
    assertThat(idsOf(phoneRig, photos.size).toSet()).isEqualTo(ids.toSet())

    // 3. Il tablet di `test2` scansiona l'area comune, legge gli album e vede "Prova Oslo" con i tre elementi e i permessi di "Gli altri aggiungono".
    tablet.graph.trustGuard.preflight(Purpose.BACKGROUND)
    checkNotNull(tabletRig.nas.scan(dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode.Full, 5.minutes))
    tabletAlbums.syncRound(1.minutes)
    val seen = kotlinx.coroutines.withTimeoutOrNull(TIMEOUT) { live(tabletAlbums).shared.albums.visible.first { list -> list.any { it.title == "Prova Oslo" && it.count == 3 } } }
      ?: run {
        val t = live(tabletAlbums).shared
        println("TABLET: sync=${tabletAlbums.lastSync.value} errori=${tabletAlbums.roundErrors} syncErrors=${t.sync.syncErrors} viste=${t.replica.views.value.values} orfani=${t.replica.orphans.value.size} irrisolti=${t.replica.unresolved.value.size}")
        val v = t.replica.views.value.values.first()
        println("TABLET: chiavi=${v.merged.items.values.map { it.contentKey.hex.take(10) + "/" + it.media.value.take(8) }} fattiTelefono=${live(phoneAlbums).shared.ports.facts.facts(ids).values.map { it.contentKey?.hex?.take(10) + " " + it.place }}")
        for (item in v.merged.items.values) println("TABLET: attesta ${item.contentKey.hex.take(10)} -> ${t.ports.facts.attest(listOf(dev.pampa.mycloudgallery.core.albums.contract.AttestRequest(item.contentKey, item.sha256))).values.map { it.value.take(8) }}")
        println("TABLET: attestati=${v.attested.values.map { it.value.take(8) }} visibili-della-galleria=${tabletRig.gallery.visibleMedia.visible(v.attested.values).map { it.value.take(8) }} snapshot=${tabletRig.gallery.visibleMedia.snapshot.value?.size}")
        println("TABLET: visibili=${t.albums.visible.value.map { it.title + ":" + it.count }} contributi=${t.replicaStore.albums().map { a -> t.replicaStore.contributions(a).map { it.toString() } }} libreria=${tabletRig.gallery.repository.view(LibraryFilter.ALL).value.size}")
        error("il tablet non vede l'album")
      }
    val album = seen.single { it.title == "Prova Oslo" }
    assertThat(album.level).isEqualTo(ShareLevel.ADD)
    assertThat(album.mine).isFalse()
    val shared = live(tabletAlbums).shared.albums
    assertThat(shared.allowed(album.id, AlbumOp.ADD_MEDIA)).isTrue()
    assertThat(shared.allowed(album.id, AlbumOp.RENAME)).isFalse()
    assertThat(shared.allowed(album.id, AlbumOp.CHANGE_LEVEL)).isFalse()
    val othersItem = shared.visibleItems(album.id).first()
    assertWithMessage("togliere un elemento di un altro a \"Gli altri aggiungono\"").that(shared.allowed(album.id, AlbumOp.REMOVE_ITEM, othersItem)).isFalse()
    // Sul telefono del creatore: tutto permesso.
    assertThat(live(phoneAlbums).shared.albums.allowed(album.id, AlbumOp.CHANGE_LEVEL)).isTrue()

    // 4. Un finto album ostile nell'area comune: spazzatura al posto della testa sotto il telefono e una cartella di un dispositivo sconosciuto.
    val fakeAlbum = Base32.encode(ByteArray(16) { 7 })
    val phoneDevice = checkNotNull(phone.graph.nasInputs.deviceId())
    backend.put(TestNas.COMMON, ".mycloudgallery/albums/$fakeAlbum/$phoneDevice/head/00000000000000000001", ByteArray(200) { 1 }, lastWriteMs = time.now())
    backend.put(TestNas.COMMON, ".mycloudgallery/albums/$fakeAlbum/${Base32.encode(ByteArray(16) { 9 })}/head/00000000000000000001", ByteArray(64), lastWriteMs = time.now())
    val opsBefore = tabletRig.nas.organizer.opsStore.ops(OpState.entries.toSet()).size
    tabletAlbums.syncRound(1.minutes)
    assertWithMessage("nessuna operazione nel diario di core-nasops da un dato letto dal NAS")
      .that(tabletRig.nas.organizer.opsStore.ops(OpState.entries.toSet())).hasSize(opsBefore)
    assertThat(live(tabletAlbums).shared.diary.openOps()).isEmpty()
    assertThat(live(tabletAlbums).shared.albums.visible.value.map { it.title }).containsExactly("Prova Oslo")
    assertThat(checkNotNull(tabletAlbums.lastSync.value).contributionsRejected).isAtLeast(1)

    // 5. L'azzeramento del tablet a operazioni ferme: gli album si fermano per primi, poi la galleria, poi i dati (database e registri).
    tablet.graph.albumsStopper = GalleryStopper { tabletAlbums.stop() }
    tablet.graph.galleryStopper = GalleryStopper { tabletRig.gallery.shutdown() }
    File(tablet.context.noBackupFilesDir, AlbumsWiring.DIR).mkdirs()
    assertThat(AppLocalDataEraser(tablet.context, tablet.graph).eraseAccountData(stopGallery = true)).isTrue()
    assertThat(tabletAlbums.live.value).isNull()
    assertThat(tabletAlbums.ensureLive()).isNull()
    assertThat(File(tablet.context.noBackupFilesDir, AlbumsWiring.DIR).exists()).isFalse()
    assertThat(tablet.context.getDatabasePath(GalleryDatabase.NAME).exists()).isFalse()
  }

  private companion object {
    val TIMEOUT = 30.seconds
    const val DAY = 24L * 60 * 60 * 1000
  }
}
