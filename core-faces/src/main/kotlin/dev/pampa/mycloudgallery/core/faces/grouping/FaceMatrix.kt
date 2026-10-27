package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.model.FaceSpaceCode

// I CONTI VELOCI DEL RAGGRUPPAMENTO (docs/fase8-progetto.md §2.5 "Le grandezze", riga P5 del §4.4 "misure JVM").
//
// **Stessi bit del riferimento.** Ogni coseno e' la somma in float dei prodotti delle componenti, nell'ordine delle componenti, partendo
// da zero: e' esattamente `GroupFace.cosine` (e `GroupingMath.dot`). Cosi' assegnazioni, espulsioni, esemplari, centroidi e unioni del
// nucleo vero sono identici, bit per bit, a quelli di `testing/ReferenceGrouping`; solo il legame medio delle nascite usa le somme dei
// vettori (stesso valore a meno di 1e-6, §5.2 "salvo i casi a +-1e-4 dalle soglie").
//
// [ColumnBlocks.dots] calcola 256 coseni alla volta tenendo un accumulatore per colonna e sommando le componenti in ordine: e' la stessa
// somma di ogni coseno, ma il ciclo interno scorre le colonne e la JVM lo vettorizza (sul PC ~10 ns a coseno contro ~60 della somma
// sequenziale). Il ciclo vettorizzato sta in una funzione a se' con due array senza spostamenti, la forma che il compilatore riconosce.

/** I vettori dei volti di un passo in un'unica matrice: la riga `i` e' il volto `i` (in ordine di riferimento). */
internal class FaceMatrix(vectors: List<FloatArray>) {
  val rows: Int = vectors.size
  val data: FloatArray = FloatArray(rows * DIM)

  init {
    for ((i, v) in vectors.withIndex()) {
      require(v.size == DIM) { "Dimensione del vettore." }
      v.copyInto(data, i * DIM)
    }
  }

  /** Il coseno fra le righe [a] e [b]: la somma sequenziale in float (come `GroupFace.cosine`). */
  fun dot(a: Int, b: Int): Float {
    val oa = a * DIM
    val ob = b * DIM
    var s = 0f
    for (k in 0 until DIM) s += data[oa + k] * data[ob + k]
    return s
  }

  /** Il prodotto scalare fra la riga [a] e [v] (come `GroupFace.dot`). */
  fun dot(a: Int, v: FloatArray): Float {
    val oa = a * DIM
    var s = 0f
    for (k in 0 until DIM) s += data[oa + k] * v[k]
    return s
  }

  /** Una copia della riga [a]. */
  fun row(a: Int): FloatArray = data.copyOfRange(a * DIM, (a + 1) * DIM)

  companion object {
    val DIM: Int = FaceSpaceCode.CURRENT.dimension
  }
}

/**
 * Un insieme di colonne (righe scelte di una [FaceMatrix]) trasposto a blocchi di [BLOCK]: per ogni blocco e ogni componente un array
 * con le [BLOCK] componenti delle sue colonne (zeri dopo l'ultima colonna). [dots] riempie `out[t]` con il coseno fra una riga e la colonna
 * `blocco * BLOCK + t`, con la somma sequenziale di [FaceMatrix.dot]: stessi bit.
 */
internal class ColumnBlocks(matrix: FaceMatrix, private val columns: IntArray) {
  val count: Int = columns.size
  val blocks: Int = (count + BLOCK - 1) / BLOCK
  private val parts: Array<FloatArray> = Array(blocks * FaceMatrix.DIM) { FloatArray(BLOCK) }

  init {
    val dim = FaceMatrix.DIM
    for ((j, row) in columns.withIndex()) {
      val block = j / BLOCK
      val t = j % BLOCK
      val offset = row * dim
      for (k in 0 until dim) parts[block * dim + k][t] = matrix.data[offset + k]
    }
  }

  /** La riga della matrice della colonna [j]. */
  fun column(j: Int): Int = columns[j]

  /** I coseni fra la riga [row] di [matrix] e le colonne del blocco [block], in [out] (lungo [BLOCK]). */
  fun dots(matrix: FaceMatrix, row: Int, block: Int, out: FloatArray) {
    out.fill(0f)
    val dim = FaceMatrix.DIM
    val offset = row * dim
    val base = block * dim
    for (k in 0 until dim) accumulate(matrix.data[offset + k], parts[base + k], out)
  }

  /** I coseni fra [vector] e le colonne del blocco [block], in [out]. */
  fun dots(vector: FloatArray, block: Int, out: FloatArray) {
    out.fill(0f)
    val dim = FaceMatrix.DIM
    val base = block * dim
    for (k in 0 until dim) accumulate(vector[k], parts[base + k], out)
  }

  companion object {
    const val BLOCK: Int = 256

    /** `out[t] += f * x[t]`: la forma che la JVM vettorizza (due array senza spostamenti, limite = lunghezza). */
    private fun accumulate(f: Float, x: FloatArray, out: FloatArray) {
      for (t in out.indices) out[t] += f * x[t]
    }
  }
}
