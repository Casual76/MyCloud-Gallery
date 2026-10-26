package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.share.FakeNasTarget
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I DIFETTI DEL CODICE DI PRODUZIONE che il banco ha trovato (docs/prova-500.md Parte E, §E6), SD-1 e SD-2, **corretti** in `ShareSteps` (P4, 27/10):
 * gli esiti di un trasferimento finito vanno a ogni file per la sua posizione nella richiesta (tutti gli elementi con quel biglietto, non i soli rimasti),
 * e un errore di `TicketStatus.find` vale "non so" (si richiede al giro dopo), mai "mai partito". Il banco non li evita piu' (niente `KnownDefect`):
 * da qui in poi questo test e' una **regressione**, come `ChaosKnownDefectsTest` di core-nasops dopo P12/P16. Fallisce se uno dei due torna:
 *  - lo scenario di ciascuno, riprodotto senza giri casuali (la morte a ogni salvataggio del diario);
 *  - i giri del banco che li avevano trovati (seme di base 20261026: il 221 per SD-1, il 73 e il 739 per SD-2), ora verdi.
 */
class ShareChaosKnownDefectsTest {

  private fun run(block: suspend () -> Unit) = runBlocking { block() }

  // --- SD-1: gli esiti di un trasferimento finito si applicano agli elementi rimasti ----------------------------------------------------------

  /**
   * Due file da spostare (un trasferimento), il PC cambia il secondo prima della copia: gli esiti sono [arrivato, cambiato]. Un'interruzione (la morte del
   * processo a un salvataggio del diario) dopo il primo assestamento lascia `TRANSFERRING` solo il secondo, che alla ripresa prende l'esito del primo.
   * Si prova con la morte a ogni salvataggio (da 1 a 40): [bad] dice se almeno una lascia il secondo elemento sbagliato o appeso per sempre.
   */
  private fun sd1Outcome(deathAtSave: Int): String? = runBlocking {
    ShareBench(::FakeNasTarget).use { b ->
      val first = b.privateMedia(1)
      val second = b.privateMedia(2, folder = "Viaggi")
      val path = b.library[second]!!.path!!
      b.target.lock(ShareNasTarget.PRIVATE, path)
      b.diary.crashAtSave = deathAtSave
      val before = b.diary.allOps().size
      repeat(6) {
        if (b.diary.allOps().size > before) return@repeat
        try {
          b.start(b.plan(ShareRequest.NewAlbum("SD-1", ShareLevel.ADD, listOf(first, second), HiddenChoice.LEAVE_OUT)))
        } catch (e: CancellationException) {
          throw e
        } catch (e: SimulatedDeath) {
          b.restart()
        }
      }
      b.modifyFromPc(second, ByteArray(300) { 7 })
      b.target.unlock(ShareNasTarget.PRIVATE, path)
      // La morte e' ancora armata: se non e' caduta nell'avvio cade nell'assestamento degli esiti, dopo il primo salvataggio.
      b.drainTolerant(24)
      b.diary.crashAtSave = null
      val items = b.items()
      val stuck = items.filter { !it.state.terminal }
      val wrong = items.filter { it.media == first && it.state == ShareItemState.FAILED && it.failure == ShareFailure.SOURCE_CHANGED }
      val arrivedButFailed = items.any { it.media == first && it.state == ShareItemState.FAILED && b.library[first]!!.place == MediaPlace.COMMON }
      when {
        stuck.isNotEmpty() -> "elemento appeso per sempre (${stuck.first().state.code})"
        wrong.isNotEmpty() || arrivedButFailed -> "un file arrivato risulta fallito (${items.first { it.media == first }.failure?.code})"
        else -> null
      }
    }
  }

  private fun sd1Bad(): List<Pair<Int, String>> = (1..40).mapNotNull { n -> sd1Outcome(n)?.let { n to it } }

  @Test
  fun sd1_giustoUnaMorteNonSbagliaGliEsiti() {
    val bad = sd1Bad()
    println("SD-1: ${bad.size} morti su 40 sbagliano gli esiti")
    assertThat(bad).isEmpty()
  }

  // --- SD-2: un errore di find vale "mai partito" ----------------------------------------------------------------------------------------

