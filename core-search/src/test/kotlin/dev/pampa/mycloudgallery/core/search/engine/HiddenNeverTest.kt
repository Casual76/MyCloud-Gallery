package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.history.LocalSearchHistory
import dev.pampa.mycloudgallery.core.search.index.key
import dev.pampa.mycloudgallery.core.search.index.vecBody
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import java.util.SplittableRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * NASCOSTI MAI (D29, E-Q7, E-H1, §4.3): test di proprieta' su **ogni percorso** del motore. Per ogni seme, un nascosto **perfetto**
 * (il vettore uguale a quello della query, tutte le parole cercate, una parola che c'e' solo lui, il bit Documenti) e, per essere
 * cattivi, presente anche dove non dovrebbe: fra le celle lette dal catalogo (P1 sbagliato), nell'istantanea non ancora rifatta (appena
 * nascosto altrove) e come membro nascosto di un gruppo visibile. Non esce mai: risultati, meno probabili, refusi corretti,
 * completamenti, suggerimenti, luoghi proposti, conteggi della copertura, "Trova simili", Documenti, cronologia, vista viva.
 */
class HiddenNeverTest {
  @get:Rule val tmp = TemporaryFolder()

  private val queries = listOf("cane in spiaggia", "tramonto in montagna", "torta di compleanno", "gatto", "bicicletta e fiori")

  @Test
  fun unNascostoPerfettoNonEsceMaiDaNessunaParte() = runTest {
    for (seed in 1..5) check(seed)
  }

