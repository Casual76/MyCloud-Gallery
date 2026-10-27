package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * O2 (Fase 8, correzione dopo il banco delle interruzioni, docs/fase8-progetto.md §2.3 riga 5 della tabella "Una volta per account"): un media calcolato dai byte del
 * telefono (risultati solo locali) che poi arriva sul NAS dentro un'unita' **gia' segnata finita** ha il corpo proprio mai messo in coda: gli altri dispositivi lo
 * ricalcolavano e i vincenti potevano restare diversi. Ora, quando il media compare sul NAS, il corpo gia' calcolato si mette in coda **una volta**, senza ricalcolarlo,
 * anche dopo un riavvio, **con la generazione e lo stato di adesso** (mai altrimenti), ed e' idempotente.
 */
class CorpoDelTelefonoSulNasTest {
  private val forever = SimWorld.FOREVER

  private class Scene(val w: SimWorld, val phone: SimDevice, val onNas: ContentKey, val media: SimMedia)

  /** Una foto sul NAS e una solo sul telefono, nello stesso mese: calcolate entrambe (l'unita' del mese e' segnata finita). */
  private suspend fun computedScene(): Scene {
    val w = SimWorld()
    val phone = w.device(w.anna, "telefono-anna")
    val onNas = w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(1)), month = "2024-03")
    val media = w.nas.phoneMedia(listOf(Faces.big(2)), month = "2024-03")
    phone.phone[media.key] = media
    phone.engine.runAll(forever)
    assertThat(phone.ticking.detects).isEqualTo(2)
    assertThat(phone.units.marks).isEqualTo(1)
    assertThat(w.published(w.anna, "telefono-anna")).containsExactly(onNas)
    return Scene(w, phone, onNas, media)
  }

  /** Il backup riesce: il media lascia il telefono ed e' sul NAS, con la sua anteprima attestata. */
  private fun backedUp(scene: Scene) {
    scene.phone.phone.remove(scene.media.key)
    val onNas = SimMedia(
      scene.media.key, scene.media.kind, scene.media.month, scene.media.source, scene.media.scene, scene.media.hq, scene.media.document, scene.media.cutout,
      scene.media.hidden, previewOnNas = true,
    )
    scene.w.nas.moveToNas(onNas, scene.w.anna.privateShare)
  }

  @Test
  fun ilCorpoDelTelefonoArrivatoSulNasInUnUnitaFinitaSiMetteInCodaESiPubblica_SenzaRicalcolare() = runTest {
    val scene = computedScene()
    backedUp(scene)

    scene.phone.engine.runAll(forever)

    assertThat(scene.phone.ticking.detects).isEqualTo(2)
    assertThat(scene.phone.engine.counters.restaged.get()).isEqualTo(1)
    assertThat(scene.w.published(scene.w.anna, "telefono-anna")).containsExactly(scene.onNas, scene.media.key)
    assertThat(scene.phone.archive.perKey.values.toSet()).containsExactly(1)
    // L'unita' era gia' finita: non si segna una seconda volta.
    assertThat(scene.phone.units.marks).isEqualTo(1)
  }

  @Test
  fun laMessaInCodaESoloUnaVolta_EIdempotente() = runTest {
    val scene = computedScene()
    backedUp(scene)
    scene.phone.engine.runAll(forever)
    val stages = scene.phone.publisher.stageCalls
    val flushes = scene.phone.publisher.flushCalls

    // Ancora tappe nello stesso processo: l'unita' ha le stesse chiavi, niente da rimettere in coda, niente da pubblicare.
    scene.phone.engine.runAll(forever)
    scene.phone.engine.runAll(forever)

    assertThat(scene.phone.publisher.stageCalls).isEqualTo(stages)
    assertThat(scene.phone.publisher.flushCalls).isEqualTo(flushes)
    assertThat(scene.phone.engine.counters.restaged.get()).isEqualTo(1)
  }

  @Test
  fun dopoUnRiavvioIlCorpoDelTelefonoSiMetteInCodaLoStesso_SenzaRicalcolare() = runTest {
    val scene = computedScene()
    backedUp(scene)
    // Il processo riparte prima di accorgersi che il media e' arrivato: la memoria si perde, l'archivio e le unita' finite no.
    scene.phone.restart()

    scene.phone.engine.runAll(forever)

    assertThat(scene.phone.ticking.detects).isEqualTo(2)
    assertThat(scene.w.published(scene.w.anna, "telefono-anna")).containsExactly(scene.onNas, scene.media.key)
    assertThat(scene.phone.publisher.staged).isEmpty()
    assertThat(scene.phone.archive.perKey.values.toSet()).containsExactly(1)
  }

  @Test
  fun ancheIlCorpoProprioCheQuiNonVinceSiMetteInCoda_PerchePerchiLeggeLeStesseTesteScegliaLoStessoVincitore() = runTest {
    val scene = computedScene()
    // Un altro dispositivo dell'account (un nome che viene prima) calcola il media dal NAS e pubblica il suo corpo; questo dispositivo lo replica: qui vince il suo.
    val other = scene.w.device(scene.w.anna, "aaa-tablet-anna")
    backedUp(scene)
    other.engine.runAll(forever)
    scene.phone.replicate()
    assertThat(scene.phone.rawArchive.winners(listOf(scene.media.key)).getValue(scene.media.key).author).isEqualTo("aaa-tablet-anna")

    scene.phone.restart()
    scene.phone.engine.runAll(forever)

    // Si rimettono in coda tutti i corpi propri, vincenti o no (come il pubblicatore: il contributo di un dispositivo non si restringe mai): quello del media nuovo
    // e quello della foto del NAS. Con i soli vincenti il corpo del telefono non sarebbe mai pubblicato e i due dispositivi sceglierebbero corpi diversi.
    assertThat(scene.phone.engine.counters.restaged.get()).isEqualTo(2)
    assertThat(scene.phone.ticking.detects).isEqualTo(2)
  }

  @Test
  fun conLaGenerazioneCambiataOLoStatoNonPiuAttivoNienteSiMetteInCoda() = runTest {
    for (flip in listOf<(Scene) -> FacesState>({ FacesState.Off }, { FacesState.On(it.w.bruno.generation, HereState.ACTIVE) })) {
      val scene = computedScene()
      backedUp(scene)
      val stages = scene.phone.publisher.stageCalls
      // Lo stato cambia proprio mentre si decide cosa fare dell'unita' (dopo che la tappa e' partita).
      scene.phone.units.onIsDone = { scene.phone.switch.state.value = flip(scene) }

      val results = scene.phone.engine.runAll(forever)

      assertThat(results.first().second.stop).isIn(listOf(FacesStop.NOT_ACTIVE, FacesStop.GENERATION_CHANGED))
      assertThat(scene.phone.publisher.stageCalls).isEqualTo(stages)
      assertThat(scene.phone.engine.counters.restaged.get()).isEqualTo(0)
      assertThat(scene.w.published(scene.w.anna, "telefono-anna")).containsExactly(scene.onNas)
    }
  }
}
