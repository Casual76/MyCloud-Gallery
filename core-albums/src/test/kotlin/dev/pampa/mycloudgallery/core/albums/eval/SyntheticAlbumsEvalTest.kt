package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySmartRows
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.RuleSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.albumId
import dev.pampa.mycloudgallery.core.albums.smart.Cities
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LE METRICHE DELLO STRUMENTO SUI VIAGGI SINTETICI (docs/fase7-progetto.md §5.3 punto 1, riga P12 del §4.4): le 20 storie di due anni di P6 passate dalla
 * **stessa strada** dello strumento sul telefono (titolo -> interprete -> album in memoria -> motore -> giudizio con le etichette) con un utente ideale
 * (dice "del viaggio" per la verita' del generatore e "non del viaggio" per ogni altra foto che l'album ha messo). I numeri devono essere quelli di
 * `SmartQualityTest` (precisione 99,96%, copertura 98,88%): se differiscono, lo strumento misura una cosa diversa dal motore.
 */
class SyntheticAlbumsEvalTest {

  @Test
  fun iNumeriDelloStrumentoSonoQuelliDelMotoreSuiViaggiSintetici() = runTest {
    val stories = SyntheticEvalKit.stories()
    val outcomes = stories.withIndex().flatMap { (i, story) -> SyntheticEvalKit.evaluate(i, story) }
    val total = AlbumsEvalMetrics.total(outcomes.map { it.metrics })
    println("VALUTAZIONE sintetica: album=${outcomes.size} sicuri=${total.sure} giusti=${total.sureRight} sbagliati=${total.sureWrong} verita=${total.truth} coperte=${total.covered} suggeriti=${total.suggested}")
    println("VALUTAZIONE sintetica: precisione=${"%.5f".format(total.precision)} copertura=${"%.5f".format(total.coverage)} dei-sicuri-sulla-verita=${"%.5f".format(total.sureCoverage)}")
    // Gli stessi numeri che `SmartQualityTest` stampa al 26/10 (95 album, 20 storie, seme 100).
    assertThat(outcomes).hasSize(95)
    assertThat(total.sure).isEqualTo(12_411)
    assertThat(total.sureRight).isEqualTo(12_406)
    assertThat(total.truth).isEqualTo(14_162)
    // R16 (revisione di fine fase): il centro dei luoghi sta su una griglia di 0,1 gradi e senza le foto di casa: 4 foto di bordo cerchio in meno (14.003 -> 13.999 su 14.162,
    // copertura 98,88% -> 98,85%); precisione e sicuri non cambiano.
    assertThat(total.covered).isEqualTo(13_999)
    // L'utente ideale ha giudicato ogni foto che l'album ha messo: niente "non giudicati".
    assertThat(total.sureUnjudged).isEqualTo(0)
    assertThat(total.sureWrong).isEqualTo(total.sure - total.sureRight)
    // Le soglie sintetiche del progetto (§5.3): piu' alte di quelle del campo (98% e 90%).
    assertWithMessage("precisione dei sicuri").that(total.precision).isAtLeast(0.99)
    assertWithMessage("copertura").that(total.coverage).isAtLeast(0.95)
    assertThat(total.meets(AlbumsEvalTargets())).isTrue()
  }

  /** Ogni album: la corsa dello strumento da' esattamente i sicuri e i suggeriti della strada di `SmartQualityTest` (motore con le righe di P6). */
  @Test
  fun ogniAlbumHaGliStessiSicuriEGliStessiSuggeritiDelMotoreDiP6() = runTest {
    for ((index, story) in SyntheticEvalKit.stories().withIndex().take(6)) {
      val interpreter = RuleSmartNameInterpreter(ItalianQueryParser(), story.world, story.world, story.world, Cities.lookup)
      val tool = SyntheticEvalKit.evaluate(index, story)
      assertThat(tool.map { it.case.case }).containsExactlyElementsIn(story.cases).inOrder()
      for (outcome in tool) {
        val proposal = interpreter.propose(outcome.case.case.title, SyntheticEvalKit.today)
        val rows = InMemorySmartRows()
        val id = albumId(1)
        rows.create(id, proposal.rules)
        val engine = RulesSmartEngine(rows, story.world, story.world, story.world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Cities.lookup, FakeAlbumClocks())
        engine.evaluate(id, Duration.INFINITE)
        val album = rows.albums.getValue(id)
        assertThat(outcome.run.sure.toSet()).isEqualTo(album.auto.keys.map { it.value }.toSet())
        assertThat(outcome.run.suggested.toSet()).isEqualTo(album.suggestions.keys.map { it.value }.toSet())
      }
    }
  }

  /** Il resoconto e' fatto di soli numeri: niente titoli, niente date, niente luoghi, niente id. */
  @Test
  fun ilResocontoDiUnaStoriaHaSoloNumeri() = runTest {
    val story = SyntheticEvalKit.stories().first()
    val outcomes = SyntheticEvalKit.evaluate(0, story)
    val report = AlbumsEvalMetrics.report(outcomes.map { AlbumsEvalMetrics.Entry(it.case.trip, it.run, it.metrics) })
    val text = report.toNumbersText()
    println(text)
    for (o in outcomes) {
      assertThat(text).doesNotContain(o.case.trip.title!!)
      assertThat(text).doesNotContain(o.case.trip.from.toString())
      for (media in o.run.sure.take(3)) assertThat(text).doesNotContain(media)
    }
    for (city in Cities.ALL) assertThat(text).doesNotContain(city.name)
    // Nessuna data scritta per esteso (aaaa-mm-gg) ne' id di media (UUID): le sole cifre sono conteggi e percentuali.
    assertThat(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").containsMatchIn(text)).isFalse()
    assertThat(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(text)).isFalse()
    assertThat(report.meets).isTrue()
    assertThat(report.conclusive).isTrue()
  }
}
