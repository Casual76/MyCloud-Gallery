package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingMath
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.random.Random
import org.junit.Test

/**
 * Le metriche contro numeri che si conoscono (P11, riga P11 del §4.4: "provate su storie sintetiche con errori noti"): famiglie sintetiche
 * raggruppate come sul telefono, errori **messi apposta** (volti di un'altra persona spostati in un gruppo, un gruppo diviso in due) e un utente
 * ideale che conosce la verita' e compila le etichette. Le attese si contano dalla verita', non dal codice sotto prova. Solo vettori inventati.
 */
class FacesEvalSyntheticTest {

  /** Una famiglia piccola: i gruppi stanno tutti sotto i 60 volti, quindi la griglia li mostra per intero e le attese sono esatte. */
  private fun family(seed: Long): FamilyStories.Story = FamilyStories.story(
    FamilyStories.Config(seed = seed, identities = 10, photos = 90, maxFacesPerPhoto = 3, groupShare = 0.1f, weakShare = 0.1f, siblings = 1, strangerShare = 0.05f),
  )

  /** Il gruppo e' puro se tutti i suoi volti hanno la stessa identita' (dalla verita'). */
  private fun pure(refs: List<FaceRef>, truth: Map<FaceRef, Int>) = refs.mapNotNull { truth[it] }.toSet().size <= 1

  private fun wrongFaces(refs: List<FaceRef>, truth: Map<FaceRef, Int>): Int {
    val ids = refs.mapNotNull { truth[it] }
    return ids.size - (ids.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0)
  }

  private fun everyGroup(state: GroupingState) = FacesEvalKit.candidates(state).sortedBy { it.person }

  @Test
  fun senzaErroriMessiAppostaLeMetricheDicononoCioCheDiceLaVerita() {
    var checked = 0
    for (seed in 1L..12L) {
      val story = family(seed)
      val state = FacesEvalKit.group(story, seed)
      val selected = everyGroup(state)
      val labels = FacesEvalKit.oracle(EvalSessionKind.MEASURE, state, story, selected, seed)
      val report = FacesEvalMetrics.report(labels)
      val groups = FacesEvalKit.members(state).filterKeys { it.value in selected.map { c -> c.person } }
      assertThat(report.groups).isEqualTo(groups.size)
      assertThat(report.pureGroups).isEqualTo(groups.values.count { pure(it, story.truth) })
      assertThat(report.facesJudged).isEqualTo(groups.values.sumOf { it.size })
      assertThat(report.facesWrong).isEqualTo(groups.values.sumOf { wrongFaces(it, story.truth) })
      checked += groups.size
    }
    assertThat(checked).isGreaterThan(60)
  }

  @Test
  fun voltiDiUnAltraPersonaMessiInUnGruppoSiVedonoEsattamente() {
    for (seed in 20L..29L) {
      val story = family(seed)
      val base = FacesEvalKit.group(story, seed)
      val members = FacesEvalKit.members(base).filterValues { it.size >= 8 }
      val ordered = members.keys.sorted()
      if (ordered.size < 4) continue
      val baseline = FacesEvalMetrics.report(FacesEvalKit.oracle(EvalSessionKind.MEASURE, base, story, everyGroup(base), seed))
      // Tre gruppi che ricevono due volti ciascuno dal gruppo di un'altra identita'; i donatori restano puri (perdono volti, non ne prendono).
      val targets = ordered.take(3)
      val moves = HashMap<FaceRef, PersonId>()
      for ((k, target) in targets.withIndex()) {
        val donor = ordered.drop(3).first { d -> FacesEvalKit.major(members.getValue(d), story.truth) != FacesEvalKit.major(members.getValue(target), story.truth) }
        for (ref in members.getValue(donor).filter { story.truth[it] == FacesEvalKit.major(members.getValue(donor), story.truth) }.drop(k * 2).take(2)) moves[ref] = target
      }
      val broken = FacesEvalKit.withMoves(base, moves, story)
      val report = FacesEvalMetrics.report(FacesEvalKit.oracle(EvalSessionKind.MEASURE, broken, story, everyGroup(broken), seed))
      val expectedPure = FacesEvalKit.members(broken).filter { (p, refs) -> refs.size >= 3 && broken.persons[p] != null }.values.count { pure(it, story.truth) }
      val expectedWrong = FacesEvalKit.members(broken).filterValues { it.size >= 3 }.values.sumOf { wrongFaces(it, story.truth) }
      assertThat(report.pureGroups).isEqualTo(expectedPure)
      assertThat(report.facesWrong).isEqualTo(expectedWrong)
      // Gli errori messi apposta si vedono: piu' volti sbagliati di prima, e la purezza non sale.
      assertThat(moves).isNotEmpty()
      assertWithMessage("storia $seed").that(report.facesWrong).isGreaterThan(baseline.facesWrong)
      assertThat(report.purity).isAtMost(baseline.purity + 1e-12)
    }
  }

