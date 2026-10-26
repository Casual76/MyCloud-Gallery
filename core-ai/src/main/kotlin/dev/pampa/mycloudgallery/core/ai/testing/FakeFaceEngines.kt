package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceAlignment
import dev.pampa.mycloudgallery.core.ai.contract.FaceDetector
import dev.pampa.mycloudgallery.core.ai.contract.FaceEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.contract.FaceQualityRule
import dev.pampa.mycloudgallery.core.ai.contract.FloatBox
import dev.pampa.mycloudgallery.core.ai.contract.QualityVerdict
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * I MOTORI DEI VOLTI FINTI E FEDELI (docs/fase8-progetto.md §5.2, `FakeFaceEngines`): rilevatore, allineamento, qualita' ed embedder sopra
 * una tabella di [SyntheticScene] e le [SyntheticIdentities]. Passano la stessa suite dei motori veri ([FaceEnginesContract]): 128
 * dimensioni, norma 1, float16 andata e ritorno, deterministici, una richiesta alla volta, latenze e guasti iniettabili.
 *
 * Le immagini finte portano la scena nei pixel ([image]): un'immagine che non viene da qui non ha volti (come un'immagine vuota per il
 * rilevatore vero). L'anteprima trova i volti dal lato corto di [previewMinPx] pixel in su, la passata sull'HQ (D17) da [hqMinPx]: una
 * scena con volti piccoli prova la seconda passata. La qualita' segue D19 con le soglie di [FaceEngineLimits].
 */
