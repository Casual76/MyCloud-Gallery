package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

// UN MONDO DI PIXEL PER I MOTORI VERI SULLA JVM (Fase 8, P2). I modelli veri girano solo sul dispositivo; qui i motori veri (`YuNetDetector`,
// `SFaceEmbedder`, `FaceAligner`, `FaceQuality`) girano sopra due **sessioni finte che guardano i pixel**: il "rilevatore" cerca nel quadro
// 1024x1024 le zone chiare su fondo scuro e scrive le uscite grezze che YuNet scriverebbe per ognuna (`TensorSynth`); l' "embedder" legge il
// colore al centro del volto allineato e ne ricava il vettore dell'identita' (un colore per identita'). Cosi' la geometria vera (ridimensionamento,
// bordi, scala, tasselli, coordinate di ritorno, similitudine e ritaglio) e' provata sui pixel, e un volto messo male nel ritaglio sbaglia identita'.
// Nessun volto, nessun ritratto: rettangoli colorati con un po' di rumore disegnati dalla prova.

/** Un volto disegnato: rettangolo di colore [PixelFaceWorld.colorOf] dell'identita' in ([x], [y]) di [w] x [h] pixel, con rumore di ampiezza [texture]. */
internal data class PixelFace(val identity: Int, val x: Int, val y: Int, val w: Int, val h: Int, val texture: Int = 14)

internal class PixelScene(val width: Int, val height: Int, val faces: List<PixelFace>)

internal object PixelFaceWorld {

  /** Il colore (R, G, B in 110..250) dell'identita': chiaro, lontano dal fondo (20-30) e diverso da quello delle altre. */
  fun colorOf(identity: Int): IntArray = intArrayOf(110 + (identity * 53) % 140, 110 + (identity * 97) % 140, 110 + (identity * 31) % 140)

  /** L'immagine disegnata (ARGB opaca): fondo grigio scuro con un lieve rumore, i volti sopra. */
  fun render(scene: PixelScene): FaceImage = FaceImage(pixels(scene), scene.width, scene.height)

  fun pixels(scene: PixelScene): IntArray {
    val out = IntArray(scene.width * scene.height)
    for (y in 0 until scene.height) for (x in 0 until scene.width) {
      val g = 22 + noise(x, y, 5, 1)
      out[y * scene.width + x] = argb(g, g, g)
    }
    for (face in scene.faces) {
      val c = colorOf(face.identity)
      for (y in face.y until minOf(scene.height, face.y + face.h)) for (x in face.x until minOf(scene.width, face.x + face.w)) {
        if (x < 0 || y < 0) continue
        out[y * scene.width + x] = argb(c[0] + noise(x, y, face.texture, 2), c[1] + noise(x, y, face.texture, 3), c[2] + noise(x, y, face.texture, 4))
      }
    }
    return out
  }

  /** Il riquadro vero di un volto: x, y, larghezza, altezza. */
  fun box(face: PixelFace): FloatArray = floatArrayOf(face.x.toFloat(), face.y.toFloat(), face.w.toFloat(), face.h.toFloat())

  private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

  /** Rumore deterministico in -amp..amp per pixel (non cambia con la scena: un volto ha sempre la stessa grana). */
  private fun noise(x: Int, y: Int, amp: Int, salt: Int): Int {
    var h = x * 73856093 xor y * 19349663 xor salt * 83492791
    h = h xor (h ushr 13)
    h *= 0x5bd1e995
    h = h xor (h ushr 15)
    return ((h and 0xffff) % (2 * amp + 1)) - amp
  }
}

/**
 * Le sessioni finte ([detectorOpener], [embedderOpener]) con i loro contatori e i guasti da iniettare. Una sessione = un modello caricato:
 * `opens` e `closes` dicono quante se ne sono aperte e chiuse, `maxConcurrent` che le corse sono sempre in fila.
 */
internal class FakeFaceSessions(val identities: SyntheticIdentities = SyntheticIdentities(seed = 33)) {