  @Test
  fun unGruppoDivisoInDueFaCrescereLaFrammentazioneEsattamente() {
    var checked = 0
    for (seed in 40L..47L) {
      val story = family(seed)
      val base = FacesEvalKit.group(story, seed)
      val members = FacesEvalKit.members(base)
      // La persona piu' grande fra quelle pure con almeno 8 volti: meta' va in un gruppo nuovo (stessa identita' in due gruppi).
      val big = members.entries.filter { (_, r) -> r.size >= 8 && pure(r, story.truth) }.maxByOrNull { it.value.size } ?: continue
      val moved = big.value.take(big.value.size / 2)
      checked++
      val split = FacesEvalKit.withSplit(base, moved, story, GroupingStories.ids(seed + 7_777L).next())
      val before = FacesEvalMetrics.report(FacesEvalKit.oracle(EvalSessionKind.MEASURE, base, story, everyGroup(base), seed))
      val after = FacesEvalMetrics.report(FacesEvalKit.oracle(EvalSessionKind.MEASURE, split, story, everyGroup(split), seed))
      // Prima: gruppi per identita' secondo la verita'; dopo: una persona vera in piu' di un gruppo.
      val expected = { s: GroupingState ->
        val major = FacesEvalKit.members(s).mapValues { (_, r) -> FacesEvalKit.major(r, story.truth) }
        val guarded = FacesEvalKit.candidates(s).map { PersonId(it.person) }
        val identities = guarded.map { major.getValue(it) }.toSet()
        major.count { it.value in identities }.toDouble() / identities.size
      }
      assertThat(before.fragmentation).isWithin(1e-9).of(expected(base))
      assertThat(after.fragmentation).isWithin(1e-9).of(expected(split))
      assertThat(after.fragmentation).isGreaterThan(before.fragmentation - 1e-12)
      assertThat(after.splitGroups).isAtLeast(2)
    }
    assertThat(checked).isAtLeast(3)
  }

  @Test
  fun laCoperturaEIVoltiCoreInUnGruppoSuiTotaliDellaStoria() {
    val story = family(5L)
    val state = FacesEvalKit.group(story, 5L)
    val totals = FacesEvalKit.totals(state, story)
    val report = FacesEvalMetrics.report(FacesEvalKit.oracle(EvalSessionKind.MEASURE, state, story, everyGroup(state), 5L))
    assertThat(report.coreCoverage).isWithin(1e-12).of(totals.coreAssigned.toDouble() / totals.coreFaces)
    // Mettendo "ignorati" tutti i volti la copertura crolla e il piano B scatta.
    val empty = FacesEvalMetrics.report(SessionLabels(EvalSessionKind.MEASURE, 1L, EvalTotals(totals.coreFaces, totals.coreFaces / 3, 0, 0, 5), emptyList()))
    assertThat(empty.planB()).isTrue()
  }

