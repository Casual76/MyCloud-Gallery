package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Unita' di lavoro e prenotazioni (D13, §5.6 M4): scelta, parti stabili, rilettura dell'indice, rinnovo, rilascio. */
class WorkUnitsTest {

  private val share = "test-comune"
  private val world = TestWorld()
  private val media = simMedia(world, share, listOf("2024-03"), perMonth = 450, Random(2)) +
    simMedia(world, share, listOf("2024-05"), perMonth = 50, Random(3))
  private val a = SimWorker(world, world.device("A"), media)
  private val b = SimWorker(world, world.device("B"), media)

  private fun sortedMonth(month: String) = media.filter { it.month == month }.sortedBy { it.file.contentKey.hex }

  @Test
  fun dalMesePiuRecente_partiDiDuecento_primaQuelleConMediaVisibili() = runTest {
    val first = a.units.next(share, WorkPass.PREP, a.priority)!!
    assertThat(first.key.resource).isEqualTo("work/1/prep/2024-05/0")
    assertThat(first.items).hasSize(50)
    assertThat(first.lease).isInstanceOf(UnitLease.Held::class.java)

    val second = a.units.next(share, WorkPass.PREP, a.priority)!!
    assertThat(second.key.resource).isEqualTo("work/1/prep/2024-03/0")
    assertThat(second.items.map { it.contentKey }).isEqualTo(sortedMonth("2024-03").take(200).map { it.file.contentKey })

    val visible = sortedMonth("2024-03")[420].file.contentKey
    b.priority.visible.value = setOf(visible.short)
    val third = b.units.next(share, WorkPass.PREP, b.priority)!!
    assertThat(third.key.resource).isEqualTo("work/1/prep/2024-03/2")
    assertThat(third.items).hasSize(50)
  }

  @Test
  fun dueDispositivi_nonPrendonoLaStessaUnita_ePoiSiPassanoIlLavoro() = runTest {
    val taken = a.units.next(share, WorkPass.PREP, a.priority)!!
    val other = b.units.next(share, WorkPass.PREP, b.priority)!!
    assertThat(other.key).isNotEqualTo(taken.key)
    assertThat(b.units.stats.busy.get()).isEqualTo(1)

    a.units.finish(taken, UnitOutcome.Completed(0, 0))
    val c = SimWorker(world, world.device("C"), media)
    assertThat(c.units.next(share, WorkPass.PREP, c.priority)!!.key).isEqualTo(taken.key)
  }

  @Test
  fun cioCheGliAltriHannoGiaPubblicato_siToglieDallUnita() = runTest {
    val part = sortedMonth("2024-05")
    for (m in part.take(30)) a.publisher.stage(share, listOf(m.file.put(setOf(SegmentClass.THUMB_S))), listOf(m.file.thumb()))
    a.publisher.flush(share, FlushReason.UNIT_FINISHED)

    val claim = b.units.next(share, WorkPass.PREP, b.priority)!!
    assertThat(claim.key.resource).isEqualTo("work/1/prep/2024-05/0")
    assertThat(claim.items.map { it.contentKey }).containsExactlyElementsIn(part.drop(30).map { it.file.contentKey })
  }

  @Test
  fun rinnovo_generazioneNuova_eSenzaICompletatiDagliAltri() = runTest {
    val claim = a.units.next(share, WorkPass.PREP, a.priority)!!
    val held = claim.lease as UnitLease.Held
    val part = sortedMonth("2024-05")
    for (m in part.take(10)) b.publisher.stage(share, listOf(m.file.put(setOf(SegmentClass.THUMB_S))), listOf(m.file.thumb()))
    b.publisher.flush(share, FlushReason.UNIT_FINISHED)
    world.clock.advance(TrustLimits.LEASE_RENEW_MILLIS)

    val renewed = (a.units.keepAlive(claim) as KeepAliveResult.Renewed).claim
    val lease = renewed.lease as UnitLease.Held
    assertThat(lease.generation).isEqualTo(held.generation + 1)
    assertThat(lease.untilNasMillis).isEqualTo(world.clock.nowMillis() + TrustLimits.MAX_LEASE_MILLIS)
    assertThat(renewed.items).hasSize(40)
  }

