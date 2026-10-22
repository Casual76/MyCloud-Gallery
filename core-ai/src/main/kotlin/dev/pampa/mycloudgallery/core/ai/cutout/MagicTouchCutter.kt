package dev.pampa.mycloudgallery.core.ai.cutout

import android.os.ParcelFileDescriptor
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.PixelBox
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.ai.runtime.EngineErrors
import dev.pampa.mycloudgallery.core.ai.runtime.EngineThread
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// IL RITAGLIATORE DEL SOGGETTO (docs/fase6-progetto.md D44, D47, §2.10, E-V10). MagicTouch su LiteRT, CPU, dal punto toccato: tre passi
//   1. il quadro: l'immagine ridotta alla taglia del modello (512 per la v1, 768 per la v2), mai deformata oltre i limiti di [Framing];
//   2. il modello: una sessione ([MaskSession]) con la mappa del punto, su un thread suo;
//   3. la maschera: [SubjectRules] (c'e' un soggetto chiaro?) e [MaskRefiner] (filtro guidato alla risoluzione dell'immagine).
// La politica (una richiesta alla volta, errori come codici, rilascio idempotente, sessione riaperta da sola) sta qui ed e' provata
// sulla JVM con sessioni finte; la sessione vera sta in `LiteRtSessions.kt`. Mai rete, mai log con contenuti (D40).

/** Apre la sessione (bloccante: gira sul thread del motore). */
internal fun interface SessionOpener {
  suspend fun open(): MaskSession
}

/** Quanto ha impiegato l'ultimo ritaglio, in millisecondi (le misure di P13). */
internal class CutTimings(val frameMs: Long, val modelMs: Long, val maskMs: Long, val totalMs: Long, val openedMs: Long)

/** Cio' che si e' misurato sulla mappa dell'ultimo ritaglio (le prove): copertura, confidenza, contrasto e l'eventuale motivo del "niente". */
internal class CutStats(val stats: SubjectRules.Stats?, val reason: SubjectRules.Reason?)