  @Test
  fun lUtenteCheDiceMistoUnGruppoMezzoSbagliatoNonLoContaNegliErroriPerVolto() {
    val story = family(8L)
    val base = FacesEvalKit.group(story, 8L)
    val members = FacesEvalKit.members(base).filterValues { it.size >= 8 }
    val ordered = members.keys.sorted()
    val target = ordered.first()
    val donor = ordered.drop(1).first { FacesEvalKit.major(members.getValue(it), story.truth) != FacesEvalKit.major(members.getValue(target), story.truth) }
    val half = members.getValue(donor).take(members.getValue(target).size)
    val broken = FacesEvalKit.withMoves(base, half.associateWith { target }, story)
    val labels = FacesEvalKit.oracle(EvalSessionKind.MEASURE, broken, story, everyGroup(broken), 8L, mixedShare = 0.3)
    val report = FacesEvalMetrics.report(labels)
    assertThat(report.mixedGroups).isAtLeast(1)
    assertThat(labels.groups.filter { it.mixed }.all { it.wrong.isEmpty() }).isTrue()
    assertThat(report.facesJudged).isEqualTo(labels.groups.filter { !it.mixed }.sumOf { it.shown })
  }

  @Test
  fun laSimulazioneDelleNascitePicchiaConIlLegameMedioDelRiferimento() {
    // Lo strumento ha una sua copia del legame medio (nessuna dipendenza da core-faces): qui si prova che dice le stesse cose di `GroupingMath`.
    var compared = 0
    var mixedAtLeastOnce = 0
    for (seed in 1L..120L) {
      val story = FamilyStories.story(
        FamilyStories.Config(seed = seed, identities = 5, photos = 20, maxFacesPerPhoto = 3, siblings = 2, siblingCosine = 0.65f, weakShare = 0f, strangerShare = 0f, mirrorShare = 0f),
      )
      val random = Random(seed)
      val faces = story.faces.shuffled(random).take(16)
      // "Giusto" = l'identita' piu' frequente fra i 16.
      val major = faces.groupingBy { story.truth.getValue(it.ref) }.eachCount().maxByOrNull { it.value }!!.key
      val right = BooleanArray(faces.size) { story.truth.getValue(faces[it].ref) == major }
      val photos = faces.map { it.photo }.distinct()
      val group = BirthGroup(right, IntArray(faces.size) { photos.indexOf(faces[it].photo) }, FloatArray(faces.size * faces.size) { faces[it / faces.size].cosine(faces[it % faces.size]) })
      for ((edge, birth) in listOf(0.45f to 0.48f, 0.30f to 0.33f, 0.60f to 0.62f)) {
        val clusters = GroupingMath.averageLinkage(faces, stop = birth, edge = edge)
        val expected = clusters.any { c -> c.size >= 3 && c.any { story.truth.getValue(it.ref) == major } && c.any { story.truth.getValue(it.ref) != major } }
        assertWithMessage("storia $seed, soglie $edge/$birth").that(FacesEvalThresholds.bornMixed(group, edge, birth)).isEqualTo(expected)
        compared++
        if (expected) mixedAtLeastOnce++
      }
    }
    assertThat(compared).isEqualTo(360)
    // La prova non e' vuota: ci sono sia gruppi nati misti sia gruppi nati puri.
    assertThat(mixedAtLeastOnce).isGreaterThan(20)
    assertThat(mixedAtLeastOnce).isLessThan(compared - 20)
  }

  @Test
  fun loStessoSemeDaLeStesseEtichette() {
    val story = family(3L)
    val state = FacesEvalKit.group(story, 3L)
    val a = FacesEvalKit.oracle(EvalSessionKind.CALIBRATION, state, story, FacesEvalSampling.calibration(FacesEvalKit.candidates(state), 11L), 11L)
    val b = FacesEvalKit.oracle(EvalSessionKind.CALIBRATION, state, story, FacesEvalSampling.calibration(FacesEvalKit.candidates(state), 11L), 11L)
    assertThat(FacesLabelFormat.serialize(FacesLabelSet().with(a))).isEqualTo(FacesLabelFormat.serialize(FacesLabelSet().with(b)))
    assertThat(FacesLabelFormat.parse(FacesLabelFormat.serialize(FacesLabelSet().with(a))).session(EvalSessionKind.CALIBRATION)!!.groups.map { it.person })
      .containsExactlyElementsIn(a.groups.map { it.person })
  }
}
