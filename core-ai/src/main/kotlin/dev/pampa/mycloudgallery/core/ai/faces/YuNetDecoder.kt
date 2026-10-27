package dev.pampa.mycloudgallery.core.ai.faces

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// LA DECODIFICA DELLE USCITE DI YUNET (docs/fase8-progetto.md §2.2 punto 2, "Nel telefono"; docs/decisioni.md "Fase 8 P1"): il modello
// converte da ONNX a LiteRT con le uscite **grezze** delle tre scale (passi 8, 16, 32: punteggi, oggettivita', riquadri, cinque punti) e
// **la decodifica e la soppressione dei doppi (NMS) le fa il nostro codice**. Puro, sulla JVM, senza classi di Android ne' di LiteRT:
// e' la traduzione di `tools/models/faces/common.py::decodifica_yunet`, provata da P1 contro `FaceDetectorYN` di OpenCV (777 riquadri su
// 777, IoU minimo 0,99999, cinque punti entro 0,00012 px), e qui contro quel decodificatore su tensori registrati (`DecoderFixturesTest`).
//
// Le formule (per la cella `idx` = riga r, colonna c del passo s):
//   punteggio = radice(clamp(cls, 0, 1) * clamp(obj, 0, 1));
//   centro    = ((c + bbox0) * s, (r + bbox1) * s);      lato = (exp(bbox2) * s, exp(bbox3) * s);      riquadro = centro -+ lato / 2;
//   punto k   = ((c + kps[2k]) * s, (r + kps[2k+1]) * s)     k = 0..4: occhio destro del soggetto (a sinistra nell'immagine), occhio
//               sinistro, punta del naso, angolo destro e angolo sinistro della bocca (l'ordine del modello a 112 px di ArcFace);
//   soglia 0,6 (>=); NMS: si scarta un riquadro con IoU > 0,3 su uno gia' tenuto di punteggio maggiore; al piu' 5.000 candidati.
// Le coordinate sono quelle del quadro 1024x1024 del modello, non ritagliate: le porta all'immagine e le tiene dentro chi chiama.

/** La forma del modello: ingresso 1x1024x1024x3 e dodici uscite float32 nell'ordine del file (indice = posizione, e hanno il nome). */
internal object YuNetLayout {
  /** Il lato del quadro di ingresso. */
  const val INPUT_SIDE: Int = 1024

  /** I passi delle tre scale. */
  val STRIDES: IntArray = intArrayOf(8, 16, 32)

  /** Celle per lato in ogni scala (`INPUT_SIDE / passo`). */
  val CELLS: IntArray = intArrayOf(128, 64, 32)

  const val SCALES: Int = 3

  /** Canali per cella: punteggio, oggettivita', riquadro (centro e lato), cinque punti. */
  const val CLS_CHANNELS: Int = 1
  const val OBJ_CHANNELS: Int = 1
  const val BBOX_CHANNELS: Int = 4
  const val KPS_CHANNELS: Int = 10

  /** I nomi delle dodici uscite nell'ordine del file (`cls_8, cls_16, cls_32, obj_8, ..., kps_32`). */
  val OUTPUT_NAMES: List<String> =
    listOf("cls", "obj", "bbox", "kps").flatMap { kind -> STRIDES.map { stride -> "${kind}_$stride" } }

  /** Le celle di una scala (`CELLS[scale]` al quadrato). */
  fun cellCount(scale: Int): Int = CELLS[scale] * CELLS[scale]

  /** La forma `[1, N, C]` che l'uscita [index] (0..11) deve avere. */
  fun expectedShape(index: Int): IntArray {
    val scale = index % SCALES
    val channels = when (index / SCALES) {
      0 -> CLS_CHANNELS
      1 -> OBJ_CHANNELS
      2 -> BBOX_CHANNELS
      else -> KPS_CHANNELS
    }
    return intArrayOf(1, cellCount(scale), channels)
  }
}

/**
 * Le uscite di una corsa del modello, una matrice piatta per tipo e per scala (indice 0 = passo 8, 1 = passo 16, 2 = passo 32):
 * `cls` e `obj` hanno N valori, `bbox` 4N e `kps` 10N, riga per riga (cella per cella). Valide fino alla corsa dopo: chi le produce le
 * riusa.
 */
internal class YuNetOutputs(
  val cls: Array<FloatArray>,
  val obj: Array<FloatArray>,
  val bbox: Array<FloatArray>,
  val kps: Array<FloatArray>,
) {
  init {
    require(cls.size == YuNetLayout.SCALES && obj.size == YuNetLayout.SCALES && bbox.size == YuNetLayout.SCALES && kps.size == YuNetLayout.SCALES) {
      "Servono le tre scale."
    }
    for (scale in 0 until YuNetLayout.SCALES) {
      val n = YuNetLayout.cellCount(scale)
      require(cls[scale].size == n * YuNetLayout.CLS_CHANNELS && obj[scale].size == n * YuNetLayout.OBJ_CHANNELS) { "Punteggi di un'altra forma." }
      require(bbox[scale].size == n * YuNetLayout.BBOX_CHANNELS && kps[scale].size == n * YuNetLayout.KPS_CHANNELS) { "Riquadri o punti di un'altra forma." }
    }
  }

  companion object {
    /** Matrici vuote (tutte a zero: nessun candidato) della forma del modello. */
    fun allocate(): YuNetOutputs = YuNetOutputs(
      Array(YuNetLayout.SCALES) { FloatArray(YuNetLayout.cellCount(it) * YuNetLayout.CLS_CHANNELS) },
      Array(YuNetLayout.SCALES) { FloatArray(YuNetLayout.cellCount(it) * YuNetLayout.OBJ_CHANNELS) },
      Array(YuNetLayout.SCALES) { FloatArray(YuNetLayout.cellCount(it) * YuNetLayout.BBOX_CHANNELS) },
      Array(YuNetLayout.SCALES) { FloatArray(YuNetLayout.cellCount(it) * YuNetLayout.KPS_CHANNELS) },
    )
  }
}

