package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Revisione B della Fase 7 (flusso della condivisione), sull'orchestratore vero e le porte finte (`ReferenceWorld`): ogni prova riproduce un difetto
 * delle revisioni (`docs/review/funzionale-7.md`, `docs/review/7-fase7.md`) ed e' rossa senza la sua correzione.
 * - SH-1: lo stesso piano dopo un'operazione finita con un problema riparte (prima: "gia' avviato" e niente).
 * - SH-2: "N da riprovare" resta dopo la morte del processo, "Riprova" fa un piano nuovo, "Chiudi" la toglie per sempre.
 * - SH-5: dopo un riavvio la definizione e il livello del piano non tornano in coda sopra una rinomina o una chiusura.
 * - I2: oltre 10.000 elementi la condivisione parte in lotti (un'operazione di core-nasops ne accetta 10.000).
 * - SG-1 e I4: con il cancello degli arrivi chiuso i media del telefono restano fuori con il motivo; con il guardiano fermo il piano lo dice.
 */
class RevisioneBShareTest {

  private val base = 1_767_225_600_000L

  private fun media(n: Int, place: MediaPlace, onPhone: Boolean = place == MediaPlace.PHONE_ONLY) =
    FakeMedia(FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, onPhone = onPhone, size = 1_000L + n)

  private class World(val ref: ReferenceWorld = ReferenceWorld()) {
    val marks: ShareProblemMarks = ShareProblemMarks.inMemory()
    var gates: ShareBackupGates = ShareBackupGates.OPEN
    var mover: AlbumMover? = null

    fun ports() = ref.ports().let { p ->
      ShareOrchestratorPorts(
        p.diary, p.facts, p.shared, p.allowed, mover ?: p.mover, p.uploader, p.tickets, p.publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks,
        backupGates = { gates }, problemMarks = marks,
      )
    }

    /** Un processo nuovo dell'orchestratore (stesso diario, stesso mondo): la ripresa dopo la morte del processo. */
    fun runner() = ShareOrchestrator(ports(), newNonce = { ref.nextNonce() })

    fun confirm(plan: SharePlan) = ShareConfirmationIssuer.issue(plan, ref.clocks.monotonicMs())

    suspend fun drain(r: ShareOrchestrator, rounds: Int = 6) {
      repeat(rounds) {
        r.resumeAll(1.minutes)
        ref.clocks.advance(6 * 60_000L)
      }
    }
  }

  private suspend fun ShareOrchestrator.ready(request: ShareRequest): SharePlan =
    (plan(request) as? SharePlanOutcome.Ready)?.plan ?: error("piano non pronto: ${plan(request)}")

  /** Un album condiviso di `test2` a "Gli altri aggiungono", con un mio media nell'area privata il cui spostamento fallisce. */
  private suspend fun failedAdd(w: World): Pair<AlbumId, ShareOrchestrator> {
    val album = w.ref.albumBy(AccountId("test2"))
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    w.ref.transfers.outcomes[FakeAlbumLibrary.id(1)] = TransferItemOutcome.FAILED
    val r = w.runner()
    val plan = r.ready(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))
    assertThat(r.start(plan, w.confirm(plan))).isInstanceOf(ShareStartOutcome.Started::class.java)
    w.drain(r)
    assertThat(w.ref.diary.allItems().single().failure).isEqualTo(ShareFailure.TRANSFER_FAILED)
    return album to r
  }

  // --- SH-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh1LoStessoAggiungiDopoUnFallimentoRiparte() = runBlocking<Unit> {
    val w = World()
    val (album, r) = failedAdd(w)
    w.ref.transfers.outcomes.clear()
    // Lo stesso "Aggiungi" (stesso album, stesso media): prima l'impronta era la stessa e la risposta "gia' avviato" senza niente in viaggio.
    val again = r.ready(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))
    val started = r.start(again, w.confirm(again))
    assertWithMessage("il secondo tentativo deve partire").that(started).isInstanceOf(ShareStartOutcome.Started::class.java)
    // Lo stesso piano (lo stesso tentativo) non parte due volte.
    assertThat(r.start(again, w.confirm(again))).isEqualTo(ShareStartOutcome.AlreadyStarted((started as ShareStartOutcome.Started).op))
    w.drain(r)
    assertThat(w.ref.transfers.started).hasSize(2)
    assertThat(w.ref.diary.items(started.op).single().state).isEqualTo(ShareItemState.DONE)
    assertThat(w.ref.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.COMMON)
  }

  // --- SH-2 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh2IProblemiRestanoDopoIlRiavvioERiprovaRiparte() = runBlocking<Unit> {
    val w = World()
    val (album, _) = failedAdd(w)
    val failedOp = w.ref.diary.allOps().single().id
    // La morte del processo: un orchestratore nuovo sullo stesso diario. Prima `operations` partiva vuoto e "N da riprovare" spariva.
    val reborn = w.runner()
    reborn.resumeAll(1.minutes)
    val partly = reborn.operations.value.filterIsInstance<ShareOpState.PartlyDone>().singleOrNull()
    assertWithMessage("la striscia dei problemi dopo il riavvio").that(partly?.op).isEqualTo(failedOp)
    assertThat(partly!!.failures).containsExactly(ShareFailure.TRANSFER_FAILED, 1)
    // "Riprova": la richiesta per gli elementi non andati (non parte niente da sola), poi il piano e la conferma di sempre.
    val request = reborn.retryRequest(failedOp)
    assertThat(request).isEqualTo(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1)), w.ref.diary.allOps().single().hidden))
    assertThat(w.ref.transfers.started).hasSize(1)
    w.ref.transfers.outcomes.clear()
    val plan = reborn.ready(request!!)
    assertThat(reborn.start(plan, w.confirm(plan))).isInstanceOf(ShareStartOutcome.Started::class.java)
    // In viaggio di nuovo: la striscia vecchia non c'e' piu'; arrivato, nemmeno (il media e' un elemento).
    assertThat(reborn.operations.value.filterIsInstance<ShareOpState.PartlyDone>()).isEmpty()
    w.drain(reborn)
    assertThat(reborn.operations.value.filterIsInstance<ShareOpState.PartlyDone>()).isEmpty()
    assertThat(reborn.retryRequest(failedOp)).isNull()
  }

  @Test
  fun sh2ChiudiTogliePerSempreLaStriscia() = runBlocking<Unit> {
    val w = World()
    failedAdd(w)
    val failedOp = w.ref.diary.allOps().single().id
    val r = w.runner()
    r.resumeAll(1.minutes)
    assertThat(r.operations.value.map { it.op }).contains(failedOp)
    r.dismiss(failedOp)
    assertThat(r.operations.value.map { it.op }).doesNotContain(failedOp)
    val reborn = w.runner()
    reborn.resumeAll(1.minutes)
    assertThat(reborn.operations.value.map { it.op }).doesNotContain(failedOp)
  }

  // --- SH-5 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh5DopoUnRiavvioLaDefinizioneNonSiRiaccodaSopraUnaRinomina() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    // Il trasferimento resta in corso: l'operazione e' aperta quando il processo muore.
    w.ref.transfers.autoComplete = false
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Prima", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    r.start(plan, w.confirm(plan))
    w.ref.clocks.advance(6 * 60_000L)
    w.ref.publisher.flush(plan.album, FlushReason.SYNC_ROUND)
    assertThat(w.ref.shared.album(plan.album)!!.title).isEqualTo("Prima")
    // Il creatore rinomina l'album e cambia livello mentre lo spostamento e' in viaggio.
    val folder = w.ref.shared.album(plan.album)!!.folder
    w.ref.publisher.stage(
      plan.album,
      listOf(SharedAlbumChange.Definition("Dopo", folder, SharedState.ACTIVE, AccountId("test"), plan.nonceHex!!, null), SharedAlbumChange.Level(ShareLevel.VIEW)),
    )
    // La morte del processo e la ripresa: prima la definizione e il livello del piano tornavano in coda sopra la rinomina.
    val reborn = w.runner()
    reborn.resumeAll(1.minutes)
    w.ref.clocks.advance(6 * 60_000L)
    w.ref.publisher.flush(plan.album, FlushReason.SYNC_ROUND)
    val published = w.ref.publisher.published(plan.album)
    assertWithMessage("titolo pubblicato dopo il riavvio").that((published["definition"] as SharedAlbumChange.Definition).title).isEqualTo("Dopo")
    assertWithMessage("livello pubblicato dopo il riavvio").that((published["level"] as SharedAlbumChange.Level).level).isEqualTo(ShareLevel.VIEW)
    w.ref.transfers.completeAll()
    w.drain(reborn)
    assertThat(w.ref.shared.album(plan.album)!!.title).isEqualTo("Dopo")
  }

  // --- I2: oltre 10.000 elementi --------------------------------------------------------------------------------------------------------

  @Test
  fun i2OltreDiecimilaElementiLaCondivisioneParteInLotti() = runBlocking<Unit> {
    val w = World()
    val count = ShareRules.MAX_ITEMS_PER_TRANSFER + 5
    w.ref.library.put(*Array(count) { media(it + 1, MediaPlace.MY_PRIVATE) })
    val album = w.ref.albumBy(AccountId("test2"))
    // Un mover che conta soltanto (quello finto cerca ogni file fra tutti: con 10.000 file e' lento e non serve qui).
    val sizes = ArrayList<Int>()
    w.mover = object : AlbumMover {
      override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart {
        sizes += items.size
        return TransferStart.Started(TransferTicket("t-${sizes.size}"))
      }
    }
    val r = w.runner()
    val plan = r.ready(ShareRequest.AddToShared(album, (1..count).map { FakeAlbumLibrary.id(it) }))
    assertThat(plan.items.map { it.action }.toSet()).containsExactly(ShareItemAction.MOVE_IN)
    r.start(plan, w.confirm(plan))
    assertWithMessage("un trasferimento oltre il tetto di core-nasops: $sizes").that(sizes.all { it <= ShareRules.MAX_ITEMS_PER_TRANSFER }).isTrue()
    assertThat(sizes.sum()).isEqualTo(count)
    assertThat(w.ref.diary.allItems().mapNotNull { it.transferKey }.toSet()).hasSize(2)
  }

  // --- SH-3 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh3UnFileTOltoDallAlbumNonEUnFileDelPc() = runBlocking<Unit> {
    val w = World()
    val album = w.ref.albumBy(AccountId("test2"))
    val folder = w.ref.sharedAlbums.folder(album)!!
    // Nella cartella dell'album: un file messo dal PC e uno che era un elemento, tolto con "Rimuovi dall'album" (D39: il file resta).
    val fromPc = media(1, MediaPlace.COMMON).copy(path = "${folder.path.value}/dal-pc.jpg")
    val removed = media(2, MediaPlace.COMMON).copy(path = "${folder.path.value}/tolto.jpg")
    w.ref.library.put(fromPc, removed)
    val ports = w.ports().let { p ->
      ShareOrchestratorPorts(
        p.diary, p.facts, p.shared, p.allowed, p.mover, p.uploader, p.tickets, p.publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks,
        folderContents = { setOf(fromPc.id, removed.id) },
      )
    }
    val visible: suspend (Collection<dev.pampa.mycloudgallery.core.model.MediaId>) -> Set<dev.pampa.mycloudgallery.core.model.MediaId> = { ids -> ids.toSet() }
    // Senza la memoria dell'album entrambi sembrano "del PC" (prima: l'avviso li contava tutti e due).
    assertThat(FolderAdoptions(ports, visible = visible).pending(album)).containsExactly(fromPc.id, removed.id)
    // Con la memoria (l'id pubblicato, o la sola chiave di contenuto se l'id e' un altro): solo quello del PC.
    assertThat(FolderAdoptions(ports, { AlbumHistory(setOf(removed.id), emptySet()) }, visible).pending(album)).containsExactly(fromPc.id)
    assertThat(FolderAdoptions(ports, { AlbumHistory(emptySet(), setOf(removed.contentKey)) }, visible).pending(album)).containsExactly(fromPc.id)
  }

  // --- SG-1 e I4 ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sg1ConGliArriviChiusiIlTelefonoRestaFuoriEIlGuardianoFermoSiDice() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.PHONE_ONLY), media(2, MediaPlace.MY_PRIVATE, onPhone = false), media(3, MediaPlace.MY_PRIVATE, onPhone = true))
    val ids = (1..3).map { FakeAlbumLibrary.id(it) }
    w.gates = ShareBackupGates(uploads = false, safeCopies = false)
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Release", ShareLevel.ADD, ids))
    assertThat(plan.items.map { it.action }).containsExactly(ShareItemAction.MOVE_IN, ShareItemAction.MOVE_IN)
    assertThat(plan.excluded).containsExactly(ShareExclusion.UPLOAD_BLOCKED, 1)
    // Il guardiano servirebbe solo al media che il telefono non ha piu' (D28).
    assertThat(plan.safeCopyBlocked).isEqualTo(1)
    // Con i cancelli aperti tutto come prima, e l'impronta e' un'altra.
    w.gates = ShareBackupGates.OPEN
    val open = r.ready(ShareRequest.NewAlbum("Release", ShareLevel.ADD, ids))
    assertThat(open.items.map { it.action }).contains(ShareItemAction.UPLOAD)
    assertThat(open.safeCopyBlocked).isEqualTo(0)
    assertThat(open.hash).isNotEqualTo(plan.hash)
  }
}
