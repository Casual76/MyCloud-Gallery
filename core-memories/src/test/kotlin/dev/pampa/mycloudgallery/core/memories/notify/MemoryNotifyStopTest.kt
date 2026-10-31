package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.plan.dayShots
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test

/**
 * La fine del giorno di una notifica (AZ-1, 31/10) e la Libreria non ancora letta (RP-2): una corsa in corso si **annulla e si aspetta** in `stop`, anche una corsa che non si
 * lascia annullare non scrive il registro e non pubblica dopo lo stop, e dopo lo stop niente si ripianifica. Con la Libreria non letta il motore sta zitto: la corsa non resta
 * ad aspettare per sempre ("niente di bello oggi").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryNotifyStopTest {

  private val today = LocalDate.of(2026, 11, 3)
  private val rigs = ArrayList<NotifyRig>()

  @After
  fun chiudiTutto() {
    rigs.forEach { it.close() }
  }

  private fun rig(): NotifyRig = NotifyRig(dayShots(today.minusYears(1), 12, series = 1), today).also { rigs += it }

  /** Un registro che ferma la lettura finche' il test non la lascia andare: la corsa e' "in corso" dentro di essa. */
  private class GatedLedger(private val inner: NotifyLedgerStore, private val cancellable: Boolean) : NotifyLedgerStore by inner {
    val entered = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun read(): NotifyLedger {
      entered.complete(Unit)
      if (cancellable) gate.await() else withContext(NonCancellable) { gate.await() }
      return inner.read()
    }
  }

  /** Una corsa in corso (ferma sulla lettura del registro) si annulla e si aspetta: al ritorno di `stop` nessun registro, nessuna notifica, nessun lavoro di domani. */
  @Test
  fun unaCorsaInCorsoSiAnnullaESiAspettaInStop() = runTest {
    val rig = rig()
    val gated = GatedLedger(rig.ledger, cancellable = true)
    val run = rig.run(ledgerStore = gated)
    val outcome = async { run.run() }
    gated.entered.await()
    run.stop()
    assertThat(outcome.await()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
    // Il lavoro di domani non si rifa' dopo lo stop (l'azzeramento lo ha tolto) e l'esito non e' un giorno saltato per la Diagnostica.
    assertThat(rig.scheduler.scheduled).isEqualTo(0)
    assertThat(rig.events.skips).isEmpty()
  }

  /**
   * Una corsa che non si lascia annullare (una lettura gia' partita, un file da scrivere) viene **aspettata**, e quando riparte guarda `stopped` prima di scrivere il
   * registro e prima di pubblicare: dopo lo stop niente torna al posto di cio' che l'azzeramento ha tolto.
   */
  @Test
  fun unaCorsaCheNonSiLasciaAnnullareNonScriveNePubblicaDopoLoStop() = runTest {
    val rig = rig()
    val gated = GatedLedger(rig.ledger, cancellable = false)
    val run = rig.run(ledgerStore = gated)
    val outcome = async { run.run() }
    gated.entered.await()
    val stopping = launch { run.stop() }
    runCurrent()
    // `stop` aspetta la corsa: non e' ancora tornato.
    assertThat(stopping.isCompleted).isFalse()
    gated.gate.complete(Unit)
    stopping.join()
    assertThat(outcome.await()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
    assertThat(rig.scheduler.scheduled).isEqualTo(0)
  }

  /** Dopo lo stop ogni corsa e' vuota e nemmeno `replan` e `watch` programmano niente; `stop` si puo' ripetere. */
  @Test
  fun dopoLoStopNienteDaFareENienteDaPianificare() = runTest {
    val rig = rig()
    val run = rig.run()
    run.stop()
    run.stop()
    assertThat(run.run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    run.replan()
    assertThat(rig.scheduler.scheduled).isEqualTo(0)
    assertThat(rig.scheduler.cancelled).isEqualTo(0)
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
  }

  /** Senza `stop` tutto resta com'era: la stessa corsa pubblica e ripianifica (la prova che le altre sono ferme per lo stop e non per altro). */
  @Test
  fun senzaStopLaCorsaFaIlSuoLavoro() = runTest {
    val rig = rig()
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    assertThat(rig.scheduler.scheduled).isEqualTo(1)
  }

  // --- La Libreria non ancora letta (RP-2) --------------------------------------------------------------------------------------------

  /** Senza l'istantanea della Libreria non si sa quali ricordi ci sono: "niente di bello oggi", subito, e il lavoro di domani si rifa'. */
  @Test
  fun conLaLibreriaNonLettaNienteDiBelloOggi() = runTest {
    val rig = rig()
    rig.night()
    rig.fake.snapshot.value = null
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
    assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
  }

  /** Un motore che non risponde mai (la Libreria si e' persa a meta' corsa) non tiene la corsa per sempre: un tetto, poi "non e' un candidato". */
  @Test
  fun unMotoreCheNonRispondeNonTieneLaCorsa() = runTest {
    val rig = rig()
    rig.night()
    val silent = object : dev.pampa.mycloudgallery.core.memories.contract.Memories by rig.counting {
      override fun memory(id: MemoryId): Flow<MemoryView?> = flow { awaitCancellation() }
    }
    val run = MemoryNotifyRun(
      MemoryNotifyPolicy(), rig.settings, rig.ledger, silent, rig.visibility, SeenTodayOpened(rig.engine.personal), rig.access, rig.publisher, rig.clock, rig.scheduler,
      rig.events,
    )
    assertThat(run.run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
  }
}