  @Test
  fun prenotazioneScadutaEPresaDaUnAltro_lUnitaSiLascia() = runTest {
    val claim = a.units.next(share, WorkPass.PREP, a.priority)!!
    world.clock.advance(TrustLimits.MAX_LEASE_MILLIS + 1)

    val stolen = b.units.next(share, WorkPass.PREP, b.priority)!!
    assertThat(stolen.key).isEqualTo(claim.key)
    assertThat(a.units.keepAlive(claim)).isEqualTo(KeepAliveResult.Lost)
  }

  @Test
  fun unUnitaInterrottaConLavoroNonPubblicato_restaPrenotata() = runTest {
    val claim = a.units.next(share, WorkPass.PREP, a.priority)!!
    a.claim = claim
    a.lastRenew = world.clock.nowMillis()
    a.step(count = 5) // preparati e nell'outbox; la testa e' rimandata di 5 minuti
    a.publisher.flush(share, FlushReason.UNIT_FINISHED)
    a.step(count = 5)
    a.units.finish(claim, UnitOutcome.Interrupted("STAGE_END"))
    assertThat(a.units.stats.kept.get()).isEqualTo(1)
    assertThat(b.units.next(share, WorkPass.PREP, b.priority)!!.key).isNotEqualTo(claim.key)

    // Pubblicato tutto: la prossima interruzione rilascia, e un altro dispositivo continua da dove si era arrivati.
    val again = a.units.next(share, WorkPass.PREP, a.priority)!!
    assertThat(again.key).isEqualTo(claim.key)
    a.publisher.flush(share, FlushReason.UNIT_FINISHED)
    a.units.finish(again, UnitOutcome.Interrupted("STAGE_END"))
    val c = SimWorker(world, world.device("C"), media)
    val continued = c.units.next(share, WorkPass.PREP, c.priority)!!
    assertThat(continued.key).isEqualTo(claim.key)
    assertThat(continued.items).hasSize(40)
  }

  @Test
  fun scritturaBloccataOShareVera_siLavoraSenzaPrenotazione() = runTest {
    a.device.gate.block(share, WriteBlock.CLOCK_JUMP)
    val claim = a.units.next(share, WorkPass.PREP, a.priority)!!
    assertThat(claim.lease).isEqualTo(UnitLease.Unleased)
    assertThat(world.nas.share(share).list(NasLayout.ROOT.child("leases"), 10).names).isEmpty()
    assertThat(a.units.stats.unleased.get()).isEqualTo(1)

    val real = "alessio"
    val realMedia = simMedia(world, real, listOf("2023-01"), perMonth = 5, Random(9))
    val worker = SimWorker(world, world.device("D"), realMedia, real)
    assertThat(worker.units.next(real, WorkPass.PREP, worker.priority)!!.lease).isEqualTo(UnitLease.Unleased)
    assertThat(world.nas.share(real).fileCount()).isEqualTo(0)
  }

  @Test
  fun cartellaDellePrenotazioniInondata_siLavoraSenzaEContandolo() = runTest {
    val dir = NasLayout.leaseDir("work/1/prep/2024-05/0")
    repeat(TrustLimits.MAX_LIST_LEASE_SHARDS + 1) { world.nas.share(share).put(dir.child("x$it").child("y"), ByteArray(1)) }

    val claim = a.units.next(share, WorkPass.PREP, a.priority)!!
    assertThat(claim.key.resource).isEqualTo("work/1/prep/2024-05/0")
    assertThat(claim.lease).isEqualTo(UnitLease.Unleased)
    assertThat(a.units.stats.contended.get()).isEqualTo(1)
  }
}
