package dev.pampa.mycloudgallery.debugtools.eval

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo strumento di etichettatura e di corsa (P9, solo debug), senza interfaccia: il metodo "a pozzo" (§5.3), le etichette salvate a ogni modifica, la corsa
 * con il resoconto di soli numeri e il campione di "Documenti". Dati sintetici: nessuna query ne' foto vera.
 */
class EvalSessionTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store() = EvalStore(tmp.newFolder("eval"))

  private fun session(rig: EvalTestRig, store: EvalStore = store()) = EvalSession(rig, store).also { it.load() }

  @Test
  fun ilPozzoDelMotoreDaiPrimiCentoRisultati() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    val q = s.addQuery(EvalCategory.OBJECTS, "cane")
    val pool = s.enginePool(q.id)
    assertThat(pool).isNotEmpty()
    assertThat(pool.size).isAtMost(EvalSession.DEPTH)
    // Le prime sono le celle del concetto cercato (sicuri), nell'ordine del motore.
    val dogs = rig.keysOf("cane")
    assertThat(pool.take(20).map { it.key }.count { it in dogs }).isAtLeast(18)
    assertThat(pool.map { it.key }.toSet()).hasSize(pool.size)
  }

  @Test
  fun ilCosenoGrezzoEUnaConfigurazioneDiversaDalMotore() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    val q = s.addQuery(EvalCategory.OBJECTS, "cane")
    val raw = s.rawPool(q.id)
    assertThat(raw).hasSize(EvalSession.DEPTH)
    assertThat(raw.take(30).map { it.key }.count { it in rig.keysOf("cane") }).isAtLeast(28)
    // Senza soglie ne' testo: ha sempre cento celle, anche quando il motore ne darebbe meno.
    val strict = s.enginePool(q.id)
    assertThat(strict.size).isAtMost(raw.size)
  }

  @Test
  fun sfogliareLaLibreriaPerAnno() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    val year = rig.snapshot.localDayAt(0) / 10_000
    val inYear = s.browse(BrowseFilter(fromYear = year, toYear = year), limit = 1_000)
    assertThat(inYear).isNotEmpty()
    assertThat(inYear.all { it.day?.year == year }).isTrue()
    assertThat(s.browse(BrowseFilter(fromYear = year + 50), limit = 1_000)).isEmpty()
    assertThat(s.browse(BrowseFilter(), limit = 25)).hasSize(25)
    // Un luogo che non c'e' non da' nessuna cella (e non tutte).
    assertThat(s.browse(BrowseFilter(place = "Atlantide"))).isEmpty()
  }

  @Test
  fun leCelleSenzaChiaveDiContenutoNonSiEtichettano() = runTest {
    val rig = EvalTestRig(withoutKeyBelow = 50)
    val s = session(rig)
    val all = s.browse(BrowseFilter(), limit = 1_000)
    assertThat(all).hasSize(250)
    assertThat(all.map { it.id }).containsNoneIn((0 until 50).map { rig.snapshot.idAt(it) })
  }

  @Test
  fun leEtichetteSiSalvanoAdOgniModificaESiRileggono() = runTest {
    val rig = EvalTestRig()
    val store = store()
    val s = session(rig, store)
    val q = s.addQuery(EvalCategory.SCENES, "mare")
    s.setExpected(q.id, EvalTestRig.keyHex(2), true)
    s.setExpected(q.id, EvalTestRig.keyHex(8), true)
    s.setExpected(q.id, EvalTestRig.keyHex(8), false)
    s.setDocument(EvalTestRig.keyHex(3), true)
    assertThat(java.io.File(tmp.root, "eval/${EvalStore.LABELS_NAME}").isFile).isTrue()
    val again = session(rig, store)
    assertThat(again.labels.value.expectedOf(q.id)).containsExactly(EvalTestRig.keyHex(2))
    assertThat(again.labels.value.documents).containsExactly(EvalTestRig.keyHex(3), true)
    assertThat(again.labels.value.query(q.id)?.text).isEqualTo("mare")
    // Nessun file temporaneo resta.
    assertThat(java.io.File(tmp.root, "eval").list().orEmpty().filter { it.endsWith(".tmp") }).isEmpty()
  }

  @Test
  fun unFileRovinatoNonBloccaLoStrumentoENonMostraIlContenuto() = runTest {
    val rig = EvalTestRig()
    val store = store()
    java.io.File(tmp.root, "eval/${EvalStore.LABELS_NAME}").writeText("${LabelFormat.MAGIC}\nE\tqx\tnon-una-chiave-privata\n")
    val s = session(rig, store)
    assertThat(s.labels.value.queries).isEmpty()
    assertThat(s.loadError).isNotNull()
    assertThat(s.loadError).doesNotContain("non-una-chiave-privata")
  }

  @Test
  fun gliIdentificativiDelleQueryCrescono() {
    val s = session(EvalTestRig())
    assertThat(s.addQuery(EvalCategory.TEXT, "uno").id).isEqualTo("q01")
    assertThat(s.addQuery(EvalCategory.TEXT, "due").id).isEqualTo("q02")
    s.removeQuery("q01")
    assertThat(s.addQuery(EvalCategory.TEXT, "tre").id).isEqualTo("q01")
  }

  @Test
  fun laCorsaDaIlResocontoDiSoliNumeri() = runTest {
    val rig = EvalTestRig()
    val store = store()
    val s = session(rig, store)
    val queries = listOf("cane" to "cane", "gatto" to "gatto", "mare" to "spiaggia", "macchina" to "auto", "cagnolino" to "cane", "micio" to "gatto")
    queries.forEachIndexed { i, (text, concept) ->
      val q = s.addQuery(EvalCategory.entries[i % EvalCategory.entries.size], text)
      for (key in rig.keysOf(concept)) s.setExpected(q.id, key, true)
    }
    val run = s.run()
    val outcome = run.outcome
    assertThat(outcome.queries).isEqualTo(6)
    assertThat(outcome.withExpected).isEqualTo(6)
    assertThat(outcome.baseline.total.recallAt20).isAtLeast(0.8)
    assertThat(outcome.baseline.total.precisionSure).isAtLeast(0.9)
    assertThat(outcome.calibration.meetsTargets).isTrue()
    assertThat(outcome.crossCheck).isNotNull()
    assertThat(outcome.order.params.halfLifeDays).isIn(EvalOrder.HALF_LIVES)
    assertThat(run.reportName).isEqualTo("report-01.txt")
    assertThat(store.readReport("report-01.txt")).isEqualTo(outcome.text)
    // Soli numeri: nessuna query, nessuna chiave di contenuto.
    for ((text, _) in queries) assertThat(outcome.text).doesNotContain(text)
    assertThat(outcome.text).doesNotContain(EvalTestRig.keyHex(0))
    assertThat(outcome.text).contains("Calibrazione proposta")
    assertThat(outcome.text).contains("SearchThresholds.ZC=")
    assertThat(outcome.text).contains("campione non segnato")
    // Un secondo resoconto ha il numero dopo.
    assertThat(s.run().reportName).isEqualTo("report-02.txt")
  }

  @Test
  fun laCorsaSenzaAtteseDiceCosaManca() = runTest {
    val s = session(EvalTestRig())
    s.addQuery(EvalCategory.OBJECTS, "cane")
    val failure = runCatching { s.run() }.exceptionOrNull()
    assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun ilCampioneDeiDocumentiDaLaSogliaDeiPrototipi() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    val docs = rig.keysOf("documento") + rig.keysOf("scontrino")
    // Un campione: 20 documenti e 20 che non lo sono.
    docs.take(20).forEach { s.setDocument(it, true) }
    (rig.keysOf("cane") + rig.keysOf("auto")).take(20).forEach { s.setDocument(it, false) }
    val choice = s.documentChoice()
    assertThat(choice).isNotNull()
    assertThat(choice!!.meetsPrecision).isTrue()
    assertThat(choice.precision).isAtLeast(0.9)
    assertThat(choice.recall).isAtLeast(0.9)
    assertThat(choice.samples).isEqualTo(40)
    assertThat(choice.toNumbersText()).doesNotContain(EvalTestRig.keyHex(3))
  }

  @Test
  fun senzaUnCampioneAbbastanzaGrandeNonSiCalcolaLaSogliaDeiDocumenti() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    rig.keysOf("documento").take(3).forEach { s.setDocument(it, true) }
    rig.keysOf("cane").take(10).forEach { s.setDocument(it, false) }
    assertThat(s.documentChoice()).isNull()
  }

  @Test
  fun ilLottoDeiDocumentiNonRipeteLeGiaSegnate() = runTest {
    val rig = EvalTestRig()
    val s = session(rig)
    val first = s.documentBatch(30)
    assertThat(first).hasSize(30)
    assertThat(first.map { it.key }.toSet()).hasSize(30)
    first.forEach { s.setDocument(it.key, false) }
    val second = s.documentBatch(30)
    assertThat(second.map { it.key }).containsNoneIn(first.map { it.key })
    // Ripetibile: la stessa chiamata di nuovo da' lo stesso lotto.
    assertThat(s.documentBatch(30).map { it.key }).isEqualTo(second.map { it.key })
  }

  @Test
  fun ilResocontoSiCancellaConIlResto() = runTest {
    val rig = EvalTestRig()
    val store = store()
    val s = session(rig, store)
    val q = s.addQuery(EvalCategory.OBJECTS, "cane")
    for (key in rig.keysOf("cane")) s.setExpected(q.id, key, true)
    s.run()
    assertThat(store.reports()).hasSize(1)
    store.wipe()
    assertThat(store.reports()).isEmpty()
    assertThat(store.loadLabels().queries).isEmpty()
  }
}
