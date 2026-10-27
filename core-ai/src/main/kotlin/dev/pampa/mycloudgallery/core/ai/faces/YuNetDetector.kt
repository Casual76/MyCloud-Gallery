package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceDetector
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.contract.FloatBox
import dev.pampa.mycloudgallery.core.ai.runtime.EngineErrors
import dev.pampa.mycloudgallery.core.ai.runtime.EngineThread
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// IL RILEVATORE DI VOLTI (docs/fase8-progetto.md D6, D10, D17, §2.2 "Nel telefono"): YuNet 2023mar su LiteRT, CPU a 4 thread, ingresso
// fisso 1024x1024 con bordi. La politica: una richiesta alla volta (le chiamate si mettono in fila), un thread suo per il motore, errori
// come codici (`AiEngineException`: mai il messaggio di una libreria nei log, D37), rilascio idempotente, riapertura da sola dopo il
// rilascio o dopo un guasto. Provata sulla JVM con sessioni finte (`DetectorSession`); la sessione vera e' `FaceLiteRtSessions.kt`.
//
// PREVIEW: l'immagine intera nel quadro (ridotta o ingrandita a lato lungo 1024). HQ_TILES: i tasselli di `FaceTiling` (fino a quattro,
// 128 px di sovrapposizione), ognuno nel quadro con la sua scala; i rilevamenti tornano alle coordinate dell'immagine intera, quelli tagliati
// dalla giunzione fra due tasselli si scartano (li trova intero l'altro tassello se stanno nella sovrapposizione) e si fondono i doppi con
// l'NMS. I volti piu' larghi della sovrapposizione che attraversano una giunzione non escono dalla passata HQ: vengono dall'anteprima.
// I riquadri escono tenuti dentro l'immagine; i cinque punti no (un volto tagliato dal bordo ha i punti previsti fuori).

