package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * L'orchestratore sulle porte finte (P0: `ReferenceWorld`), riga per riga del §2.5 e del §2.6 dove conta il dettaglio delle porte: il piano
 * (D12, Q3, D39, D31, E-SH22, i tetti), il diario prima di ogni azione (§2.5 passo 3), le chiavi prima delle porte, il guardiano (D28), il livello
 * cambiato a meta' (E-SH13), "Riporta" (D31), l'annullamento, la replica non ancora pronta, "Condividi" su un album personale.
 */
class ShareOrchestratorTest {

  private val base = 1_767_225_600_000L

  private fun media(n: Int, place: MediaPlace, onPhone: Boolean = place == MediaPlace.PHONE_ONLY, hidden: Boolean = false, mine: Boolean = place != MediaPlace.OTHERS_PRIVATE) =
    FakeMedia(FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, onPhone = onPhone, hidden = hidden, mine = mine, size = 1_000L * n)

  private class World(val ref: ReferenceWorld = ReferenceWorld()) {
    var personal: suspend (AlbumId) -> SharedFromPersonal? = { null }
    val linked = mutableListOf<Pair<AlbumId, AlbumId>>()
    var shared: SharedAlbumFacts = ref.sharedAlbums
    var mover: AlbumMover = ref.transfers
    var uploader: AlbumUploader = ref.transfers

    fun ports() = ref.ports().let { p ->
      ShareOrchestratorPorts(
        p.diary, p.facts, shared, p.allowed, mover, uploader, p.tickets, p.publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks,
        personal = personal, linkPersonal = { a, b -> linked += a to b },
      )
    }

    fun runner() = ShareOrchestrator(ports(), newNonce = { ref.nextNonce() })

    fun confirm(plan: SharePlan) = ShareConfirmationIssuer.issue(plan, ref.clocks.monotonicMs())

    suspend fun drain(r: ShareOrchestrator, rounds: Int = 6) {
      repeat(rounds) {
        r.resumeAll(1.minutes)
        ref.clocks.advance(6 * 60_000L)
      }
    }
  }

  private suspend fun ShareOrchestrator.ready(request: ShareRequest): SharePlan = (plan(request) as SharePlanOutcome.Ready).plan

  // --- §2.5 passo 1: il piano -----------------------------------------------------------------------------------------------------------