class FakeFaceEngines(
  val identities: SyntheticIdentities = SyntheticIdentities(seed = 8),
  private val previewMinPx: Int = 12,
  private val hqMinPx: Int = 6,
  private val latencyMillis: Long = 0,
) {
  private val scenes = HashMap<Int, SyntheticScene>()
  private val lock = Mutex()

  /** Guasti iniettabili: scene il cui rilevamento lancia, volti (scena, fotogramma, indice) con il vettore non valido, tutto il motore. */
  val detectFailures: MutableMap<Int, AiEngineError> = java.util.concurrent.ConcurrentHashMap()
  val invalidVectors: MutableSet<Triple<Int, Int, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()

  @Volatile var embedderDown: AiEngineError? = null

  val detectCalls = AtomicInteger()
  val embedCalls = AtomicInteger()
  val releases = AtomicInteger()

  /** Quante richieste ai motori sono in corso adesso (la suite controlla che siano in fila: mai piu' di una). */
  val concurrent = AtomicInteger()
  val maxConcurrent = AtomicInteger()

  @Synchronized
  fun register(scene: SyntheticScene): FakeFaceEngines {
    scenes[scene.id] = scene
    return this
  }

  @Synchronized
  fun scene(id: Int): SyntheticScene? = scenes[id]

  /**
   * L'immagine del fotogramma [frame] di [scene] con il lato lungo [longSide] (di serie quello della scena): la scena si registra da sola.
   * I pixel portano la scena, il resto e' nero.
   */
  fun image(scene: SyntheticScene, frame: Int = 0, longSide: Int = maxOf(scene.width, scene.height)): FaceImage {
    register(scene)
    val scale = longSide.toFloat() / maxOf(scene.width, scene.height)
    val w = (scene.width * scale).roundToInt().coerceAtLeast(4)
    val h = (scene.height * scale).roundToInt().coerceAtLeast(4)
    val pixels = IntArray(w * h)
    pixels[0] = MAGIC
    pixels[1] = scene.id
    return FaceImage(pixels, w, h, frame)
  }

  val detector: FaceDetector = object : FaceDetector {
    override suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace> = serve {
      detectCalls.incrementAndGet()
      val scene = sceneOf(image) ?: return@serve emptyList()
      detectFailures[scene.id]?.let { throw AiEngineException(it) }
      val min = if (pass == DetectPass.HQ_TILES) hqMinPx else previewMinPx
      facesIn(scene, image.frame).map { (_, face) -> detected(face, image) }
        .filter { it.box.shortSide >= min }
        .sortedByDescending { it.score }
        .take(FaceEngineLimits.MAX_FACES)
    }

    override suspend fun release() {
      releases.incrementAndGet()
    }
  }

  val aligner: FaceAlignment = FaceAlignment { image, landmarks ->
    require(landmarks.size == FaceEngineLimits.LANDMARK_VALUES) { "Cinque punti attesi." }
    val scene = sceneOf(image)
    val match = scene?.let { s ->
      facesIn(s, image.frame).minByOrNull { (_, face) -> distance(landmarksOf(face, image), landmarks) }
        ?.takeIf { (_, face) -> distance(landmarksOf(face, image), landmarks) <= tolerance(face, image) }
    }
    val pixels = IntArray(FaceEngineLimits.ALIGNED_SIDE * FaceEngineLimits.ALIGNED_SIDE)
    pixels[0] = MAGIC
    pixels[1] = scene?.id ?: -1
    pixels[2] = image.frame
    pixels[3] = match?.first ?: -1
    pixels[4] = match?.second?.sharpness ?: 0
    AlignedFace(pixels)
  }

  val quality: FaceQualityRule = FaceQualityRule { face, sidePx, aligned -> judge(face, sidePx, aligned) }

  val embedder: FaceEmbedder = object : FaceEmbedder {
    override val space: FaceSpaceCode = FaceSpaceCode.CURRENT

    override suspend fun embed(faces: List<AlignedFace>): List<FloatArray?> = serve {
      embedCalls.incrementAndGet()
      embedderDown?.let { throw AiEngineException(it) }
      faces.map { aligned -> vectorOf(aligned) }
    }

    override suspend fun release() {
      releases.incrementAndGet()
    }
  }

  /** I quattro pezzi insieme, come li riceve il calcolo (P4). */
  val engines: FaceEngines get() = FaceEngines(detector, embedder, aligner, quality)

  /** L'identita' del volto allineato [aligned] (per le prove: P5 e P11 sanno chi e' chi), o null per un volto che non e' di una scena. */
  fun identityOf(aligned: AlignedFace): Int? {
    val face = faceOf(aligned) ?: return null
    return face.identity
  }

  private suspend fun <T> serve(block: suspend () -> T): T = lock.withLock {
    val now = concurrent.incrementAndGet()
    maxConcurrent.updateAndGet { maxOf(it, now) }
    try {
      if (latencyMillis > 0) delay(latencyMillis)
      block()
    } finally {
      concurrent.decrementAndGet()
    }
  }

  private fun sceneOf(image: FaceImage): SyntheticScene? = if (image.pixelAt(0, 0) == MAGIC && image.width * image.height >= 2) {
    scene(if (image.width > 1) image.pixelAt(1, 0) else image.pixelAt(0, 1))
  } else {
    null
  }

  /** I volti di [frame] con il loro indice nella scena. */
  private fun facesIn(scene: SyntheticScene, frame: Int): List<Pair<Int, SyntheticFace>> =
    scene.faces.withIndex().filter { it.value.frame == frame }.map { it.index to it.value }

  private fun boxOf(face: SyntheticFace, image: FaceImage): FloatBox =
    FloatBox(face.x0 * image.width, face.y0 * image.height, face.x1 * image.width, face.y1 * image.height)

  /** I cinque punti finti: occhi, naso (spostato con l'imbardata), bocca, in pixel dell'immagine. */
  private fun landmarksOf(face: SyntheticFace, image: FaceImage): FloatArray {
    val b = boxOf(face, image)
    val w = b.x1 - b.x0
    val h = b.y1 - b.y0
    val nose = b.x0 + w * (0.5f + face.yaw / 90f * 0.25f)
    return floatArrayOf(
      b.x0 + w * 0.3f, b.y0 + h * 0.4f,
      b.x0 + w * 0.7f, b.y0 + h * 0.4f,
      nose, b.y0 + h * 0.6f,
      b.x0 + w * 0.35f, b.y0 + h * 0.8f,
      b.x0 + w * 0.65f, b.y0 + h * 0.8f,
    )
  }

  private fun detected(face: SyntheticFace, image: FaceImage): DetectedFace = DetectedFace(boxOf(face, image), landmarksOf(face, image), face.score)

  private fun distance(a: FloatArray, b: FloatArray): Float = a.indices.fold(0f) { acc, i -> acc + abs(a[i] - b[i]) }

  private fun tolerance(face: SyntheticFace, image: FaceImage): Float = boxOf(face, image).shortSide * 0.5f + 2f

  private fun faceOf(aligned: AlignedFace): SyntheticFace? {
    if (aligned.pixelAt(0, 0) != MAGIC) return null
    val scene = scene(aligned.pixelAt(1, 0)) ?: return null
    val index = aligned.pixelAt(3, 0)
    return scene.faces.getOrNull(index)
  }

  private fun vectorOf(aligned: AlignedFace): FloatArray? {
    val face = faceOf(aligned) ?: return null
    val key = Triple(aligned.pixelAt(1, 0), aligned.pixelAt(2, 0), aligned.pixelAt(3, 0))
    if (key in invalidVectors) return null
    val sample = SyntheticIdentities.mix(key.first.toLong(), key.second.toLong(), key.third.toLong())
    return identities.vector(face.identity, sample, face.noise, face.ageYears)
  }

  /** D19 con le soglie di [FaceEngineLimits]: l'imbardata dai cinque punti (il naso fra gli occhi), la nitidezza dal volto allineato. */
  private fun judge(face: DetectedFace, sidePx: Int, aligned: AlignedFace?): QualityVerdict {
    val p = face.landmarks
    val eyes = (p[0] + p[2]) / 2f
    val width = (face.box.x1 - face.box.x0).coerceAtLeast(1f)
    val yaw = ((p[4] - eyes) / (width * 0.25f) * 90f).roundToInt().coerceIn(-90, 90)
    val sharpness = (aligned?.takeIf { it.pixelAt(0, 0) == MAGIC }?.pixelAt(4, 0) ?: 0).coerceIn(0, 255)
    var reasons = 0
    if (sidePx < FaceEngineLimits.CORE_MIN_SIDE_PX) reasons = reasons or FaceReasons.SMALL
    if (abs(yaw) > FaceEngineLimits.CORE_MAX_YAW) reasons = reasons or FaceReasons.PROFILE
    if (sharpness < SHARP_MIN) reasons = reasons or FaceReasons.BLURRY
    val tier = when {
      sidePx < FaceEngineLimits.WEAK_MIN_SIDE_PX || abs(yaw) > FaceEngineLimits.WEAK_MAX_YAW -> FaceTier.NONE
      reasons == 0 && face.score >= FaceEngineLimits.CORE_MIN_SCORE -> FaceTier.CORE
      else -> FaceTier.WEAK
    }
    return QualityVerdict(tier, reasons, yaw, sharpness)
  }

  companion object {
    /** Il segno nei pixel di un'immagine o di un volto allineato finti. */
    const val MAGIC: Int = 0x7E1A_F00D

    /** La nitidezza sotto cui un volto e' "sfocato" per il motore finto. */
    const val SHARP_MIN: Int = 80
  }
}
