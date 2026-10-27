package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.compute.Faces
import dev.pampa.mycloudgallery.core.faces.compute.SimMedia
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I DUE DIFETTI DEL CODICE DI PRODUZIONE CHE IL BANCO AVEVA TROVATO (P12) E CHE LA CORREZIONE O1/O2 HA TOLTO (docs/prova-500.md Parte F, §F6). Sono scenari senza giri
 * casuali, con l'asserzione giusta (erano `@Ignore` finche' i difetti c'erano: il banco li evitava di serie); ora il banco non evita piu' niente e li copre anche a caso.
 *
 * - **O1**: un errore del database che non e' un `IOException` (SQLite pieno o bloccato, come Room lo lancia) nel segno di unita' finita, nella messa in coda del
 *   pubblicatore o nella pubblicazione non fa cadere la tappa del calcolo con un'eccezione: si guarda come il salvataggio del corpo. Nessun dato perso, nessun media rifatto.
 * - **O2**: un media calcolato dai byte del telefono (risultati solo locali) che poi arriva sul NAS dentro un'unita' gia' segnata finita ha il corpo messo in coda e
 *   pubblicato, e gli altri dispositivi scelgono gli stessi corpi vincenti.
 */
class FacesChaosKnownDefectsTest {

  // --- O1 ----------------------------------------------------------------------------------------------------------------------------------------

  /** Una tappa su tre media privati con un errore del database alla chiamata [nth] di [surface]: l'eccezione che esce dalla tappa, se esce. */
  private suspend fun o1(surface: Surface, nth: Int): Throwable? {
    val world = ChaosTestKit.world(devices = 1, privateMedia = 3)
    try {
      val device = world.devices.first()
      world.inj.arm(listOf(Blow(0, surface, nth, Moment.BEFORE, FaultKind.IO_FAIL)))
      device.prepareCompute()
      return runCatching { device.computeAll() }.exceptionOrNull()
    } finally {
      world.inj.disarm()
      world.close()
    }
  }

  @Test
  fun o1_unErroreDelDatabaseNelSegnoDiUnitaFinitaFermaLaTappaSenzaLanciare(): Unit = runBlocking {
    assertThat(o1(Surface.DB_UNITS, 1)).isNull()
  }

  @Test
  fun o1_unErroreDelDatabaseNellOutboxFermaLaTappaSenzaLanciare(): Unit = runBlocking {
    // La prima scrittura dell'outbox e' la messa in coda del primo corpo, le seguenti sono le altre messe in coda e la pubblicazione di fine unita'.
    for (nth in 1..4) assertThat(o1(Surface.OUTBOX, nth)).isNull()
  }

  @Test
  fun o1_unErroreDelDatabaseNellIstantaneaDelRaggruppamentoNonFaCadereLaTappa(): Unit = runBlocking {
    assertThat(o1(Surface.DB_GROUPING, 1)).isNull()
  }

  // --- O2 ----------------------------------------------------------------------------------------------------------------------------------------

  /**
   * D0 ha una foto solo sul suo telefono, nello stesso mese di una foto del NAS: la calcola in locale (D0 e' l'unico che la vede) e l'unita' del mese si
   * segna finita. La foto arriva sul NAS (backup riuscito). I dispositivi ripartono e lavorano finche' non c'e' piu' niente da fare. Restituisce
   * (D0 ha il corpo della foto nell'outbox o gia' pubblicato, la foto e il suo corpo che D0 e D1 scelgono sono gli stessi).
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
  fun o2_ilCorpoDelSoloTelefonoSiPubblicaQuandoIlMediaArrivaSulNas(): Unit = runBlocking {
    val (queued, sameWinners) = o2()
    assertThat(queued).isTrue()
    assertThat(sameWinners).isTrue()
  }

  private companion object {
    const val MONTH = "2024-03"
  }
}