  val detectorOpens = AtomicInteger()
  val detectorCloses = AtomicInteger()
  val detectorRuns = AtomicInteger()
  val embedderOpens = AtomicInteger()
  val embedderCloses = AtomicInteger()
  val embedderRuns = AtomicInteger()
  val concurrent = AtomicInteger()
  val maxConcurrent = AtomicInteger()

  /** Se non nullo, l'apertura lancia questo. */
  @Volatile var detectorOpenFailure: Throwable? = null
  @Volatile var embedderOpenFailure: Throwable? = null

  /** Le corse (1, 2, 3...) del rilevatore e dell'embedder che lanciano. */
  val detectorFailOnRuns: MutableSet<Int> = ConcurrentHashMap.newKeySet()
  val embedderFailOnRuns: MutableSet<Int> = ConcurrentHashMap.newKeySet()
  @Volatile var failure: () -> Throwable = { IllegalStateException("guasto di prova") }

  /** Chiamata dentro ogni corsa del rilevatore (le prove la usano per fermare o contare). */
  @Volatile var onDetectorRun: () -> Unit = {}

  /** Le identita' il cui vettore esce non finito. */
  val nanIdentities: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  /** Dove cade il centro del riquadro nel volto allineato: la similitudine dei cinque punti "da volto" di una scatola di 100 px. */
  private val center: FloatArray = run {
    val box = floatArrayOf(0f, 0f, 100f, 100f)
    val m = checkNotNull(dev.pampa.mycloudgallery.core.ai.faces.FaceAligner.similarity(TensorSynth.faceLandmarks(box)))
    floatArrayOf((m[0] * 50 - m[1] * 50 + m[2]).toFloat(), (m[1] * 50 + m[0] * 50 + m[3]).toFloat())
  }

  val detectorOpener = DetectorOpener {
    detectorOpenFailure?.let { throw it }
    detectorOpens.incrementAndGet()
    PixelDetector()
  }

  val embedderOpener = EmbedderOpener {
    embedderOpenFailure?.let { throw it }
    embedderOpens.incrementAndGet()
    PixelEmbedder()
  }

  private inline fun <T> counted(block: () -> T): T {
    val now = concurrent.incrementAndGet()
    maxConcurrent.updateAndGet { max(it, now) }
    try {
      return block()
    } finally {
      concurrent.decrementAndGet()
    }
  }

  /** Il "rilevatore": zone con un canale sopra 80 su fondo scuro, una componente connessa = un volto. */
  private inner class PixelDetector : DetectorSession {
    private val outputs = YuNetOutputs.allocate()
    private val seen = BooleanArray(YuNetLayout.INPUT_SIDE * YuNetLayout.INPUT_SIDE)
    private val stack = IntArray(YuNetLayout.INPUT_SIDE * YuNetLayout.INPUT_SIDE)

    override fun run(frame: FloatArray): YuNetOutputs = counted {
      val run = detectorRuns.incrementAndGet()
      onDetectorRun()
      if (run in detectorFailOnRuns) throw failure()
      for (a in outputs.cls) a.fill(0f)
      for (a in outputs.obj) a.fill(0f)
      for (a in outputs.bbox) a.fill(0f)
      for (a in outputs.kps) a.fill(0f)
      seen.fill(false)
      val side = YuNetLayout.INPUT_SIDE
      for (start in 0 until side * side) {
        if (seen[start] || !bright(frame, start)) continue
        var top = 0
        stack[top++] = start
        seen[start] = true
        var x0 = side
        var y0 = side
        var x1 = -1
        var y1 = -1
        var area = 0
        while (top > 0) {
          val p = stack[--top]
          val x = p % side
          val y = p / side
          area++
          if (x < x0) x0 = x
          if (x > x1) x1 = x
          if (y < y0) y0 = y
          if (y > y1) y1 = y
          if (x > 0 && !seen[p - 1] && bright(frame, p - 1)) { seen[p - 1] = true; stack[top++] = p - 1 }
          if (x < side - 1 && !seen[p + 1] && bright(frame, p + 1)) { seen[p + 1] = true; stack[top++] = p + 1 }
          if (y > 0 && !seen[p - side] && bright(frame, p - side)) { seen[p - side] = true; stack[top++] = p - side }
          if (y < side - 1 && !seen[p + side] && bright(frame, p + side)) { seen[p + side] = true; stack[top++] = p + side }
        }
        if (area < MIN_AREA) continue
        val box = floatArrayOf(x0.toFloat(), y0.toFloat(), (x1 - x0 + 1).toFloat(), (y1 - y0 + 1).toFloat())
        TensorSynth.putFace(outputs, box, TensorSynth.faceLandmarks(box), 0.95f)
      }
      outputs
    }

    private fun bright(frame: FloatArray, pixel: Int): Boolean {
      val i = pixel * 3
      return frame[i] > 80f || frame[i + 1] > 80f || frame[i + 2] > 80f
    }

    override fun close() {
      detectorCloses.incrementAndGet()
    }
  }

