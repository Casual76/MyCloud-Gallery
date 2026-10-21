package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiBuffer
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regole, temperatura e buffer (§2.2 "Edge case", D10, D22): E-A5, E-A6, E-A17 e i tetti del buffer. */
class PaceAndBufferTest {
  @get:Rule val tmp = TemporaryFolder()

  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  private class Script(val paces: () -> AiPace) : AiRunControl {
    var checks = 0
    val points = ArrayList<AiCheckpoint>()

    override suspend fun check(): AiPace {
      checks++
      return paces()
    }

    override suspend fun checkpoint(point: AiCheckpoint) {
      points += point
    }
  }

  /** E-A6: notte senza NAS, `Run(LOCAL_BUFFER_ONLY)`: si calcola solo il buffer e ci si ferma con lo stato giusto. */
  @Test
  fun unaNotteSenzaNasElaboraSoloIlBuffer() = runTest {
    val f = TestFamily()
    val keys = (1..10).map { f.nas.add(common, concepts = "notte $it") }
    val phone = f.anna()
    val sink = BufferAnalysisSink(phone.buffer) { true }
    keys.take(3).forEach { sink.deliver(it, listOf(PrepOutput.Image(SegmentClass.ANALYSIS, "FAKE:notte locale".toByteArray(), 1024, 768))) }
    f.nas.offline = true

    val r = phone.engine.run(AiScope.Share(common), forever, Script { AiPace.Go(localOnly = true) })

    assertThat(r.computed).isEqualTo(3)
    assertThat(r.stoppedBy).isEqualTo(AiStop.BUFFER_ONLY)
    assertThat(phone.engine.status.value).isEqualTo(AiStatus.BufferOnlyDone(3))
    assertThat(phone.fetcher.calls).isEqualTo(0)
    assertThat(phone.units.claims).isEmpty()
    assertThat(phone.publisher.pending.value.staged).isEqualTo(3) // pronti per il primo giro con il NAS

    // Il NAS torna: il resto si calcola, i tre del buffer non si rifanno.
    f.nas.offline = false
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(phone.embedded).isEqualTo(10)
    assertThat(f.published(common, phone.id)).containsExactlyElementsIn(keys)
  }