  private suspend fun check(seed: Int) {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = seed.toLong(), count = 400, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    val r = SplittableRandom(seed.toLong())
    val query = queries[seed % queries.size]
    val perfect = FakeConcepts.mix(FakeConcepts.conceptsOf(query))
    val fillers = listOf("albero", "auto", "pizza", "neve", "fiore", "bambino")
    val vectors = HashMap<Long, List<FloatArray>>()
    for (i in 0 until f.snapshot.size) vectors[i.toLong()] = listOf(f.image(fillers[r.nextInt(fillers.size)], i.toLong()))
    for ((_, k) in f.memberKeys) vectors[k] = listOf(f.image("auto", k))
    // Qualche visibile che la query trova davvero (per non provare il nulla).
    val goodOnes = (0 until 5).map { r.nextInt(f.snapshot.size) }.toSet()
    goodOnes.forEach { vectors[it.toLong()] = listOf(f.image(query, it.toLong())) }
    f.putAll(vectors)

    // 1. I nascosti della Libreria, "per errore" anche fra le celle lette dal catalogo, con tutto perfetto.
    val hidden = f.library.hidden.map { it.id }
    hidden.forEachIndexed { j, id ->
      val k = 5_000_000L + j
      f.cells.cells += CellContent(900_000L + j, id, listOf(key(k)), emptyList())
      f.index.saveVectors(key(k), vecBody(perfect))
      f.putText(k, "$query segretissimo")
    }
    // 2. Una cella visibile appena nascosta altrove: l'istantanea non e' ancora rifatta.
    val stale = (0 until f.snapshot.size).first { it !in goodOnes && f.members[it] == null }
    f.index.saveVectors(key(stale.toLong()), vecBody(perfect))
    f.putText(stale.toLong(), "$query segretissimo")
    val flagged = f.snapshot.withFlags(stale, f.snapshot.flagsAt(stale) or CellFlags.DOCUMENT)
    f.data.snapshot.value = flagged
    // 3. Un membro nascosto di un gruppo visibile con il vettore perfetto (la copertina non lo e').
    val cover = f.members.keys.first { it != stale && it !in goodOnes }
    val hiddenMember = f.members.getValue(cover).first()
    f.index.saveVectors(key(f.memberKeys.getValue(hiddenMember)), vecBody(perfect))
    f.data.hiddenIds += hidden + f.id(stale) + hiddenMember
    val secret = hidden.toSet() + f.id(stale) + hiddenMember

    val history = InMemorySearchHistory()
    val tracker = dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker()
    val engine = f.engine(coverage = tracker)
    val suggestions = LibrarySuggestions(f.data, f.places, history)

    fun assertClean(results: SearchResults, what: String) {
      val all = ids(results.confident) + ids(results.likely)
      assertWithMessage("$what (seme $seed)").that(all).containsNoneIn(secret)
      assertWithMessage("$what: la copertina vinta dal membro nascosto (seme $seed)").that(ids(results.confident)).doesNotContain(f.id(cover))
    }

    // Risultati e meno probabili, nei due ordini e con "mostra anche".
    for (order in dev.pampa.mycloudgallery.core.search.contract.ResultOrder.entries) {
      val results = engine.search(SearchRequest(query, showLikely = true, order = order))
      assertClean(results, "«$query»")
      assertThat(ids(results.confident)).containsAtLeastElementsIn(goodOnes.map { f.id(it) })
    }
    // La parola che c'e' solo nei nascosti, esatta e con un refuso: niente risultati, nessun "Ho cercato…".
    for (text in listOf("segretissimo", "segretisimo", "\"segretissimo\"")) {
      val results = engine.search(SearchRequest(text, showLikely = true))
      assertClean(results, "«$text»")
      assertThat(results.notices.filterIsInstance<SearchNotice.TypoCorrected>()).isEmpty()
    }
    // Documenti: il bit della cella appena nascosta non la fa uscire.
    assertClean(engine.search(SearchRequest("documenti")), "documenti")
    // Completamenti e suggerimenti: mai parole lette, mai il luogo che c'e' solo nei nascosti.
    for (prefix in listOf("segre", "segretissimo", "s", query.take(3))) {
      val completions = suggestions.complete(prefix)
      assertThat(completions.map { it.label.lowercase() }).containsNoneOf("segretissimo", "segretopoli")
      assertThat(completions.filterIsInstance<Suggestion.Term>()).isEmpty()
    }
    assertThat(suggestions.idle().places.map { it.label }).doesNotContain("Segretopoli")
    assertThat(f.places.dictionary(flagged).entries.map { it.name }).doesNotContain("segretopoli")
    // Conteggi della copertura: solo le celle dell'istantanea, mai i nascosti con il loro vettore.
    assertThat(tracker.report.total).isEqualTo(flagged.size)
    assertThat(tracker.report.content).isEqualTo(dev.pampa.mycloudgallery.core.search.contract.SearchCoverage.Complete)
    // "Trova simili": mai da un nascosto, mai un nascosto fra i simili (anche partendo da un media che gli somiglia).
    for (id in secret) assertThat(engine.similar(id).confident.size + engine.similar(id).likely.size).isEqualTo(0)
    for (g in goodOnes) assertClean(engine.similar(f.id(g)), "simili a $g")
    // Cronologia: il chip "Simili a" (che nomina un media) non si salva.
    assertThat(LocalSearchHistory.labelsOf(listOf(SearchChip.Similar(hidden.first(), "Simili a"), SearchChip.Word("x", false))))
      .containsExactly("x")
    // Vista viva: un risultato nascosto dopo esce alla prossima istantanea.
    val before = engine.search(SearchRequest(query))
    val victim = goodOnes.first()
    f.data.hiddenIds += f.id(victim)
    f.data.snapshot.value = Without(flagged, victim)
    val live = LiveResults.follow(before, f.data).first()
    assertThat(ids(live.confident)).doesNotContain(f.id(victim))
    assertThat(ids(live.confident)).containsAtLeastElementsIn((goodOnes - victim).map { f.id(it) })
    f.index.close()
  }

  /** Un'istantanea senza la riga [removed] (nascosta o eliminata altrove, E-Q13). */
  private class Without(private val base: TimelineSnapshot, private val removed: Int) : TimelineSnapshot {
    override val size: Int get() = base.size - 1
    override val seq: Long get() = base.seq + 1
    private fun b(i: Int) = if (i < removed) i else i + 1
    override fun idAt(i: Int): MediaId = base.idAt(b(i))
    override fun sortTimeAt(i: Int): Long = base.sortTimeAt(b(i))
    override fun localDayAt(i: Int): Int = base.localDayAt(b(i))
    override fun kindAt(i: Int) = base.kindAt(b(i))
    override fun sourceAt(i: Int) = base.sourceAt(b(i))
    override fun flagsAt(i: Int): Int = base.flagsAt(b(i))
    override fun aspectMilliAt(i: Int): Int = base.aspectMilliAt(b(i))
    override fun durationMsAt(i: Int): Long = base.durationMsAt(b(i))
    override fun placeAt(i: Int): Int = base.placeAt(b(i))
    override fun thumbHashAt(i: Int) = base.thumbHashAt(b(i))
    override fun groupSizeAt(i: Int): Int = base.groupSizeAt(b(i))
    override fun indexOf(id: MediaId): Int = base.indexOf(id).let { if (it < 0 || it == removed) -1 else if (it < removed) it else it - 1 }
  }
}
