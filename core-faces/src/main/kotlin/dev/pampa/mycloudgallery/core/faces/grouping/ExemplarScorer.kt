package dev.pampa.mycloudgallery.core.faces.grouping

/**
 * La somiglianza (§2.5) di un volto con molte persone insieme: [sets] sono gli esemplari (righe) di ogni persona candidata. Per ogni riga
 * chiesta [score] scrive in `out[s]` la media dei 3 coseni piu' alti con gli esemplari della persona `s` (esclusa la riga stessa), o NaN se
 * non ne resta nessuno: lo stesso valore, bit per bit, di [Exemplars.similarity]. Con tante colonne usa i blocchi vettorizzati.
 */
internal class ExemplarScorer(private val matrix: FaceMatrix, private val sets: List<IntArray>) {
  private val start = IntArray(sets.size + 1)
  private val all: IntArray

  init {
    for ((i, s) in sets.withIndex()) start[i + 1] = start[i] + s.size
    all = IntArray(start[sets.size])
    for ((i, s) in sets.withIndex()) s.copyInto(all, start[i])
  }

  private val blocks: ColumnBlocks? = if (all.size >= BLOCKED_FROM) ColumnBlocks(matrix, all) else null
  private val dots = FloatArray(all.size)
  private val buffer = FloatArray(ColumnBlocks.BLOCK)
  private val scratch = FloatArray(sets.maxOfOrNull { it.size } ?: 0)

  val size: Int get() = sets.size

  fun score(row: Int, out: FloatArray) {
    val b = blocks
    if (b == null) {
      for (j in all.indices) dots[j] = matrix.dot(row, all[j])
    } else {
      for (block in 0 until b.blocks) {
        b.dots(matrix, row, block, buffer)
        val from = block * ColumnBlocks.BLOCK
        val len = minOf(ColumnBlocks.BLOCK, all.size - from)
        buffer.copyInto(dots, from, 0, len)
      }
    }
    for (s in sets.indices) {
      var count = 0
      for (j in start[s] until start[s + 1]) if (all[j] != row) scratch[count++] = dots[j]
      out[s] = Exemplars.topMean(scratch, count) ?: Float.NaN
    }
  }

  private companion object {
    /** Da quante colonne conviene costruire i blocchi (sotto, i coseni uno per uno). */
    const val BLOCKED_FROM = 512
  }
}