class YuNetDetector internal constructor(
  private val opener: DetectorOpener,
  private val threshold: Float = FaceEngineLimits.DETECT_THRESHOLD,
  private val nmsIou: Float = FaceEngineLimits.NMS_THRESHOLD,
) : FaceDetector {

  private val gate = Mutex()
  private var thread: EngineThread? = null
  private var session: DetectorSession? = null
  private var builder: FrameBuilder? = null

  /** Quante sessioni si sono aperte (le prove del rilascio e della riapertura). */
  @Volatile
  internal var opened: Int = 0
    private set

  /** Quante corse del modello (un tassello e' una corsa). */
  @Volatile
  internal var inferences: Int = 0
    private set

  /** Le misure dell'ultima richiesta riuscita (le legge chi misura). */
  @Volatile
  internal var lastTimings: DetectTimings? = null
    private set

  override suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace> = gate.withLock {
    var opening = false
    try {
      val engine = thread ?: EngineThread(THREAD_NAME).also { thread = it }
      withContext(engine.dispatcher) {
        val current = session ?: run {
          opening = true
          opener.open().also {
            session = it
            opened++
          }
        }
        opening = false
        work(current, image, pass)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      // Il modello e' di 240 KB e si riapre in pochi millisecondi: dopo qualunque guasto si riparte da una sessione nuova.
      val error = EngineErrors.map(t, whileInitializing = opening)
      closeEngine()
      throw error
    }
  }

  private suspend fun work(s: DetectorSession, image: FaceImage, pass: DetectPass): List<DetectedFace> {
    val t0 = System.nanoTime()
    val frames = builder ?: FrameBuilder().also { builder = it }
    val plan = if (pass == DetectPass.HQ_TILES) {
      FaceTiling.plan(image.width, image.height)
    } else {
      TilePlan(listOf(FaceRegion(0, 0, image.width, image.height)), 1, 1)
    }
    val pixels = image.raw()
    val found = ArrayList<RawDetection>()
    var prepareNanos = 0L
    var inferNanos = 0L
    var decodeNanos = 0L
    for ((index, region) in plan.regions.withIndex()) {
      currentCoroutineContext().ensureActive()
      val a = System.nanoTime()
      val geometry = frames.build(pixels, image.width, region)
      val b = System.nanoTime()
      val outputs = s.run(frames.frame)
      inferences++
      val c = System.nanoTime()
      val raw = YuNetDecoder.decode(outputs, threshold, nmsIou)
      val seams = plan.seams(index)
      for (d in raw) if (!cutBySeam(d, geometry, seams)) found += toImage(d, geometry)
      val e = System.nanoTime()
      prepareNanos += b - a
      inferNanos += c - b
      decodeNanos += e - c
    }
    val merged = if (plan.regions.size > 1) YuNetDecoder.nms(found, nmsIou) else found
    val faces = ArrayList<DetectedFace>(merged.size)
    for (d in merged) {
      faces += clipped(d, image.width, image.height) ?: continue
      if (faces.size == FaceEngineLimits.MAX_FACES) break
    }
    val end = System.nanoTime()
    lastTimings = DetectTimings(prepareNanos / NANOS_PER_MS, inferNanos / NANOS_PER_MS, decodeNanos / NANOS_PER_MS, (end - t0) / NANOS_PER_MS, plan.regions.size)
    return faces
  }

  /** Un rilevamento che tocca (a meno di [FaceTiling.SEAM_MARGIN] px del quadro) una giunzione fra tasselli: tagliato dal bordo del tassello. */
  private fun cutBySeam(d: RawDetection, g: FrameGeometry, seams: TilePlan.Seams): Boolean {
    val margin = FaceTiling.SEAM_MARGIN
    return (seams.left && d.x < margin) ||
      (seams.top && d.y < margin) ||
      (seams.right && d.x1 > g.contentWidth - margin) ||
      (seams.bottom && d.y1 > g.contentHeight - margin)
  }

  /** Dal quadro alle coordinate dell'immagine intera. */
  private fun toImage(d: RawDetection, g: FrameGeometry): RawDetection {
    val x0 = g.imageX(d.x)
    val y0 = g.imageY(d.y)
    val landmarks = FloatArray(FaceEngineLimits.LANDMARK_VALUES)
    for (p in 0 until 5) {
      landmarks[2 * p] = g.imageX(d.landmarks[2 * p])
      landmarks[2 * p + 1] = g.imageY(d.landmarks[2 * p + 1])
    }
    return RawDetection(x0, y0, g.imageX(d.x1) - x0, g.imageY(d.y1) - y0, landmarks, d.score)
  }

  /** Il volto con il riquadro tenuto dentro l'immagine; null se fuori dall'immagine o ridotto a niente. */
  private fun clipped(d: RawDetection, width: Int, height: Int): DetectedFace? {
    val x0 = d.x.coerceIn(0f, width.toFloat())
    val y0 = d.y.coerceIn(0f, height.toFloat())
    val x1 = d.x1.coerceIn(0f, width.toFloat())
    val y1 = d.y1.coerceIn(0f, height.toFloat())
    if (!(x1 - x0 >= MIN_SIDE_PX && y1 - y0 >= MIN_SIDE_PX)) return null
    return DetectedFace(FloatBox(x0, y0, x1, y1), d.landmarks, d.score.coerceIn(0f, 1f))
  }

  /** Idempotente; dopo il rilascio un [detect] riapre il modello da solo. */
  override suspend fun release() {
    gate.withLock { withContext(NonCancellable) { closeEngine() } }
  }

  private suspend fun closeEngine() {
    val s = session
    val t = thread
    session = null
    thread = null
    builder = null
    if (t != null && s != null) {
      runCatching { withContext(t.dispatcher) { s.close() } }
    } else {
      runCatching { s?.close() }
    }
    t?.shutdown()
  }

  companion object {
    /** Il modello del catalogo (verificato a ogni apertura, D16), su CPU. */
    fun create(files: ModelFiles, spec: ModelSpec = ModelCatalog.FACE_YUNET, threads: Int = FaceEngineLimits.CPU_THREADS): YuNetDetector =
      YuNetDetector(FaceModelOpeners.detector(files, spec, threads))

    private const val THREAD_NAME = "mcg-face-detect"
    private const val NANOS_PER_MS = 1_000_000L

    /** Un riquadro piu' stretto di cosi' dopo il taglio non e' un volto. */
    private const val MIN_SIDE_PX = 1f
  }
}

/** Quanto ha impiegato l'ultima richiesta, in millisecondi: preparazione del quadro, corse del modello, decodifica e NMS, totale; e i tasselli. */
internal class DetectTimings(val prepareMs: Long, val inferMs: Long, val decodeMs: Long, val totalMs: Long, val tiles: Int)
