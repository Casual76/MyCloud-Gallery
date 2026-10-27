package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.testing.FailingFacesPorts
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regole, temperatura, spazio, NAS e recinzione (§2.3 "Edge case"; E-A5, E-A6, E-A17, D34, D35): il ritmo si riduce col caldo, a `SEVERE`
 * ci si ferma, le regole si chiedono **una volta per lotto**, sotto 1 GiB libero non si calcola, con la recinzione niente va sul NAS, con il
 * cancello chiuso si lavora in locale, senza NAS si fa cio' che c'e'.
 */
class PaceAndFenceTest {
  private val forever = SimWorld.FOREVER

  private class Case(media: Int, here: HereState = HereState.ACTIVE, config: FacesComputeConfig = FacesComputeConfig.DEFAULT) {
    val w = SimWorld()
    val keys = (1..media).map { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it % 9 + 1)), month = "2024-03") }
    val phone = w.device(w.anna, "telefono-anna", here = here).also { it.restart(config) }

    suspend fun run() = phone.engine.run(phone.area(AreaKind.PRIVATE), SimWorld.FOREVER)
  }

  /** E-A5: col caldo (`LIGHT`) lotti da 5 e una pausa di 2 s fra un lotto e l'altro; mai una pausa dopo l'ultimo. */
  @Test
  fun colCaldoLottiDa5ConPausaDi2Secondi() = runTest {
    val c = Case(12)
    c.phone.control.permit = FacesPermit.Go(reducedPace = true)

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(12)
    assertThat(c.phone.pauses).containsExactly(2.seconds, 2.seconds) // lotti da 5, 5 e 2
    assertThat(c.phone.control.asked).isEqualTo(3) // le regole una volta per lotto
  }

  @Test
  fun senzaCaldoNientePauseELottiDa20() = runTest {
    val c = Case(12)
    c.run()
    assertThat(c.phone.pauses).isEmpty()
    assertThat(c.phone.control.asked).isEqualTo(1)

    val big = Case(45)
    big.run()
    assertThat(big.phone.control.asked).isEqualTo(3) // 20, 20, 5
  }

  /** `SEVERE`: stop; la ripresa non rifa' niente. */
  @Test
  fun aSevereStop_laRipresaNonRifaNiente() = runTest {
    val c = Case(12)
    c.phone.control.permit = FacesPermit.Go(reducedPace = true)
    c.phone.control.onAsk = { n -> if (n == 2) c.phone.control.permit = FacesPermit.Stop(FacesStop.RULES) }

    val first = c.run()

    assertThat(first.stop).isEqualTo(FacesStop.RULES)
    assertThat(first.analyzed).isEqualTo(5)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.RULES))
    assertThat(c.w.nas.publishedBy(c.w.anna.generation, c.phone.id)).hasSize(5)
    assertThat(c.w.nas.leases).isEmpty() // unita' lasciata

    c.phone.control.permit = FacesPermit.Go(reducedPace = false)
    val second = c.run()
    assertThat(second.analyzed).isEqualTo(7)
    assertThat(c.phone.archive.perKey.values.toSet()).containsExactly(1)
    assertThat(c.phone.ticking.detects).isEqualTo(12)
  }

  /** E-A17: sotto 1 GiB libero non si calcola. */
  @Test
  fun sottoUnGiBLibero_nonSiCalcola() = runTest {
    val c = Case(3)
    c.phone.freeBytes = FacesFreeSpace.MIN_FREE_BYTES - 1
    val r = c.run()
    assertThat(r.stop).isEqualTo(FacesStop.STORAGE_LOW)
    assertThat(c.phone.ticking.detects).isEqualTo(0)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.STORAGE))

    c.phone.freeBytes = FacesFreeSpace.MIN_FREE_BYTES
    assertThat(c.run().analyzed).isEqualTo(3)
  }

  // ---- La recinzione (D34) e i cancelli (D35) -----------------------------------------------------------------------------------------------

  /** D34: con la recinzione le porte del NAS **non si chiamano nemmeno**: le prenotazioni falliscono se chiamate, il pubblicatore non e' toccato. */
  @Test
  fun conLaRecinzioneNienteVaSulNas() = runTest {
    val c = Case(6, here = HereState.LOCAL_ONLY)
    c.phone.restart(parts = c.phone.parts().copy(leases = FailingFacesPorts.leases))

    val results = c.phone.engine.runAll(forever)

    assertThat(results.all { it.second.stop == FacesStop.DONE }).isTrue()
    assertThat(results.first().second.analyzed).isEqualTo(6)
    assertThat(c.phone.publisher.flushCalls).isEqualTo(0)
    assertThat(c.phone.publisher.staged).containsExactlyElementsIn(c.keys) // in coda, pronte per quando si accendera'
    assertThat(c.w.nas.writes).isEmpty()
    assertThat(c.w.nas.leases).isEmpty()
    assertThat(c.w.nas.published).isEmpty()
  }

  /** La recinzione che si accende a meta' tappa: da quel momento niente prenotazioni e niente pubblicazioni, il lavoro locale continua. */
  @Test
  fun laRecinzioneSiAccendeAMeta() = runTest {
    val c = Case(6, config = FacesComputeConfig(batch = 2, reducedBatch = 1))
    c.phone.control.onAsk = { n ->
      if (n == 2) c.phone.switch.state.value = dev.pampa.mycloudgallery.core.faces.contract.FacesState.On(c.phone.generation, HereState.LOCAL_ONLY)
    }

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(6)
    assertThat(c.phone.leases.acquired).isEqualTo(1) // presa prima
    assertThat(c.phone.publisher.flushCalls).isEqualTo(0) // a fine unita' la recinzione c'e': niente
    assertThat(c.w.nas.published).isEmpty()
  }

  /** Il cancello chiuso (congelamento...): le prenotazioni rispondono `Blocked`, si lavora in locale, la pubblicazione resta in coda; riaperto, si pubblica. */
  @Test
  fun cancelloChiuso_siLavoraInLocaleESiPubblicaDopo() = runTest {
    val c = Case(4)
    c.phone.leases.block = FacesWriteBlock.FROZEN
    c.phone.publisher.block = FacesWriteBlock.FROZEN

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(4)
    assertThat(c.phone.engine.counters.leaseTroubles.get()).isEqualTo(1)
    assertThat(c.w.nas.published).isEmpty()
    assertThat(c.phone.publisher.staged).hasSize(4)

    // Il congelamento passa: la visita dopo dell'unita' (non segnata finita? lo e', i corpi sono in coda) li pubblica al primo giro del pubblicatore.
    c.phone.leases.block = null
    c.phone.publisher.block = null
    c.phone.publisher.flush(dev.pampa.mycloudgallery.core.faces.contract.FlushReason.SYNC_ROUND)
    assertThat(c.w.nas.publishedBy(c.w.anna.generation, c.phone.id)).hasSize(4)
    assertThat(c.phone.ticking.detects).isEqualTo(4) // nessun ricalcolo
  }

  /** E-A6: senza NAS si fa cio' che c'e' in locale (nel buffer), il resto aspetta; tornato il NAS il resto si calcola e niente si rifa'. */
  @Test
  fun senzaNas_fattoCioCheCeraInLocale() = runTest {
    val c = Case(6)
    c.keys.take(2).forEach { c.phone.buffer += it }
    c.w.nas.offline = true

    val r = c.run()

    assertThat(r.stop).isEqualTo(FacesStop.NO_NAS)
    assertThat(r.analyzed).isEqualTo(2)
    assertThat(r.waiting).isEqualTo(4)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.NAS))
    assertThat(c.w.nas.published).isEmpty()

    c.w.nas.offline = false
    val second = c.run()
    assertThat(second.stop).isEqualTo(FacesStop.DONE)
    assertThat(second.analyzed).isEqualTo(4)
    assertThat(c.phone.archive.perKey.values.toSet()).containsExactly(1)
    assertThat(c.w.nas.publishedBy(c.w.anna.generation, c.phone.id)).hasSize(6)
  }

  /** Il raggruppamento (D21) parte a fine unita', con il tempo che resta; un suo guasto non ferma il calcolo. */
  @Test
  fun raggruppamentoAFineUnita_unGuastoNonFermaNiente() = runTest {
    val c = Case(4)
    c.phone.grouping.failWith = IllegalStateException("archivio chiuso")
    val r = c.run()
    assertThat(r.analyzed).isEqualTo(4)
    assertThat(c.phone.grouping.incrementals).isEqualTo(1)
    assertThat(c.phone.engine.counters.groupingFailures.get()).isEqualTo(1)
    assertThat(c.w.nas.publishedBy(c.w.anna.generation, c.phone.id)).hasSize(4)
  }

  /** L'anteprima chiesta al lotto (D17): chi sa scaricarle insieme (lotti di 50) le prende prima del lotto, una volta per gruppo di 50. */
  @Test
  fun leAnteprimeSiScaricanoInAnticipoAGruppiDi50() = runTest {
    val c = Case(120)
    c.phone.inputs.prefetching = true
    val r = c.run()
    assertThat(r.analyzed).isEqualTo(120)
    assertThat(c.phone.inputs.prefetchCalls).isEqualTo(3) // 120 media in gruppi da 50
    assertThat(c.phone.inputs.prefetchedItems).isEqualTo(120)
    // Gia' nel buffer: nessuna lettura dell'indice per media.
    assertThat(c.w.nas.previewBytesRead).isEqualTo(0L)
  }
}
