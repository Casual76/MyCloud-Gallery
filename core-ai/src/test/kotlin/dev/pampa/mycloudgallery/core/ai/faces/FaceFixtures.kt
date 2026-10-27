package dev.pampa.mycloudgallery.core.ai.faces

import java.io.File

/**
 * Le fixture numeriche dei motori dei volti (`core-ai/src/test/resources/faces/`, Fase 8 P2): solo numeri, nessuna immagine.
 *
 *  - `tensori-*.txt`: le uscite grezze di YuNet in forma sparsa, con l'atteso del decodificatore di riferimento di P1 ([TensorScene]);
 *  - `allineamento.txt`: cinque punti e la matrice di P1 che li porta sul modello a 112 px di ArcFace ([AlignmentCase]).
 * Si leggono dal file system (come `BundledFaceModelsTest` legge gli asset): il test gira nella cartella del modulo.
 */
internal object FaceFixtures {

  val dir: File = File("src/test/resources/faces")

  /** Una cella accesa: passo, riga, colonna e i 16 valori (cls, obj, bbox0..3, kps0..9). */
  class Cell(val stride: Int, val row: Int, val col: Int, val values: FloatArray)

  /** Un rilevamento atteso: punteggio, riquadro (x, y, larghezza, altezza) e cinque punti. */
  class Expected(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val landmarks: FloatArray)

  class TensorScene(val file: String, val name: String, val cells: List<Cell>, val expected: List<Expected>) {
    /** Le uscite del modello con le sole celle della fixture (le altre valgono zero). */
    fun outputs(): YuNetOutputs {
      val out = YuNetOutputs.allocate()
      for (cell in cells) {
        val scale = YuNetLayout.STRIDES.indexOf(cell.stride)
        require(scale >= 0) { "passo sconosciuto" }
        val idx = cell.row * YuNetLayout.CELLS[scale] + cell.col
        val v = cell.values
        out.cls[scale][idx] = v[0]
        out.obj[scale][idx] = v[1]
        for (k in 0 until 4) out.bbox[scale][idx * 4 + k] = v[2 + k]
        for (k in 0 until 10) out.kps[scale][idx * 10 + k] = v[6 + k]
      }
      return out
    }

    override fun toString(): String = "$file/$name"
  }

  /** Cinque punti (pixel) e la similitudine di P1 `[a, b, tx, ty]` (`x' = a x - b y + tx`, `y' = b x + a y + ty`). */
  class AlignmentCase(val landmarks: FloatArray, val a: Double, val b: Double, val tx: Double, val ty: Double)

  fun tensorFiles(): List<File> = dir.listFiles { f -> f.name.startsWith("tensori-") && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }

  fun tensorScenes(): List<TensorScene> = tensorFiles().flatMap { parseTensors(it) }

  fun parseTensors(file: File): List<TensorScene> {
    val lines = file.readLines()
    require(lines.firstOrNull() == "MCGF-TENSORI 1") { "${file.name}: intestazione sconosciuta" }
    val scenes = ArrayList<TensorScene>()
    var name: String? = null
    var cells = ArrayList<Cell>()
    var expected = ArrayList<Expected>()
    for (line in lines.drop(1)) {
      val parts = line.trim().split(' ').filter { it.isNotEmpty() }
      if (parts.isEmpty() || parts[0].startsWith("#")) continue
      when (parts[0]) {
        "immagine" -> {
          name = parts[1]
          cells = ArrayList()
          expected = ArrayList()
        }
        "cella" -> cells += Cell(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), FloatArray(16) { hex(parts[4 + it]) })
        "atteso" -> {
          val n = parts.drop(1).map { it.toFloat() }
          expected += Expected(n[0], n[1], n[2], n[3], n[4], FloatArray(10) { n[5 + it] })
        }
        "fine" -> {
          scenes += TensorScene(file.name, checkNotNull(name) { "fine senza immagine" }, cells, expected)
          name = null
        }
        else -> error("${file.name}: riga sconosciuta: ${parts[0]}")
      }
    }
    return scenes
  }

  fun alignmentCases(): List<AlignmentCase> {
    val lines = File(dir, "allineamento.txt").readLines()
    require(lines.firstOrNull() == "MCGF-ALLINEAMENTO 1") { "allineamento.txt: intestazione sconosciuta" }
    val cases = ArrayList<AlignmentCase>()
    var landmarks: FloatArray? = null
    for (line in lines.drop(1)) {
      val parts = line.trim().split(' ').filter { it.isNotEmpty() }
      if (parts.isEmpty() || parts[0].startsWith("#")) continue
      when (parts[0]) {
        "punti" -> landmarks = FloatArray(10) { hex(parts[1 + it]) }
        "matrice" -> {
          val m = parts.drop(1).map { it.toDouble() }
          // [[a, -b, tx], [b, a, ty]]
          cases += AlignmentCase(checkNotNull(landmarks), m[0], m[3], m[2], m[5])
          landmarks = null
        }
        else -> error("allineamento.txt: riga sconosciuta: ${parts[0]}")
      }
    }
    return cases
  }

  /** Un float32 da 8 cifre esadecimali. */
  fun hex(text: String): Float = java.lang.Float.intBitsToFloat(text.toLong(16).toInt())
}
