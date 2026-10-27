package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.model.FaceRef
import kotlin.random.Random
import org.junit.Test

/**
 * La regola delle soglie del §2.5 **provata sul set sintetico** (riga P11 del §4.4: "regola delle soglie ... provata sul set sintetico"): famiglie sintetiche
 * raggruppate con soglie volutamente **larghe** (cosi' nascono errori veri: fratelli e gemelli finiscono insieme), un utente ideale che conosce la verita' e
 * compila la sessione 1, la regola che propone le soglie, il raggruppamento rifatto con le soglie proposte. Misure: purezza dei gruppi (dalla verita'), copertura
 * dei volti CORE raggruppabili e frammentazione, prima e dopo. Le cifre stampate vanno in `docs/decisioni.md`. Solo vettori inventati.
 */
class FacesEvalCalibrationTest {

  /** Soglie larghe: sotto le soglie di partenza, e senza margine. Qui le sceglie il banco; sul telefono sono quelle con cui sono stati formati i gruppi. */
  private val loose = GroupingParams(tJoin = 0.30f, tJoinWeak = 0.40f, margin = 0f, tEdge = 0.30f, tBirth = 0.33f)

  /** Una famiglia con fratelli (centri a coseno 0,6), bambini che crescono, volti deboli, sconosciuti, video e specchi; con [twins] anche una coppia di gemelli (0,8, E-F1). */
  private fun hard(seed: Long, twins: Int = 0): FamilyStories.Config {
    val r = Random(seed)
    return FamilyStories.Config(
      seed = seed, identities = 14 + r.nextInt(10), photos = 260 + r.nextInt(120), maxFacesPerPhoto = 3, groupShare = 0.15f, weakShare = 0.2f,
      siblings = 2 + r.nextInt(2), siblingCosine = 0.6f, twins = twins, twinCosine = 0.8f, years = 10f, children = 2, strangerShare = 0.1f, videoShare = 0.03f,
      mirrorShare = 0.01f,
    )
  }

  private fun values(p: GroupingParams) = ThresholdValues(p.tJoin, p.tJoinWeak, p.margin, p.tEdge, p.tBirth)

  private fun params(v: ThresholdValues) = GroupingParams(tJoin = v.tJoin, tJoinWeak = v.tJoinWeak, margin = v.margin, tEdge = v.tEdge, tBirth = v.tBirth)

  /** I gruppi che Persone mostrerebbe (almeno 3 volti): quanti sono puri secondo la verita'. */
  private fun purity(state: GroupingState, truth: Map<FaceRef, Int>): Pair<Int, Int> {
    val groups = FacesEvalKit.members(state).filterValues { it.size >= 3 }.values
    return groups.count { refs -> refs.mapNotNull { truth[it] }.toSet().size <= 1 } to groups.size
  }

  private class Run(val seed: Long, val beforePure: Int, val beforeGroups: Int, val afterPure: Int, val afterGroups: Int, val proposal: ThresholdProposal, val coverage: Double, val coverageBefore: Double, val fragmentation: Double)

  private fun run(seed: Long, twins: Int = 0): Run {
    val story = FamilyStories.story(hard(seed, twins))
    val before = FacesEvalKit.group(story, seed, loose)
    val selected = FacesEvalSampling.calibration(FacesEvalKit.candidates(before), seed)
    val labels = FacesEvalKit.oracle(EvalSessionKind.CALIBRATION, before, story, selected, seed, mixedShare = 0.45)
    val proposal = FacesEvalThresholds.choose(
      FacesEvalKit.joinObservations(labels, before, story),
      FacesEvalKit.birthGroups(labels, before, story),
      labels.reviewedGroups.count { it.mixed },
      values(loose),
      labels.totals,
    )
    val after = FacesEvalKit.group(story, seed, params(proposal.values))
    val (bp, bg) = purity(before, story.truth)
    val (ap, ag) = purity(after, story.truth)
    return Run(seed, bp, bg, ap, ag, proposal, FamilyStories.coverableCoverage(after, story), FamilyStories.coverableCoverage(before, story), FamilyStories.fragmentation(after, story))
  }

