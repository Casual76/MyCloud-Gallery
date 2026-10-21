package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.PixelBox
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextVector
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// I MOTORI FINTI FEDELI (docs/fase6-progetto.md §5.2): in src/main per i test di P5, P6, P8, P9, P10, P12, P13 e P11; mai nella
// release (tools/check.ps1). Passano le stesse suite di contratto dei motori veri (`EmbedderContract`): 768 dimensioni, norma 1,
// valori passati per float16 (come si salvano), deterministici, una richiesta alla volta, latenze e guasti iniettabili.
//
// Le immagini finte portano i loro concetti nei byte (`AiTestInputs`): "FAKE:cane spiaggia" e' un'immagine di un cane in spiaggia.

/** Gli ingressi dei motori per i test (i costruttori veri sono `internal`: solo core-ai li fa, D5). */
object AiTestInputs {
  private const val PREFIX = "FAKE:"

  /** Un'immagine "con" questi [concepts] (parole italiane, vedi [FakeConcepts]). */
  fun image(concepts: String, width: Int = 768, height: Int = 576, frameMs: Int? = null): ModelImage =
    ModelImage((PREFIX + concepts).toByteArray(Charsets.UTF_8), width, height, frameMs)

  /** Un'immagine da byte qualunque (come le anteprime nel buffer): il motore finto ne ricava un concetto dall'impronta. */
  fun imageOf(bytes: ByteArray, width: Int = 768, height: Int = 576, frameMs: Int? = null): ModelImage =
    ModelImage(bytes, width, height, frameMs)

  /** Un'immagine per l'OCR con l'etichetta di una fixture di [FakeTextReader]. */
  fun ocr(tag: String, source: OcrSource = OcrSource.ANALYSIS, width: Int = 1024, height: Int = 768): OcrImage =
    OcrImage((PREFIX + tag).toByteArray(Charsets.UTF_8), width, height, source)

  fun ocrOf(bytes: ByteArray, source: OcrSource, width: Int = 1024, height: Int = 768): OcrImage = OcrImage(bytes, width, height, source)

  fun cutout(width: Int, height: Int, argb: IntArray): CutoutImage = CutoutImage(width, height, argb)

  /** I concetti scritti in un'immagine finta, o null se i byte non sono di un'immagine finta. */
  internal fun conceptsIn(bytes: ByteArray): String? {
    val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    return if (text.startsWith(PREFIX)) text.removePrefix(PREFIX) else null
  }
}

/** I vettori passano per float16 come quando si salvano: cio' che il fake restituisce e' gia' "salvabile". */
private fun throughFloat16(v: FloatArray): FloatArray = Float16.decode(Float16.encode(v))

/**
 * Il motore delle query finto (D3): concetti dalla tabella dei sinonimi, una richiesta alla volta ([maxConcurrent] lo prova),
 * [latencyMs] di attesa (tempo virtuale nei test), guasti con [failWarmUp] e [failNext]. Dopo [release] un [embed] lo riscalda.
 */
class FakeTextEmbedder(
  var latencyMs: Long = 0,
  var coldStartMs: Long = 0,
) : TextEmbedder {
  private val mutex = Mutex()
  private var warm = false
  private var inFlight = 0

  var failWarmUp: AiEngineError? = null
  var failNext: AiEngineError? = null
  var warmUps: Int = 0
    private set
  var embeds: Int = 0
    private set
  var maxConcurrent: Int = 0
    private set

  override suspend fun warmUp(): WarmUpResult = mutex.withLock { warmUpLocked() }

  private suspend fun warmUpLocked(): WarmUpResult {
    failWarmUp?.let { return WarmUpResult.Failed(it) }
    if (warm) return WarmUpResult.Ready(0)
    warmUps++
    if (coldStartMs > 0) delay(coldStartMs)
    warm = true
    return WarmUpResult.Ready(coldStartMs)
  }

  override suspend fun embed(text: String): TextVector = mutex.withLock {
    inFlight++
    maxConcurrent = maxOf(maxConcurrent, inFlight)
    try {
      when (val w = warmUpLocked()) {
        is WarmUpResult.Failed -> throw AiEngineException(w.error)
        is WarmUpResult.Ready -> Unit
      }
      failNext?.let {
        failNext = null
        throw AiEngineException(it)
      }
      if (latencyMs > 0) delay(latencyMs)
      embeds++
      val truncated = text.take(AiRecipe.CURRENT.maxQueryChars)
      TextVector(SpaceCode.CURRENT.space, throughFloat16(FakeConcepts.mix(FakeConcepts.conceptsOf(truncated))))
    } finally {
      inFlight--
    }
  }

  override suspend fun release() {
    mutex.withLock { warm = false }
  }
}

/**
 * Il motore delle immagini finto (D3, E-A12): i concetti delle immagini finte (piu' un rumore deterministico per immagine, cosi'
 * testo e immagine non coincidono mai del tutto), GPU di partenza e ripiego su CPU per la sessione quando [gpuFailsNext] e' vero.
 * Un'immagine con byte qualunque prende un concetto dall'impronta dei byte (stessi byte, stesso vettore).
 */
