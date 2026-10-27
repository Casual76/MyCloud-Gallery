package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceRef
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * INCREMENTALE CONTRO RICALCOLO DA ZERO (riga P5 del §4.4; §2.5 "incrementale contro ricalcolo da zero: stessa purezza entro 0,5 punti").
 * Un raggruppamento incrementale dipende dall'ordine di arrivo per progetto (D21: per questo c'e' un solo custode), quindi "uguale" vuol dire
 * **stessi gruppi a meno degli id**, misurato: purezza entro 0,5 punti, copertura entro 2 punti, e l'accordo fra le due partizioni sulle
 * coppie di volti (F1 delle coppie "stessa persona") almeno 0,95. Le foto arrivano in 6 lotti con l'incrementale e una notte dopo ognuno,
 * contro un passo solo su tutto l'archivio e una notte.
 */
class IncrementalVsScratchTest {
  private val engine = FastGrouping()

  /** L'F1 delle coppie "stessa persona" fra due partizioni, sui volti che entrambe mettono in una persona. */
  private fun pairAgreement(a: GroupingState, b: GroupingState): Double {
    val pa = FamilyStories.groups(a).flatMap { (p, fs) -> fs.map { it to a.resolve(p) } }.toMap()
    val pb = FamilyStories.groups(b).flatMap { (p, fs) -> fs.map { it to b.resolve(p) } }.toMap()
    val common = pa.keys.filter { it in pb }
    val inA = common.groupBy { pa[it] }.values
    val inB = common.groupBy { pb[it] }.values
    fun pairs(groups: Collection<List<FaceRef>>) = groups.sumOf { it.size.toLong() * (it.size - 1) / 2 }
    val both = common.groupBy { pa[it] to pb[it] }.values.sumOf { it.size.toLong() * (it.size - 1) / 2 }
    val p = pairs(inA)
    val q = pairs(inB)
    if (p == 0L && q == 0L) return 1.0
    return 2.0 * both / (p + q)
  }

  @Test
  fun stessaPurezzaEGliStessiGruppiAMenoDegliId() {
    var purityInc = 0.0
    var purityScratch = 0.0
    var coverageInc = 0.0
    var coverageScratch = 0.0
    var agreement = 0.0
    val n = 30
    for (seed in 1L..n.toLong()) {
      val story = FamilyStories.story(FamilyStories.Config(seed = seed, identities = 15 + (seed % 12).toInt(), photos = 400, siblings = 2, years = 10f, weakShare = 0.2f))
      val ids = GroupingStories.ids(seed)
      var inc = GroupingState.EMPTY
      for ((k, part) in Scenarios.arrivals(story.faces, 6, Random(seed)).withIndex()) {
        val params = if (k == 0) GroupingParams.DEFAULT.copy(birthsEvery = 1) else GroupingParams.DEFAULT
        inc = engine.incremental(GroupingInput(inc, part, params = params), ids).state
        inc = engine.nightly(GroupingInput(inc, part), ids).state
      }
      val first = engine.incremental(GroupingInput(GroupingState.EMPTY, story.faces, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), GroupingStories.ids(seed + 1)).state
      val scratch = engine.nightly(GroupingInput(first, story.faces), GroupingStories.ids(seed + 1)).state
      purityInc += FamilyStories.purity(inc, story.truth)
      purityScratch += FamilyStories.purity(scratch, story.truth)
      coverageInc += FamilyStories.coverableCoverage(inc, story)
      coverageScratch += FamilyStories.coverableCoverage(scratch, story)
      agreement += pairAgreement(inc, scratch)
    }
    println(
      "Incrementale contro da zero (%d storie): purezza %.4f contro %.4f, copertura %.4f contro %.4f, accordo sulle coppie %.4f".format(
        n, purityInc / n, purityScratch / n, coverageInc / n, coverageScratch / n, agreement / n,
      ),
    )
    assertWithMessage("purezza entro 0,5 punti").that(abs(purityInc - purityScratch) / n).isAtMost(0.005)
    assertWithMessage("copertura entro 2 punti").that(abs(coverageInc - coverageScratch) / n).isAtMost(0.02)
    assertWithMessage("accordo fra le due partizioni").that(agreement / n).isAtLeast(0.95)
  }
}
