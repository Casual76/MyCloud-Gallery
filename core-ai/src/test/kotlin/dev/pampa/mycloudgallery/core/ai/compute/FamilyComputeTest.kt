package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La famiglia dei test: due account (Anna, Bruno), le loro aree private e l'area comune, sul NAS finto. */
class TestFamily(unitSize: Int = 200) {
  val nas = SimNas(unitSize).apply {
    share(ANNA_PRIVATE, "anna")
    share(BRUNO_PRIVATE, "bruno")
    share(COMMON)
  }

  fun anna(name: String = "telefono-anna", indexer: Boolean = true, reading: Boolean = false) =
    SimDevice(nas, name, "anna", listOf(ANNA_PRIVATE, COMMON), indexer, reading)

  fun bruno(name: String = "telefono-bruno", indexer: Boolean = true, reading: Boolean = false) =
    SimDevice(nas, name, "bruno", listOf(BRUNO_PRIVATE, COMMON), indexer, reading)

  /** Le voci pubblicate da [device] nella share [share], per classe. */
  fun published(share: String, device: String, cls: AiClass = AiClass.VEC) =
    nas.of(share).heads[device].orEmpty().keys.filter { it.second == cls }.map { it.first }.toSet()

  companion object {
    const val ANNA_PRIVATE = "test-privata-anna"
    const val BRUNO_PRIVATE = "test-privata-bruno"
    const val COMMON = "test-comune"
  }
}

/**
 * La tabella "una volta per tutta la famiglia" del §2.2 (il "Fatto quando" di 6.2), scenario per scenario, con due (o tre)
 * dispositivi finti sul NAS finto. Le prenotazioni, l'attestazione, M3 e i blocchi sono quelli del NAS finto ([SimNas]).
 */
class FamilyComputeTest {
  private val forever = Duration.INFINITE

  /** Riga 1: foto nuova caricata dal backup nell'area privata; il telefono che ha caricato ha l'anteprima nel buffer (D10). */
  @Test
  fun fotoNuovaDalBackupNellAreaPrivata() = runTest {
    val f = TestFamily()
    val key = f.nas.add(TestFamily.ANNA_PRIVATE, preview = false) // con F4-D31 le anteprime pubblicate si buttano
    val phone = f.anna()
    BufferAnalysisSink(phone.buffer) { true }
      .deliver(key, listOf(PrepOutput.Image(SegmentClass.ANALYSIS, "FAKE:bambini al mare".toByteArray(), 1024, 768)))

    val results = phone.engine.runAll(forever)

    assertThat(results.first().second.computed).isEqualTo(1)
    assertThat(f.nas.bytesRead).isEqualTo(0) // nessuna anteprima dal NAS
    assertThat(f.published(TestFamily.ANNA_PRIVATE, phone.id)).containsExactly(key)
    // Lo riceve il tablet dello stesso account (indicizzatore anche lui): niente da rifare.
    val tablet = f.anna("tablet-anna")
    tablet.engine.runAll(forever)
    assertThat(tablet.embedded).isEqualTo(0)
    // Il telefono di Bruno non tocca mai l'area privata di Anna.
    val bruno = f.bruno()
    bruno.engine.runAll(forever)
    assertThat(f.nas.touched[bruno.id]).doesNotContain(TestFamily.ANNA_PRIVATE)
  }

  /** Riga 2: i media dell'area comune li calcola il primo indicizzatore, di qualunque account; tutti li ricevono. */
  @Test
  fun mediaDellAreaComune() = runTest {
    val f = TestFamily()
    val keys = (1..7).map { f.nas.add(TestFamily.COMMON, concepts = "festa $it") }
    val bruno = f.bruno()
    bruno.engine.runAll(forever)
    assertThat(f.published(TestFamily.COMMON, bruno.id)).containsExactlyElementsIn(keys)
    val anna = f.anna()
    anna.engine.runAll(forever)
    assertThat(anna.embedded).isEqualTo(0)
    assertThat(bruno.embedded).isEqualTo(7)
  }

  /** Riga 3 (E-I1): due indicizzatori insieme; le prenotazioni dividono le unita', nessun media si calcola due volte. */
  @Test
  fun dueIndicizzatoriInsieme() = runTest {
    val f = TestFamily(unitSize = 5)
    val keys = (1..30).map { f.nas.add(TestFamily.COMMON, month = "2024-0${1 + it % 3}", concepts = "gita $it") }
    val anna = f.anna()
    val bruno = f.bruno()
    anna.images.fake.latencyMsPerImage = 10
    bruno.images.fake.latencyMsPerImage = 13
    val a = launch { anna.engine.run(AiScope.Share(TestFamily.COMMON), forever) }
    val b = launch { bruno.engine.run(AiScope.Share(TestFamily.COMMON), forever) }
    a.join()
    b.join()

    assertThat(anna.embedded + bruno.embedded).isEqualTo(30)
    assertThat(anna.embedded).isGreaterThan(0)
    assertThat(bruno.embedded).isGreaterThan(0)
    val all = f.published(TestFamily.COMMON, anna.id) + f.published(TestFamily.COMMON, bruno.id)
    assertThat(all).containsExactlyElementsIn(keys)
    assertThat(anna.units.busy + bruno.units.busy).isGreaterThan(0) // si sono davvero incontrati
  }

