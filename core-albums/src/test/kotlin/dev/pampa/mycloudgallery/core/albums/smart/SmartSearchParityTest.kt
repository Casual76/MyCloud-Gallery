package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.database.TextMatchCounts
import dev.pampa.mycloudgallery.core.database.VocabularyWord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.engine.EmbedderQueryEmbedding
import dev.pampa.mycloudgallery.core.search.engine.IndexSearchData
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LocalSearchEngine
import dev.pampa.mycloudgallery.core.search.index.CellKeySource
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.TextRowStore
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LA PARITA' DEL §5.2 (riga `InMemorySearchEngine` della tabella dei fake fedeli): su 200 album sintetici le classi delle regole C e S4 (i "sicuri" e i
 * "meno probabili" della descrizione e delle foto d'esempio) sono **le stesse** con l'esecutore di riferimento della ricerca (forza bruta in float32) e con il
 * motore vero (indice a due passate sul file dei vettori), salvo i casi a +-1e-4 dalla soglia (qui nessuno: i punteggi sono quelli dello stesso indice).
 * La Libreria e' la stessa, i vettori anche (letti dall'indice vero); cambia solo il motore. Poi l'album intero, scritto con l'uno e con l'altro.
 */
class SmartSearchParityTest {
  @get:Rule val tmp = TemporaryFolder()

  private val concepts = listOf("cane", "gatto", "spiaggia", "montagna", "tramonto", "torta", "auto", "bicicletta", "fiore", "albero", "pizza", "bambino")
  private val today = LocalDate.of(2026, 10, 26)

  /** Il banco: una Libreria con le sue date, i vettori nell'indice vero, il motore vero e il riferimento sugli stessi dati. */
  private class Bench(val world: MemoryWorld, val real: SearchEngine, val reference: SearchEngine)

  private fun normalized(v: FloatArray): FloatArray {
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
  }

