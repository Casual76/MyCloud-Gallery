package dev.pampa.mycloudgallery.core.ai.faces

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8 (P2, "Fatto quando": parita' con i riferimenti di P1, coseno >= 0,999). I motori veri sul dispositivo contro l'ONNX di P1 sulle STESSE immagini
 * (le composizioni: ritratti di pubblico dominio incollati su sfondi senza volti), con le regole di parita' scritte da P1 prima di misurare
 * (docs/decisioni.md, "Fase 8 P1", regola 3):
 *   - YuNet: ogni rilevamento di riferimento con punteggio >= 0,605 ha un gemello con **IoU >= 0,95** e i **cinque punti entro 1 px**, e viceversa; la fascia
 *     0,595-0,605 puo' cadere dall'una o dall'altra parte della soglia e non si conta;
 *   - SFace: il vettore del volto allineato con i punti di riferimento ha **coseno >= 0,999** con quello dell'ONNX, per ogni volto (il minimo, non la media).
 * In piu': il richiamo dei volti di verita' di terra fra 24 e 48 px (P1: 98,4% sulla serie B) e dai 48 px in su. I riferimenti li esporta
 * `esporta_riferimenti.py` (numeri, nessun volto) e l'operatore li mette in `/data/local/tmp/mcg-faces/` con le immagini: senza, queste prove si saltano.
 */
@RunWith(AndroidJUnit4::class)
class FaceParityDeviceTest {

  private companion object {
    const val SURE = 0.605f
    const val DOUBT = 0.595f
    const val MIN_IOU = 0.95f
    const val MAX_POINT_PX = 1.0f
    const val MIN_COSINE = 0.999f
  }

  /** IoU di due riquadri (x, y, larghezza, altezza). */
  private fun iou(a: FloatArray, b: FloatArray): Float {
    val iw = max(0f, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    val ih = max(0f, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    val inter = iw * ih
    val union = a[2] * a[3] + b[2] * b[3] - inter
    return if (union > 0f) inter / union else 0f
  }

  private fun boxOf(face: DetectedFace): FloatArray = floatArrayOf(face.box.x0, face.box.y0, face.box.x1 - face.box.x0, face.box.y1 - face.box.y0)

  /** Il riferimento tenuto dentro l'immagine come i nostri riquadri (un volto tagliato dal bordo sfora, in Python non si taglia). */
  private fun clip(box: FloatArray, w: Float, h: Float): FloatArray {
    val x0 = box[0].coerceIn(0f, w)
    val y0 = box[1].coerceIn(0f, h)
    val x1 = (box[0] + box[2]).coerceIn(0f, w)
    val y1 = (box[1] + box[3]).coerceIn(0f, h)
    return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
  }

  private fun pointShift(a: FloatArray, b: FloatArray): Float {
    var worst = 0f
    for (i in 0 until 5) worst = max(worst, hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]))
    return worst
  }

  @Test
  fun iRilevamentiCoincidonoConQuelliDellOnnxDiP1_EIVettoriConCoseno0999() = runBlocking<Unit> {
    val refs = FacesDevice.requireReferences()
    val engines = FacesDevice.engines()
    var referenceChecked = 0
    var oursChecked = 0
    var withoutTwin = 0
    var vectors = 0
    var minIou = 1f
    var maxPoint = 0f
    var maxScoreDiff = 0f
    var minCosine = 1f
    var minEndToEnd = 1f
    var mismatchedCounts = 0
    val failures = ArrayList<String>()
    try {
      for (item in refs.images) {
        val image = FacesDevice.loadFixture(item.name)
        val ours = engines.detector.detect(image, DetectPass.PREVIEW)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        if (ours.size != item.detections.size) mismatchedCounts++
        // I riferimenti cercano il gemello fra i nostri (e viceversa).
        for (ref in item.detections) {
          val refBox = clip(ref.box, w, h)
          val twin = ours.maxByOrNull { iou(boxOf(it), refBox) }
          val i = twin?.let { iou(boxOf(it), refBox) } ?: 0f
          if (ref.score >= SURE) {
            referenceChecked++
            val shift = twin?.let { pointShift(it.landmarks, ref.landmarks) } ?: Float.MAX_VALUE
            if (twin == null || i < MIN_IOU || shift > MAX_POINT_PX) {
              withoutTwin++
              failures += "${item.name}: riferimento ${"%.3f".format(ref.score)} senza gemello (IoU ${"%.4f".format(i)}, punti ${"%.3f".format(shift)} px)"
            } else {
              minIou = min(minIou, i)
              maxPoint = max(maxPoint, shift)
              maxScoreDiff = max(maxScoreDiff, abs(twin.score - ref.score))
            }
          }
        }
        for (mine in ours) {
          val twin = item.detections.maxByOrNull { iou(boxOf(mine), clip(it.box, w, h)) }
          val i = twin?.let { iou(boxOf(mine), clip(it.box, w, h)) } ?: 0f
          if (mine.score >= SURE) {
            oursChecked++
            if (twin == null || i < MIN_IOU) {
              withoutTwin++
              failures += "${item.name}: nostro ${"%.3f".format(mine.score)} senza gemello nel riferimento (IoU ${"%.4f".format(i)})"
            }
          } else if (mine.score < DOUBT) {
            failures += "${item.name}: nostro con punteggio ${mine.score} sotto la soglia di rilevamento"
          }
        }
        // SFace: il volto allineato con i punti di RIFERIMENTO (gli stessi di Python) e il vettore dell'ONNX.
        for (ref in item.detections) {
          if (ref.score < SURE) continue
          val aligned = engines.aligner.align(image, ref.landmarks)
          val vector = engines.embedder.embed(listOf(aligned)).single()
          if (vector == null) {
            failures += "${item.name}: vettore non valido per un riferimento di punteggio ${ref.score}"
            continue
          }
          FaceEnginesContract.checkVector(vector)
          val cosine = FaceEnginesContract.cosine(vector, ref.vector)
          minCosine = min(minCosine, cosine)
          vectors++
          if (cosine < MIN_COSINE) failures += "${item.name}: coseno ${"%.6f".format(cosine)} sotto ${MIN_COSINE} (punteggio ${ref.score})"
          // Giro intero: i punti NOSTRI (di YuNet sul telefono) invece di quelli dell'ONNX; cinque punti entro 1 px non spostano il vettore di piu' di cosi'.
          val twin = ours.maxByOrNull { iou(boxOf(it), clip(ref.box, w, h)) }
          if (twin != null && iou(boxOf(twin), clip(ref.box, w, h)) >= MIN_IOU) {
            val mine = engines.embedder.embed(listOf(engines.aligner.align(image, twin.landmarks))).single()
            if (mine != null) minEndToEnd = min(minEndToEnd, FaceEnginesContract.cosine(mine, ref.vector))
          }
        }
      }
    } finally {
      engines.release()
    }
    FacesDevice.record("parita_con_p1") {
      put("immagini", refs.images.size)
      put("riferimenti_controllati", referenceChecked)
      put("nostri_controllati", oursChecked)
      put("senza_gemello", withoutTwin)
      put("iou_minimo", minIou.toDouble())
      put("punti_max_px", maxPoint.toDouble())
      put("punteggio_max_diff", maxScoreDiff.toDouble())
      put("immagini_con_numero_diverso", mismatchedCounts)
      put("vettori_controllati", vectors)
      put("coseno_minimo_stessi_punti", minCosine.toDouble())
      put("coseno_minimo_punti_nostri", minEndToEnd.toDouble())
    }
    assertTrue("niente da confrontare: ${refs.images.size} immagini, $referenceChecked riferimenti", referenceChecked >= 30 && vectors >= 30)
    assertEquals("parita' non passata:\n" + failures.take(20).joinToString("\n"), 0, failures.size)
    assertTrue("coseno minimo $minCosine", minCosine >= MIN_COSINE)
    assertTrue("coseno minimo con i punti del telefono $minEndToEnd", minEndToEnd >= 0.99f)
  }

  @Test
  fun ilRichiamoDeiVoltiDiVeritaFra24E48PxEDaiLORo48InSu() = runBlocking<Unit> {
    val refs = FacesDevice.requireReferences()
    assumeTrue("serve la verita' di terra delle composizioni", refs.images.any { it.truth.isNotEmpty() })
    val engines = FacesDevice.engines()
    var small = 0
    var smallFound = 0
    var large = 0
    var largeFound = 0
    var extra = 0
    try {
      for (item in refs.images) {
        val image = FacesDevice.loadFixture(item.name)
        val ours = engines.detector.detect(image, DetectPass.PREVIEW)
        val used = HashSet<Int>()
        for (truth in item.truth) {
          val side = min(truth[2], truth[3])
          if (side < 24f) continue
          // Come P1 (regola 1): trovato = almeno un riquadro dopo l'NMS con IoU >= 0,3.
          val hit = ours.indices.maxByOrNull { iou(boxOf(ours[it]), truth) }
          val found = hit != null && iou(boxOf(ours[hit]), truth) >= 0.3f
          if (found) used += hit!!
          if (side < 48f) {
            small++
            if (found) smallFound++
          } else {
            large++
            if (found) largeFound++
          }
        }
        extra += ours.size - used.size
      }
    } finally {
      engines.release()
    }
    FacesDevice.record("richiamo_composizioni") {
      put("volti_24_47_px", small)
      put("trovati_24_47_px", smallFound)
      put("volti_da_48_px", large)
      put("trovati_da_48_px", largeFound)
      put("riquadri_senza_verita", extra)
    }
    assertTrue("pochi volti di verita': $small piccoli e $large grandi", small + large >= 30)
    if (small > 0) assertTrue("richiamo fra 24 e 47 px: $smallFound su $small (P1: 98,4%)", smallFound >= small * 0.95)
    if (large > 0) assertTrue("richiamo da 48 px: $largeFound su $large", largeFound >= large * 0.97)
  }
}
