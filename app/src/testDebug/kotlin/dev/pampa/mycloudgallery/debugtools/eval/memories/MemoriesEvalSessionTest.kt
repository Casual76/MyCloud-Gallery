package dev.pampa.mycloudgallery.debugtools.eval.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalTestRig.Companion.media
import dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalTestRig.Companion.memoryId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo strumento di valutazione dei ricordi (P11, solo debug), senza interfaccia: la corsa in memoria, i giudizi salvati a ogni modifica, le foto da togliere, i numeri e i
 * resoconti, i nascosti che escono dalla sessione. Ricordi inventati: nessuna foto vera.
 */
class MemoriesEvalSessionTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store(): MemoriesEvalStore = MemoriesEvalStore(File(tmp.root, "memories"))

  private fun session(rig: MemoriesEvalTestRig, store: MemoriesEvalStore = store()) = MemoriesEvalSession(rig, store).also { it.load() }

  @Test
  fun laCorsaPrendeGliUltimi45GiorniEScegliAlPiuSessanta() = runBlocking<Unit> {
    val rig = MemoriesEvalTestRig(count = 80)
    val s = session(rig)
    assertThat(s.start()).isEqualTo(MemoriesEvalDays.MAX_JUDGED)
    assertThat(rig.lastDays).isEqualTo(45)
    assertThat(s.labels.value.run).hasSize(60)
    assertThat(s.labels.value.run.map { it.day }).isInOrder()
    assertThat(s.labels.value.judged).isEmpty()
  }

  @Test
  fun senzaRicordiLaCorsaDiceIlMotivo() {
    val s = session(MemoriesEvalTestRig(count = 0))
    val error = assertThrows(IllegalStateException::class.java) { runBlocking { s.start() } }
    assertThat(error.message).isEqualTo(MemoriesEvalStrings.NO_MEMORIES)
    assertThat(s.labels.value.run).isEmpty()
  }

  @Test
  fun iGiudiziSiSalvanoAOgniModificaESiRitrovano() = runBlocking<Unit> {
    val store = store()
    val rig = MemoriesEvalTestRig()
    val s = session(rig, store)
    s.start()
    val first = s.labels.value.run[0]
    val second = s.labels.value.run[1]
    s.judge(first.id, EvalVerdict.GOOD)
    s.judge(second.id, EvalVerdict.DONT_SHOW, EvalWhy.SAD)
    s.setFault(second.id, second.members[2], EvalFault.SAD)
    s.setFault(second.id, second.members[3], EvalFault.DUPLICATE)
    // Riaprendo lo strumento (un'altra sessione sullo stesso file) c'e' tutto, nello stesso ordine.
    val again = session(rig, store)
    assertThat(again.labels.value.run.map { it.id }).isEqualTo(s.labels.value.run.map { it.id })
    assertThat(again.labels.value.judgement(first.id)!!.verdict).isEqualTo(EvalVerdict.GOOD)
    assertThat(again.labels.value.judgement(second.id)).isEqualTo(EvalJudgement(EvalVerdict.DONT_SHOW, EvalWhy.SAD))
    assertThat(again.labels.value.faultsOf(second.id)).containsExactly(second.members[2], EvalFault.SAD, second.members[3], EvalFault.DUPLICATE)
    // Togliere un segno, tutti i segni, un giudizio.
    s.setFault(second.id, second.members[3], null)
    s.clearFaults(second.id)
    s.clearJudgement(first.id)
    val last = session(rig, store)
    assertThat(last.labels.value.faultsOf(second.id)).isEmpty()
    assertThat(last.labels.value.judgement(first.id)).isNull()
  }

  @Test
  fun unFileRovinatoRipartDaUnaSessioneVuotaEDiceDoveENonCosa() {
    val store = store()
    File(tmp.root, "memories").mkdirs()
    File(tmp.root, "memories/" + MemoriesLabelFormat.FILE_NAME).writeText(MemoriesLabelFormat.MAGIC + "\nR\tnon-un-id\totd\n")
    val s = MemoriesEvalSession(MemoriesEvalTestRig(), store).also { it.load() }
    assertThat(s.labels.value.run).isEmpty()
    assertThat(s.loadError).contains("Riga 2")
    assertThat(s.loadError).doesNotContain("non-un-id")
  }

  @Test
  fun ilProssimoDaGiudicareSaltaIGiaFattiEGiraInTondo() = runBlocking<Unit> {
    val s = session(MemoriesEvalTestRig(count = 4))
    s.start()
    val ids = s.labels.value.run.map { it.id }
    assertThat(s.nextPending(null)).isEqualTo(ids[0])
    s.judge(ids[0], EvalVerdict.GOOD)
    assertThat(s.nextPending(ids[0])).isEqualTo(ids[1])
    s.judge(ids[1], EvalVerdict.SO_SO)
    s.judge(ids[3], EvalVerdict.GOOD)
    // Dopo l'ultimo ricomincia dall'inizio e trova quello che resta; con tutto giudicato non c'e' piu' niente.
    assertThat(s.nextPending(ids[3])).isEqualTo(ids[2])
    s.judge(ids[2], EvalVerdict.GOOD)
    assertThat(s.nextPending(ids[2])).isNull()
  }

  @Test
  fun iNumeriEIlResocontoSonoSoloNumeri() = runBlocking<Unit> {
    val store = store()
    val s = session(MemoriesEvalTestRig(), store)
    assertThat(s.report()).isNull()
    assertThat(s.saveReport()).isNull()
    s.start()
    val run = s.labels.value.run
    s.judge(run[0].id, EvalVerdict.GOOD)
    s.judge(run[1].id, EvalVerdict.DONT_SHOW, EvalWhy.BORING)
    s.setFault(run[1].id, run[1].members[0], EvalFault.UNRELATED)
    val (name, text) = s.saveReport()!!
    assertThat(name).isEqualTo("report-01.txt")
    assertThat(store.readReport(name)).isEqualTo(text)
    assertThat(text).contains("2 giudicati (6 senza giudizio)")
    assertThat(text).contains("noioso 1")
    // Mai titoli, giorni, id: solo numeri.
    for (m in run) {
      assertThat(text).doesNotContain(m.id)
      assertThat(text).doesNotContain(m.title)
      assertThat(text).doesNotContain(m.day.toString())
      for (media in m.members) assertThat(text).doesNotContain(media)
    }
    // Lo stesso resoconto due volte non riempie la cartella; uno diverso e' il numero 2.
    assertThat(s.saveReport()!!.first).isEqualTo("report-01.txt")
    s.judge(run[2].id, EvalVerdict.SO_SO)
    assertThat(s.saveReport()!!.first).isEqualTo("report-02.txt")
    assertThat(store.reports()).containsExactly("report-01.txt", "report-02.txt").inOrder()
    assertThat(store.readReport("../labels")).isNull()
  }

  @Test
  fun unNascostoEsceDallaSessioneEDaiNumeri() = runBlocking<Unit> {
    val rig = MemoriesEvalTestRig()
    val store = store()
    val s = session(rig, store)
    s.start()
    val run = s.labels.value.run
    s.judge(run[0].id, EvalVerdict.GOOD)
    s.setFault(run[0].id, run[0].members[1], EvalFault.BLURRY_OR_DARK)
    s.judge(run[1].id, EvalVerdict.GOOD)
    // Nessun nascosto: non cambia niente.
    assertThat(s.dropHidden()).isFalse()
    // L'utente nasconde una foto del primo ricordo (anche segnata) e quasi tutto il secondo: il secondo esce col suo giudizio.
    rig.hidden += run[0].members[1]
    rig.hidden += run[1].members.drop(2)
    assertThat(s.dropHidden()).isTrue()
    val after = s.labels.value
    assertThat(after.memory(run[0].id)!!.members).doesNotContain(run[0].members[1])
    assertThat(after.faultsOf(run[0].id)).isEmpty()
    assertThat(after.memory(run[1].id)).isNull()
    assertThat(after.judgement(run[1].id)).isNull()
    assertThat(after.run.flatMap { it.members }).containsNoneIn(rig.hidden)
    // Il file e i numeri non li hanno piu'.
    assertThat(store.loadLabels().run.flatMap { it.members }).containsNoneIn(rig.hidden)
    assertThat(s.report()!!.total.photos).isEqualTo(after.judged.sumOf { it.members.size })
    assertThat(s.dropHidden()).isFalse()
  }

  @Test
  fun unNascostoNonEntraNemmenoSeNascostoFraLaCorsaELaScelta() = runBlocking<Unit> {
    // La porta ha generato, poi qualcosa e' stato nascosto prima che la sessione si scriva: il `start` lo toglie subito.
    val rig = object : MemoriesEvalEnvironment by MemoriesEvalTestRig(count = 3) {
      override suspend fun visibleAmong(ids: Collection<String>): Set<String> = ids.filter { it != media(1) }.toSet()
    }
    val s = MemoriesEvalSession(rig, store()).also { it.load() }
    assertThat(s.start()).isEqualTo(3)
    assertThat(s.labels.value.run.flatMap { it.members }).doesNotContain(media(1))
    // Se tutto fosse nascosto, la corsa dice che non c'e' niente.
    val none = object : MemoriesEvalEnvironment by MemoriesEvalTestRig(count = 3) {
      override suspend fun visibleAmong(ids: Collection<String>): Set<String> = emptySet()
    }
    assertThrows(IllegalStateException::class.java) { runBlocking { MemoriesEvalSession(none, store()).also { it.load() }.start() } }
  }

  @Test
  fun ricominciareELaCancellazioneTolgonoTutto() = runBlocking<Unit> {
    val store = store()
    val s = session(MemoriesEvalTestRig(), store)
    s.start()
    s.judge(s.labels.value.run[0].id, EvalVerdict.GOOD)
    s.saveReport()
    // Una corsa nuova perde i giudizi di prima.
    s.start()
    assertThat(s.labels.value.judged).isEmpty()
    assertThat(store.loadLabels().judgements).isEmpty()
    // "Cancella giudizi e resoconti" toglie la sessione, i giudizi e i resoconti.
    s.judge(s.labels.value.run[0].id, EvalVerdict.GOOD)
    s.saveReport()
    s.wipe()
    assertThat(s.labels.value.run).isEmpty()
    assertThat(store.reports()).isEmpty()
    assertThat(File(tmp.root, "memories/" + MemoriesLabelFormat.FILE_NAME).exists()).isFalse()
    assertThat(File(tmp.root, "memories").listFiles().orEmpty().toList()).isEmpty()
    assertThat(memoryId(0)).hasLength(26)
  }
}