  @Test
  fun ilPianoSoloDalCatalogoLocale() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(
      media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.PHONE_ONLY), media(3, MediaPlace.COMMON), media(4, MediaPlace.OTHERS_PRIVATE),
      media(5, MediaPlace.MY_PRIVATE, hidden = true), media(6, MediaPlace.PHONE_ONLY, hidden = true),
    )
    val ids = (1..7).map { FakeAlbumLibrary.id(it) }
    val r = w.runner()
    val out = r.ready(ShareRequest.NewAlbum("Estate", ShareLevel.ADD, ids + ids[0]))
    assertThat(out.items.map { it.media to it.action }).containsExactly(
      ids[0] to ShareItemAction.MOVE_IN, ids[1] to ShareItemAction.UPLOAD, ids[2] to ShareItemAction.REFERENCE,
    ).inOrder()
    assertThat(out.excluded).containsExactly(ShareExclusion.OTHERS_AREA, 1, ShareExclusion.HIDDEN_LEFT_OUT, 2, ShareExclusion.UNAVAILABLE, 1)
    assertThat(out.hiddenLeftOut).isEqualTo(2)
    // Con «Condividi anche i nascosti» entrano e si contano (E-SH9); l'impronta cambia.
    val withHidden = r.ready(ShareRequest.NewAlbum("Estate", ShareLevel.ADD, ids, HiddenChoice.SHARE_TOO))
    assertThat(withHidden.hiddenIncluded).isEqualTo(2)
    assertThat(withHidden.hash).isNotEqualTo(out.hash)
    // Nessun media condivisibile: il motivo per ognuno.
    assertThat(r.plan(ShareRequest.NewAlbum("Niente", ShareLevel.ADD, listOf(ids[3], ids[4]))))
      .isEqualTo(SharePlanOutcome.NothingToShare(mapOf(ShareExclusion.OTHERS_AREA to 1, ShareExclusion.HIDDEN_LEFT_OUT to 1)))
    assertThat(w.ref.diary.allOps()).isEmpty()
    assertThat(w.ref.transfers.started).isEmpty()
  }

  @Test
  fun oltreIlTettoDegliElementiIlPianoEBloccato() = runBlocking<Unit> {
    val w = World()
    val album = w.ref.albumBy(AccountId("test2"))
    val many = (1..SharedAlbumLimits.MAX_ITEMS).associate { FakeAlbumLibrary.id(100_000 + it) to AccountId("test2") }
    val real = w.ref.sharedAlbums
    w.shared = object : SharedAlbumFacts {
      override suspend fun folder(album: AlbumId): ShareFolder? = real.folder(album)

      override suspend fun members(album: AlbumId): Map<MediaId, AccountId> = many

      override suspend fun state(album: AlbumId): SharedState? = real.state(album)
    }
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    assertThat(w.runner().plan(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.TOO_MANY_ITEMS))
  }

  @Test
  fun aggiungereAUnAlbumDegliAltriSecondoLaTabella() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    val viewOnly = w.ref.albumBy(AccountId("test2"), "Solo guardare", ShareLevel.VIEW)
    assertThat(w.runner().plan(ShareRequest.AddToShared(viewOnly, listOf(FakeAlbumLibrary.id(1))))).isEqualTo(SharePlanOutcome.NotAllowed(AlbumOp.ADD_MEDIA))
    val open = w.ref.albumBy(AccountId("test2"), "Aperto", ShareLevel.ADD)
    w.ref.shared.apply(open, AccountId("test2"), listOf(SharedAlbumChange.Definition("Aperto", SharePath.of("Album condivisi", "Aperto"), SharedState.CLOSED, AccountId("test2"), w.ref.shared.album(open)!!.nonceHex, dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose.REMOVE)))
    assertThat(w.runner().plan(ShareRequest.AddToShared(open, listOf(FakeAlbumLibrary.id(1))))).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE))
    assertThat(w.ref.transfers.started).isEmpty()
  }

  /** Un media che una mia operazione sta ancora portando non entra in un altro piano (ne' un secondo album, ne' "Riporta"). */
  @Test
  fun unMediaInViaggioNonEntraInUnAltroPiano() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE))
    w.ref.transfers.autoComplete = false
    val r = w.runner()
    val first = r.ready(ShareRequest.NewAlbum("Primo", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    r.start(first, w.confirm(first))
    val second = r.ready(ShareRequest.NewAlbum("Secondo", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    assertThat(second.items.map { it.media }).containsExactly(FakeAlbumLibrary.id(2))
    assertThat(second.excluded).containsExactly(ShareExclusion.UNAVAILABLE, 1)
  }

  // --- §2.5 passi 3-4: il diario prima di ogni azione, la chiave prima della porta --------------------------------------------------------

  @Test
  fun ilDiarioEPrimaDiOgniAzioneELaChiavePrimaDellaPorta() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.PHONE_ONLY))
    val seen = mutableListOf<String>()
    val inner = w.ref.transfers
    w.mover = AlbumMover { key, items, dest, leave, c ->
      val records = w.ref.diary.allItems()
      seen += "move: ${records.size} ${records.all { it.state == ShareItemState.PLANNED }} ${records.any { it.transferKey == key }}"
      assertThat(w.ref.origins.origins.keys).contains(FakeAlbumLibrary.id(1))
      inner.move(key, items, dest, leave, c)
    }
    w.uploader = AlbumUploader { key, media, dest, c ->
      seen += "upload: ${w.ref.diary.allItems().any { it.transferKey == key }}"
      inner.upload(key, media, dest, c)
    }
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Diario", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    r.start(plan, w.confirm(plan))
    assertThat(seen).containsExactly("move: 2 true true", "upload: true")
    // La definizione dell'album nuovo e' in coda (attiva, senza elementi) prima di qualunque voce.
    assertThat(w.ref.publisher.pending.value).isEqualTo(2)
  }

  // --- §2.5 passo 7: il guardiano (D28) -------------------------------------------------------------------------------------------------

  @Test
  fun ilGuardianoSoloPerIMieiSpostatiSenzaCopiaSulTelefono() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(
      media(1, MediaPlace.MY_PRIVATE, onPhone = false), media(2, MediaPlace.MY_PRIVATE, onPhone = true), media(3, MediaPlace.PHONE_ONLY),
      media(4, MediaPlace.COMMON, mine = true), media(5, MediaPlace.COMMON, mine = false),
    )
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Guardiano", ShareLevel.ADD, (1..5).map { FakeAlbumLibrary.id(it) }))
    r.start(plan, w.confirm(plan))
    w.drain(r)
    assertThat(w.ref.diary.allItems().map { it.state }.toSet()).containsExactly(ShareItemState.DONE)
    assertThat(w.ref.safeCopies.requested).containsExactly(FakeAlbumLibrary.id(1))
    assertThat(w.ref.diary.allItems().single { it.media == FakeAlbumLibrary.id(1) }.safeCopyRequested).isTrue()
  }

  /** Un guardiano che non registra la richiesta non fa chiudere l'elemento: si riprova finche' la richiesta e' registrata. */
  @Test
  fun unaRichiestaDelGuardianoNonRegistrataSiRiprova() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    w.ref.safeCopies.failNext = true
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Riprova", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    r.start(plan, w.confirm(plan))
    w.drain(r)
    assertThat(w.ref.safeCopies.requested).containsExactly(FakeAlbumLibrary.id(1))
    assertThat(w.ref.diary.allItems().single().state).isEqualTo(ShareItemState.DONE)
  }

  // --- E-SH13 e "Riporta" (D31) -------------------------------------------------------------------------------------------------------

  /** E-SH13: il livello cambia a meta'; il file resta nell'area comune, nessuna voce; "Riporta" lo offre e lo riporta, senza lapide. */
  @Test
  fun eSh13IlFileRimastoSiRiporta() = runBlocking<Unit> {
    val w = World()
    val album = w.ref.albumBy(AccountId("test2"))
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    w.ref.transfers.autoComplete = false
    val r = w.runner()
    val plan = r.ready(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))
    r.start(plan, w.confirm(plan))
    w.ref.shared.apply(album, AccountId("test2"), listOf(SharedAlbumChange.Level(ShareLevel.VIEW)))
    w.ref.transfers.completeAll()
    w.drain(r)
    assertThat(w.ref.diary.allItems().single().failure).isEqualTo(ShareFailure.NOT_ALLOWED)
    val back = r.ready(ShareRequest.BringBack(album, listOf(FakeAlbumLibrary.id(1))))
    assertThat(back.items.single().action).isEqualTo(ShareItemAction.MOVE_OUT)
    w.ref.transfers.autoComplete = true
    r.start(back, w.confirm(back))
    w.drain(r)
    assertThat(w.ref.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(w.ref.diary.allItems().last().stageSeq).isNull()
    assertThat(w.ref.shared.album(album)!!.items).isEmpty()
  }

  /** D31: "Riporta" non si offre per un media senza cartella d'origine, non mio, non nell'area comune, o aggiunto da un altro. */
  @Test
  fun riportaSoloConLaCartellaDOrigineESoloIMiei() = runBlocking<Unit> {
    val w = World()
    val album = w.ref.albumBy(AccountId("test"))
    w.ref.library.put(media(1, MediaPlace.COMMON), media(2, MediaPlace.COMMON), media(3, MediaPlace.MY_PRIVATE), media(4, MediaPlace.COMMON))
    val ids = (1..4).map { FakeAlbumLibrary.id(it) }
    w.ref.origins.record(ids[1], ShareFolder("test", SharePath.of("Foto")))
    w.ref.origins.record(ids[3], ShareFolder("test", SharePath.of("Foto")))
    // Il 4 e' un elemento aggiunto da un altro account (anche se il file e' "mio" per il catalogo): non lo riporto io.
    val f4 = w.ref.library[ids[3]]!!
    w.ref.shared.apply(album, AccountId("test2"), listOf(SharedAlbumChange.Item(f4.id, null, f4.contentKey, SharePath.parse(f4.path!!), 1, AccountId("test2"))))
    val plan = w.runner().ready(ShareRequest.BringBack(album, ids + FakeAlbumLibrary.id(9)))
    assertThat(plan.items.map { it.media }).containsExactly(ids[1])
    assertThat(plan.excluded).containsExactly(ShareExclusion.NOT_MINE, 2, ShareExclusion.NOT_IN_COMMON, 1, ShareExclusion.UNAVAILABLE, 1)
    // Il foglio di chiusura offre gli stessi.
    assertThat(BringBackCandidates(w.ports()).among(album, ids)).containsExactly(ids[1])
  }

  /** D31: "Riporta" verso la cartella d'origine; se l'area privata e' cambiata, in `Backup/<dispositivo>/AAAA/MM/` di adesso. */
  @Test
  fun riportaVersoLaCartellaDOrigineOBackup() {
    val origin = ShareFolder("test", SharePath.of("Viaggi", "Oslo"))
    assertThat(SharePlanner.bringBackFolder(origin, "test", "Backup/telefono", 1_767_225_600_000L)).isEqualTo(origin)
    val moved = SharePlanner.bringBackFolder(origin, "nuova", "Backup/telefono", 1_767_225_600_000L)
    assertThat(moved).isEqualTo(ShareFolder("nuova", SharePath.of("Backup", "telefono", "2026", "01")))
    assertThat(SharePlanner.bringBackFolder(origin, "nuova", "Backup/telefono", null)).isEqualTo(ShareFolder("nuova", SharePath.of("Backup", "telefono")))
  }

  // --- Annullamento, replica non pronta, "Condividi" un album personale -----------------------------------------------------------------

  @Test
  fun annullareFermaCioCheNonEPartitoENienteResteAMeta() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.PHONE_ONLY), media(3, MediaPlace.COMMON))
    w.ref.transfers.autoComplete = false
    w.ref.transfers.refuseNext = dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal.NAS_UNREACHABLE
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Annulla", ShareLevel.ADD, (1..3).map { FakeAlbumLibrary.id(it) }))
    val op = (r.start(plan, w.confirm(plan)) as ShareStartOutcome.Started).op
    r.cancel(op)
    w.drain(r)
    val items = w.ref.diary.allItems().associateBy { it.media }
    // Lo spostamento non era partito (rifiutato): annullato; il caricamento era in corso: lo ferma la sua porta (nessun file a meta').
    assertThat(items.getValue(FakeAlbumLibrary.id(1)).state).isEqualTo(ShareItemState.CANCELLED)
    assertThat(items.getValue(FakeAlbumLibrary.id(2)).state).isEqualTo(ShareItemState.CANCELLED)
    assertThat(w.ref.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(w.ref.library[FakeAlbumLibrary.id(2)]!!.place).isEqualTo(MediaPlace.PHONE_ONLY)
  }

  /** Appena ripartito il processo la replica non conosce ancora l'album: l'elemento aspetta, non diventa "non permesso". */
  @Test
  fun conLaReplicaNonProntaSiAspetta() = runBlocking<Unit> {
    val w = World()
    val album = w.ref.albumBy(AccountId("test2"))
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE))
    val r = w.runner()
    val plan = r.ready(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))
    r.start(plan, w.confirm(plan))
    val real = w.ref.sharedAlbums
    var ready = false
    w.shared = object : SharedAlbumFacts {
      override suspend fun folder(album: AlbumId): ShareFolder? = real.folder(album)

      override suspend fun members(album: AlbumId): Map<MediaId, AccountId> = real.members(album)

      override suspend fun state(album: AlbumId): SharedState? = if (ready) real.state(album) else null
    }
    val after = w.runner()
    w.drain(after, rounds = 2)
    assertThat(w.ref.diary.allItems().single().state).isEqualTo(ShareItemState.IN_COMMON)
    assertThat(w.ref.diary.allItems().single().wait).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.ShareWait.RULES)
    ready = true
    w.drain(after)
    assertThat(w.ref.diary.allItems().single().state).isEqualTo(ShareItemState.DONE)
  }

  @Test
  fun condividereUnAlbumPersonaleCreaUnAlbumConUnIdSuoECollegato() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE, hidden = true))
    val personalId = AlbumId("11111111-2222-4333-8444-555555555555")
    w.personal = { id -> if (id == personalId) SharedFromPersonal("Mare 2026", listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))) else null }
    val r = w.runner()
    val plan = r.ready(ShareRequest.FromPersonal(personalId, ShareLevel.ADD))
    assertThat(plan.title).isEqualTo("Mare 2026")
    assertThat(plan.album).isNotEqualTo(personalId)
    assertThat(plan.items.map { it.media }).containsExactly(FakeAlbumLibrary.id(1))
    assertThat(plan.hiddenLeftOut).isEqualTo(1)
    r.start(plan, w.confirm(plan))
    assertThat(w.linked).containsExactly(personalId to plan.album)
    w.drain(r)
    assertThat(w.ref.shared.album(plan.album)!!.title).isEqualTo("Mare 2026")
    assertThat(w.runner().plan(ShareRequest.FromPersonal(AlbumId("11111111-2222-4333-8444-555555555556"), ShareLevel.ADD)))
      .isEqualTo(SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE))
  }

  /** E-SH18 sulle porte finte: piu' esiti per file nello stesso trasferimento, ognuno al suo elemento (nell'ordine della richiesta). */
  @Test
  fun gliEsitiPerFileVannoAlLoroElemento() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE), media(3, MediaPlace.MY_PRIVATE), media(4, MediaPlace.MY_PRIVATE))
    w.ref.transfers.outcomes[FakeAlbumLibrary.id(2)] = TransferItemOutcome.SOURCE_CHANGED
    w.ref.transfers.outcomes[FakeAlbumLibrary.id(3)] = TransferItemOutcome.SOURCE_GONE
    w.ref.transfers.outcomes[FakeAlbumLibrary.id(4)] = TransferItemOutcome.FAILED
    val r = w.runner()
    val plan = r.ready(ShareRequest.NewAlbum("Esiti", ShareLevel.ADD, (1..4).map { FakeAlbumLibrary.id(it) }))
    r.start(plan, w.confirm(plan))
    w.drain(r)
    val byMedia = w.ref.diary.allItems().associate { it.media to (it.failure ?: it.state) }
    assertThat(byMedia).containsExactly(
      FakeAlbumLibrary.id(1), ShareItemState.DONE, FakeAlbumLibrary.id(2), ShareFailure.SOURCE_CHANGED,
      FakeAlbumLibrary.id(3), ShareFailure.SOURCE_GONE, FakeAlbumLibrary.id(4), ShareFailure.TRANSFER_FAILED,
    )
    assertThat(w.ref.shared.album(plan.album)!!.items.keys).containsExactly(FakeAlbumLibrary.id(1))
  }

  // --- E-SH22: le cartelle degli album ----------------------------------------------------------------------------------------------------

  @Test
  fun eSh22NomiDelleCartelle() {
    assertThat(AlbumFolderNames.nameFor("Oslo 2026")).isEqualTo("Oslo 2026")
    assertThat(AlbumFolderNames.nameFor("a/b\\c:d*e?f\"g<h>i|j")).isEqualTo("a_b_c_d_e_f_g_h_i_j")
    assertThat(AlbumFolderNames.nameFor("Fine.. ")).isEqualTo("Fine")
    assertThat(AlbumFolderNames.nameFor("CON")).isEqualTo("CON_")
    assertThat(AlbumFolderNames.nameFor("lpt1.foto")).isEqualTo("lpt1_.foto")
    assertThat(SharePath.isValidComponent(AlbumFolderNames.nameFor("COM3 .gita"))).isTrue()
    assertThat(AlbumFolderNames.nameFor("...")).isEqualTo("Album")
    val long = "😀".repeat(70)
    val name = AlbumFolderNames.nameFor(long)
    assertThat(name.codePointCount(0, name.length)).isAtMost(64)
    assertThat(name.toByteArray(Charsets.UTF_8).size).isAtMost(200)
    assertThat(SharePath.isValidComponent(name)).isTrue()
    val used = setOf(SharePath.of("Album condivisi", "Oslo"), SharePath.of("Album condivisi", "oslo (2)"))
    assertThat(AlbumFolderNames.folderFor("Oslo", used).value).isEqualTo("Album condivisi/Oslo (3)")
    assertThat(AlbumFolderNames.folderFor("Oslo", emptySet()).value).isEqualTo("Album condivisi/Oslo")
  }

  /** Un video conta come video, e i byte di un riferimento non chiedono spazio (non si spostano). */
  @Test
  fun iConteggiDelFoglio() = runBlocking<Unit> {
    val w = World()
    w.ref.library.put(media(1, MediaPlace.MY_PRIVATE).copy(kind = MediaKind.VIDEO), media(2, MediaPlace.COMMON))
    val plan = w.runner().ready(ShareRequest.NewAlbum("Conti", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    assertThat(plan.photos to plan.videos).isEqualTo(1 to 1)
    assertThat(plan.bytes).isEqualTo(1_000L)
    assertThat(plan.requiredBytes).isEqualTo(1_000L + (1L shl 30))
    val onlyReference = w.runner().ready(ShareRequest.NewAlbum("Riferimento", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(2))))
    assertWithMessage("un riferimento non chiede spazio").that(onlyReference.requiredBytes).isEqualTo(0L)
  }
}