  /** E-A6 con il NAS che sparisce a meta': le regole passano a solo-buffer fra un lotto e l'altro. */
  @Test
  fun leRegolePassanoASoloBufferAMetaTappa() = runTest {
    val f = TestFamily()
    (1..6).forEach { f.nas.add(common, concepts = "sera $it") }
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 2, reducedBatch = 1))
    var n = 0
    val r = phone.engine.run(AiScope.Share(common), forever, Script { if (++n > 1) AiPace.Go(localOnly = true) else AiPace.GO })
    assertThat(r.stoppedBy).isEqualTo(AiStop.BUFFER_ONLY)
    // Il primo lotto e' fatto e pubblicato; il resto non ha anteprime nel buffer e aspetta il NAS.
    assertThat(phone.embedded).isEqualTo(2)
    assertThat(f.nas.of(common).leases).isEmpty()
  }

  /** E-A5: col caldo lotti da 5 e pause di 2 s; a `SEVERE` stop, e la ripresa non ricalcola niente. */
  @Test
  fun ritmoRidottoColCaldoEStopASevere() = runTest {
    val f = TestFamily()
    (1..12).forEach { f.nas.add(common, concepts = "sole $it") }
    val phone = f.anna()
    val pauses = ArrayList<Duration>()
    phone.restart(pauses = pauses)
    val hot = Script { AiPace.Go(reduced = true) }
    phone.engine.run(AiScope.Share(common), forever, hot)
    assertThat(pauses).hasSize(3) // lotti da 5, 5 e 2
    assertThat(pauses.toSet()).containsExactly(2.seconds)
    assertThat(hot.points.mapNotNull { it.lastKey }).hasSize(3)

    val g = TestFamily()
    (1..12).forEach { g.nas.add(common, concepts = "afa $it") }
    val other = g.anna()
    var n = 0
    val severe = Script { if (++n <= 1) AiPace.Go(reduced = true) else AiPace.Halt(AiStop.RULES) }
    val r = other.engine.run(AiScope.Share(common), forever, severe)
    assertThat(r.stoppedBy).isEqualTo(AiStop.RULES)
    assertThat(r.computed).isEqualTo(5)
    assertThat(g.published(common, other.id)).hasSize(5)
    assertThat(g.nas.of(common).leases).isEmpty() // unita' lasciata (Interrupted)
    other.engine.run(AiScope.Share(common), forever)
    assertThat(other.embedded).isEqualTo(12)
    assertThat(other.images.perImage.values.toSet()).containsExactly(1)
  }

  /** Senza caldo: niente pause, lotti da 20. */
  @Test
  fun ritmoNormaleSenzaPause() = runTest {
    val f = TestFamily()
    (1..12).forEach { f.nas.add(common, concepts = "fresco $it") }
    val phone = f.anna()
    val pauses = ArrayList<Duration>()
    phone.restart(pauses = pauses)
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(pauses).isEmpty()
  }

  /** E-A17: sotto 1 GiB libero il buffer si svuota e il calcolo si ferma con il motivo. */
  @Test
  fun sottoUnGiBLiberoBufferSvuotatoECalcoloFermo() = runTest {
    val f = TestFamily()
    val keys = (1..3).map { f.nas.add(common, concepts = "pieno $it") }
    var free = 8L * 1024 * 1024 * 1024
    val disk = DiskAiBuffer(tmp.newFolder("ai-buffer"), freeSpace = { free })
    val phone = SimDevice(f.nas, "telefono-anna", "anna", listOf(TestFamily.ANNA_PRIVATE, common), buffer = disk)
    disk.put(keys[0], SegmentClass.ANALYSIS, "FAKE:pieno".toByteArray())
    free = AiBuffer.MIN_FREE_BYTES - 1
    phone.freeBytes = free

    val r = phone.engine.run(AiScope.Share(common), forever)

    assertThat(r.stoppedBy).isEqualTo(AiStop.STORAGE_LOW)
    assertThat(phone.embedded).isEqualTo(0)
    assertThat(disk.usedBytes.value).isEqualTo(0)
    assertThat(phone.engine.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.STORAGE_LOW))
    assertThat(phone.engine.counters.storageLow.get()).isEqualTo(1)
  }

  /** D10: tetti del buffer (500 MB, 1 GiB libero), temporanei orfani tolti alla riapertura, svuotamento di cio' che e' fatto. */
  @Test
  fun tettiDelBufferERipresaDalDisco() = runTest {
    val dir = tmp.newFolder("buffer")
    var free = 4L * 1024 * 1024 * 1024
    val buffer = DiskAiBuffer(dir, freeSpace = { free }, maxBytes = 100)
    val nas = SimNas()
    val a = nas.newKey()
    val b = nas.newKey()
    assertThat(buffer.put(a, SegmentClass.ANALYSIS, ByteArray(60) { 1 })).isTrue()
    assertThat(buffer.put(b, SegmentClass.ANALYSIS, ByteArray(60) { 2 })).isFalse() // oltre il tetto
    assertThat(buffer.put(a, SegmentClass.ANALYSIS, ByteArray(90) { 3 })).isTrue() // sostituisce
    assertThat(buffer.usedBytes.value).isEqualTo(90)
    assertThat(buffer.put(b, SegmentClass.THUMB_S, ByteArray(5))).isFalse() // solo analysis, frames e hq
    free = AiBuffer.MIN_FREE_BYTES + 5
    assertThat(buffer.put(b, SegmentClass.HQ, ByteArray(6))).isFalse() // resterebbe meno di 1 GiB

    File(dir, "${b.hex}.analysis.tmp").writeBytes(ByteArray(7)) // morte durante una scrittura
    File(dir, "estraneo.txt").writeBytes(ByteArray(3))
    val reopened = DiskAiBuffer(dir, freeSpace = { free }, maxBytes = 100)
    assertThat(reopened.usedBytes.value).isEqualTo(90)
    assertThat(dir.list()!!.toList()).containsExactly("${a.hex}.analysis")
    assertThat(reopened.get(a, SegmentClass.ANALYSIS)!!.size).isEqualTo(90)
    reopened.evict(listOf(a))
    assertThat(reopened.usedBytes.value).isEqualTo(0)
    assertThat(reopened.keys()).isEmpty()
  }

  /** Il buffer pieno ferma lo scaricamento: i media restano "in attesa", niente di rotto. */
  @Test
  fun bufferPienoIMediaAspettano() = runTest {
    val f = TestFamily()
    (1..4).forEach { f.nas.add(common, concepts = "x$it") }
    val phone = SimDevice(f.nas, "telefono-anna", "anna", listOf(common), buffer = FakeAiBuffer(maxBytes = 40))
    val r = phone.engine.run(AiScope.Share(common), forever)
    assertThat(r.computed + r.waitingForPreparation).isEqualTo(4)
    assertThat(r.waitingForPreparation).isGreaterThan(0)
  }
}
