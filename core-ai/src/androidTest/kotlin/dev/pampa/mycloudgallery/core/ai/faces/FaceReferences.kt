package dev.pampa.mycloudgallery.core.ai.faces

/**
 * I riferimenti numerici di P1 per la parita' sul dispositivo (Fase 8, P2): per ogni immagine di prova, i rilevamenti dell'ONNX di YuNet (decodificati dal
 * decodificatore di riferimento di P1, `common.decodifica_yunet`) e, per ognuno, il vettore dell'ONNX di SFace sul volto allineato con **i punti dell'ONNX**
 * (`common.volto_allineato`); piu' i riquadri di verita' di terra delle composizioni (per il richiamo). **Solo numeri** (nessuna immagine, nessun volto):
 * li produce `core-ai/src/androidTest/faces/esporta_riferimenti.py` sul PC.
 *
 * ```
 * MCGF-RIFERIMENTI 1
 * immagine img/B-img-000.png
 * verita <x> <y> <w> <h>
 * rilevamento <punteggio> <x> <y> <w> <h> <x0> <y0> ... <x4> <y4> <128 float32 esadecimali>
 * fine
 * ```
 * Le coordinate sono nel quadro di 1024 px delle composizioni (che hanno il lato di 1024: il quadro del modello e' l'immagine stessa). I numeri dei
 * rilevamenti sono decimali, il vettore in esadecimale (8 cifre per float32, esatto).
 */
internal class FaceReferences(val images: List<Item>) {

  class Item(val name: String, val truth: List<FloatArray>, val detections: List<Detection>)

  /** Un rilevamento di riferimento: punteggio, riquadro (x, y, larghezza, altezza), cinque punti e il vettore di SFace (norma 1). */
  class Detection(val score: Float, val box: FloatArray, val landmarks: FloatArray, val vector: FloatArray)

  companion object {
    fun parse(text: String): FaceReferences {
      val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
      require(lines.firstOrNull() == "MCGF-RIFERIMENTI 1") { "intestazione dei riferimenti sconosciuta" }
      val items = ArrayList<Item>()
      var name: String? = null
      var truth = ArrayList<FloatArray>()
      var detections = ArrayList<Detection>()
      for (line in lines.drop(1)) {
        val p = line.split(' ').filter { it.isNotEmpty() }
        when (p[0]) {
          "immagine" -> {
            name = p[1]
            truth = ArrayList()
            detections = ArrayList()
          }
          "verita" -> truth += FloatArray(4) { p[1 + it].toFloat() }
          "rilevamento" -> {
            val numbers = p.subList(1, 16).map { it.toFloat() }
            detections += Detection(
              score = numbers[0],
              box = floatArrayOf(numbers[1], numbers[2], numbers[3], numbers[4]),
              landmarks = FloatArray(10) { numbers[5 + it] },
              vector = FloatArray(128) { java.lang.Float.intBitsToFloat(p[16 + it].toLong(16).toInt()) },
            )
          }
          "fine" -> {
            items += Item(checkNotNull(name) { "fine senza immagine" }, truth, detections)
            name = null
          }
          else -> error("riga sconosciuta: ${p[0]}")
        }
      }
      return FaceReferences(items)
    }
  }
}