class MagicTouchCutter internal constructor(
  private val opener: SessionOpener,
  private val tuning: MagicTouchTuning = MagicTouchTuning.DEFAULT,
) : SubjectCutter {

  private val gate = Mutex()
  private var thread: EngineThread? = null
  private var session: MaskSession? = null

  /** Le misure dell'ultimo ritaglio riuscito (le legge chi misura). */
  @Volatile
  internal var lastTimings: CutTimings? = null
    private set

  /** Le misure dell'ultimo ritaglio, anche se non ha dato niente (le prove le scrivono nel log). */
  @Volatile
  internal var lastStats: CutStats? = null
    private set

  /** Quante sessioni si sono aperte (la prova del rilascio e della riapertura). */
  @Volatile
  internal var opened: Int = 0
    private set

  override suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult = gate.withLock {
    var opening = false
    try {
      val engine = thread ?: EngineThread(THREAD_NAME).also { thread = it }
      withContext(engine.dispatcher) {
        var openedMs = 0L
        val current = session ?: run {
          opening = true
          val t0 = System.nanoTime()
          opener.open().also {
            session = it
            opened++
            openedMs = (System.nanoTime() - t0) / NANOS_PER_MS
          }
        }
        opening = false
        work(current, image, point, openedMs)
      }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (t: Throwable) {
      val error = EngineErrors.map(t, whileInitializing = opening).error
      // Memoria finita o motore che non parte: si chiude tutto, la prossima volta si riparte da zero.
      if (error == AiEngineError.OUT_OF_MEMORY || error == AiEngineError.INIT_FAILED || error == AiEngineError.MODEL_UNAVAILABLE) closeEngine()
      CutoutResult.Failed(error)
    }
  }

  private suspend fun work(s: MaskSession, image: CutoutImage, point: NormalizedPoint, openedMs: Long): CutoutResult {
    lastStats = null
    lastTimings = null
    val t0 = System.nanoTime()
    // Un'immagine molto allungata (un panorama) si guarda a finestre intorno al dito: il modello riceve un quadro di proporzioni normali.
    val window = Framing.window(image.width, image.height, point.x, point.y, tuning)
    val pixels = if (window == null) image.raw() else crop(image.raw(), image.width, window)
    val w = window?.width ?: image.width
    val h = window?.height ?: image.height
    val px = if (window == null) point.x else ((point.x * image.width - window.left) / window.width).coerceIn(0f, 1f)
    val py = if (window == null) point.y else ((point.y * image.height - window.top) / window.height).coerceIn(0f, 1f)
    val letterbox = Framing.useLetterbox(w, h, tuning)
    val frame = Framing.frame(pixels, w, h, s.side, letterbox)
    currentCoroutineContext().ensureActive()
    val t1 = System.nanoTime()
    val raw = s.run(frame, frame.frameX(px), frame.frameY(py), if (window == null) image else null)
    currentCoroutineContext().ensureActive()
    val t2 = System.nanoTime()
    val map = contentMap(raw, frame)
    val verdict = SubjectRules.decide(map, px, py, contentRgb(frame))
    if (verdict !is SubjectRules.Verdict.Subject) {
      lastStats = (verdict as? SubjectRules.Verdict.None)?.let { CutStats(it.stats, it.reason) }
      return CutoutResult.NoClearSubject
    }
    lastStats = CutStats(verdict.stats, null)
    val refined = MaskRefiner.refine(pixels, w, h, verdict.map)
    var bounds = refined.bounds ?: return CutoutResult.NoClearSubject
    if (refined.coverage < CutoutResult.MIN_COVERAGE || refined.coverage > CutoutResult.MAX_COVERAGE) return CutoutResult.NoClearSubject
    val alpha = if (window == null) {
      refined.alpha
    } else {
      // La maschera della finestra nell'immagine intera: fuori dalla finestra non c'e' soggetto.
      bounds = PixelBox(bounds.left + window.left, bounds.top + window.top, bounds.right + window.left, bounds.bottom + window.top)
      ByteArray(image.width * image.height).also { full ->
        for (y in 0 until h) System.arraycopy(refined.alpha, y * w, full, (window.top + y) * image.width + window.left, w)
      }
    }
    val t3 = System.nanoTime()
    lastTimings = CutTimings((t1 - t0) / NANOS_PER_MS, (t2 - t1) / NANOS_PER_MS, (t3 - t2) / NANOS_PER_MS, (t3 - t0) / NANOS_PER_MS, openedMs)
    return CutoutResult.Subject(AlphaMask.adopt(image.width, image.height, alpha), bounds)
  }

  /** I pixel della finestra, in un array suo. */
  private fun crop(pixels: IntArray, stride: Int, window: Framing.Window): IntArray {
    val out = IntArray(window.width * window.height)
    for (y in 0 until window.height) System.arraycopy(pixels, (window.top + y) * stride + window.left, out, y * window.width, window.width)
    return out
  }

  /** I colori della parte del quadro che copre l'immagine, alla risoluzione della mappa. */
  private fun contentRgb(frame: ModelFrame): FloatArray {
    if (frame.contentWidth == frame.side && frame.contentHeight == frame.side) return frame.rgb
    val out = FloatArray(frame.contentWidth * frame.contentHeight * 3)
    for (y in 0 until frame.contentHeight) {
      System.arraycopy(frame.rgb, ((frame.contentTop + y) * frame.side + frame.contentLeft) * 3, out, y * frame.contentWidth * 3, frame.contentWidth * 3)
    }
    return out
  }

  /** La parte della mappa del modello che copre l'immagine (senza i bordi del letterbox). */
  private fun contentMap(raw: FloatArray, frame: ModelFrame): ProbabilityMap {
    if (frame.contentWidth == frame.side && frame.contentHeight == frame.side) return ProbabilityMap(frame.side, frame.side, raw)
    val out = FloatArray(frame.contentWidth * frame.contentHeight)
    for (y in 0 until frame.contentHeight) {
      System.arraycopy(raw, (frame.contentTop + y) * frame.side + frame.contentLeft, out, y * frame.contentWidth, frame.contentWidth)
    }
    return ProbabilityMap(frame.contentWidth, frame.contentHeight, out)
  }

  /** Idempotente; dopo il rilascio un [cut] riapre il modello da solo. */
  override suspend fun release() {
    gate.withLock { withContext(NonCancellable) { closeEngine() } }
  }

  private suspend fun closeEngine() {
    val s = session
    val t = thread
    session = null
    thread = null
    if (t != null && s != null) {
      runCatching { withContext(t.dispatcher) { s.close() } }
    } else {
      runCatching { s?.close() }
    }
    t?.shutdown()
  }

  companion object {
    /** Il modello del catalogo (verificato a ogni apertura, D16), su CPU. */
    fun create(files: ModelFiles, spec: ModelSpec = ModelCatalog.CUTOUT_MAGICTOUCH, threads: Int = DEFAULT_THREADS): MagicTouchCutter =
      MagicTouchCutter(ModelSessions.opener(files, spec, threads, MagicTouchTuning.DEFAULT))

    /** Per le prove strumentate: il modello da due file e un'impostazione diversa. */
    internal fun forTesting(opener: SessionOpener, tuning: MagicTouchTuning = MagicTouchTuning.DEFAULT): MagicTouchCutter = MagicTouchCutter(opener, tuning)

    /** Le CPU usate dal modello. */
    const val DEFAULT_THREADS: Int = 4

    private const val THREAD_NAME = "mcg-cutout"
    private const val NANOS_PER_MS = 1_000_000L
  }
}

/** Come si apre il modello del catalogo: i byte verificati, mappati, e la sessione giusta per il suo formato. */
internal object ModelSessions {

  fun opener(files: ModelFiles, spec: ModelSpec, threads: Int, tuning: MagicTouchTuning): SessionOpener = SessionOpener {
    val model = files.open(spec.id) ?: throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    try {
      val bytes = ModelBytes.map(model)
      val inner: MaskSession = if (spec.fileName.endsWith(".task")) {
        val parts = TaskBundle.parts(bytes)
        V2Session(parts.encoder, parts.decoder, threads, tuning)
      } else {
        V1Session(bytes, threads, tuning)
      }
      OwnedSession(inner, model)
    } catch (t: Throwable) {
      runCatching { model.close() }
      throw t
    }
  }
}

/** Una sessione che chiude con se' il modello verificato (per ultimo). */
private class OwnedSession(private val inner: MaskSession, private val model: VerifiedModel) : MaskSession by inner {
  override fun close() {
    runCatching { inner.close() }
    runCatching { model.close() }
  }
}

/** I byte del modello come `ByteBuffer` mappato sul descrittore verificato (D16): l'interprete legge quello che ha l'impronta giusta. */
internal object ModelBytes {

  fun map(model: VerifiedModel): ByteBuffer {
    val size = model.spec.sizeBytes
    val descriptor = model.descriptor
    val mapped = if (descriptor != null) {
      val dup = ParcelFileDescriptor.dup(descriptor)
      ParcelFileDescriptor.AutoCloseInputStream(dup).use { input -> mapChannel(input.channel, size) }
    } else {
      // Solo nei fake dei test: nessun descrittore, il file per nome.
      RandomAccessFile(model.file, "r").use { mapChannel(it.channel, size) }
    }
    return mapped.order(ByteOrder.nativeOrder())
  }

  private fun mapChannel(channel: FileChannel, size: Long): ByteBuffer {
    if (channel.size() != size) throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    return channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
  }

  /** Mappa un file qualunque (le prove strumentate che misurano la v2 da `/data/local/tmp`). */
  fun mapFile(file: java.io.File): ByteBuffer =
    FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }.order(ByteOrder.nativeOrder())
}
