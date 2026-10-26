package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.share.FakeNasTarget
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il verificatore ha i denti, invariante per invariante, con mondi fatti a mano (come `ChaosVerifierTest` di core-nasops): un mondo sano e finito
 * passa; poi si manomette UNA cosa (un byte, un file in piu', un file in meno, una voce senza il suo file, un'operazione senza conferma...) e il
 * verificatore deve dire l'invariante giusto. Senza i colpi, senza il motore sotto stress: solo il giudice.
 */
class ShareChaosVerifierTest {

  private class World(val bench: ShareBench) {
    val book = ConfirmationBook()
    val observer = ShareChaosObserver(book).also { it.bench = bench }
    val stats = ShareChaosStats()
    val verifier = ShareChaosVerifier(bench, observer, stats)
    var album: AlbumId? = null
    var media: List<MediaId> = emptyList()
  }

  /** Un album condiviso nuovo con tre media, finito e sano. */
  private fun healthy(block: suspend (World) -> Unit) = runBlocking {
    ShareBench(::FakeNasTarget).use { bench ->
      val w = World(bench)
      w.media = listOf(bench.privateMedia(1, onPhone = true), bench.privateMedia(2, folder = "Viaggi"), bench.privateMedia(3))
      val plan = bench.plan(ShareRequest.NewAlbum("Sano", ShareLevel.ADD, w.media))
      bench.orchestrator.start(plan, w.book.issue(bench, plan))
      w.album = plan.album
      bench.drain()
      block(w)
    }
  }

  private fun invariants(violations: List<Violation>): Set<ShareInvariant> = violations.map { it.invariant }.toSet()

  private fun commonFileOf(w: World, id: MediaId): String = w.bench.library[id]!!.path!!

  @Test
  fun unMondoSanoEFinitoPassaATuttiGliInvarianti() = healthy { w ->
    assertWithMessage("a meta'").that(w.verifier.check("prova", final = false)).isEmpty()
    assertWithMessage("a lavoro finito").that(w.verifier.check("prova", final = true)).isEmpty()
  }

  @Test
  fun unFileVisibileConAltriByteEUnFileAMeta() = healthy { w ->
    w.bench.target.put(ShareNasTarget.COMMON, "Album condivisi/Sano/mezzo.jpg", ByteArray(33) { 9 })
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun unTemporaneoCheNessunDiarioCitaEUnFileAMeta() = healthy { w ->
    w.bench.target.put(ShareNasTarget.COMMON, ".mycloudgallery/tmp/${ShareNasTarget.DEVICE}-op-orfano-0", ByteArray(5))
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun unFileDellAppFuoriPostoEUnaViolazione() = healthy { w ->
    w.bench.target.put(ShareNasTarget.PRIVATE, ".mycloudgallery/altro/x", ByteArray(5))
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun lStessoMediaDueVolteNellAreaComuneEUnDoppione() = healthy { w ->
    val bytes = w.bench.originals[w.media[0]]!!
    w.bench.target.put(ShareNasTarget.COMMON, "Album condivisi/Sano/copia.jpg", bytes)
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_DUPLICATE)
  }

  @Test
  fun unMediaSenzaNessunaCopiaEUnaPerdita() = healthy { w ->
    // Il file sparisce dall'area comune, e il catalogo lo sa ancora al suo posto: perso (e il catalogo punta a un file che non c'e').
    w.bench.target.delete(ShareNasTarget.COMMON, commonFileOf(w, w.media[0]))
    val seen = invariants(w.verifier.check("prova", final = false))
    assertThat(seen).contains(ShareInvariant.NO_LOSS)
    assertThat(seen).contains(ShareInvariant.NOTHING_MISSING)
  }

  @Test
  fun unFileSoloNelCestinoEUnaPerdita() = healthy { w ->
    // La sola copia sta nel cestino: tolta dall'area comune e rimessa nel cestino, senza una copia completa altrove.
    val path = commonFileOf(w, w.media[1])
    val bytes = w.bench.target.bytes(ShareNasTarget.COMMON, path)!!
    w.bench.target.delete(ShareNasTarget.COMMON, path)
    w.bench.target.put(ShareNasTarget.COMMON, ".mycloudgallery/trash/${ShareNasTarget.DEVICE}/files/solo", bytes)
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_LOSS)
  }

  @Test
  fun unByteDiversoAlPostoDelCatalogoSpezzaLHash() = healthy { w ->
    val path = commonFileOf(w, w.media[2])
    val bytes = w.bench.target.bytes(ShareNasTarget.COMMON, path)!!.copyOf().also { it[3] = (it[3] + 1).toByte() }
    w.bench.target.delete(ShareNasTarget.COMMON, path)
    w.bench.target.put(ShareNasTarget.COMMON, path, bytes)
    val seen = invariants(w.verifier.check("prova", final = false))
    assertThat(seen).containsAnyOf(ShareInvariant.HASH, ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun unCatalogoCheNonSegueIlDiarioMancaInApp() = healthy { w ->
    // Il diario dice che il media e' arrivato (DONE) ma il catalogo lo rimette nell'area privata, dove il file non c'e' piu'.
    w.bench.library.move(w.media[0], MediaPlace.MY_PRIVATE, "Foto/non-c-e.jpg")
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NOTHING_MISSING)
  }

  @Test
  fun unaVocePubblicataPerUnFileCheNonCEEUnaVoceSenzaFile() = healthy { w ->
    val me = w.bench.me.account
    val m = w.bench.library[w.media[0]]!!
    val item = SharedAlbumChange.Item(
      MediaId("00000000-0000-0000-0000-000000000042"), null, m.contentKey, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("Album condivisi/Sano/non-c-e.jpg"), 9_000L, me,
    )
    w.bench.me.publisher.stage(w.album!!, listOf(item))
    w.bench.world.settle(1)
    w.bench.me.publisher.flush(w.album!!, FlushReason.SYNC_ROUND)
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_ENTRY_WITHOUT_FILE)
  }

  @Test
  fun unaVoceAccodataSenzaIlFileLaVedeLOsservatore() = healthy { w ->
    val m = w.bench.library[w.media[1]]!!
    val change = SharedAlbumChange.Item(
      w.media[1], null, m.contentKey, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("Album condivisi/Sano/altrove.jpg"), 9_000L, w.bench.me.account,
    )
    w.observer.onStage(listOf(change))
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_ENTRY_WITHOUT_FILE)
  }

