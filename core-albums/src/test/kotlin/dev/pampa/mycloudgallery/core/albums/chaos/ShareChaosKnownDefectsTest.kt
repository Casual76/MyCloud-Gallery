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
import org.junit.Ignore
import org.junit.Test

/**
 * I DIFETTI DEL CODICE DI PRODUZIONE che il banco ha trovato (docs/prova-500.md Parte E, §E6). Il codice di produzione non si tocca (riga P5 del §4.4): il
 * banco li evita ([KnownDefect], `ShareChaosConfig.avoid`) e li riproduce qui, senza giri casuali, per tre prove ciascuno:
 *  - il comportamento di OGGI (verde): lo scenario riprodotto; quando diventa rosso il difetto e' stato corretto;
 *  - il comportamento GIUSTO, lo stesso scenario con l'asserzione rovesciata, `@Ignore` con il motivo: si riaccende quando il difetto si corregge;
 *  - il banco lo trova quando non lo evita (`avoid = emptySet()`).
 * Come `ChaosKnownDefectsTest` del banco della 5B e `AiChaosKnownDefectsTest` della Fase 6: un test rosso qui e' un avviso ("il difetto e' stato
 * corretto, togli l'evitamento"), non una regressione.
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
  fun sd1_oggiGliEsitiSiApplicanoAgliElementiRimasti() {
    val bad = sd1Bad()
    println("SD-1: ${bad.size} morti su 40 sbagliano gli esiti (${bad.take(3).joinToString { "salvataggio ${it.first}: ${it.second}" }})")
    assertWithMessage("SD-1 non si riproduce piu': e' stato corretto? togli l'evitamento e riaccendi il test giusto").that(bad).isNotEmpty()
  }

  @Ignore("SD-1 (ShareSteps.pollTransfers): gli esiti di un trasferimento finito si applicano agli elementi rimasti. Si riaccende quando si corregge.")
  @Test
  fun sd1_giustoUnaMorteNonSbagliaGliEsiti() {
    assertThat(sd1Bad()).isEmpty()
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
  fun sd2_oggiUnErroreDiFindVale_maiPartito() {
    val bad = sd2Bad()
    println("SD-2: ${bad.size} morti su 12 danno un elemento 'mai partito' con il file arrivato (${bad.take(2).joinToString { "salvataggio ${it.first}: ${it.second}" }})")
    assertWithMessage("SD-2 non si riproduce piu': e' stato corretto? togli l'evitamento e riaccendi il test giusto").that(bad).isNotEmpty()
  }

  @Ignore("SD-2 (ShareSteps.startTransfers): un errore di TicketStatus.find vale 'il trasferimento non e' mai partito'. Si riaccende quando si corregge.")
  @Test
  fun sd2_giustoUnErroreDiFindNonDiventaMaiPartito() {
    assertThat(sd2Bad()).isEmpty()
  }

  // --- Il banco li trova quando non li evita ----------------------------------------------------------------------------------------------

  @Test
  fun ilBancoTrovaSd1ESd2QuandoNonLiEvita() = run {
    val config = ShareChaosConfig(FakeChaosTargets.capabilities, avoid = emptySet())
    val result = ShareChaosBench.run(1_000, 20261026L, FakeChaosTargets, config)
    val texts = result.failures.flatMap { it.violations }.map { it.text }
    println("senza evitamenti: ${result.failures.size} giri rossi su ${result.rounds}, seme di base ${result.baseSeed}: " +
      result.failures.joinToString("; ") { "giro ${it.index} (${it.violations.first().invariant.number}: ${it.violations.first().text.take(80)})" })
    assertWithMessage("senza gli evitamenti il banco deve trovare i difetti noti").that(result.failures).isNotEmpty()
    assertWithMessage("le violazioni sono quelle dei difetti noti").that(texts.any { "mai partito" in it || "errore permanente" in it || "non finito" in it }).isTrue()
    // E con gli evitamenti (di serie) gli stessi giri sono verdi: il rosso viene dal difetto, non dal banco.
    val avoided = ShareChaosBench.run(300, 20261026L, FakeChaosTargets)
    assertWithMessage(avoided.failureText()).that(avoided.failures).isEmpty()
  }
}
