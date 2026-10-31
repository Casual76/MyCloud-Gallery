package dev.pampa.mycloudgallery.memories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.notify.NotifyOutcome
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.integration.until
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'ordine delle cose nel grafo dei ricordi, sul montaggio vero con le porte finte ([GraphRig]) e senza Hilt ne' NAS: le correzioni della Fase 9 dopo le revisioni (31/10) che
 * riguardano **quando** i ricordi si montano e **che cosa dicono prima**: NT-1 (il lavoro delle notifiche monta senza il primo disegno), RP-1 (le schermate non sentono "non
 * c'e'" prima del montaggio), MO-2 (la notte non lancia), MO-3 (il giro di oggi si rifa'), AZ-1 (lo stop ferma il giorno di una notifica).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesGraphCorrectionsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val dirs = ArrayList<File>()
  private val rigs = ArrayList<GraphRig>()

  @After
  fun tearDown() {
    runBlocking { rigs.forEach { runCatching { it.graph.stop() } } }
    rigs.forEach { it.close() }
    dirs.forEach { it.deleteRecursively() }
  }

  private fun rig(canMount: Boolean = true, withMemory: Boolean = true, trips: SwitchableTrips = SwitchableTrips()): GraphRig {
    val dir = Files.createTempDirectory("memories").toFile().also { dirs += it }
    return GraphRig(app, dir, canMount = canMount, withMemory = withMemory, trips = trips).also { rigs += it }
  }

  // --- NT-1 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Un processo nato per il lavoro: nessuno disegna, il primo disegno non c'e'. `awaitLive` monta lo stesso appena la famiglia e' pronta, e il lavoro (lo slot) ha il suo
   * giorno di una notifica; `start()` resta fermo sul primo disegno.
   */
  @Test
  fun ilLavoroMontaIRicordiSenzaIlPrimoDisegno() = runBlocking<Unit> {
    val rig = rig()
    rig.graph.start()
    assertThat(rig.graph.live.value).isNull()
    // La famiglia non e' pronta: il lavoro aspetta, e a famiglia pronta i ricordi si montano da soli.
    val waiting = CoroutineScope(Dispatchers.Default + Job()).launch { rig.slot.awaitRunner(Attese.TETTO) }
    until("il lavoro e' in attesa") { waiting.isActive }
    assertThat(rig.graph.live.value).isNull()
    rig.ready.value = true
    withTimeout(Attese.TETTO) { waiting.join() }
    assertThat(rig.firstDraw.isCompleted).isFalse()
    assertThat(rig.graph.live.value).isNotNull()
    assertThat(rig.slot.runner).isNotNull()
    assertThat(rig.slot.awaitRunner(Attese.TETTO)).isSameInstanceAs(rig.graph.live.value!!.run)
    assertThat(rig.builds.get()).isEqualTo(1)
  }

  /** Nessun NAS: i ricordi non si monteranno mai e il lavoro non aspetta niente. */
  @Test
  fun senzaFamigliaIlLavoroNonAspetta() = runBlocking<Unit> {
    val rig = rig(canMount = false)
    assertThat(rig.graph.awaitLive(Attese.TETTO)).isNull()
    assertThat(rig.slot.awaitRunner(Attese.TETTO)).isNull()
  }

  /** Il lavoro aspetta anche l'istantanea della Libreria: senza, il motore non sa dire quali ricordi ci sono. */
  @Test
  fun ilLavoroAspettaLIstantaneaDellaLibreria() = runBlocking<Unit> {
    val rig = rig()
    val snapshot = checkNotNull(rig.lib.visibility.snapshot.value)
    rig.lib.visibility.snapshot.value = null
    rig.ready.value = true
    val waiting = CoroutineScope(Dispatchers.Default + Job()).launch { rig.graph.awaitNotifyReady(Attese.TETTO) }
    until("i ricordi montati") { rig.graph.live.value != null }
    // Montati, ma la Libreria non c'e' ancora: il lavoro aspetta.
    assertThat(waiting.isCompleted).isFalse()
    rig.lib.visibility.snapshot.value = snapshot
    withTimeout(Attese.TETTO) { waiting.join() }
  }

  // --- RP-1 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Prima del montaggio la facciata non dice niente su un ricordo ("non lo so ancora": le schermate restano in caricamento, mai "Non trovo questo ricordo"); a ricordi
   * montati e Libreria letta dice la verita' (qui un id che non c'e': `null`). Senza famiglia i ricordi non si monteranno mai e "non c'e'" e' la verita'.
   */
  @Test
  fun primaDelMontaggioLaFacciataNonDiceNiente() = runBlocking<Unit> {
    val rig = rig()
    val ghost = MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2001, 1, 1)))
    val answers = CopyOnWriteArrayList<MemoryView?>()
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      scope.launch { rig.graph.memories.memory(ghost).collect { answers += it } }
      rig.graph.start()
      assertThat(answers).isEmpty()
      rig.firstDraw.complete(Unit)
      rig.ready.value = true
      until("l'ora di montare e di rispondere") { answers.isNotEmpty() }
      // Montati: `null`, cioe' "non c'e'" (anche piu' di una volta: il flusso si rifa' a ogni cambio dello scaffale), mai altro.
      assertThat(answers.toList().all { it == null }).isTrue()
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun senzaFamigliaLaFacciataDiceNonCe() = runBlocking<Unit> {
    val rig = rig(canMount = false)
    val ghost = MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2001, 1, 1)))
    assertThat(withTimeout(Attese.TETTO) { rig.graph.memories.memory(ghost).first() }).isNull()
  }

  // --- MO-2 -----------------------------------------------------------------------------------------------------------------------

  /** Un errore del motore nella generazione notturna non esce: la manutenzione va avanti con le Utility, e l'errore e' contato (Diagnostica). La notte dopo si riprova. */
  @Test
  fun unErroreDelMotoreNonFaSaltareLaManutenzione() = runBlocking<Unit> {
    val trips = SwitchableTrips().also { it.fail = true }
    val rig = rig(trips = trips)
    rig.ready.value = true
    assertThat(rig.graph.nightly()).isFalse()
    assertThat(rig.counters.roundErrors).isEqualTo(1)
    // Un guasto che passa: la notte dopo il giro riesce.
    trips.fail = false
    assertThat(rig.graph.nightly()).isTrue()
    assertThat(rig.counters.roundErrors).isEqualTo(1)
    assertThat(rig.graph.live.value!!.engine.shelf.value.items).isNotEmpty()
  }

  /** Famiglia non pronta: niente da generare, e niente eccezione. */
  @Test
  fun laNotteSenzaFamigliaNonFaNienteENonLancia() = runBlocking<Unit> {
    val rig = rig()
    assertThat(rig.graph.nightly()).isFalse()
    assertThat(rig.counters.roundErrors).isEqualTo(0)
  }

  // --- MO-3 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Un giro di oggi fatto con la Libreria ancora quasi vuota fissa "oggi" senza ricordi, e `OPEN` non lo rifa'. Con Raccolte aperta, quando la Libreria cambia e lo scaffale
   * e' vuoto il giro si rifa' da solo (e ora i ricordi ci sono).
   */
  @Test
  fun ilGiroDiOggiSiRifaQuandoLaLibreriaCambiaEOggiLoScaffaleEVuoto() = runBlocking<Unit> {
    val rig = rig(withMemory = false)
    rig.ready.value = true
    rig.firstDraw.complete(Unit)
    rig.graph.start()
    rig.graph.onCollectionsOpened()
    until("il primo giro, con la Libreria vuota") { rig.counters.rounds >= 1 && rig.graph.live.value != null }
    assertThat(rig.graph.live.value!!.engine.shelf.value.items).isEmpty()
    val rounds = rig.counters.rounds
    // La lettura del telefono porta le foto: un anniversario che ieri non c'era.
    rig.lib.photos(rig.today.minusYears(1), 9, place = 3_100_001)
    withTimeout(Attese.TETTO) { rig.graph.memories.shelf.first { it.items.isNotEmpty() } }
    assertThat(rig.counters.rounds).isGreaterThan(rounds)
  }

  /** Raccolte aperta prima del montaggio (la famiglia non era pronta): il suo giro non si perde, parte al montaggio. */
  @Test
  fun ilGiroDiRaccolteAperteInAnticipoParteAlMontaggio() = runBlocking<Unit> {
    val rig = rig()
    rig.firstDraw.complete(Unit)
    rig.graph.start()
    rig.graph.onCollectionsOpened()
    assertThat(rig.counters.rounds).isEqualTo(0)
    rig.ready.value = true
    until("il giro di oggi al montaggio") { rig.counters.rounds >= 1 }
    withTimeout(Attese.TETTO) { rig.graph.memories.shelf.first { it.items.isNotEmpty() } }
  }

  /** Mai nei lavori in background: se Raccolte non e' stata aperta in questo processo la Libreria che cambia non fa partire nessun giro. */
  @Test
  fun senzaRaccolteApertaNessunGiroInPiu() = runBlocking<Unit> {
    val rig = rig(withMemory = false)
    rig.ready.value = true
    val live = checkNotNull(rig.graph.awaitLive(Attese.TETTO))
    rig.graph.lightRoundIfEmpty(live)
    assertThat(rig.counters.rounds).isEqualTo(0)
    // Con Raccolte aperta si'.
    rig.graph.onCollectionsOpened()
    until("il giro all'apertura") { rig.counters.rounds >= 1 }
    val rounds = rig.counters.rounds
    rig.graph.lightRoundIfEmpty(live)
    assertThat(rig.counters.rounds).isGreaterThan(rounds)
  }

  // --- AZ-1 -----------------------------------------------------------------------------------------------------------------------

  /** Lo stop del grafo ferma anche il giorno di una notifica: dopo, nessuna corsa scrive, pubblica o ripianifica. */
  @Test
  fun loStopDelGrafoFermaIlGiornoDiUnaNotifica() = runBlocking<Unit> {
    val rig = rig()
    rig.ready.value = true
    val live = checkNotNull(rig.graph.awaitLive(Attese.TETTO))
    rig.graph.stop()
    val planned = rig.scheduler.scheduled
    assertThat(live.run.run()).isEqualTo(NotifyOutcome.Skipped(dev.pampa.mycloudgallery.core.memories.contract.NotifySkip.NOTHING))
    live.run.replan()
    assertThat(rig.scheduler.scheduled).isEqualTo(planned)
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.slot.runner).isNull()
    assertThat(rig.graph.live.value).isNull()
  }
}
