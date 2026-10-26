package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LA QUALITA' SUI VIAGGI SINTETICI (docs/fase7-progetto.md §5.3 punto 1, riga P6 del §4.4): 20 storie di due anni; per ogni album il titolo passa
 * dall'interprete, le regole proposte dal motore, e si confrontano i risultati con la verita' scritta dal generatore. **Precisione** dei sicuri >= 99%
 * e **copertura** (sicuri piu' suggeriti) >= 95%: la soglia e' piu' alta di quella del campo (98% e 90%), che ha dati piu' sporchi.
 */
class SmartQualityTest {
  private val today = LocalDate.of(2026, 1, 15)

  private class Measure(val sure: Int, val sureRight: Int, val truth: Int, val covered: Int, val sureOfTruth: Int) {
    val precision: Double get() = if (sure == 0) 1.0 else sureRight.toDouble() / sure
    val coverage: Double get() = if (truth == 0) 1.0 else covered.toDouble() / truth
  }

  private class Outcome(val case: AlbumCase, val measure: Measure, val series: Int, val recognized: Boolean)

  private suspend fun run(story: SyntheticStory): List<Outcome> {
    val interpreter = RuleSmartNameInterpreter(ItalianQueryParser(), story.world, story.world, story.world, Cities.lookup)
    val out = ArrayList<Outcome>()
    for (case in story.cases) {
      val proposal = interpreter.propose(case.title, today)
      val rows = InMemorySmartRows()
      val id = albumId(1)
      rows.create(id, proposal.rules)
      val engine = RulesSmartEngine(rows, story.world, story.world, story.world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Cities.lookup, FakeAlbumClocks())
      engine.evaluate(id, Duration.INFINITE)
      val album = rows.albums.getValue(id)
      val sure = album.auto.keys
      val suggested = album.suggestions.keys
      val right = sure.count { it in case.truth }
      val covered = case.truth.count { it in sure || it in suggested }
      val series = (proposal.rules.period as? SmartPeriod.Ranges)?.ranges?.size ?: 0
      val wrong = sure.filter { it !in case.truth }
      if (wrong.size > 3) {
        val byId = story.world.items().associateBy { it.media }
        val kinds = wrong.groupingBy { m -> byId.getValue(m).let { c -> "gps=${c.c.gps != null} src=${c.c.source.code} dev=${c.c.originDevice} screen=${c.c.screen}" } }.eachCount()
        println("QUALITA diagnosi ${case.title} (${case.kind}): sicuri=${sure.size} sbagliati=${wrong.size} $kinds")
        for (m in wrong.take(8)) {
          val c = byId.getValue(m)
          val other = story.cases.firstOrNull { m in it.truth }?.title ?: "casa"
          println("QUALITA   sbagliato giorno=${c.c.day} ${c.c.localTime} di=$other gps=${c.c.gps != null}")
        }
      }
      out += Outcome(case, Measure(sure.size, right, case.truth.size, covered, case.truth.count { it in sure }), series, proposal.rules.place != null)
    }
    return out
  }

  private fun total(outcomes: List<Outcome>): Measure = Measure(
    outcomes.sumOf { it.measure.sure }, outcomes.sumOf { it.measure.sureRight }, outcomes.sumOf { it.measure.truth },
    outcomes.sumOf { it.measure.covered }, outcomes.sumOf { it.measure.sureOfTruth },
  )

  @Test
  fun precisioneAlmeno99ECoperturaAlmeno95SuiViaggiSintetici() = runTest {
    val stories = SyntheticStory.stories(20, firstSeed = 100)
    val all = ArrayList<Outcome>()
    for ((index, story) in stories.withIndex()) {
      val outcomes = run(story)
      all += outcomes
      val m = total(outcomes)
      println("QUALITA storia $index: album=${outcomes.size} foto=${story.world.items().size} precisione=${"%.4f".format(m.precision)} copertura=${"%.4f".format(m.coverage)}")
    }
    val m = total(all)
    println("QUALITA totale: album=${all.size} sicuri=${m.sure} giusti=${m.sureRight} verita=${m.truth} coperte=${m.covered} dei-quali-sicure=${m.sureOfTruth}")
    println("QUALITA precisione=${"%.5f".format(m.precision)} copertura=${"%.5f".format(m.coverage)}")
    // Tutti i viaggi sono stati riconosciuti (il luogo c'e') e hanno almeno un elemento: nessun album vuoto per un luogo che il dizionario non conosceva.
    assertThat(all.filter { !it.recognized }.map { it.case }).isEmpty()
    assertThat(all.size).isAtLeast(60)
    assertThat(all.filter { it.series > 1 }).isNotEmpty()
    assertThat(all.map { it.case.kind }.toSet()).containsAtLeast("viaggio", "cavallo-danno")
    assertWithMessage("precisione dei sicuri").that(m.precision).isAtLeast(0.99)
    assertWithMessage("copertura (sicuri e suggeriti)").that(m.coverage).isAtLeast(0.95)
    // Nessuna storia sola nasconde un disastro dietro la media.
    for ((index, outcomes) in all.groupBy { o -> stories.indexOfFirst { s -> s.cases.any { it === o.case } } }) {
      val s = total(outcomes)
      assertWithMessage("precisione della storia $index").that(s.precision).isAtLeast(0.97)
      assertWithMessage("copertura della storia $index").that(s.coverage).isAtLeast(0.90)
    }
    // E la maggior parte dei sicuri e' anche la maggior parte della verita': non e' una copertura fatta di soli suggeriti.
    assertThat(m.sureOfTruth.toDouble() / m.truth).isAtLeast(0.75)
  }

  @Test
  fun ogniCategoriaDiAlbumHaLaSuaQualita() = runTest {
    val all = SyntheticStory.stories(20, firstSeed = 100).flatMap { run(it) }
    for ((kind, group) in all.groupBy { it.case.kind }) {
      val m = total(group)
      println("QUALITA categoria $kind: album=${group.size} precisione=${"%.4f".format(m.precision)} copertura=${"%.4f".format(m.coverage)}")
      assertWithMessage("precisione di $kind").that(m.precision).isAtLeast(0.98)
      assertWithMessage("copertura di $kind").that(m.coverage).isAtLeast(0.93)
    }
  }

  @Test
  fun ilGeneratoreEDeterministicoEHaLaVerita() {
    val a = SyntheticStory(7).build()
    val b = SyntheticStory(7).build()
    assertThat(a.world.items().map { it.media to it.c.takenAtUtcMs }).isEqualTo(b.world.items().map { it.media to it.c.takenAtUtcMs })
    assertThat(a.cases.map { it.title }).isEqualTo(b.cases.map { it.title })
    val truth: Set<MediaId> = a.cases.flatMapTo(HashSet()) { it.truth }
    assertThat(truth).isNotEmpty()
    // La verita' non contiene mai uno screenshot.
    assertThat(a.world.items().filter { it.c.screen }.map { it.media }.toSet().intersect(truth)).isEmpty()
  }
}
