package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.albums.AppSharedAlbums
import dev.pampa.mycloudgallery.albums.idsOf
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.share.ShareOrchestrator
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
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
 * Fase 7, revisione E, voci 3 e 4: i **confini fra i pacchetti A e B** della correzione della Fase 7, provati sul grafo vero dell'app (`AlbumsWiring`, il
 * diario su Room, il NAS finto). Il pacchetto A avvertiva due cose a chi avrebbe unito il lavoro di B:
 * - **SH-2 (B) non deve toccare gli elementi in viaggio senza biglietto** (I5, A: una riga del diario che non si legge resta un elemento fermo). La striscia
 *   "N da riprovare" e il tocco "Riprova" leggono le operazioni **chiuse** dal diario: il diario di Room non le dava mai (`closedOps` valeva l'elenco
 *   vuoto di serie), quindi dopo la morte del processo la striscia spariva; e, letta un'operazione chiusa con un elemento fermo, la striscia l'avrebbe
 *   mostrata "in corso" per sempre.
 * - **SC-1 (B) deve passare da `copyKept`** (R1, A-3): dopo "Smetti di condividere" l'album personale del creatore nasce subito e il giro dell'indice dopo
 *   non scrive una seconda copia ne' ne rimette gli elementi tolti.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RevisioneEConfiniAppTest {

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

  private suspend fun phoneOnly(): TestDevice {
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend), FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    return phone
  }

  private suspend fun opened(device: TestDevice): Pair<BackupRig, AlbumsGraph> {
    val rig = BackupRig(app, device, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(device.context, device.graph, rig.gallery, search = null, today = today).also { graphs += it }
    checkNotNull(albums.ensureLive()) { "album non montati" }
    return rig to albums
  }

  private fun live(albums: AlbumsGraph): AlbumsLive = checkNotNull(albums.live.value)

  private suspend fun libraryIds(rig: BackupRig, count: Int): List<MediaId> {
    val view = withTimeout(Attese.TETTO) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == count } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  /** Foto di prova nell'area privata di `test`, con byte diversi per ognuna. */
  private fun privatePhotos(vararg names: String) {
    names.withIndex().forEach { (n, name) ->
      val bytes = BackupRig.jpeg(tone = 30 + n * 40) + ByteArray(120 + 17 * n) { (n * 29 + it).toByte() }
      backend.put(TestNas.ADMIN_USER, "Foto/Prova/$name", bytes, lastWriteMs = time.now() - 2 * DAY)
    }
  }

  private fun confirmAndStart(albums: AlbumsGraph, plan: SharePlan): ShareStartOutcome.Started {
    val l = live(albums)
    val confirmation = checkNotNull(ShareConfirmationSheet(plan).tap(l.shared.ports.clocks.monotonicMs()))
    val started = runBlocking { l.shared.orchestrator.start(plan, confirmation) }
    assertWithMessage("avvio: $started").that(started).isInstanceOf(ShareStartOutcome.Started::class.java)
    return started as ShareStartOutcome.Started
  }

  /** Il lavoro come lo fanno i lavori dell'app (qui non c'e' JobScheduler), fino al diario chiuso. */
  private suspend fun drive(rig: BackupRig, device: TestDevice, albums: AlbumsGraph): Boolean = withTimeoutOrNull(Attese.TETTO) {
    while (live(albums).shared.diary.openOps().isNotEmpty()) {
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

  // --- Confine A/B, SH-2 ----------------------------------------------------------------------------------------------------------------

  private val album = AlbumId.derived(AccountId.of("test"), ByteArray(16) { 9 })

  private fun op(n: Int, kind: SharePlanKind = SharePlanKind.SHARE) = ShareOpRecord(
    id = ShareOpId("op-e-$n"), album = album, kind = kind, planHash = SharePlanHash("%064x".format(100 + n)), title = "Prova", level = ShareLevel.ADD,
    nonceHex = null, folder = ShareFolder(TestNas.COMMON, SharePath.of("Album condivisi", "Prova")), hidden = HiddenChoice.LEAVE_OUT,
  )

  private fun failedItem(op: ShareOpRecord, index: Int, media: MediaId, failure: ShareFailure = ShareFailure.NOT_STARTED) =
    ShareItemRecord(op.id, index, media, ShareItemAction.MOVE_IN, ShareItemState.FAILED, mine = true, size = 100, transferKey = TransferKey("k-${op.id.value}:in:0"), failure = failure)

  /** Un processo nuovo: un orchestratore nuovo sulle stesse porte (lo stesso diario su Room), che riprende come fa il lavoro dell'app. */
  private suspend fun reborn(l: AlbumsLive): ShareOrchestrator = ShareOrchestrator(l.shared.ports).also { it.resumeAll(30.seconds) }

  @Test
  fun sh2LeOperazioniChiuseSiLeggonoDalDiarioVeroEIlRiprovaSopravvive() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_A.jpg", "IMG_B.jpg", "IMG_C.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 3)
    val l = live(albums)
    // Un'operazione finita con due elementi da riprovare (non partiti) e uno fatto; poi il processo muore.
    val closed = op(1)
    val done = ShareItemRecord(closed.id, 2, ids[2], ShareItemAction.MOVE_IN, ShareItemState.DONE, mine = true, size = 100)
    l.shared.diary.create(closed, listOf(failedItem(closed, 0, ids[0]), failedItem(closed, 1, ids[1]), done))
    l.shared.diary.finish(closed.id)

    val again = reborn(l)
    val strip = again.operations.value.filterIsInstance<ShareOpState.PartlyDone>().single { it.op == closed.id }
    assertWithMessage("la striscia dopo la morte del processo").that(strip.failures).containsExactly(ShareFailure.NOT_STARTED, 2)
    assertThat(strip.done).isEqualTo(1)
    // "Tocca per riprovare": la richiesta per i soli elementi da riprovare, non per quello fatto.
    val request = again.retryRequest(closed.id)
    assertThat(request).isEqualTo(ShareRequest.AddToShared(album, listOf(ids[0], ids[1]), HiddenChoice.LEAVE_OUT))
    // L'utente chiude la striscia: non torna con un altro processo.
    again.dismiss(closed.id)
    assertThat(again.operations.value.map { it.op }).doesNotContain(closed.id)
    assertThat(reborn(l).operations.value.map { it.op }).doesNotContain(closed.id)
  }

  @Test
  fun sh2UnElementoFermoDalDiarioNonDiventaLavoroInCorsoNeSiRiprova() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_D.jpg", "IMG_E.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 2)
    val l = live(albums)
    // Un'operazione chiusa con un elemento da riprovare e uno la cui riga questa versione non sa leggere (scritta da un'app piu' nuova, I5).
    val closed = op(2)
    l.shared.diary.create(closed, listOf(failedItem(closed, 0, ids[0]), failedItem(closed, 1, ids[1])))
    l.shared.diary.finish(closed.id)
    sql(phone, "UPDATE album_share_item SET state = 'futuro', failure = NULL WHERE op_id = 'op-e-2' AND item_index = 1")
    val before = rows(phone)

    val again = reborn(l)
    // Non e' lavoro in corso: nessuna striscia "in corso" per un'operazione chiusa (con l'elemento fermo `stateOf` direbbe "in corso" per sempre).
    assertWithMessage("stati mostrati: ${again.operations.value}").that(again.operations.value.map { it.op }).doesNotContain(closed.id)
    // "Tocca per riprovare" non rifa niente di un'operazione con un elemento in viaggio senza biglietto.
    assertThat(again.retryRequest(closed.id)).isNull()
    // E nessun passo ha toccato le righe: ne' biglietti, ne' stati, ne' motivi.
    assertThat(rows(phone)).isEqualTo(before)
  }

  @Test
  fun sh2UnElementoFermoTieneOccupatoIlMediaPerIlRiprovaEPerIlPianoNuovo() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_F.jpg", "IMG_G.jpg", "IMG_H.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 3)
    val l = live(albums)
    // Un album condiviso vero (con il media 2): il piano per "Aggiungi" su un album attivo e' un piano vero.
    val seed = (l.shared.albums.create("Fermo", ShareLevel.ADD, listOf(ids[2])) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, seed)
    assertThat(drive(rig, phone, albums)).isTrue()
    // Un'operazione chiusa coi media 0 e 1 da riprovare; un'altra, aperta, ha il media 0 fermo (la sua riga non si legge piu': in viaggio senza biglietto).
    val closed = op(3).copy(album = seed.album)
    l.shared.diary.create(closed, listOf(failedItem(closed, 0, ids[0], ShareFailure.TRANSFER_FAILED), failedItem(closed, 1, ids[1], ShareFailure.TRANSFER_FAILED)))
    l.shared.diary.finish(closed.id)
    val open = op(4).copy(album = seed.album)
    l.shared.diary.create(
      open,
      listOf(ShareItemRecord(open.id, 0, ids[0], ShareItemAction.MOVE_IN, ShareItemState.PLANNED, mine = true, size = 100, transferKey = TransferKey("k-op-e-4:in:0"))),
    )
    sql(phone, "UPDATE album_share_item SET state = 'futuro' WHERE op_id = 'op-e-4'")

    val again = reborn(l)
    // Il media fermo non si riprova (per il diario e' ancora in viaggio): si riprova solo l'altro.
    val request = again.retryRequest(closed.id) as ShareRequest.AddToShared
    assertThat(request.media).containsExactly(ids[1])
    // E nemmeno un piano chiesto a mano lo riprende: un media e' in una sola operazione aperta.
    val outcome = again.plan(ShareRequest.AddToShared(seed.album, listOf(ids[0]), HiddenChoice.LEAVE_OUT))
    assertWithMessage("piano per il media fermo: $outcome").that(outcome).isNotEqualTo(SharePlanOutcome.Blocked(dev.pampa.mycloudgallery.core.albums.contract.ShareBlock.ALBUM_NOT_ACTIVE))
    assertWithMessage("piano per il media fermo: $outcome").that((outcome as? SharePlanOutcome.Ready)?.plan?.items.orEmpty()).isEmpty()
  }

  // --- Confine A/B, SC-1 ----------------------------------------------------------------------------------------------------------------

  private fun manualTitled(albums: AlbumsGraph, title: String): List<Album.Manual> =
    live(albums).albums.all.value.albums.filterIsInstance<Album.Manual>().filter { it.title == title }

  private suspend fun itemsOf(albums: AlbumsGraph, id: AlbumId): List<MediaId> {
    val content = withTimeout(Attese.TETTO) { live(albums).albums.content(id).first { it != null } }
    return idsOf(checkNotNull(content).items)
  }

  /** Un giro dell'indice come lo fa il lavoro (le copie dei chiusi, la potatura dei registri, le notifiche), con l'ora del NAS che passa. */
  private suspend fun indexRound(albums: AlbumsGraph, times: Int = 1) = repeat(times) {
    time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
    albums.syncRound(1.minutes)
  }

  @Test
  fun sc1ConLaCopiaPassaDaCopyKeptEIlGiroDopoNonScriveUnaSecondaCopiaNeRimetteGliElementiTolti() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_I.jpg", "IMG_J.jpg", "IMG_K.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 3)
    val plan = (live(albums).shared.albums.create("Da chiudere", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    withTimeout(Attese.TETTO) { live(albums).shared.albums.visible.first { list -> list.any { it.id == plan.album && it.count == 3 } } }

    // "Smetti di condividere" dal creatore: l'album personale nasce subito, con i tre elementi, senza aspettare il giro dell'indice.
    val shared = AppSharedAlbums(albums, facadeScope)
    assertThat(shared.close(plan.album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)).isEqualTo(CloseOutcome.Closed)
    val personal = checkNotNull(shared.keptAfterClose(plan.album)) { "l'album personale subito dopo la chiusura" }
    assertThat(itemsOf(albums, personal)).containsExactlyElementsIn(ids)
    // La scrittura e' passata da `copyKept`: il segno della copia c'e' (con i tre elementi) e dice che e' scritta.
    val mark = live(albums).shared.replica.marks.all().getValue(plan.album).copy
    assertWithMessage("segno della copia scritta").that(mark).isNotNull()
    assertThat(mark!!.items).isEqualTo(3)
    assertThat(manualTitled(albums, "Da chiudere")).hasSize(1)

    // L'utente toglie un elemento dall'album personale; poi passano due giri dell'indice: nessuna seconda copia, l'elemento tolto non torna.
    assertThat(live(albums).albums.remove(personal, listOf(ids[1]))).isEqualTo(EditOutcome.Done)
    assertThat(itemsOf(albums, personal)).containsExactly(ids[0], ids[2])
    indexRound(albums, times = 2)
    assertWithMessage("album personali dopo i giri dell'indice").that(manualTitled(albums, "Da chiudere").map { it.id }).containsExactly(personal)
    assertThat(itemsOf(albums, personal)).containsExactly(ids[0], ids[2])
    // Un riavvio del processo (un grafo nuovo sugli stessi dati) non la riscrive: il segno e' su disco.
    assertThat(live(albums).shared.replica.marks.all().getValue(plan.album).copy).isEqualTo(mark)
  }

  @Test
  fun sc1SenzaIlSegnoVistoAttivoIlCreatoreHaComunqueLAlbumPersonale() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_L.jpg", "IMG_M.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 2)
    val plan = (live(albums).shared.albums.create("Senza segno", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    assertThat(drive(rig, phone, albums)).isTrue()
    withTimeout(Attese.TETTO) { live(albums).shared.albums.visible.first { list -> list.any { it.id == plan.album && it.count == 2 } } }
    // I segni di R1 si perdono (un file che non si e' potuto scrivere, o messo da parte): la replica non ha piu' memoria di aver visto l'album attivo.
    live(albums).shared.replica.marks.update { it.clear() }

    val shared = AppSharedAlbums(albums, facadeScope)
    assertThat(shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    val personal = checkNotNull(shared.keptAfterClose(plan.album)) { "l'album personale del creatore" }
    assertThat(itemsOf(albums, personal)).containsExactlyElementsIn(ids)
    indexRound(albums, times = 2)
    assertThat(manualTitled(albums, "Senza segno").map { it.id }).containsExactly(personal)
  }

  @Test
  fun sc1UnaChiusuraMentreGliElementiSonoInViaggioNonPerdeNienteNeDuplica() = runBlocking<Unit> {
    val phone = phoneOnly()
    privatePhotos("IMG_N.jpg", "IMG_O.jpg")
    val (rig, albums) = opened(phone)
    val ids = libraryIds(rig, 2)
    val plan = (live(albums).shared.albums.create("In viaggio", ShareLevel.ADD, ids) as SharePlanOutcome.Ready).plan
    confirmAndStart(albums, plan)
    // L'album c'e' (definizione in coda, replica che lo vede attivo) ma gli spostamenti non sono ancora finiti: il creatore smette subito di condividere.
    val shared = AppSharedAlbums(albums, facadeScope)
    assertThat(shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    val personal = checkNotNull(shared.keptAfterClose(plan.album)) { "l'album personale del creatore" }
    // Gli spostamenti finiscono lo stesso (i file sono dei tuoi, la conferma e' stata data): l'operazione si chiude, nessun file si perde.
    assertWithMessage("l'operazione in viaggio finisce").that(drive(rig, phone, albums)).isTrue()
    indexRound(albums, times = 2)
    assertThat(libraryIds(rig, 2)).containsExactlyElementsIn(ids)
    assertThat(backend.children(TestNas.COMMON, "Album condivisi/In viaggio")).hasSize(2)
    // Una sola copia personale, e gli elementi sono quelli della prima scrittura (D31, A-3): gli arrivati dopo la chiusura non entrano nell'album personale.
    assertThat(manualTitled(albums, "In viaggio").map { it.id }).containsExactly(personal)
    assertThat(itemsOf(albums, personal)).isEmpty()
  }

  private fun sql(device: TestDevice, statement: String) {
    rigs.single().gallery.database.openHelper.writableDatabase.execSQL(statement)
  }

  /** Le righe del diario com'e' scritto adesso (stato, biglietto, motivo): per provare che nessun passo le ha toccate. */
  private fun rows(device: TestDevice): List<String> =
    rigs.single().gallery.database.openHelper.readableDatabase.query(
      "SELECT op_id, item_index, state, ticket, failure, wait FROM album_share_item ORDER BY op_id, item_index",
    ).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it).orEmpty() }) } }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