class FakeImageEmbedder(
  var latencyMsPerImage: Long = 0,
  var imageNoise: Float = 0.30f,
) : ImageEmbedder {
  private val mutex = Mutex()
  override val backend: MutableStateFlow<AiBackend> = MutableStateFlow(AiBackend.GPU)

  /** La GPU da' valori non finiti alla prossima chiamata: si ripete su CPU e si resta su CPU (E-A12). */
  var gpuFailsNext: Boolean = false
  var failNext: AiEngineError? = null
  var calls: Int = 0
    private set
  var images: Int = 0
    private set
  var fallbacks: Int = 0
    private set

  override suspend fun embed(images: List<ModelImage>): List<FloatArray> = mutex.withLock {
    calls++
    failNext?.let {
      failNext = null
      throw AiEngineException(it)
    }
    if (gpuFailsNext && backend.value == AiBackend.GPU) {
      gpuFailsNext = false
      fallbacks++
      backend.value = AiBackend.CPU
    }
    if (latencyMsPerImage > 0) delay(latencyMsPerImage * images.size)
    this.images += images.size
    images.map { image ->
      val bytes = image.jpeg
      val concepts = AiTestInputs.conceptsIn(bytes)
      val seed = concepts ?: bytes.contentHashCode().toString()
      val list = if (concepts != null) FakeConcepts.conceptsOf(concepts) else listOf("bytes:$seed")
      throughFloat16(FakeConcepts.mix(list, noise = imageNoise, noiseSeed = "$seed@${image.frameMs}"))
    }
  }

  override suspend fun release() = Unit
}

/**
 * Il lettore del testo finto (§5.2): le righe di ogni fixture le sceglie il test ([fixture]). **Fallisce se chiamato quando la
 * lettura del testo e' spenta** ([readingHere] falso): un `AssertionError`, che nessun `catch (e: Exception)` puo' nascondere (§4.3,
 * D41). Un'immagine senza fixture = nessun testo (0 righe).
 */
class FakeTextReader(var readingHere: () -> Boolean = { true }, var latencyMs: Long = 0) : TextReader {
  private val fixtures = HashMap<String, OcrResult>()
  val reads: MutableList<OcrSource> = mutableListOf()

  fun fixture(tag: String, result: OcrResult) {
    fixtures[tag] = result
  }

  override suspend fun read(image: OcrImage): OcrResult {
    if (!readingHere()) throw AssertionError("FakeTextReader chiamato con la lettura del testo spenta (D41)")
    if (latencyMs > 0) delay(latencyMs)
    reads += image.source
    val tag = AiTestInputs.conceptsIn(image.jpeg) ?: return OcrResult(emptyList(), dense = false)
    return fixtures[tag] ?: OcrResult(emptyList(), dense = false)
  }
}

/**
 * Il ritagliatore finto (§5.2): il soggetto e' la regione **connessa** dei pixel dello stesso colore di quello toccato (le fixture
 * sintetiche sono forme piene su sfondi diversi). Sotto l'1% o sopra il 90% dell'immagine = nessun soggetto chiaro (E-V10).
 */
class FakeSubjectCutter(var latencyMs: Long = 0, private val tolerance: Int = 24) : SubjectCutter {
  var cuts: Int = 0
    private set

  override suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult {
    cuts++
    if (latencyMs > 0) delay(latencyMs)
    val w = image.width
    val h = image.height
    val sx = (point.x * (w - 1)).toInt()
    val sy = (point.y * (h - 1)).toInt()
    val target = image.pixel(sx, sy)
    val alpha = ByteArray(w * h)
    val queue = ArrayDeque<Int>()
    queue.add(sy * w + sx)
    alpha[sy * w + sx] = 0xff.toByte()
    var left = sx
    var right = sx
    var top = sy
    var bottom = sy
    while (queue.isNotEmpty()) {
      val p = queue.poll()
      val x = p % w
      val y = p / w
      left = minOf(left, x)
      right = maxOf(right, x)
      top = minOf(top, y)
      bottom = maxOf(bottom, y)
      for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
        if (nx !in 0 until w || ny !in 0 until h) continue
        val q = ny * w + nx
        if (alpha[q] != 0.toByte() || !close(image.pixel(nx, ny), target)) continue
        alpha[q] = 0xff.toByte()
        queue.add(q)
      }
    }
    val mask = AlphaMask(w, h, alpha)
    if (mask.coverage < CutoutResult.MIN_COVERAGE || mask.coverage > CutoutResult.MAX_COVERAGE) return CutoutResult.NoClearSubject
    return CutoutResult.Subject(mask, PixelBox(left, top, right + 1, bottom + 1))
  }

  override suspend fun release() = Unit

  private fun close(a: Int, b: Int): Boolean {
    val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
    val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
    val db = (a and 0xff) - (b and 0xff)
    return dr * dr + dg * dg + db * db <= tolerance * tolerance
  }
}
