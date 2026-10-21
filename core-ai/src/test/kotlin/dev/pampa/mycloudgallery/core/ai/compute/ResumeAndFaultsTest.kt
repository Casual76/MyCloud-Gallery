package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ripresa e guasti (§4.3 "ogni passo in piu' tappe", §5.5 invarianti 4-8, D22, E-I2, E-A13): `FaultPlan` a ogni chiamata al NAS
 * (prima e dopo l'effetto) e morte del processo dopo ogni salvataggio. Dopo la ripresa nessun media si ricalcola e il lavoro arriva
 * in fondo: ogni media ha vettore e testo pubblicati.
 */
class ResumeAndFaultsTest {
  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  private class World(val f: TestFamily, val phone: SimDevice, val keys: List<ContentKey>)

  /** 6 foto e 2 video, testo attivo: 16 salvataggi (vettore e testo per ognuno). */
  private fun world(): World {
    val f = TestFamily(unitSize = 3)
    val keys = (1..8).map { i ->
      f.nas.add(common, month = "2024-0${1 + i % 2}", kind = if (i % 4 == 0) MediaKind.VIDEO else MediaKind.PHOTO, concepts = "mare $i")
    }
    val phone = f.anna(reading = true)
    val line = OcrLine(ShortBox(0, 0, 60_000, 5_000), 0, listOf(WordSpan(0, 30_000), WordSpan(30_001, 60_000)), "Totale euro")
    keys.forEachIndexed { i, k -> phone.reader.fixture("mare ${i + 1} ${k.hex.takeLast(4)}", OcrResult(listOf(line), dense = false)) }
    return World(f, phone, keys)
  }

  /** Esegue tappe (ripartendo dopo ogni morte o guasto) finche' una tappa intera va liscia. */
  private suspend fun untilDone(w: World, rounds: Int = 30) {
    repeat(rounds) {
      try {
        val r = w.phone.engine.run(AiScope.Share(common), forever)
        if (r.stoppedBy == null && w.f.nas.of(common).heads[w.phone.id].orEmpty().size == expected(w)) return
      } catch (e: ProcessDeath) {
        // processo morto: si riparte
      } catch (e: IOException) {
        // guasto non gestito sopra: come una morte
      }
      w.phone.restart()
    }
    error("il lavoro non arriva in fondo")
  }

  private fun expected(w: World) = w.keys.size * 2

  private fun assertNothingTwiceAndAllPublished(w: World) {
    assertThat(w.phone.images.perImage.values.toSet()).containsExactly(1)
    val heads = w.f.nas.of(common).heads[w.phone.id].orEmpty()
    for (k in w.keys) {
      assertThat(heads.keys).contains(k to AiClass.VEC)
      assertThat(heads.keys).contains(k to AiClass.OCR)
    }
  }

  @Test
  fun morteDelProcessoDopoOgniSalvataggio_nessunMediaRicalcolato() = runTest {
    for (n in 1..16) {
      val w = world()
      w.phone.dieAfterSave = n
      untilDone(w)
      assertNothingTwiceAndAllPublished(w)
      // Il testo letto una volta sola: le letture sono esattamente quelle di 6 foto e 2 video x 2 fotogrammi.
      assertThat(w.phone.reader.reads).hasSize(6 + 2 * 2)
    }
  }

  @Test
  fun guastoAOgniChiamataDelNas_primaEDopo() = runTest {
    val clean = world()
    untilDone(clean)
    val calls = clean.f.nas.faults.calls
    assertThat(calls).isGreaterThan(5)
    for (after in listOf(false, true)) {
      for (at in 1..calls) {
        val w = world()
        w.f.nas.faults.at = at
        w.f.nas.faults.after = after
        untilDone(w)
        assertThat(w.f.nas.faults.tripped).isTrue()
        assertNothingTwiceAndAllPublished(w)
      }
    }
  }

  /** E-I2: chi muore tiene l'unita' fino alla scadenza (1 ora del NAS); poi un altro la prende. Chi muore non rifa' niente. */
  @Test
  fun unIndicizzatoreMortoLiberaLUnitaEntroLOra() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "bosco $it") }
    val anna = f.anna()
    anna.dieAfterSave = 3
    assertThat(runCatching { anna.engine.run(AiScope.Share(common), forever) }.exceptionOrNull()).isInstanceOf(ProcessDeath::class.java)
    val bruno = f.bruno()
    assertThat(bruno.engine.run(AiScope.Share(common), forever).computed).isEqualTo(0)
    assertThat(bruno.units.busy).isEqualTo(1)

    f.nas.now += TrustLimits.MAX_LEASE_MILLIS + 1
    assertThat(bruno.engine.run(AiScope.Share(common), forever).computed).isEqualTo(6)
    anna.restart()
    anna.engine.run(AiScope.Share(common), forever)
    assertThat(anna.embedded).isEqualTo(3) // solo quelli di prima della morte
    assertThat(f.published(common, bruno.id)).containsExactlyElementsIn(keys)
  }

  /** Prenotazione persa al rinnovo (un altro l'ha presa): si pubblica cio' che e' fatto e si cambia unita'. */
  @Test
  fun prenotazionePersaAlRinnovo() = runTest {
    val f = TestFamily()
    (1..6).forEach { f.nas.add(common, concepts = "lago $it") }
    val anna = f.anna()
    anna.restart(AiComputeConfig(batch = 2, reducedBatch = 1, keepAliveEvery = 1.nanoseconds))
    var checks = 0
    val steal = object : AiRunControl {
      override suspend fun check(): AiPace {
        if (++checks == 3) f.nas.of(common).leases.keys.forEach { f.nas.of(common).leases[it] = "telefono-bruno" to f.nas.now + 1_000_000 }
        return AiPace.GO
      }
    }
    val r = anna.engine.run(AiScope.Share(common), forever, steal)
    assertThat(anna.engine.counters.unitsLost.get()).isEqualTo(1)
    assertThat(f.published(common, anna.id)).hasSize(r.computed)
    assertThat(r.computed).isLessThan(6)
  }

  /** E-A13: il ruolo si spegne a meta' unita': ci si ferma al media dopo, cio' che e' fatto si pubblica, la prenotazione si lascia. */
  @Test
  fun ruoloSpentoAMetaUnita() = runTest {
    val f = TestFamily()
    (1..6).forEach { f.nas.add(common, concepts = "prato $it") }
    val anna = f.anna()
    anna.restart(AiComputeConfig(batch = 2, reducedBatch = 1))
    var checks = 0
    val off = object : AiRunControl {
      override suspend fun check(): AiPace {
        if (++checks == 2) anna.roles.indexer.value = false
        return AiPace.GO
      }
    }
    val r = anna.engine.run(AiScope.Share(common), forever, off)
    assertThat(r.stoppedBy).isEqualTo(AiStop.ROLE_OFF)
    assertThat(r.computed).isEqualTo(2)
    assertThat(f.published(common, anna.id)).hasSize(2)
    assertThat(f.nas.of(common).leases).isEmpty()
  }
}
