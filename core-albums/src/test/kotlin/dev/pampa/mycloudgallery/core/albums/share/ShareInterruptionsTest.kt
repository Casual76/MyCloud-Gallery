package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * GLI INVARIANTI DEL §5.5 SOTTO INTERRUZIONE (P4, riga del §4.4: "invarianti del §2.5 verificati dopo `FaultPlan` a ogni chiamata e dopo la morte
 * del processo a ogni salvataggio del diario"), sul NAS finto e sul simulatore SMB con le vere `SmbNasFileOps`:
 * - **un guasto a ogni chiamata** di ogni porta dell'orchestratore (spostamento, caricamento, biglietti, pubblicatore, guardiano, cartelle
 *   d'origine, catalogo), prima e dopo il suo effetto: a meta' gli invarianti tengono, alla fine **ogni elemento e' fatto** (nessun errore
 *   permanente per un guasto passeggero);
 * - **la morte del processo a ogni salvataggio del diario della condivisione**, prima e dopo la scrittura: tutto cio' che vive in memoria si perde
 *   (la conferma compresa), si riparte dal diario; alla fine ogni elemento e' fatto, o fermo con un motivo, e un trasferimento mai partito non ha
 *   mosso niente.
 * Lo scenario: un album nuovo con due media dell'area privata (uno senza copia sul telefono: guardiano), uno solo sul telefono (E-SH1), uno gia'
 * nell'area comune (E-SH11) e un nascosto lasciato fuori; poi "Riporta" di uno spostato.
 */
class ShareInterruptionsTest {

  private val targets: List<(MoveCompletionSink) -> ShareNasTarget> = listOf(::FakeNasTarget, ::SmbNasTarget)

  /** Il guasto programmato: alla chiamata [at] di una porta qualunque, prima o dopo il suo effetto. */
  private class Faults(private val at: Int = Int.MAX_VALUE, private val after: Boolean = false) {
    var calls = 0
      private set
    var fired = false
      private set

    suspend fun <T> around(block: suspend () -> T): T {
      val here = ++calls
      if (here == at && !after) {
        fired = true
        throw IllegalStateException("guasto del banco (prima)")
      }
      val result = block()
      if (here == at && after) {
        fired = true
        throw IllegalStateException("guasto del banco (dopo)")
      }
      return result
    }
  }

  private fun ShareBench.inject(f: Faults) {
    wrapMover = { inner -> AlbumMover { k, i, d, l, c -> f.around { inner.move(k, i, d, l, c) } } }
    wrapUploader = { inner -> AlbumUploader { k, m, d, c -> f.around { inner.upload(k, m, d, c) } } }
    wrapTickets = { inner ->
      object : TicketStatus {
        override suspend fun status(ticket: TransferTicket): TicketState = f.around { inner.status(ticket) }

        override suspend fun find(key: TransferKey): TransferTicket? = f.around { inner.find(key) }

        override suspend fun cancel(ticket: TransferTicket) = f.around { inner.cancel(ticket) }
      }
    }
    wrapPublisher = { inner ->
      object : AlbumPublisher {
        override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket = f.around { inner.stage(album, changes) }

        override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome = f.around { inner.flush(album, reason) }

        override suspend fun publishedUpTo(album: AlbumId): StageTicket = f.around { inner.publishedUpTo(album) }

        override val pending: StateFlow<Int> get() = inner.pending
      }
    }
    wrapSafeCopies = { inner -> SafeCopyRequester { m -> f.around { inner.request(m) } } }
    wrapOrigins = { inner ->
      object : SharedOrigins {
        override suspend fun record(media: MediaId, origin: ShareFolder) = f.around { inner.record(media, origin) }

        override suspend fun originOf(media: MediaId): ShareFolder? = f.around { inner.originOf(media) }
      }
    }
    wrapFacts = { inner ->
      object : CatalogFacts {
        override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> = f.around { inner.facts(media) }

        override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> = f.around { inner.attest(items) }
      }
    }
    rewire()
  }

  private class Scenario(val share: List<MediaId>, val hidden: MediaId)

  private fun ShareBench.seedScenario(): Scenario = Scenario(
    listOf(privateMedia(1, onPhone = true), privateMedia(2, folder = "Viaggi"), phoneMedia(3), commonMedia(4)),
    privateMedia(5, hidden = true),
  )

  /** Pianifica e avvia; se l'avvio non e' arrivato al diario (un guasto o una morte prima), l'utente riprova con un foglio nuovo. */
  private suspend fun ShareBench.shareWithRetry(request: ShareRequest) {
    val before = diary.allOps().size
    repeat(6) {
      if (diary.allOps().size > before) return
      try {
        start(plan(request))
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimulatedDeath) {
        restart()
      } catch (e: Exception) {
        Unit
      }
    }
  }

