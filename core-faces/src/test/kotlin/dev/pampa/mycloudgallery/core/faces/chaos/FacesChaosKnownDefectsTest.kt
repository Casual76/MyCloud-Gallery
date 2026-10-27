package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.compute.Faces
import dev.pampa.mycloudgallery.core.faces.compute.SimMedia
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test

/**
 * I DIFETTI DEL CODICE DI PRODUZIONE CHE IL BANCO HA TROVATO e che P12 non corregge (docs/prova-500.md Parte F, §F6; il perimetro di P12 non e' il codice di
 * produzione). Per ognuno, come negli altri banchi: lo scenario senza giri casuali con il **comportamento di oggi** (verde: quando diventa rosso il difetto e' corretto),
 * lo stesso scenario con l'asserzione rovesciata, `@Ignore` con il motivo (**il comportamento giusto**: si riaccende quando il difetto si corregge), e il banco che
 * lo trova quando non lo evita (`ChaosConfig.avoid`, di serie tutti evitati).
 *
 * - **O1**: un errore del database che non e' un `IOException` (SQLite pieno o bloccato, come Room lo lancia) in un salvataggio che la produzione non guarda
 *   (`FacesUnitsDone.markDone`, la messa in coda del pubblicatore, quella del raggruppamento) esce dalla tappa del calcolo come eccezione invece di fermarla con un codice.
 *   Nessun dato perso (il corpo e' gia' salvato, la ripresa lo rimette in coda); lo vede chi lancia la tappa (il lavoro di `:app`, P13).
 * - **O2**: un media calcolato dai byte del telefono (risultati solo locali) che poi arriva sul NAS dentro un'unita' gia' segnata finita non viene mai messo in coda per la
 *   pubblicazione: nemmeno dopo un riavvio la unita' viene piu' visitata. Gli altri dispositivi lo ricalcolano e non vedono mai il corpo di questo.
 */
class FacesChaosKnownDefectsTest {

  // --- O1 ----------------------------------------------------------------------------------------------------------------------------------------

  private suspend fun o1(surface: Surface): Throwable? {
    val world = ChaosTestKit.world(devices = 1, privateMedia = 3)
    try {
      val device = world.devices.first()
      world.inj.arm(listOf(Blow(0, surface, 1, Moment.BEFORE, FaultKind.IO_FAIL)))
      device.prepareCompute()
      return runCatching { device.computeAll() }.exceptionOrNull()
    } finally {
      world.inj.disarm()
      world.close()
    }
  }

  @Test
  fun o1_oggiUnErroreDelDatabaseNelSegnoDiUnitaFinitaEsceDallaTappa(): Unit = runBlocking {
    assertThat(o1(Surface.DB_UNITS)).isInstanceOf(ChaosDbFault::class.java)
  }

  @Test
  fun o1_oggiUnErroreDelDatabaseNellOutboxEsceDallaTappa(): Unit = runBlocking {
    assertThat(o1(Surface.OUTBOX)).isInstanceOf(ChaosDbFault::class.java)
  }

  @Ignore("O1: la tappa dovrebbe fermarsi con un codice (come per il corpo dei volti), non lanciare: si riaccende quando e' corretto")
  @Test
  fun o1_giusto_unErroreDelDatabaseFermaLaTappaSenzaLanciare(): Unit = runBlocking {
    assertThat(o1(Surface.DB_UNITS)).isNull()
    assertThat(o1(Surface.OUTBOX)).isNull()
  }

  // --- O2 ----------------------------------------------------------------------------------------------------------------------------------------

  /**
   * D0 ha una foto solo sul suo telefono, nello stesso mese di una foto del NAS: la calcola in locale (D0 e' l'unico che la vede) e l'unita' del mese si
   * segna finita. La foto arriva sul NAS (backup riuscito). I dispositivi ripartono e lavorano finche' non c'e' piu' niente da fare. Restituisce
   * (D0 ha il corpo della foto nell'outbox, la foto e il suo corpo che D0 e D1 scelgono sono gli stessi).
   */
  private suspend fun o2(): Pair<Boolean, Boolean> {
    val world = ChaosTestKit.world(devices = 2, privateMedia = 0)
    try {
      val share = world.media.account.privateShare
      world.media.nas.add(share, listOf(Faces.big(1)), month = MONTH)
      val phone = world.media.nas.phoneMedia(listOf(Faces.big(2)), month = MONTH)
      val d0 = world.devices[0]
      val d1 = world.devices[1]
      d0.sim.phone[phone.key] = phone
      ChaosTestKit.turns(world, 6)
      // Il backup riesce: la foto lascia il telefono e ora e' nell'area privata, con la sua anteprima attestata.
      d0.sim.phone.remove(phone.key)
      world.media.nas.moveToNas(SimMedia(phone.key, phone.kind, phone.month, phone.source, phone.scene, phone.hq, phone.document, phone.cutout, phone.hidden, previewOnNas = true), share)
      world.devices.forEach { it.restart() }
      ChaosTestKit.turns(world, 12)
      val g = d0.generation()!!
      val queued = phone.key in d0.local.publishStore.load(g).keys
      val winners0 = d0.local.raw(g)!!.winners(listOf(phone.key))[phone.key]?.let { it.author to it.digest }
      val winners1 = d1.local.raw(d1.generation()!!)!!.winners(listOf(phone.key))[phone.key]?.let { it.author to it.digest }
      assertThat(d0.local.raw(g)!!.analyzed(listOf(phone.key), FacesComputeLimits.RECIPE)).containsExactly(phone.key)
      return queued to (winners0 == winners1)
    } finally {
      world.close()
    }
  }

  @Test
  fun o2_oggiIlCorpoDelSoloTelefonoNonVieneMaiAccodatoEGliAltriScelgonoUnAltroCorpo(): Unit = runBlocking {
    val (queued, sameWinners) = o2()
    assertWithMessage("oggi il corpo non e' nell'outbox").that(queued).isFalse()
    assertWithMessage("oggi i vincenti dei due dispositivi sono diversi").that(sameWinners).isFalse()
  }

  @Ignore("O2: dopo l'arrivo del media sul NAS il corpo calcolato dal telefono va messo in coda (riga 5 della tabella del 2.3): si riaccende quando e' corretto")
  @Test
  fun o2_giusto_ilCorpoDelSoloTelefonoSiPubblicaQuandoIlMediaArrivaSulNas(): Unit = runBlocking {
    val (queued, sameWinners) = o2()
    assertThat(queued).isTrue()
    assertThat(sameWinners).isTrue()
  }

  private companion object {
    const val MONTH = "2024-03"
  }
}
