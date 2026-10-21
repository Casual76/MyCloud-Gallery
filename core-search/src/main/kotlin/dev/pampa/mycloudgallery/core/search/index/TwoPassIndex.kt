package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.search.contract.CandidateSet
import dev.pampa.mycloudgallery.core.search.contract.ScoreBlock
import dev.pampa.mycloudgallery.core.search.contract.VectorIndex
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/**
 * L'INDICE VETTORIALE A DUE PASSATE (D24) sopra [LocalAiIndex] e una [CellMap]:
 * 1. [coarse]: per ogni candidato, il massimo dei suoi slot sul codice int8 delle prime 256 componenti (scansione parallela a 4
 *    thread, prodotti interi);
 * 2. [rerank]: il coseno **esatto** (float32 sui 768 float16 del file mappato) dei migliori, massimo dei membri e dei fotogrammi.
 *
 * Una cella senza vettori ha `NaN`. Un record rovinato vale `NaN` e finisce fra quelli da riparare. Si usa **sotto**
 * `LocalAiIndex.lock` in lettura (chi chiama lo tiene): i thread della scansione leggono gli array senza prenderlo.
 */
internal class TwoPassIndex(private val index: LocalAiIndex, private val map: CellMap) : VectorIndex {

  override fun coarse(query: FloatArray, candidates: CandidateSet, out: ScoreBlock) {
    val q = ByteArray(CoarseCodes.DIMS)
    val qScale = CoarseCodes.query(query, q)
    val positions = candidates.positions
    parallel(positions.size) { from, to -> coarseRange(q, qScale, positions, out.scores, from, to) }
  }

  override fun rerank(query: FloatArray, rows: IntArray): FloatArray {
    val out = FloatArray(rows.size)
    parallel(rows.size, minPerThread = 256) { from, to ->
      val scratch = ShortArray(index.dimension)
      for (i in from until to) out[i] = exactCell(query, rows[i], scratch).first
    }
    return out
  }

  /** Il coseno esatto della cella in [position] e il membro che lo da' (NaN e -1 senza vettori sani). */
  fun exactCell(query: FloatArray, position: Int, scratch: ShortArray): Pair<Float, Int> {
    var best = Float.NaN
    var who = -1
    for (k in map.start[position] until map.start[position + 1]) {
      val s = index.exact(map.slots[k], query, scratch) ?: continue
      if (best.isNaN() || s > best) {
        best = s
        who = map.member[k]
      }
    }
    return best to who
  }

  /** Come [rerank] ma con il membro migliore di ogni cella (per il filtro finale dei nascosti). */
  fun rerankWithMembers(query: FloatArray, rows: IntArray): Pair<FloatArray, IntArray> {
    val scores = FloatArray(rows.size)
    val members = IntArray(rows.size)
    parallel(rows.size, minPerThread = 256) { from, to ->
      val scratch = ShortArray(index.dimension)
      for (i in from until to) {
        val (s, m) = exactCell(query, rows[i], scratch)
        scores[i] = s
        members[i] = m
      }
    }
    return scores to members
  }

  private fun coarseRange(q: ByteArray, qScale: Float, positions: IntArray, out: FloatArray, from: Int, to: Int) {
    val codes = index.codes
    val scales = index.scales
    val start = map.start
    val slots = map.slots
    for (i in from until to) {
      val p = positions[i]
      val a = start[p]
      val b = start[p + 1]
      if (a == b) {
        out[i] = Float.NaN
        continue
      }
      var best = Float.NEGATIVE_INFINITY
      for (k in a until b) {
        val slot = slots[k]
        val base = slot * CoarseCodes.DIMS
        var acc = 0
        for (j in 0 until CoarseCodes.DIMS) acc += q[j] * codes[base + j]
        val s = acc * scales[slot] * qScale
        if (s > best) best = s
      }
      out[i] = best
    }
  }

  companion object {
    const val THREADS: Int = 4

    private val pool: ExecutorService by lazy {
      Executors.newFixedThreadPool(THREADS - 1, ThreadFactory { r -> Thread(r, "mcg-search-scan").apply { isDaemon = true } })
    }

    /** Divide [size] in al piu' [THREADS] parti: una sul thread che chiama, le altre nel gruppo di thread della scansione. */
    fun parallel(size: Int, minPerThread: Int = 4_096, block: (Int, Int) -> Unit) {
      val parts = minOf(THREADS, maxOf(1, size / minPerThread))
      if (parts == 1) {
        block(0, size)
        return
      }
      val step = (size + parts - 1) / parts
      val futures = ArrayList<Future<*>>(parts - 1)
      for (p in 1 until parts) {
        val from = p * step
        val to = minOf(size, from + step)
        if (from < to) futures += pool.submit(Callable { block(from, to) })
      }
      block(0, minOf(size, step))
      futures.forEach { it.get() }
    }
  }
}
