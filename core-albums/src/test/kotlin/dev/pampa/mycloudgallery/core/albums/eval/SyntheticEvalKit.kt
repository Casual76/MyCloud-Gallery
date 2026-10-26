package dev.pampa.mycloudgallery.core.albums.eval

import dev.pampa.mycloudgallery.core.albums.smart.AlbumCase
import dev.pampa.mycloudgallery.core.albums.smart.Cities
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.SyntheticStory
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate

// IL BANCO DELLE PROVE DI VALUTAZIONE (P12): i viaggi sintetici di P6 (`SyntheticStory`: 20 storie di due anni con la verita' scritta insieme alle
// foto) passati dalla stessa strada dello strumento sul telefono, con un utente ideale al posto della persona. Nessun dato personale.

internal object SyntheticEvalKit {
  /** "Oggi" delle prove di P6 (`SmartQualityTest`): il titolo "Oslo 2024" non dipende da quando si lancia la prova. */
  val today: LocalDate = LocalDate.of(2026, 1, 15)

  /** Le porte della corsa sopra il mondo di una storia: i candidati, la visibilita' e i luoghi sono quelli della storia. */
  fun ports(world: MemoryWorld): AlbumsEvalPorts = AlbumsEvalPorts(
    candidates = world,
    visible = world,
    mask = world,
    parser = ItalianQueryParser(),
    lookup = Cities.lookup,
    search = NoSmartContentSearch,
    devices = DeviceNormalizer("Backup/questo-telefono"),
    clocks = FakeAlbumClocks(),
    geocoder = null,
    today = { today },
  )

  /** Un album di una storia: il viaggio come lo scriverebbe l'utente (i giorni delle sue foto, il titolo) e le sue foto come id. */
  class Case(val story: Int, val case: AlbumCase, val trip: EvalTrip, val truth: Set<String>)

  /** I viaggi di una storia come li scriverebbe l'utente: titolo del caso e giorni delle foto del viaggio (al piu' un anno). */
  fun cases(storyIndex: Int, story: SyntheticStory, firstId: Int = 1): List<Case> {
    val byId = story.world.items().associateBy { it.media }
    return story.cases.mapIndexed { i, case ->
      val days = case.truth.map { byId.getValue(it).c.day }.sorted()
      val from = days.first()
      val to = minOf(days.last(), from.plusDays(EvalTrip.MAX_DAYS - 1))
      Case(storyIndex, case, EvalTrip("v%02d".format(firstId + i), from, to, case.title), case.truth.mapTo(HashSet()) { it.value })
    }
  }

  /** L'esito di un album dell'utente ideale: dice "del viaggio" per tutta la verita' e "non del viaggio" per ogni altra foto che l'album ha messo. */
  class Outcome(val case: Case, val run: TripRun, val metrics: TripMetrics)

  suspend fun evaluate(storyIndex: Int, story: SyntheticStory): List<Outcome> {
    val runner = AlbumsEvalRunner(ports(story.world))
    return cases(storyIndex, story).map { c ->
      val run = runner.run(c.trip.id, c.trip.title!!)
      val wrong = (run.sure + run.suggested).filter { it !in c.truth }.toSet()
      Outcome(c, run, AlbumsEvalMetrics.judge(run, c.truth, wrong))
    }
  }

  fun stories(): List<SyntheticStory> = SyntheticStory.stories(20, firstSeed = 100)
}