  private fun vecBody(v: FloatArray): VecBody = VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(VecItem(null, Float16.encode(normalized(v)))))

  private fun keyOf(n: Int): ContentKey = ContentKey("%064x".format(n.toLong()))

  private suspend fun bench(count: Int, seed: Long): Bench {
    val rnd = Random(seed)
    val world = MemoryWorld()
    val base = LocalDate.of(2025, 1, 1)
    val items = (1..count).map { cand(it, base.plusDays(rnd.nextLong(0, 400)), minutes = rnd.nextInt(24 * 60)) }
    world.put(*items.toTypedArray())
    world.publish()
    val cells = items.mapIndexed { i, c -> CellContent(i + 1L, c.media, listOf(keyOf(c.media.value.substringAfterLast('-').toLong(16).toInt())), emptyList()) }
    val texts = NoTexts()
    val index = LocalAiIndex(tmp.newFolder(), texts)
    index.open()
    val vectors = items.map { c ->
      val n = c.media.value.substringAfterLast('-').toLong(16).toInt()
      val mix = (0 until 1 + rnd.nextInt(2)).joinToString(" ") { concepts[rnd.nextInt(concepts.size)] }
      LocalAiIndex.ReplicaItem.Vectors(keyOf(n), "dev-a", vecBody(FakeConcepts.mix(FakeConcepts.conceptsOf(mix), noise = 0.3f, noiseSeed = "img$n")))
    }
    for (chunk in vectors.chunked(2_000)) index.applyReplica("comune", chunk)
    val keys = CellKeys(ListKeys(cells))
    val data = FakeSearchData(world.snapshot.value)
    val embedder = FakeTextEmbedder()
    val places = LibraryPlaces(Spots.LOOKUP)
    val fixed = FixedToday(today)
    val real = LocalSearchEngine(data, index, keys, texts, EmbedderQueryEmbedding(embedder), fixed, places, coverageTracker = CoverageTracker())
    val reference = InMemorySearchEngine(IndexSearchData(data, keys, index), embedder, ItalianQueryParser(), fixed, places = { places.dictionary(world.snapshot.value!!) })
    return Bench(world, real, reference)
  }

  private class NoTexts : TextRowStore {
    override suspend fun put(record: AiTextRecord): Boolean = true

    override suspend fun remove(keys: Collection<ContentKey>) = Unit

    override suspend fun matches(terms: List<List<String>>): TextMatchCounts = TextMatchCounts(terms.size, emptyMap())

    override suspend fun wordCounts(words: Collection<String>): Map<String, Int> = emptyMap()

    override suspend fun vocabularyPage(after: String, limit: Int): List<VocabularyWord> = emptyList()
  }

  private class ListKeys(private val cells: List<CellContent>) : CellKeySource {
    override suspend fun page(afterSeq: Long, limit: Int): CellContentPage {
      val after = cells.filter { it.seq > afterSeq }.sortedBy { it.seq }
      val page = after.take(limit)
      return CellContentPage(page, if (after.size > limit) page.last().seq else null)
    }

    override suspend fun cellsOf(ids: Collection<MediaId>): List<CellContent> = cells.filter { it.mediaId in ids.toSet() }
  }

  private fun randomPeriod(rnd: Random): SmartPeriod? = when (rnd.nextInt(5)) {
    0 -> SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.of(2025, 3, 1).plusDays(rnd.nextLong(0, 200)), LocalDate.of(2025, 9, 1).plusDays(rnd.nextLong(0, 100)))))
    1 -> SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)), LocalDayRange(LocalDate.of(2025, 6, 10), LocalDate.of(2025, 7, 20))))
    2 -> SmartPeriod.EveryYear(MonthDay.of(rnd.nextInt(1, 6), 1), MonthDay.of(rnd.nextInt(7, 12), 28))
    3 -> SmartPeriod.Days((0 until 60).map { LocalDate.of(2025, 1, 1).plusDays(rnd.nextLong(0, 400)) }.toSet())
    else -> null
  }

  @Test
  fun dueCentoAlbumStesseClassiConIlRiferimentoEIlMotoreVero() = runTest {
    val bench = bench(count = 1_200, seed = 5)
    val realSearch = EngineContentSearch(bench.real)
    val referenceSearch = EngineContentSearch(bench.reference)
    assertThat(bench.real.readiness.value).isEqualTo(SearchReadiness.Ready)
    val rnd = Random(2026)
    val items = bench.world.items()
    var nonEmpty = 0
    var withExamples = 0
    var likelySeen = 0
    repeat(200) { n ->
      val text = if (rnd.nextInt(10) == 0) null else concepts.shuffled(rnd).take(1 + rnd.nextInt(2)).joinToString(" ")
      val examples = if (text == null || rnd.nextInt(4) == 0) items.shuffled(rnd).take(1 + rnd.nextInt(3)).map { it.media } else emptyList()
      val period = randomPeriod(rnd)
      val a = realSearch.match(text, examples, period)
      val b = referenceSearch.match(text, examples, period)
      assertWithMessage("album $n (periodo $period): sicuri").that(a.sure).isEqualTo(b.sure)
      assertWithMessage("album $n (periodo $period): meno probabili").that(a.likely).isEqualTo(b.likely)
      if (a.sure.isNotEmpty()) nonEmpty++
      if (examples.isNotEmpty()) withExamples++
      if (a.likely.isNotEmpty()) likelySeen++
    }
    // La parita' su risultati vuoti non dimostrerebbe niente.
    assertThat(nonEmpty).isGreaterThan(120)
    assertThat(likelySeen).isGreaterThan(40)
    assertThat(withExamples).isGreaterThan(20)
  }

  @Test
  fun lAlbumScrittoConLUnoELAltroMotoreEUguale() = runTest {
    val bench = bench(count = 1_200, seed = 6)
    val rnd = Random(77)
    var different = 0
    repeat(40) { n ->
      val text = concepts[rnd.nextInt(concepts.size)]
      val period = randomPeriod(rnd)
      val rules = SmartRules(period, null, text)
      val views = listOf(bench.real, bench.reference).map { engine ->
        val rows = InMemorySmartRows()
        val id = albumId(1)
        rows.create(id, rules)
        RulesSmartEngine(rows, bench.world, bench.world, bench.world, EngineContentSearch(engine), DeviceNormalizer(null), Spots.LOOKUP, FakeAlbumClocks())
          .evaluate(id, Duration.INFINITE)
        rows.snapshot(id).let { (auto, suggested) -> auto.mapValues { it.value.position } to suggested.mapValues { it.value.reason } }
      }
      assertWithMessage("album $n con «$text»").that(views[0]).isEqualTo(views[1])
      if (views[0].first.isNotEmpty()) different++
    }
    assertThat(different).isGreaterThan(20)
  }

  @Test
  fun leFotoDEsempioSiCombinanoPerUnioneDeiTrovaSimili() = runTest {
    val bench = bench(count = 600, seed = 7)
    val search = EngineContentSearch(bench.real)
    val items = bench.world.items()
    val a = items[3].media
    val b = items[40].media
    val one = search.match(null, listOf(a), null)
    val two = search.match(null, listOf(b), null)
    val both = search.match(null, listOf(a, b), null)
    assertThat(both.sure).containsAtLeastElementsIn(one.sure + two.sure)
    assertThat(both.sure + both.likely).containsAtLeastElementsIn(one.sure + one.likely + two.sure + two.likely)
    // Senza testo ne' esempi non c'e' niente da chiedere al motore.
    assertThat(search.match(null, emptyList(), null).used).isFalse()
    assertThat(search.match("   ", emptyList(), null).used).isFalse()
  }
}
