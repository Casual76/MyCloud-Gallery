package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * «Smetti di condividere» con degli elementi ancora in viaggio (Fase 7, correzioni F, risposta dell'utente del 27/10 su L231). L'utente vuole poterli annullare,
 * **solo se si fa senza rischi**: gli elementi **non ancora partiti** restano dove sono (mai toccati), quelli **gia' in viaggio** finiscono il loro passo atomico
 * (copia verificata, poi cestino, lo decide la porta di core-nasops come per «Annulla») e poi l'operazione si chiude. Provato sul banco della condivisione
 * (`ShareBench`: il NAS finto, il diario, la replica degli album e la porta di completamento veri) con gli invarianti del §5.5: nessun file a meta', nessun
 * doppione, nessuna perdita, ogni elemento finito.
 *
 * Il caso che conta: due file spostati (arrivati prima della chiusura) e due tenuti aperti dal PC (il loro spostamento aspetta). Si chiude l'album: i due
 * che aspettano non partono piu', **qualunque sia il momento in cui il processo muore** (a ogni salvataggio del diario dopo la chiusura, da 1 a 30).
 */
class ShareCloseInFlightTest {

  /** Cosa e' successo a un media dopo la chiusura. */
  private class Outcome(val violations: List<String>, val states: Map<Int, ShareItemState>, val places: Map<Int, MediaPlace?>, val closed: SharedState?)

  private fun scenario(deathAtSave: Int?, rounds: Int = 2): Outcome = runBlocking {
    ShareBench(::FakeNasTarget).use { b ->
      val ids = (1..4).map { b.privateMedia(it, folder = "Viaggi") }
      val paths = ids.map { b.library[it]!!.path!! }
      // Gli ultimi due sono aperti dal PC: il loro spostamento aspetta (in viaggio, ma il file non e' ancora partito).
      b.target.lock(ShareNasTarget.PRIVATE, paths[2])
      b.target.lock(ShareNasTarget.PRIVATE, paths[3])
      val plan = b.share("Chiudo", ids)
      repeat(rounds) { b.round() }

      // L'utente smette di condividere con l'album ancora in viaggio.
      assertThat(b.me.shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
      val closed = b.me.shared.state(plan.album)
      b.diary.crashAtSave = deathAtSave?.let { b.diary.saves + it }
      b.drainTolerant(24)
      b.diary.crashAtSave = null
      // Il PC chiude i file: se qualcuno dovesse ancora partire, parte adesso.
      b.target.unlock(ShareNasTarget.PRIVATE, paths[2])
      b.target.unlock(ShareNasTarget.PRIVATE, paths[3])
      b.drainTolerant(24)
      outcomeOf(b, ids, closed)
    }
  }

  private fun outcomeOf(b: ShareBench, ids: List<MediaId>, closed: SharedState?): Outcome {
    val states = b.items().associate { item -> ids.indexOf(item.media) + 1 to item.state }
    val places = ids.withIndex().associate { (i, id) -> i + 1 to b.library[id]?.place }
    return Outcome(runBlocking { b.violations(final = true) }, states, places, closed)
  }

  @Test
  fun chiudendoGliElementiCheNonSonoPartitiNonPartonoPiu_EGliAltriFinisconoSenzaPerdereNiente() {
    val out = scenario(deathAtSave = null)

    assertThat(out.closed).isEqualTo(SharedState.CLOSED)
    // I due tenuti aperti non si sono mai mossi: annullati, e il loro file e' ancora nell'area privata (mai toccato).
    assertThat(out.states[3]).isEqualTo(ShareItemState.CANCELLED)
    assertThat(out.states[4]).isEqualTo(ShareItemState.CANCELLED)
    assertThat(out.places[3]).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(out.places[4]).isEqualTo(MediaPlace.MY_PRIVATE)
    // Tutto finito, e gli invarianti del banco (nessun file a meta', nessun doppione, nessuna perdita, byte giusti, tutto finito) reggono.
    assertThat(out.states.values.all { it.terminal }).isTrue()
    assertThat(out.violations).isEmpty()
  }

  @Test
  fun lAnnullamentoReggeAllaMorteDelProcessoInOgniPuntoDopoLaChiusura() {
    val bad = (1..30).mapNotNull { n ->
      val out = scenario(deathAtSave = n)
      val problems = buildList {
        addAll(out.violations)
        if (out.states[3] != ShareItemState.CANCELLED || out.places[3] != MediaPlace.MY_PRIVATE) add("il terzo si e' mosso o non e' annullato (${out.states[3]}, ${out.places[3]})")
        if (out.states[4] != ShareItemState.CANCELLED || out.places[4] != MediaPlace.MY_PRIVATE) add("il quarto si e' mosso o non e' annullato (${out.states[4]}, ${out.places[4]})")
        if (!out.states.values.all { it.terminal }) add("un elemento non e' finito")
      }
      if (problems.isEmpty()) null else n to problems
    }
    println("Chiusura con elementi in viaggio: ${bad.size} morti su 30 danno un problema")
    assertWithMessage(bad.joinToString("; ") { "morte al salvataggio ${it.first}: ${it.second}" }).that(bad).isEmpty()
  }

  @Test
  fun unAlbumChiusoPrimaCheQualcosaSiaPartitoNonSpostaNiente() {
    val out = runBlocking {
      ShareBench(::FakeNasTarget).use { b ->
        val ids = (1..3).map { b.privateMedia(it, folder = "Viaggi") }
        val paths = ids.map { b.library[it]!!.path!! }
        // Tre file aperti dal PC: gli spostamenti sono chiesti, ma nessun file e' stato ancora copiato.
        paths.forEach { b.target.lock(ShareNasTarget.PRIVATE, it) }
        val plan = b.share("Subito chiuso", ids)
        assertThat(b.me.shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
        b.drainTolerant(24)
        paths.forEach { b.target.unlock(ShareNasTarget.PRIVATE, it) }
        b.drainTolerant(24)
        outcomeOf(b, ids, b.me.shared.state(plan.album))
      }
    }

    assertThat(out.states.values).containsExactly(ShareItemState.CANCELLED, ShareItemState.CANCELLED, ShareItemState.CANCELLED)
    assertThat(out.places.values).containsExactly(MediaPlace.MY_PRIVATE, MediaPlace.MY_PRIVATE, MediaPlace.MY_PRIVATE)
    assertThat(out.violations).isEmpty()
  }

  @Test
  fun lAlbumNuovoAncoraAttivoNonFermaNiente_ElOperazioneFinisceComeSempre() {
    val out = runBlocking {
      ShareBench(::FakeNasTarget).use { b ->
        val ids = (1..3).map { b.privateMedia(it, folder = "Viaggi") }
        val plan = b.share("Resta aperto", ids)
        b.drainTolerant(24)
        outcomeOf(b, ids, b.me.shared.state(plan.album))
      }
    }

    // Senza chiusura la regola non scatta: tutti gli elementi arrivano, come prima.
    assertThat(out.closed).isEqualTo(SharedState.ACTIVE)
    assertThat(out.states.values.all { it == ShareItemState.DONE }).isTrue()
    assertThat(out.places.values.all { it == MediaPlace.COMMON }).isTrue()
    assertThat(out.violations).isEmpty()
  }

  @Test
  fun unaMorteNonSimulataNonCambiaIlConto() {
    // Un controllo sul controllo: la morte simulata deve poter cadere davvero (almeno un punto fra 1 e 30 la fa scattare), se no la prova sopra non prova niente.
    var died = 0
    for (n in 1..30) {
      runBlocking {
        ShareBench(::FakeNasTarget).use { b ->
          val ids = (1..2).map { b.privateMedia(it, folder = "Viaggi") }
          val plan = b.share("Conta", ids)
          b.diary.crashAtSave = b.diary.saves + n
          try {
            b.round()
            b.me.shared.close(plan.album, CloseMode.STOP, OthersAfterClose.REMOVE)
            b.round()
          } catch (e: CancellationException) {
            throw e
          } catch (e: SimulatedDeath) {
            died++
          }
        }
      }
    }
    assertThat(died).isGreaterThan(0)
  }
}
