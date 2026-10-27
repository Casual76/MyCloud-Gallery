package dev.pampa.mycloudgallery.core.ai.faces

import kotlin.math.floor
import kotlin.math.ln

/**
 * L'inverso della decodifica, per le prove: scrive in [YuNetOutputs] la cella che farebbe uscire un volto con un dato riquadro e dati cinque
 * punti (nelle coordinate del quadro 1024x1024). Serve ai modelli finti che lavorano sui pixel (`PixelFaceWorld`) e alle prove di
 * proprieta' del decodificatore; l'atteso delle fixture, invece, viene dal decodificatore di riferimento di P1 (Python), non da qui.
 */
internal object TensorSynth {

  /** Il passo della scala che YuNet userebbe per un volto di lato corto [side] (le stesse fasce delle fixture). */
  fun strideFor(side: Float): Int = if (side < 36f) 8 else if (side < 90f) 16 else 32

  /** Scrive la cella (passo, riga, colonna) con il punteggio [score] (cls = obj = punteggio) che prevede [box] e [landmarks]. */
  fun put(
    outputs: YuNetOutputs,
    stride: Int,
    row: Int,
    col: Int,
    score: Float,
    box: FloatArray,
    landmarks: FloatArray,
  ) {
    val scale = YuNetLayout.STRIDES.indexOf(stride)
    val cells = YuNetLayout.CELLS[scale]
    if (row !in 0 until cells || col !in 0 until cells) return
    val idx = row * cells + col
    outputs.cls[scale][idx] = score
    outputs.obj[scale][idx] = score
    val cx = box[0] + box[2] / 2f
    val cy = box[1] + box[3] / 2f
    val b = idx * 4
    outputs.bbox[scale][b] = cx / stride - col
    outputs.bbox[scale][b + 1] = cy / stride - row
    outputs.bbox[scale][b + 2] = ln(box[2] / stride)
    outputs.bbox[scale][b + 3] = ln(box[3] / stride)
    val k = idx * 10
    for (p in 0 until 5) {
      outputs.kps[scale][k + 2 * p] = landmarks[2 * p] / stride - col
      outputs.kps[scale][k + 2 * p + 1] = landmarks[2 * p + 1] / stride - row
    }
  }

  /** La cella centrale di un volto (la piu' vicina al centro del riquadro) e le sue vicine, piu' deboli: come le accende il modello. */
  fun putFace(outputs: YuNetOutputs, box: FloatArray, landmarks: FloatArray, score: Float, neighbours: Boolean = true) {
    val stride = strideFor(minOf(box[2], box[3]))
    val col = floor((box[0] + box[2] / 2f) / stride).toInt()
    val row = floor((box[1] + box[3] / 2f) / stride).toInt()
    put(outputs, stride, row, col, score, box, landmarks)
    if (neighbours) {
      for (dr in -1..1) for (dc in -1..1) if (dr != 0 || dc != 0) put(outputs, stride, row + dr, col + dc, score - 0.15f, box, landmarks)
    }
  }

  /** Cinque punti "da volto" dentro [box] (x, y, larghezza, altezza): occhi, naso, bocca, in proporzioni plausibili. */
  fun faceLandmarks(box: FloatArray, noseShift: Float = 0f): FloatArray {
    val (x, y, w, h) = box.toList()
    return floatArrayOf(
      x + w * 0.30f, y + h * 0.40f,
      x + w * 0.70f, y + h * 0.40f,
      x + w * (0.50f + noseShift), y + h * 0.60f,
      x + w * 0.35f, y + h * 0.80f,
      x + w * 0.65f, y + h * 0.80f,
    )
  }
}
