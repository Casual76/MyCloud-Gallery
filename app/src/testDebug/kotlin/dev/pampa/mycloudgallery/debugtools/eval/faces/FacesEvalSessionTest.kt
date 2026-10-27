package dev.pampa.mycloudgallery.debugtools.eval.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo strumento di etichettatura (P11, solo debug), senza interfaccia: le due sessioni del §5.3 -- la scelta dei gruppi e dei volti, i tocchi, "Gruppo misto", "e' la
 * stessa di ...", le etichette salvate a ogni modifica, i numeri e le soglie proposte. Famiglia sintetica (vettori inventati): nessun volto vero, nessun nome.
 * L'utente ideale conosce la verita' e segna cio' che segnerebbe un uomo che non sbaglia mai.
 */
class FacesEvalSessionTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun folder(): File = tmp.newFolder("faces")

  private fun session(rig: FacesEvalTestRig, store: FacesEvalStore = FacesEvalStore(folder()), seed: Long = 11L) =
    FacesEvalSession(rig, store, newSeed = { seed }).also { it.load() }

  private val calibration = EvalSessionKind.CALIBRATION
  private val measure = EvalSessionKind.MEASURE

  @Test
  fun laSessioneUnoScegliAlPiuQuarantaGruppiEFotografaIConteggi() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    assertThat(s.session(calibration)).isNull()
    val created = s.start(calibration)
    assertThat(created.groups).hasSize(minOf(40, rig.members().size))
    val members = rig.members().keys.map { it.value }.toSet()
    if (members.size <= 40) assertThat(created.groups.map { it.person }.toSet()).isEqualTo(members) else assertThat(members).containsAtLeastElementsIn(created.groups.map { it.person })
    assertThat(created.totals).isEqualTo(rig.totals())
    assertThat(created.closed).isFalse()
    assertThat(created.groups.all { !it.reviewed && !it.mixed && it.wrong.isEmpty() }).isTrue()
    assertThat(created.groups.map { it.size }).isInOrder(compareByDescending<Int> { it })
    assertThat(rig.prepared).isEqualTo(1)
    // Una seconda volta la scelta non si rifa': nessun nuovo sorteggio finche' non si cancella la sessione.
    assertThat(s.start(calibration).groups.map { it.person }).containsExactlyElementsIn(created.groups.map { it.person }).inOrder()
    assertThat(rig.prepared).isEqualTo(1)
  }

  @Test
  fun laSceltaSiRitrovaRiaprendoLoStrumentoELeEtichetteVannoEtornano() = runTest {
    val rig = FacesEvalTestRig.family()
    val store = FacesEvalStore(folder())
    val first = session(rig, store)
    val created = first.start(calibration)
    val person = created.groups.first().person
    val choice = first.choice(calibration, person)
    first.toggleWrong(calibration, person, choice.all.first().locator, choice.all.size)
    first.toggleSame(calibration, person, created.groups.last().person.takeIf { it != person } ?: "00000000-0000-4000-8000-000000000001")

    val again = session(rig, store, seed = 999L)
    assertThat(again.loadError).isNull()
    assertThat(again.session(calibration)!!.groups.map { it.person }).containsExactlyElementsIn(created.groups.map { it.person }).inOrder()
    assertThat(again.session(calibration)!!.seed).isEqualTo(created.seed)
    assertThat(again.isWrong(calibration, person, choice.all.first().locator)).isTrue()
    assertThat(again.session(calibration)!!.group(person)!!.reviewed).isTrue()
    // E la stessa griglia: stesso seme, stessi volti.
    assertThat(again.choice(calibration, person).all.map { it.locator }).containsExactlyElementsIn(choice.all.map { it.locator }).inOrder()
  }

  @Test
  fun unVoltoRicalcolatoConIlRiquadroUnPoSpostatoRestaSegnato() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    val person = s.start(calibration).groups.first().person
    val face = s.choice(calibration, person).all.first().locator
    s.toggleWrong(calibration, person, face, 10)
    // Lo stesso volto dopo un ricalcolo: stesso contenuto e fotogramma, riquadro con IoU >= 0,5 (D23).
    val moved = FaceLocator(face.contentKey, face.frame, dev.pampa.mycloudgallery.core.model.FaceBox(face.box.x0 + 200, face.box.y0 + 100, face.box.x1 + 200, face.box.y1 + 100))
    assertThat(moved.matches(face)).isTrue()
    assertThat(s.isWrong(calibration, person, moved)).isTrue()
    // Il tocco su quel posto toglie il segno invece di aggiungerne un altro.
    s.toggleWrong(calibration, person, moved, 10)
    assertThat(s.session(calibration)!!.group(person)!!.wrong).isEmpty()
  }

  @Test
  fun gruppoMistoCancellaIVoltiSegnatiEUnToccoRitiraIlMisto() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    val person = s.start(measure).groups.first().person
    val choice = s.choice(measure, person)
    s.toggleWrong(measure, person, choice.all[0].locator, choice.all.size)
    s.toggleWrong(measure, person, choice.all[1].locator, choice.all.size)
    s.setMixed(measure, person, true, choice.all.size)
    var g = s.session(measure)!!.group(person)!!
    assertThat(g.mixed).isTrue()
    assertThat(g.wrong).isEmpty()
    assertThat(g.reviewed).isTrue()
    assertThat(g.pure).isFalse()
    // Un tocco su un volto dice che il gruppo non e' (solo) misto: si torna ai volti uno a uno.
    s.toggleWrong(measure, person, choice.all[2].locator, choice.all.size)
    g = s.session(measure)!!.group(person)!!
    assertThat(g.mixed).isFalse()
    assertThat(g.wrong).hasSize(1)
    // "Non e' misto" lo rimette da guardare.
    s.setMixed(measure, person, true, choice.all.size)
    s.setMixed(measure, person, false)
    assertThat(s.session(measure)!!.group(person)!!.reviewed).isFalse()
  }

  @Test
  fun iNumeriDellUtenteIdealeSonoQuelliDellaVerita() = runTest {
    val rig = FacesEvalTestRig.family(seed = 3L, named = 2)
    val s = session(rig)
    s.start(measure)
    idealUser(rig, s, measure)
    val report = s.report(measure)!!
    // Le attese dalla verita': un gruppo e' puro se tutti i volti hanno la stessa identita'; tutti i gruppi sono guardati e i loro volti mostrati per intero.
    val groups = rig.members().values
    assertThat(report.groups).isEqualTo(groups.size)
    assertThat(report.pureGroups).isEqualTo(groups.count { g -> g.map { rig.truth.getValue(it.ref) }.toSet().size == 1 })
    assertThat(report.facesJudged).isEqualTo(groups.sumOf { it.size })
    val wrong = groups.sumOf { g -> g.size - g.groupingBy { rig.truth.getValue(it.ref) }.eachCount().values.max() }
    assertThat(report.facesWrong).isEqualTo(wrong)
    assertThat(report.coreCoverage).isWithin(1e-12).of(rig.totals().coreAssigned.toDouble() / rig.totals().coreFaces)
    val text = s.reportText(measure)!!
    assertThat(text).contains("sessione 2 (misura)")
    for (g in rig.members().keys) assertThat(text).doesNotContain(g.value)
  }

  @Test
  fun senzaSessioneNonCiSonoNumeriNeSoglieEUnGruppoSconosciutoELancia() = runTest {
    val s = session(FacesEvalTestRig.family())
    assertThat(s.report(calibration)).isNull()
    assertThat(s.reportText(measure)).isNull()
    assertThat(s.saveReport(measure)).isNull()
    assertThat(s.proposal()).isNull()
    assertThat(s.saveProposal()).isNull()
    assertThrows(IllegalStateException::class.java) { s.setReviewed(calibration, "00000000-0000-4000-8000-000000000001", 3) }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { s.choice(calibration, "00000000-0000-4000-8000-000000000001") } }
  }

  @Test
  fun unaSessioneChiusaNonCambiaPiuEIlNumeroRestaQuello() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    s.start(calibration)
    idealUser(rig, s, calibration)
    val before = s.reportText(calibration)
    s.close(calibration)
    val person = s.session(calibration)!!.groups.first().person
    val face = s.choice(calibration, person).all.first().locator
    assertThrows(IllegalStateException::class.java) { s.toggleWrong(calibration, person, face, 3) }
    assertThrows(IllegalStateException::class.java) { s.setMixed(calibration, person, true) }
    assertThat(s.reportText(calibration)).isEqualTo(before)
    assertThat(session(rig, FacesEvalStore(File(tmp.root, "faces"))).session(calibration)!!.closed).isTrue()
    // Ricominciare toglie la sessione e ne sceglie un'altra.
    s.restart(calibration)
    assertThat(s.session(calibration)).isNull()
  }

  @Test
  fun ilProssimoGruppoDaGuardareSaltaQuelliGuardati() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    val groups = s.start(calibration).groups.map { it.person }
    assertThat(groups.size).isAtLeast(3)
    assertThat(s.nextToReview(calibration, null)).isEqualTo(groups[0])
    s.setReviewed(calibration, groups[1], 5)
    assertThat(s.nextToReview(calibration, groups[0])).isEqualTo(groups[2])
    assertThat(s.nextToReview(calibration, groups.last())).isEqualTo(groups[0])
    for (g in groups) s.setReviewed(calibration, g, 5)
    assertThat(s.nextToReview(calibration, groups[0])).isNull()
    assertThat(s.nextToReview(measure, null)).isNull()
  }

  @Test
  fun eLaStessaDiCollegaIGruppiESiToglieAncora() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    val groups = s.start(calibration).groups.map { it.person }
    s.setReviewed(calibration, groups[0], 5)
    s.setReviewed(calibration, groups[1], 5)
    s.toggleSame(calibration, groups[0], groups[1])
    val linked = s.report(calibration)!!
    assertThat(linked.splitGroups).isEqualTo(2)
    assertThat(linked.fragmentation).isGreaterThan(1.0)
    s.toggleSame(calibration, groups[0], groups[1])
    assertThat(s.report(calibration)!!.splitGroups).isEqualTo(0)
  }

  @Test
  fun unVoltoDiUnMediaNascostoNonEntraMaiNellaScelta() = runTest {
    val rig = FacesEvalTestRig.family()
    val all = rig.members().values.flatten()
    // Un contenuto nascosto: i suoi volti spariscono da tutta la porta.
    rig.hidden += all.first().ref.contentKey
    val hiddenLocators = rig.story.faces.filter { it.ref.contentKey in rig.hidden }.map { it.locator }.toSet()
    val s = session(rig)
    val created = s.start(calibration)
    for (g in created.groups) {
      val shown = s.choice(calibration, g.person).all.map { it.locator }
      assertThat(shown.intersect(hiddenLocators)).isEmpty()
    }
    assertThat(rig.totals().coreFaces).isEqualTo(rig.story.faces.count { it.tier == dev.pampa.mycloudgallery.core.model.FaceTier.CORE && it.ref.contentKey !in rig.hidden })
  }

  @Test
  fun unGruppoCheNonCePiuDaUnaSceltaVuota() = runTest {
    val rig = FacesEvalTestRig.family()
    val s = session(rig)
    s.start(calibration)
    assertThat(s.choice(calibration, "00000000-0000-4000-8000-0000000000aa").all).isEmpty()
    assertThat(s.groupInfo("00000000-0000-4000-8000-0000000000aa")).isNull()
  }

  @Test
  fun iResocontiHannoNumeriProgressiviESiTolgonoConLEtichette() = runTest {
    val rig = FacesEvalTestRig.family()
    val folder = folder()
    val store = FacesEvalStore(folder)
    val s = session(rig, store)
    s.start(calibration)
    idealUser(rig, s, calibration)
    assertThat(s.saveReport(calibration)!!.first).isEqualTo("report-01.txt")
    // Gli stessi numeri non fanno un resoconto in piu'; numeri diversi si.
    assertThat(s.saveReport(calibration)!!.first).isEqualTo("report-01.txt")
    s.setMixed(calibration, s.session(calibration)!!.groups.first().person, true, 5)
    assertThat(s.saveReport(calibration)!!.first).isEqualTo("report-02.txt")
    assertThat(store.reports()).containsExactly("report-01.txt", "report-02.txt").inOrder()
    assertThat(store.readReport("report-01.txt")).contains("purezza")
    assertThat(store.readReport("../labels")).isNull()
    assertThat(File(folder, FacesLabelFormat.FILE_NAME).isFile).isTrue()
    s.wipe()
    assertThat(File(folder, FacesLabelFormat.FILE_NAME).exists()).isFalse()
    assertThat(store.reports()).isEmpty()
    assertThat(s.session(calibration)).isNull()
    assertThat(folder.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }).isEmpty()
  }

  @Test
  fun unFileDiEtichetteRovinatoNonFaCrollareEDiceLaRiga() = runTest {
    val folder = folder()
    File(folder, FacesLabelFormat.FILE_NAME).writeText("${FacesLabelFormat.MAGIC}\nS\t1\t5\t0\t0\t0\t0\t0\t0\nG\t1\tnon-una-persona\t1\t0\t1\t-\n")
    val s = FacesEvalSession(FacesEvalTestRig.family(), FacesEvalStore(folder)).also { it.load() }
    assertThat(s.loadError).contains("Riga 3")
    assertThat(s.loadError).doesNotContain("non-una-persona")
    assertThat(s.labels.value.isEmpty).isTrue()
  }

  @Test
  fun leSoglieProposteDallUtenteIdealeSuGruppiFormatiConSoglieLargheTengonoLaPurezza() = runTest {
    // Gruppi formati con soglie larghe (i fratelli finiscono insieme): la regola ha errori da correggere.
    val rig = (21L..60L).asSequence().map { FacesEvalTestRig.family(seed = it, photos = 300, params = FacesEvalTestRig.LOOSE, siblings = 2, identities = 14) }
      .first { r -> r.members().values.any { g -> g.map { f -> r.truth.getValue(f.ref) }.toSet().size > 1 } }
    val s = session(rig)
    s.start(calibration)
    // Un gruppo con piu' di un terzo di volti sbagliati l'utente lo dice "misto".
    idealUser(rig, s, calibration, mixedShare = 0.34)
    val before = s.report(calibration)!!
    assertWithMessage("senza errori non c'e' niente da provare").that(before.pureGroups).isLessThan(before.groups)
    val p = s.proposal()!!
    println("soglie larghe: purezza prima ${FacesEvalMetrics.pct(before.purity)}, proposta $p, purezza alla proposta ${FacesEvalMetrics.pct(p.purityAtProposal)}")
    assertThat(p.values.tJoin).isAtLeast(rig.thresholds.tJoin)
    assertThat(p.values.tJoinWeak).isAtLeast(p.values.tJoin)
    assertThat(p.purityAtProposal).isAtLeast(p.purityAtCurrent)
    if (p.joinReachable) assertThat(p.purityAtProposal).isAtLeast(FacesEvalThresholds.TARGET_PURITY - 1e-9)
    assertThat(p.changes).isTrue()
    val saved = s.saveProposal()!!
    assertThat(saved.first).startsWith("report-")
    assertThat(saved.second).contains("soglie proposte dalla sessione 1")
    for (g in rig.members().keys) assertThat(saved.second).doesNotContain(g.value)
  }

  @Test
  fun senzaGruppiGuardatiNonSiProponeNiente() = runTest {
    val s = session(FacesEvalTestRig.family())
    s.start(calibration)
    assertThat(s.proposal()).isNull()
  }
}
