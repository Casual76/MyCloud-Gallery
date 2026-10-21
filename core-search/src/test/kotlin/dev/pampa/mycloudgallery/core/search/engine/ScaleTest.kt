package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.CandidateSet
import dev.pampa.mycloudgallery.core.search.contract.ScoreBlock
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.index.FastNoise
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.key
import dev.pampa.mycloudgallery.core.search.index.vecBody
import dev.pampa.mycloudgallery.core.search.index.TwoPassIndex
import dev.pampa.mycloudgallery.core.search.index.normalized
import java.util.SplittableRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La riga P6 del §4.4 a 100 mila: **parita'** delle due passate contro la forza bruta float32 su 100 mila vettori sintetici a gruppi
 * (>= 99% dei primi 20 uguali) e **velocita'** (100 mila candidati sotto 60 ms sulla JVM, escluso il vettore della query: riscaldamento,
 * il turno migliore di 20).
 *
 * I vettori: 200 gruppi grandi x 10 gruppi piccoli x 50 media; ogni media = `0,6 grande + 0,8 piccolo + rumore` (dispersione diversa
 * per gruppo), normalizzato. La forza bruta li ricalcola in float32 (mai passati per float16), l'indice li ha in float16 come sul
 * telefono (D4).
 */
class ScaleTest {

  @Test
  fun leDuePassateDannoGliStessiPrimiVentiDellaForzaBruta() {
    val f = fixture
    val queries = 40
    var same = 0
    val r = SplittableRandom(5)
    val all = IntArray(COUNT) { it }
    val qs = List(queries) { query(r.nextInt(CLUSTERS), r.nextLong()) }
    // La forza bruta in float32 sui vettori originali, ricalcolati una volta per media (100 mila x 768 float non stanno in memoria).
    val bestIds = Array(queries) { IntArray(20) { -1 } }
    val bestScores = Array(queries) { FloatArray(20) { Float.NEGATIVE_INFINITY } }
    for (i in 0 until COUNT) {
      val v = vectorOf(i)
      for (q in 0 until queries) {
        val s = dot(qs[q], v)
        val scores = bestScores[q]
        if (s <= scores[19]) continue
        var at = 19
        while (at > 0 && scores[at - 1] < s) {
          scores[at] = scores[at - 1]
          bestIds[q][at] = bestIds[q][at - 1]
          at--
        }
        scores[at] = s
        bestIds[q][at] = i
      }
    }
    val core = f.core()
    for (n in 0 until queries) {
      val q = qs[n]
      val brute = bestIds[n].toSet()
      val two = core.withMap(f.snapshot) { map ->
        val index = TwoPassIndex(f.index, map)
        val coarse = ScoreBlock(COUNT)
        index.coarse(q, CandidateSet(all), coarse)
        val top = ContentScorer.topRange(coarse.scores, 0, ContentScorer.RERANK)
        val exact = index.rerank(q, top)
        top.indices.sortedByDescending { exact[it] }.take(20).map { top[it] }.toSet()
      }
      same += (two intersect brute).size
    }
    val parity = same.toDouble() / (queries * 20)
    println("P6 parita' primi 20 (100 mila, $queries query): ${"%.4f".format(parity)}")
    assertThat(parity).isAtLeast(0.99)
  }

  @Test
  fun centomilaCandidatiSottoSessantaMillisecondi() = runBlocking {
    val f = fixture
    val r = SplittableRandom(8)
    val embedding = FixedQueries()
    val engine = LocalSearchEngine(f.data, f.index, f.keys, f.texts, embedding, f.today, f.places)
    fun next() {
      embedding.next = query(r.nextInt(CLUSTERS), r.nextLong())
    }
    repeat(25) {
      next()
      engine.search(SearchRequest("tramonto sul mare"))
    }
    val times = LongArray(20) {
      next()
      val start = System.nanoTime()
      val results = engine.search(SearchRequest("tramonto sul mare"))
      val elapsed = System.nanoTime() - start
      assertThat(results.confident.size + results.likely.size).isGreaterThan(0)
      elapsed
    }
    times.sort()
    val best = times.first() / 1_000_000.0
    val median = times[times.size / 2] / 1_000_000.0
    println("P6 ricerca su 100 mila candidati: migliore ${"%.1f".format(best)} ms, mediana ${"%.1f".format(median)} ms")
    assertThat(best).isLessThan(60.0)
  }

  /** Il motore delle query finto: il vettore scelto dal test (il vettore della query e' escluso dalla misura). */
  private class FixedQueries : QueryEmbedding {
    var next: FloatArray = FloatArray(768)
    override val readiness: StateFlow<SearchReadiness> = MutableStateFlow(SearchReadiness.Ready)

    override suspend fun vector(text: String): FloatArray = next
  }

  companion object {
    const val COUNT = 100_000
    const val CLUSTERS = 2_000

    @get:ClassRule @JvmStatic val tmp = TemporaryFolder()

    private lateinit var fixture: EngineFixture

    private val big = Array(200) { normalized(FloatArray(768).also { v -> FastNoise.fill(10_000L + it, v) }) }
    private val small = Array(CLUSTERS) { normalized(FloatArray(768).also { v -> FastNoise.fill(20_000L + it, v) }) }

    /** Il vettore float32 originale del media [i] (gruppo piccolo `i % 2000`). */
    fun vectorOf(i: Int): FloatArray {
      val c = i % CLUSTERS
      val spread = 0.6f + (c % 9) * 0.1f
      val v = FloatArray(768)
      FastNoise.fill(1_000_000L + i, v, spread / 8f)
      val b = big[c / 10]
      val s = small[c]
      for (j in v.indices) v[j] += 0.6f * b[j] + 0.8f * s[j]
      return normalized(v)
    }

    fun query(cluster: Int, seed: Long): FloatArray {
      val v = FloatArray(768)
      FastNoise.fill(seed, v, 0.9f / 8f)
      for (j in v.indices) v[j] += 0.6f * big[cluster / 10][j] + 0.8f * small[cluster][j]
      return normalized(v)
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
      var s = 0f
      for (i in a.indices) s += a[i] * b[i]
      return s
    }

    private fun EngineFixture.core() = SearchCore(data, index, keys, today, dev.pampa.mycloudgallery.core.search.contract.RankParams(),
      dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker(), 1).also { runBlocking { it.prepare() } }

    @BeforeClass
    @JvmStatic
    fun build() = runBlocking {
      val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 3, count = COUNT))
      f.open()
      for (chunk in (0 until COUNT).chunked(2_000)) {
        f.index.applyReplica("comune", chunk.map { LocalAiIndex.ReplicaItem.Vectors(key(it), "dev-a", vecBody(vectorOf(it))) })
      }
      fixture = f
    }

    @AfterClass
    @JvmStatic
    fun close() {
      fixture.index.close()
    }
  }
}