  /** L' "embedder": l'identita' dal colore al centro del volto; il vettore e' quello dell'identita', scalato come l'uscita grezza di SFace. */
  private inner class PixelEmbedder : EmbedderSession {
    override fun run(rgb: FloatArray): FloatArray = counted {
      val run = embedderRuns.incrementAndGet()
      if (run in embedderFailOnRuns) throw failure()
      val side = 112
      val cx = center[0].toInt().coerceIn(3, side - 4)
      val cy = center[1].toInt().coerceIn(3, side - 4)
      var r = 0f
      var g = 0f
      var b = 0f
      for (y in cy - 3..cy + 3) for (x in cx - 3..cx + 3) {
        val i = (y * side + x) * 3
        r += rgb[i]
        g += rgb[i + 1]
        b += rgb[i + 2]
      }
      val mean = intArrayOf((r / 49f).toInt(), (g / 49f).toInt(), (b / 49f).toInt())
      val id = identityOf(mean)
      if (id in nanIdentities) return@counted FloatArray(128) { Float.NaN }
      val sample = rgb.fold(17L) { acc, v -> acc * 31 + v.toInt() }
      val unit = if (id >= 0) identities.vector(id, sample, 0.3f) else identities.vector(UNKNOWN, sample, 1f)
      FloatArray(unit.size) { unit[it] * RAW_NORM }
    }

    override fun close() {
      embedderCloses.incrementAndGet()
    }
  }

  /** L'identita' il cui colore e' piu' vicino a [mean] (distanza L1 sotto 60), o -1. */
  private fun identityOf(mean: IntArray): Int {
    var best = -1
    var bestDistance = 60
    for (id in 0 until KNOWN_IDENTITIES) {
      val c = PixelFaceWorld.colorOf(id)
      val d = abs(c[0] - mean[0]) + abs(c[1] - mean[1]) + abs(c[2] - mean[2])
      if (d < bestDistance) {
        bestDistance = d
        best = id
      }
    }
    return best
  }

  /** Dove sta il centro del volto nel ritaglio (per le prove). */
  fun faceCenterInCrop(): Pair<Float, Float> = center[0] to center[1]

  companion object {
    const val MIN_AREA = 12

    /** Le identita' 0..KNOWN_IDENTITIES-1 hanno un colore riconoscibile e diverso (i colori si ripetono ogni 140). */
    const val KNOWN_IDENTITIES = 140

    /** L'identita' di un volto che non si riconosce. */
    const val UNKNOWN = 100_000

    /** La norma dell'uscita grezza di SFace (circa 11-12): i vettori si normalizzano in `FaceVectors`. */
    const val RAW_NORM = 11.5f

    /** La distanza fra un punto e l'altro (per le prove di posizione). */
    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(ax - bx, ay - by)
  }
}