  @Test
  fun unOperazioneDelMotoreSenzaUnaConfermaDellaCondivisioneEUnAzioneSenzaConferma() = healthy { w ->
    val source = w.bench.privateMedia(40, folder = "Foto")
    val item = w.bench.target.item(ShareNasTarget.PRIVATE, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse(w.bench.library[source]!!.path!!))!!
    val request = OpRequest.Move(listOf(item), ShareNasTarget.COMMON, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("Album condivisi/Sano"))
    val plan = (w.bench.target.operations.plan(request) as dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome.Ready).plan
    w.bench.target.start(plan)
    assertThat(invariants(w.verifier.check("prova", final = false))).contains(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION)
  }

  @Test
  fun unaConfermaDiUnProcessoMortoRiusataLaVedeLOsservatore() = healthy { w ->
    val old = w.book.issue(w.bench, w.bench.plan(ShareRequest.NewAlbum("Altro", ShareLevel.ADD, listOf(w.bench.privateMedia(50)))))
    w.book.nextProcess()
    val op = w.bench.diary.allOps().first()
    w.observer.onTransfer(dev.pampa.mycloudgallery.core.albums.contract.TransferKey("${op.id.value}:in"), old)
    val seen = w.verifier.check("prova", final = false).filter { it.invariant == ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION }
    assertThat(seen.map { it.text }.joinToString()).contains("processo morto")
    assertThat(seen.map { it.text }.joinToString()).contains("altro piano")
  }

  @Test
  fun unElementoFallitoSenzaUnMotivoVeroEUnErrorePermanente() = healthy { w ->
    // Un elemento finito non puo' "ri-fallire": si avvia un'operazione nuova e, prima che finisca, si ferma il suo elemento senza un motivo vero.
    val extra = w.bench.privateMedia(60)
    val plan = w.bench.plan(ShareRequest.AddToShared(w.album!!, listOf(extra)))
    w.bench.orchestrator.start(plan, w.book.issue(w.bench, plan))
    val mine = w.bench.items().first { it.media == extra }
    assertThat(mine.state.terminal).isFalse()
    w.bench.diary.update(mine.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED))
    w.bench.drain()
    assertThat(invariants(w.verifier.check("prova", final = true))).contains(ShareInvariant.REACHES_THE_END)
  }

  @Test
  fun unNascostoLasciatoFuoriCheSiESpostatoEUnaViolazione() = runBlocking {
    ShareBench(::FakeNasTarget).use { bench ->
      val w = World(bench)
      val hidden = bench.privateMedia(70, hidden = true)
      val shown = bench.privateMedia(71)
      // Il nascosto lo si condivide DAVVERO (con la scelta E-SH9), ma il banco crede che sia stato lasciato fuori: l'invariante 9 lo vede.
      val plan = bench.plan(ShareRequest.NewAlbum("Nascosti", ShareLevel.ADD, listOf(hidden, shown), dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice.SHARE_TOO))
      bench.orchestrator.start(plan, w.book.issue(bench, plan))
      bench.drain()
      w.verifier.hiddenLeftOut += hidden
      assertThat(invariants(w.verifier.check("prova", final = true))).contains(ShareInvariant.HIDDEN)
    }
  }

  @Test
  fun lImprontaCambiaSeUnFileCambia() = healthy { w ->
    val before = w.verifier.fingerprint()
    assertThat(w.verifier.fingerprint()).isEqualTo(before)
    val path = commonFileOf(w, w.media[0])
    w.bench.target.delete(ShareNasTarget.COMMON, path)
    assertThat(w.verifier.fingerprint()).isNotEqualTo(before)
  }
}