  @Test
  fun conSoglieLargheLaRegolaPropostaTieneLaPurezzaESegnalaSeNonCeLaFa() {
    val runs = (1L..24L).map { run(it) }
    val beforePure = runs.sumOf { it.beforePure }
    val beforeGroups = runs.sumOf { it.beforeGroups }
    val afterPure = runs.sumOf { it.afterPure }
    val afterGroups = runs.sumOf { it.afterGroups }
    println("Calibrazione sintetica, 24 famiglie con fratelli, bambini e volti deboli, gruppi formati con soglie larghe (${values(loose)}):")
    for (r in runs) {
      println(
        "  famiglia ${r.seed}: purezza ${r.beforePure}/${r.beforeGroups} -> ${r.afterPure}/${r.afterGroups}, proposta ${r.proposal.values}, " +
          "purezza sul set ${FacesEvalMetrics.pct(r.proposal.purityAtCurrent)} -> ${FacesEvalMetrics.pct(r.proposal.purityAtProposal)}, trattenuti ${FacesEvalMetrics.pct(r.proposal.keptRight)}, " +
          "copertura raggruppabili ${FacesEvalMetrics.num(r.coverageBefore)} -> ${FacesEvalMetrics.num(r.coverage)}, nati misti ${r.proposal.bornMixedAtCurrent} -> ${r.proposal.bornMixedAtProposal}, " +
          "raggiungibile ${r.proposal.joinReachable && r.proposal.birthReachable}, piano B ${r.proposal.planB}",
      )
    }
    println("  TOTALE: purezza ${FacesEvalMetrics.pct(beforePure.toDouble() / beforeGroups)} ($beforePure/$beforeGroups) -> ${FacesEvalMetrics.pct(afterPure.toDouble() / afterGroups)} ($afterPure/$afterGroups)")
    // Con le soglie larghe ci sono errori da correggere...
    assertThat(beforePure.toDouble() / beforeGroups).isLessThan(0.97)
    // ...e con quelle proposte la purezza dei gruppi (dalla verita', su tutti i volti, anche quelli che la griglia non ha mostrato) sale sopra il 97% del
    // piano (sul set di calibrazione e' 100%: ci sono i volti non guardati, i gruppi misti non servono a scegliere le soglie di ingresso e il ricalcolo cambia
    // altre cose), e la copertura dei raggruppabili resta sopra il 50%.
    assertThat(afterPure.toDouble() / afterGroups).isAtLeast(0.97)
    assertThat(afterPure.toDouble() / afterGroups).isGreaterThan(beforePure.toDouble() / beforeGroups)
    for (r in runs) assertThat(r.proposal.purityAtProposal).isAtLeast(FacesEvalThresholds.TARGET_PURITY - 1e-9)
    assertThat(runs.map { it.coverage }.average()).isAtLeast(0.5)
    // La regola non scende mai sotto le soglie con cui sono stati formati i gruppi.
    for (r in runs) {
      assertThat(r.proposal.values.tJoin).isAtLeast(loose.tJoin)
      assertThat(r.proposal.values.tJoinWeak).isAtLeast(loose.tJoinWeak)
      assertThat(r.proposal.values.tJoinWeak).isAtLeast(r.proposal.values.tJoin)
      assertThat(r.proposal.values.margin).isAtLeast(loose.margin)
      assertThat(r.proposal.values.tBirth).isAtLeast(loose.tBirth)
      assertThat(r.proposal.purityAtProposal).isAtLeast(r.proposal.purityAtCurrent - 1e-12)
    }
  }

  @Test
  fun conIGemelliLaRegolaDiceCioCheSiPuoFareEMaiDiPiu() {
    // E-F1: due gemelli (coseno dei centri 0,8) restano insieme con qualunque soglia ragionevole: si dividono a mano ("Dividi"). La regola non deve peggiorare
    // niente, deve tenere la purezza sul set di calibrazione quando si dichiara raggiungibile e deve dire "piano B" se per riuscirci perderebbe troppi volti.
    val runs = (101L..112L).map { run(it, twins = 1) }
    val beforePure = runs.sumOf { it.beforePure }
    val beforeGroups = runs.sumOf { it.beforeGroups }
    val afterPure = runs.sumOf { it.afterPure }
    val afterGroups = runs.sumOf { it.afterGroups }
    println(
      "Con i gemelli, 12 famiglie: purezza ${FacesEvalMetrics.pct(beforePure.toDouble() / beforeGroups)} -> ${FacesEvalMetrics.pct(afterPure.toDouble() / afterGroups)}, " +
        "piano B in ${runs.count { it.proposal.planB }} famiglie, non raggiungibile in ${runs.count { !(it.proposal.joinReachable && it.proposal.birthReachable) }}",
    )
    assertThat(afterPure.toDouble() / afterGroups).isAtLeast(beforePure.toDouble() / beforeGroups)
    for (r in runs) if (r.proposal.joinReachable) assertThat(r.proposal.purityAtProposal).isAtLeast(FacesEvalThresholds.TARGET_PURITY - 1e-9)
  }

  @Test
  fun lePropostePeroNonSonoMaiSottoLaPurezzaDelSetDiCalibrazione() {
    // Sul set di calibrazione (i gruppi guardati) la proposta tiene il 98,5% ogni volta che si dichiara raggiungibile.
    for (seed in 30L..45L) {
      val p = run(seed).proposal
      if (p.joinReachable) assertWithMessage("famiglia $seed").that(p.purityAtProposal).isAtLeast(FacesEvalThresholds.TARGET_PURITY - 1e-9)
    }
  }

  @Test
  fun conLeSoglieDiPartenzaSuUnaFamigliaTipicaLaRegolaNonCambiaNiente() {
    var unchanged = 0
    var runs = 0
    for (seed in 50L..61L) {
      val story = FamilyStories.story(
        FamilyStories.Config(seed = seed, identities = 14, photos = 300, siblings = 2, years = 10f, weakShare = 0.15f, strangerShare = 0.1f),
      )
      val state = FacesEvalKit.group(story, seed)
      val labels = FacesEvalKit.oracle(EvalSessionKind.CALIBRATION, state, story, FacesEvalSampling.calibration(FacesEvalKit.candidates(state), seed), seed, mixedShare = 0.45)
      val p = FacesEvalThresholds.choose(
        FacesEvalKit.joinObservations(labels, state, story), FacesEvalKit.birthGroups(labels, state, story), labels.reviewedGroups.count { it.mixed }, values(GroupingParams.DEFAULT), labels.totals,
      )
      runs++
      if (!p.changes) unchanged++
      assertThat(p.purityAtProposal).isAtLeast(p.purityAtCurrent - 1e-12)
      assertThat(p.planB).isFalse()
    }
    println("Soglie di partenza, famiglie tipiche: la regola non cambia niente in $unchanged famiglie su $runs")
    assertThat(unchanged).isAtLeast(runs / 2)
  }
}
