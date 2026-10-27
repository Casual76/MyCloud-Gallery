package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import org.junit.Test

/**
 * LE MISURE SULLA JVM (riga P5 del §4.4: "20.000 volti < 60 s la prima volta, < 50 ms a volto dopo, notte < 30 s"; D10; il brief chiede
 * anche 50 mila volti). Un archivio di famiglia sintetico grande: 400 identita' con popolarita' di Zipf (poche persone in moltissime foto),
 * fratelli, eta', deboli, sconosciuti, video, foto di gruppo. "La prima volta" = un incrementale su tutto l'archivio (nascite su tutti i volti
 * CORE: oltre 3.000 volti a lotti, [BirthGroups]) e la prima notte; "dopo" = un'unita' di ~300 volti nuovi sopra l'archivio raggruppato; la
 * notte = la manutenzione sull'archivio intero. Il PC e' condiviso: i tempi sono un ordine di grandezza, le soglie quelle del progetto. Il
 * telefono si misura sul campo (F104).
 */
class GroupingPerformanceTest {
  private val engine = FastGrouping()

  private fun archive(seed: Long, photos: Int): FamilyStories.Story = FamilyStories.story(
    FamilyStories.Config(
      seed = seed, identities = 400, photos = photos, maxFacesPerPhoto = 3, groupShare = 0.1f, weakShare = 0.15f, siblings = 10,
      years = 10f, strangerShare = 0.1f, videoShare = 0.03f, mirrorShare = 0.01f, popularity = 1.0f,
    ),
  )

  private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / 1_000_000
  }

  private class Numbers(val faces: Int, val firstMs: Long, val perFaceMs: Double, val nightMs: Long, val purity: Double, val coverage: Double) {
    override fun toString(): String =
      "%d volti: prima volta %.1f s, dopo %.2f ms a volto, notte %.1f s; purezza %.4f, copertura dei raggruppabili %.3f".format(
        faces, firstMs / 1000.0, perFaceMs, nightMs / 1000.0, purity, coverage,
      )
  }

  private fun run(seed: Long, photos: Int): Numbers {
    val story = archive(seed, photos)
    val keys = story.faces.map { it.ref.contentKey }.distinct()
    val held = keys.takeLast(130).toSet()
    val base = story.faces.filter { it.ref.contentKey !in held }
    val ids = GroupingStories.ids(seed)
    val (first, firstMs) = timed {
      val s = engine.incremental(GroupingInput(GroupingState.EMPTY, base, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), ids).state
      engine.nightly(GroupingInput(s, base), ids).state
    }
    val all: List<GroupFace> = story.faces
    val unit = all.size - base.size
    val (afterUnit, unitMs) = timed { engine.incremental(GroupingInput(first, all), ids).state }
    val (night, nightMs) = timed { engine.nightly(GroupingInput(afterUnit, all, GroupingCorrections.NONE), ids).state }
    return Numbers(all.size, firstMs, unitMs.toDouble() / unit, nightMs, FamilyStories.purity(night, story.truth), FamilyStories.coverableCoverage(night, story))
  }

  @Test
  fun ventimilaVolti() {
    val n = run(2026, 8_600)
    println("Misure JVM: $n")
    assertWithMessage("volti dell'archivio").that(n.faces).isAtLeast(19_000)
    assertWithMessage("prima volta (ms)").that(n.firstMs).isLessThan(60_000)
    assertWithMessage("a volto dopo (ms)").that(n.perFaceMs).isLessThan(50.0)
    assertWithMessage("notte (ms)").that(n.nightMs).isLessThan(30_000)
    assertWithMessage("purezza").that(n.purity).isAtLeast(0.97)
  }

  @Test
  fun cinquantamilaVolti() {
    val n = run(2027, 21_500)
    println("Misure JVM: $n")
    assertWithMessage("volti dell'archivio").that(n.faces).isAtLeast(48_000)
    // Oltre il progetto (20.000): le stesse soglie scalate con il numero di volti (2,5 volte), il costo per volto resta quello.
    assertWithMessage("prima volta (ms)").that(n.firstMs).isLessThan(150_000)
    assertWithMessage("a volto dopo (ms)").that(n.perFaceMs).isLessThan(50.0)
    assertWithMessage("notte (ms)").that(n.nightMs).isLessThan(75_000)
    assertWithMessage("purezza").that(n.purity).isAtLeast(0.97)
  }
}