  /** Riga 4: lo stesso contenuto in area privata e comune si calcola una volta e si pubblica in entrambe (M3 rispettata). */
  @Test
  fun stessoContenutoInAreaPrivataEComune() = runTest {
    val f = TestFamily()
    val key = f.nas.add(TestFamily.ANNA_PRIVATE, concepts = "torta")
    f.nas.add(TestFamily.COMMON, concepts = "torta", key = key)
    val phone = f.anna()
    phone.engine.runAll(forever)
    assertThat(phone.embedded).isEqualTo(1)
    assertThat(phone.engine.counters.restaged.get()).isEqualTo(1)
    assertThat(f.published(TestFamily.ANNA_PRIVATE, phone.id)).containsExactly(key)
    assertThat(f.published(TestFamily.COMMON, phone.id)).containsExactly(key)
    assertThat(phone.publisher.foreign).isEqualTo(0)
  }

  /** Righe 5 e 6: i media del solo telefono li calcola solo un indicizzatore, dai byte del telefono, e restano locali. */
  @Test
  fun mediaDelSoloTelefono() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    val key = f.nas.newKey()
    phone.phoneMedia[key] = MediaKind.PHOTO to "gatto sul divano"
    val result = phone.engine.run(AiScope.PhoneOnly, forever)
    assertThat(result.computed).isEqualTo(1)
    assertThat(phone.results.vectors.keys).containsExactly(key)
    assertThat(phone.preparer.calls).isEqualTo(1)
    assertThat(f.nas.touched[phone.id]).isNull() // nessuna share toccata
    assertThat(phone.publisher.pending.value.staged).isEqualTo(0)

    val tablet = f.anna("tablet-anna", indexer = false)
    tablet.phoneMedia[f.nas.newKey()] = MediaKind.PHOTO to "fiori"
    val none = tablet.engine.run(AiScope.PhoneOnly, forever)
    assertThat(none.stoppedBy).isEqualTo(AiStop.ROLE_OFF)
    assertThat(tablet.preparer.calls).isEqualTo(0)
    assertThat(tablet.engine.status.value).isEqualTo(AiStatus.Off)
  }

  /** Riga 7 (D8, F4-D31): share vera, ogni indicizzatore per se'; i risultati restano locali e si pubblicano dopo, senza ricalcolo. */
  @Test
  fun shareVeraConF4D31() = runTest {
    val f = TestFamily()
    val keys = (1..4).map { f.nas.add(TestFamily.COMMON, concepts = "neve $it") }
    val anna = f.anna()
    val bruno = f.bruno()
    anna.gate.block(TestFamily.COMMON, WriteBlock.LOCAL_ONLY)
    bruno.gate.block(TestFamily.COMMON, WriteBlock.LOCAL_ONLY)
    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    bruno.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(anna.embedded).isEqualTo(4)
    assertThat(bruno.embedded).isEqualTo(4)
    assertThat(f.nas.of(TestFamily.COMMON).heads).isEmpty()
    assertThat(anna.publisher.waiting(TestFamily.COMMON)).isEqualTo(4)
    assertThat(f.nas.of(TestFamily.COMMON).leases).isEmpty() // senza prenotazione

    anna.gate.block(TestFamily.COMMON, null) // la share si apre (Q7)
    anna.engine.run(AiScope.Share(TestFamily.COMMON), 8.minutes)
    assertThat(anna.engine.counters.pendingReleased.get()).isEqualTo(4)
    assertThat(f.published(TestFamily.COMMON, anna.id)).containsExactlyElementsIn(keys)
    assertThat(anna.embedded).isEqualTo(4) // nessun ricalcolo
  }

  /** Riga 8 (E-I12, D14): un'area privata senza indicizzatori non la calcola nessuno; lo si dice, mai un'azione. */
  @Test
  fun areaPrivataSenzaIndicizzatori() = runTest {
    val f = TestFamily()
    f.nas.add(TestFamily.BRUNO_PRIVATE, concepts = "moto")
    val anna = f.anna()
    anna.engine.runAll(forever)
    assertThat(anna.engine.run(AiScope.Share(TestFamily.BRUNO_PRIVATE), forever).computed).isEqualTo(0)
    assertThat(f.nas.of(TestFamily.BRUNO_PRIVATE).heads).isEmpty()
    assertThat(f.nas.touched[anna.id]).doesNotContain(TestFamily.BRUNO_PRIVATE)
    assertThat(anna.engine.counters.foreignScopes.get()).isEqualTo(1)

    val now = f.nas.now
    val devices = listOf(DeviceRoleView("anna", true, now - 1_000), DeviceRoleView("bruno", false, now))
    val areas = listOf(AreaView(TestFamily.ANNA_PRIVATE, "anna"), AreaView(TestFamily.BRUNO_PRIVATE, "bruno"), AreaView(TestFamily.COMMON, null))
    assertThat(IndexerAreas.uncovered(areas, devices, now).map { it.share }).containsExactly(TestFamily.BRUNO_PRIVATE)
    // Un indicizzatore non visto da piu' di 7 giorni non conta: anche la comune resta scoperta.
    val stale = listOf(DeviceRoleView("anna", true, now - IndexerAreas.FRESH_MILLIS - 1))
    assertThat(IndexerAreas.uncovered(areas, stale, now)).hasSize(3)
  }

  /** Q6 ed E-H1: i nascosti si calcolano (nessuna esclusione qui); che non compaiano lo garantisce la ricerca (P6). */
  @Test
  fun iNascostiSiCalcolano() = runTest {
    val f = TestFamily()
    val hidden = f.nas.add(TestFamily.COMMON, concepts = "segreto", hidden = true)
    val phone = f.anna()
    phone.engine.runAll(forever)
    assertThat(f.published(TestFamily.COMMON, phone.id)).containsExactly(hidden)
  }
}