  /**
   * Due file da spostare, il secondo tenuto aperto dal PC (il motore ha l'operazione ma il secondo file non e' ancora partito); il processo muore a un
   * salvataggio del diario mentre i due elementi passano a `TRANSFERRING` (il primo e' salvato, il secondo no). Alla ripresa la prima chiamata a `find`
   * fallisce una volta: l'orchestratore la prende per "mai partito" e il secondo elemento diventa `FAILED(not_started)`; poi il PC chiude il file, il
   * motore finisce e il file e' nell'area comune: arrivato, ma l'elemento resta fallito.
   */
  private fun sd2Outcome(deathAtSave: Int): String? = runBlocking {
    ShareBench(::FakeNasTarget).use { b ->
      val first = b.privateMedia(1)
      val second = b.privateMedia(2, folder = "Viaggi")
      var failFindOnce = false
      val path = b.library[second]!!.path!!
      b.target.lock(ShareNasTarget.PRIVATE, path)
      b.wrapTickets = { inner ->
        object : TicketStatus {
          override suspend fun status(ticket: TransferTicket): TicketState = inner.status(ticket)

          override suspend fun find(key: TransferKey): TransferTicket? {
            if (failFindOnce) {
              failFindOnce = false
              throw IllegalStateException("guasto del catalogo del diario dei trasferimenti")
            }
            return inner.find(key)
          }

          override suspend fun cancel(ticket: TransferTicket) = inner.cancel(ticket)
        }
      }
      b.rewire()
      b.diary.crashAtSave = deathAtSave
      val before = b.diary.allOps().size
      try {
        b.start(b.plan(ShareRequest.NewAlbum("SD-2", ShareLevel.ADD, listOf(first, second), HiddenChoice.LEAVE_OUT)))
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimulatedDeath) {
        b.restart()
      }
      b.diary.crashAtSave = null
      if (b.diary.allOps().size == before) return@use null
      failFindOnce = true
      b.round()
      b.target.unlock(ShareNasTarget.PRIVATE, path)
      b.drainTolerant(24)
      val lost = b.items().firstOrNull { it.state == ShareItemState.FAILED && it.failure == ShareFailure.NOT_STARTED && b.library[it.media]!!.place == MediaPlace.COMMON }
      lost?.let { "il file di un elemento fallito come mai partito e' nell'area comune (#${it.index})" }
    }
  }

  private fun sd2Bad(): List<Pair<Int, String>> = (1..12).mapNotNull { n -> sd2Outcome(n)?.let { n to it } }

  @Test
  fun sd2_giustoUnErroreDiFindNonDiventaMaiPartito() {
    val bad = sd2Bad()
    println("SD-2: ${bad.size} morti su 12 danno un elemento 'mai partito' con il file arrivato")
    assertThat(bad).isEmpty()
  }

  /**
   * La stessa radice di SD-2 in "Annulla": un elemento `PLANNED` con la chiave non e' per forza "mai partito". Due file, il secondo tenuto aperto dal
   * PC; la morte a un salvataggio del diario dopo l'avvio del trasferimento (prima che gli elementi siano `TRANSFERRING`); alla ripresa l'utente
   * annulla subito, poi il PC chiude il file. Un elemento annullato (o "mai partito") non ha mai il suo file spostato nell'area comune: il
   * trasferimento si ritrova con `find` e si ferma con la sua porta.
   */
  private fun cancelOutcome(deathAtSave: Int): String? = runBlocking {
    ShareBench(::FakeNasTarget).use { b ->
      val first = b.privateMedia(1)
      val second = b.privateMedia(2, folder = "Viaggi")
      val path = b.library[second]!!.path!!
      b.target.lock(ShareNasTarget.PRIVATE, path)
      b.diary.crashAtSave = deathAtSave
      val before = b.diary.allOps().size
      try {
        b.start(b.plan(ShareRequest.NewAlbum("Annulla", ShareLevel.ADD, listOf(first, second), HiddenChoice.LEAVE_OUT)))
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimulatedDeath) {
        b.restart()
      }
      b.diary.crashAtSave = null
      val op = b.diary.allOps().drop(before).firstOrNull() ?: return@use null
      b.orchestrator.cancel(op.id)
      b.target.unlock(ShareNasTarget.PRIVATE, path)
      b.drainTolerant(24)
      val wrong = b.items().firstOrNull {
        (it.state == ShareItemState.CANCELLED || it.failure == ShareFailure.NOT_STARTED) && b.library[it.media]!!.place == MediaPlace.COMMON
      }
      wrong?.let { "elemento #${it.index} ${it.state.code} con il file nell'area comune" }
    }
  }

  @Test
  fun annullareDopoLaMorteFermaIlTrasferimentoGiaPartito() {
    val bad = (1..12).mapNotNull { n -> cancelOutcome(n)?.let { n to it } }
    println("Annulla dopo la morte: ${bad.size} morti su 12 chiudono un elemento con il file spostato (${bad.take(2).joinToString { "salvataggio ${it.first}: ${it.second}" }})")
    assertThat(bad).isEmpty()
  }

  // --- I giri del banco che li avevano trovati -------------------------------------------------------------------------------------------

  /**
   * Con il seme di base 20261026 e senza evitamenti (oggi il banco di serie) 1.000 giri ne davano 3 rossi: il 221 (SD-1, un errore permanente
   * `source_changed` per un file arrivato) e il 73 e il 739 (SD-2, "trasferimento mai partito ma il media ha cambiato posto"). Ora sono verdi.
   */
  @Test
  fun iGiriRossiDiPrimaOraSonoVerdi() = run {
    for (round in listOf(73, 221, 739)) {
      val result = ShareChaosBench.run(1, 20261026L, FakeChaosTargets, firstRound = round)
      println("giro $round (seme di base 20261026): ${if (result.failures.isEmpty()) "verde" else "rosso"}")
      assertWithMessage("giro $round: " + result.failureText()).that(result.failures).isEmpty()
    }
  }
}