/** Un rilevamento nelle coordinate del quadro del modello: riquadro (x, y, larghezza, altezza), cinque punti e punteggio. */
internal class RawDetection(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val landmarks: FloatArray,
  val score: Float,
) {
  val x1: Float get() = x + width
  val y1: Float get() = y + height

  override fun toString(): String = "RawDetection"
}

internal object YuNetDecoder {

  /** Al piu' questi candidati entrano nell'NMS (i migliori per punteggio): un tetto alla spesa su uscite assurde. */
  const val MAX_CANDIDATES: Int = 5000

  /** Tutti i candidati con punteggio >= [threshold], nell'ordine in cui il modello li dà (scala, riga, colonna), senza NMS. */
  fun candidates(outputs: YuNetOutputs, threshold: Float): ArrayList<RawDetection> {
    val found = ArrayList<RawDetection>()
    for (scale in 0 until YuNetLayout.SCALES) {
      val stride = YuNetLayout.STRIDES[scale]
      val cells = YuNetLayout.CELLS[scale]
      val cls = outputs.cls[scale]
      val obj = outputs.obj[scale]
      val bbox = outputs.bbox[scale]
      val kps = outputs.kps[scale]
      for (idx in 0 until cells * cells) {
        val c = clamp01(cls[idx])
        val o = clamp01(obj[idx])
        // NaN: il confronto e' falso e la cella non e' un candidato.
        val score = sqrt(c * o)
        if (!(score >= threshold)) continue
        val row = idx / cells
        val col = idx % cells
        val b = idx * YuNetLayout.BBOX_CHANNELS
        val cx = (col + bbox[b]).toDouble() * stride
        val cy = (row + bbox[b + 1]).toDouble() * stride
        val w = exp(bbox[b + 2].toDouble()) * stride
        val h = exp(bbox[b + 3].toDouble()) * stride
        val k = idx * YuNetLayout.KPS_CHANNELS
        val landmarks = FloatArray(10)
        for (p in 0 until 5) {
          landmarks[2 * p] = ((col + kps[k + 2 * p]).toDouble() * stride).toFloat()
          landmarks[2 * p + 1] = ((row + kps[k + 2 * p + 1]).toDouble() * stride).toFloat()
        }
        val detection = RawDetection((cx - w / 2).toFloat(), (cy - h / 2).toFloat(), w.toFloat(), h.toFloat(), landmarks, score)
        if (isUsable(detection)) found += detection
      }
    }
    return found
  }

  /**
   * La soppressione dei doppi: dal punteggio piu' alto al piu' basso (a pari punteggio resta l'ordine di arrivo), un riquadro si tiene solo
   * se **non** si sovrappone (IoU > [maxIou]) a uno gia' tenuto. Al piu' [limit] candidati, i migliori.
   */
  fun nms(items: List<RawDetection>, maxIou: Float, limit: Int = MAX_CANDIDATES): List<RawDetection> {
    val ordered = items.sortedByDescending { it.score }.let { if (it.size > limit) it.subList(0, limit) else it }
    val kept = ArrayList<RawDetection>()
    for (candidate in ordered) {
      if (kept.none { iou(candidate, it) > maxIou }) kept += candidate
    }
    return kept
  }

  /** La decodifica intera: candidati sopra la soglia e NMS. */
  fun decode(outputs: YuNetOutputs, threshold: Float, maxIou: Float): List<RawDetection> = nms(candidates(outputs, threshold), maxIou)

  /** L'intersezione sull'unione di due riquadri (0 se non si toccano). */
  fun iou(a: RawDetection, b: RawDetection): Float {
    val iw = max(0f, min(a.x1, b.x1) - max(a.x, b.x))
    val ih = max(0f, min(a.y1, b.y1) - max(a.y, b.y))
    val inter = iw * ih
    val union = a.width * a.height + b.width * b.height - inter
    return if (union > 0f) inter / union else 0f
  }

  private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

  /** Un candidato con numeri finiti e un riquadro che esiste: il resto e' un'uscita assurda e non si propaga. */
  private fun isUsable(d: RawDetection): Boolean =
    d.x.isFinite() && d.y.isFinite() && d.width.isFinite() && d.height.isFinite() && d.width > 0f && d.height > 0f && d.landmarks.all { it.isFinite() }
}
