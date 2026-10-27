package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Revisione E, confine A/B (SH-2 e I5): la striscia "N da riprovare" e il tocco "Riprova" di B leggono le operazioni chiuse dal diario; il pacchetto A ha
 * introdotto l'elemento **fermo** (una riga del diario che questa versione non sa leggere: in viaggio, senza biglietto, mai terminale). Qui la regola, sul
 * diario in memoria (che sa rappresentare lo stesso elemento); la prova sul diario vero di Room e' `RevisioneEConfiniAppTest` in `:app`.
 */
class RevisioneEShareTest {

  private val base = 1_767_225_600_000L

  private fun media(n: Int) = FakeMedia(FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = MediaPlace.MY_PRIVATE, onPhone = false, size = 1_000L + n)

  private fun op(n: Int, album: AlbumId) = ShareOpRecord(
    id = ShareOpId("op-e-$n"), album = album, kind = SharePlanKind.SHARE, planHash = SharePlanHash("%064x".format(40 + n)), title = "Prova", level = null,
    nonceHex = null, folder = null, hidden = HiddenChoice.LEAVE_OUT,
  )

  private fun item(op: ShareOpRecord, index: Int, media: Int, state: ShareItemState, failure: ShareFailure? = null) =
    ShareItemRecord(op.id, index, FakeAlbumLibrary.id(media), ShareItemAction.MOVE_IN, state, mine = true, size = 100, transferKey = TransferKey("k-${op.id.value}:in:0"), failure = failure)

  private fun world(): Pair<ReferenceWorld, AlbumId> {
    val ref = ReferenceWorld()
    val album = ref.albumBy(AccountId("test2"))
    listOf(1, 2, 3).forEach { ref.library.put(media(it)) }
    return ref to album
  }

  private fun runner(ref: ReferenceWorld): ShareOrchestrator {
    val ports = ref.ports().let { p ->
      ShareOrchestratorPorts(
        p.diary, p.facts, p.shared, p.allowed, p.mover, p.uploader, p.tickets, p.publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks,
        problemMarks = ShareProblemMarks.inMemory(),
      )
    }
    return ShareOrchestrator(ports, newNonce = { ref.nextNonce() })
  }

  /** Un'operazione chiusa con un elemento fermo non e' lavoro in corso: `stateOf` direbbe "in corso" per sempre, e "Riprova" non la tocca. */
  @Test
  fun unOperazioneChiusaConUnElementoFermoNonSiMostraComeLavoroInCorso() = runBlocking<Unit> {
    val (ref, album) = world()
    val closed = op(1, album)
    // L'elemento 1 e' fermo (la sua riga non si legge piu'): in viaggio, senza biglietto. L'elemento 0 e' da riprovare.
    ref.diary.create(closed, listOf(item(closed, 0, 1, ShareItemState.FAILED, ShareFailure.NOT_STARTED), item(closed, 1, 2, ShareItemState.TRANSFERRING)))
    ref.diary.finish(closed.id)
    val before = ref.diary.items(closed.id)

    val r = runner(ref)
    r.resumeAll(1.minutes)
    assertWithMessage("stati mostrati: ${r.operations.value}").that(r.operations.value.map { it.op }).doesNotContain(closed.id)
    assertThat(r.retryRequest(closed.id)).isNull()
    // Nessun passo ha toccato l'elemento fermo ne' quello da riprovare.
    assertThat(ref.diary.items(closed.id)).isEqualTo(before)
    assertThat(ref.transfers.started).isEmpty()
  }

  /** Un'operazione chiusa senza elementi fermi si mostra come prima: "N da riprovare" con il motivo (la regola nuova non nasconde le strisce vere). */
  @Test
  fun unOperazioneChiusaConElementiDaRiprovareSiMostraComeDaRiprovare() = runBlocking<Unit> {
    val (ref, album) = world()
    val closed = op(2, album)
    ref.diary.create(closed, listOf(item(closed, 0, 1, ShareItemState.FAILED, ShareFailure.TRANSFER_FAILED), item(closed, 1, 2, ShareItemState.DONE)))
    ref.diary.finish(closed.id)
    val r = runner(ref)
    r.resumeAll(1.minutes)
    val strip = r.operations.value.single { it.op == closed.id }
    assertThat(strip).isInstanceOf(ShareOpState.PartlyDone::class.java)
    assertThat((strip as ShareOpState.PartlyDone).failures).containsExactly(ShareFailure.TRANSFER_FAILED, 1)
    assertThat(r.retryRequest(closed.id)).isEqualTo(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1)), HiddenChoice.LEAVE_OUT))
  }

  /** Il media di un elemento fermo di un'operazione aperta non si riprova: un media e' in una sola operazione aperta. */
  @Test
  fun ilMediaDiUnElementoFermoNonSiRiprova() = runBlocking<Unit> {
    val (ref, album) = world()
    val closed = op(3, album)
    ref.diary.create(closed, listOf(item(closed, 0, 1, ShareItemState.FAILED, ShareFailure.TRANSFER_FAILED), item(closed, 1, 3, ShareItemState.FAILED, ShareFailure.TRANSFER_FAILED)))
    ref.diary.finish(closed.id)
    val open = op(4, album)
    ref.diary.create(open, listOf(item(open, 0, 1, ShareItemState.TRANSFERRING)))

    val r = runner(ref)
    r.resumeAll(1.minutes)
    val request = r.retryRequest(closed.id) as ShareRequest.AddToShared
    assertThat(request.media).containsExactly(FakeAlbumLibrary.id(3))
    // L'operazione aperta con l'elemento fermo resta aperta, com'e'.
    assertThat(ref.diary.openOps().map { it.id }).containsExactly(open.id)
    assertThat(ref.diary.items(open.id).single().state).isEqualTo(ShareItemState.TRANSFERRING)
    assertThat(ref.diary.items(open.id).single().ticket).isNull()
  }
}