  private suspend fun run(newTarget: (MoveCompletionSink) -> ShareNasTarget, faults: Faults?, deathAt: Int?, after: Boolean): Pair<ShareBench, String> {
    val b = ShareBench(newTarget)
    val s = b.seedScenario()
    faults?.let { b.inject(it) }
    if (deathAt != null) {
      b.diary.crashAtSave = deathAt
      b.diary.crashAfterWrite = after
    }
    b.shareWithRetry(ShareRequest.NewAlbum("Banco", ShareLevel.ADD, s.share + s.hidden, HiddenChoice.LEAVE_OUT))
    b.drainTolerant()
    val album = b.diary.allOps().first().album
    // "Riporta" di uno spostato (con la sua cartella d'origine), dopo che la condivisione e' finita.
    if (b.library[s.share[1]]?.place == dev.pampa.mycloudgallery.core.albums.contract.MediaPlace.COMMON && b.origins.origins.containsKey(s.share[1])) {
      b.shareWithRetry(ShareRequest.BringBack(album, listOf(s.share[1])))
      b.drainTolerant()
    }
    return b to "${b.target.name}"
  }

  private suspend fun check(b: ShareBench, label: String, transientOnly: Boolean) {
    val hidden = setOf(dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary.id(5))
    assertWithMessage(label).that(b.violations(final = true, hiddenLeftOut = hidden, transientOnly = transientOnly)).isEmpty()
    // Un trasferimento mai partito (conferma persa con il processo) non ha mosso niente.
    for (item in b.items().filter { it.failure == ShareFailure.NOT_STARTED }) {
      val before = when (item.action) {
        dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction.MOVE_IN -> dev.pampa.mycloudgallery.core.albums.contract.MediaPlace.MY_PRIVATE
        dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction.UPLOAD -> dev.pampa.mycloudgallery.core.albums.contract.MediaPlace.PHONE_ONLY
        else -> dev.pampa.mycloudgallery.core.albums.contract.MediaPlace.COMMON
      }
      assertWithMessage("$label: mai partito ma spostato (${item.action.code})").that(b.library[item.media]!!.place).isEqualTo(before)
    }
  }

  @Test
  fun unGuastoAOgniChiamataDiOgniPortaPrimaEDopo() = runBlocking {
    for (factory in targets) {
      val counter = Faults()
      val (counted, name) = run(factory, counter, null, false)
      counted.use { check(it, "$name, pulito", transientOnly = true) }
      val total = counter.calls
      assertWithMessage("$name: chiamate contate").that(total).isAtLeast(30)
      var fired = 0
      for (n in 1..total) for (after in listOf(false, true)) {
        val faults = Faults(n, after)
        val (b, _) = run(factory, faults, null, false)
        b.use {
          val label = "$name, guasto alla chiamata $n/$total ${if (after) "dopo" else "prima"}"
          if (faults.fired) fired++
          check(b, label, transientOnly = true)
          assertWithMessage("$label: tutto fatto").that(b.items().map { it.state }.toSet()).containsExactly(ShareItemState.DONE)
        }
      }
      assertWithMessage("$name: guasti scattati").that(fired).isAtLeast(total)
      println("P4 $name: $total chiamate alle porte x 2 fasi, $fired guasti scattati, 0 violazioni")
    }
  }

  @Test
  fun laMorteDelProcessoAOgniSalvataggioDelDiario() = runBlocking {
    for (factory in targets) {
      val (clean, name) = run(factory, null, null, false)
      val total = clean.diary.saves
      clean.close()
      assertWithMessage("$name: salvataggi contati").that(total).isAtLeast(20)
      var deaths = 0
      for (n in 1..total) for (after in listOf(false, true)) {
        val (b, _) = run(factory, null, n, after)
        b.use {
          val label = "$name, morte al salvataggio $n/$total ${if (after) "dopo" else "prima"} la scrittura"
          if (b.diary.saves >= n) deaths++
          check(b, label, transientOnly = false)
          // Il lavoro arriva in fondo: ogni elemento e' fatto, o fermo perche' la conferma si e' persa prima che il trasferimento partisse.
          for (item in b.items()) {
            assertWithMessage("$label: elemento").that(item.state == ShareItemState.DONE || item.failure == ShareFailure.NOT_STARTED).isTrue()
          }
        }
      }
      assertThat(deaths).isAtLeast(total)
      println("P4 $name: $total salvataggi del diario x 2 (prima/dopo), $deaths morti, 0 violazioni")
    }
  }
}
