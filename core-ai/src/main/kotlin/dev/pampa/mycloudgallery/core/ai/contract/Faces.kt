package dev.pampa.mycloudgallery.core.ai.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier

// I MOTORI DEI VOLTI (docs/fase8-progetto.md §1.2 "core-ai", §2.2 "Nel telefono", D6, D7, D10, D17, D19). Contratti di P0; i motori veri
// sono di P2 in `core-ai/.../faces/` (YuNet e SFace su LiteRT, CPU a 4 thread, modello mappato sul descrittore verificato): **solo**
// `faces/FaceLiteRtSessions.kt` nomina `org.tensorflow.lite` fra le classi dei volti (Phase6ArchitectureTest, Phase8ArchitectureTest).
// I fake fedeli (`testing/FakeFaceEngines`, identita' sintetiche) passano la stessa suite dei motori veri (`testing/FaceEnginesContract`).
//
// D17, F6-D5: gli ingressi li prepara SEMPRE l'app (`ModelInputs.forFaces` di P2: intestazione letta prima di decodificare,
// `ImageBounds`, `ImageDecoder`), mai byte letti dal NAS al motore. Per questo [FaceImage] e [AlignedFace] hanno il costruttore
// `internal`: li costruiscono solo il codice di core-ai (P2) e i fake di questo modulo. **Nessun attributo della persona** (D19, C20):
// i motori danno riquadri, cinque punti, punteggi e vettori; la qualita' guarda solo dimensione, imbardata e nitidezza.

/**
 * Un'immagine pronta per il rilevatore (D17): pixel ARGB_8888 gia' raddrizzati, lato lungo al piu' [FaceEngineLimits.HQ_SIDE]
 * (l'anteprima di analisi ne ha 1024, l'HQ 2048, un fotogramma 1024). [frame] e' il fotogramma (0 per le foto, 0..8 nel blob `frames`).
 * Costruttore `internal`: la fa `ModelInputs.forFaces` (P2) o un fake di core-ai. I pixel non escono mai dal processo ne' finiscono nei log.
 */
class FaceImage internal constructor(
  pixels: IntArray,
  val width: Int,
  val height: Int,
  val frame: Int = 0,
) {
  private val data: IntArray = pixels.copyOf()

  init {
    require(width > 0 && height > 0 && width.toLong() * height.toLong() == data.size.toLong()) { "Immagine vuota o incoerente." }
    require(maxOf(width, height) <= FaceEngineLimits.HQ_SIDE) { "Immagine oltre il lato massimo." }
    require(frame in 0..FaceEngineLimits.MAX_FRAME) { "Fotogramma fuori intervallo." }
  }

  /** Una copia dei pixel (ARGB_8888, riga per riga). */
  val pixels: IntArray get() = data.copyOf()

  internal fun pixelAt(x: Int, y: Int): Int = data[y * width + x]

  /** P2: i pixel **senza copia** (fino a 16 MB all'HQ): per i motori di core-ai (`faces/`), che non li cambiano e non li fanno uscire. */
  internal fun raw(): IntArray = data

  /** Mai i pixel nei log (D37). */
  override fun toString(): String = "FaceImage(${width}x$height)"
}

/** Un riquadro in pixel dell'immagine passata al rilevatore (`x0 < x1`, `y0 < y1`). */
data class FloatBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
  init {
    require(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite()) { "Riquadro non finito." }
    require(x0 < x1 && y0 < y1) { "Riquadro vuoto o rovesciato." }
  }

  /** Il lato corto, in pixel dell'immagine. */
  val shortSide: Float get() = minOf(x1 - x0, y1 - y0)

  override fun toString(): String = "FloatBox"
}

/**
 * Un volto trovato (D7): il riquadro, i **cinque punti** (occhio destro, sinistro, naso, bocca a destra, a sinistra: x, y alternati, 10
 * valori in pixel dell'immagine) e il punteggio del rilevatore (0..1). Nessun attributo della persona.
 */
class DetectedFace(val box: FloatBox, landmarks: FloatArray, val score: Float) {
  private val points: FloatArray = landmarks.copyOf()

  init {
    require(points.size == FaceEngineLimits.LANDMARK_VALUES && points.all { it.isFinite() }) { "Cinque punti finiti attesi." }
    require(score.isFinite() && score in 0f..1f) { "Punteggio fuori intervallo." }
  }

  val landmarks: FloatArray get() = points.copyOf()

  override fun toString(): String = "DetectedFace"
}

/** La passata del rilevatore (D17): l'anteprima intera, o l'HQ in tasselli da 1024 con 128 px di sovrapposizione e fusione dei doppi. */
enum class DetectPass { PREVIEW, HQ_TILES }

/**
 * Il volto ritagliato e allineato per l'embedder (§2.2): una similitudine dai cinque punti al modello a 112 px di ArcFace, pixel RGB
 * 112x112. Costruttore `internal`: lo fa solo l'allineamento di core-ai (P2) o un fake. **Non si salva mai** (D25, C36): vive il tempo
 * del calcolo del vettore.
 */
class AlignedFace internal constructor(pixels: IntArray) {
  private val data: IntArray = pixels.copyOf()

  init {
    require(data.size == FaceEngineLimits.ALIGNED_SIDE * FaceEngineLimits.ALIGNED_SIDE) { "Un volto allineato e' 112x112." }
  }

  val pixels: IntArray get() = data.copyOf()

  internal fun pixelAt(x: Int, y: Int): Int = data[y * FaceEngineLimits.ALIGNED_SIDE + x]

  /** P2: i pixel senza copia (112x112), per l'embedder e la qualita' di core-ai. */
  internal fun raw(): IntArray = data

  override fun toString(): String = "AlignedFace"
}

/**
 * Il rilevatore (D6, D7): YuNet a 1024x1024 con bordi (letterbox), uscite grezze decodificate e NMS dal nostro codice (soglia 0,6, NMS
 * 0,3), al piu' [FaceEngineLimits.MAX_FACES] volti in ordine di punteggio. [DetectPass.HQ_TILES] lavora sull'HQ in quattro tasselli.
 * **Una richiesta alla volta** (le chiamate si mettono in fila); [release] e' idempotente e dopo un [detect] riscalda da solo. Un guasto
 * del motore e' [AiEngineException] (`INIT_FAILED`, `OUT_OF_MEMORY`, `MODEL_UNAVAILABLE`, `BAD_INPUT`), mai un messaggio della libreria.
 */
interface FaceDetector {
  suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace>

  suspend fun release()
}

/**
 * L'embedder (D6, D7): SFace 2021dec, ingresso 112x112, un vettore a [FaceSpaceCode.dimension] componenti **normalizzato in float32** per
 * volto, nello stesso ordine. Un vettore con valori non finiti o con la norma lontana da 1 e' **null** (`FACES_VECTOR_INVALID`: il volto
 * resta senza vettore), mai un'eccezione per un volto solo; se il motore lancia su un volto, gli altri si calcolano uno alla volta (§2.2).
 * Un guasto di tutto il motore e' [AiEngineException]. [release] idempotente.
 */
interface FaceEmbedder {
  val space: FaceSpaceCode

  suspend fun embed(faces: List<AlignedFace>): List<FloatArray?>

  suspend fun release()
}

/**
 * L'allineamento (§2.2): similitudine ai minimi quadrati (Umeyama) dai cinque punti al modello a 112 px di ArcFace, dalla sorgente piu'
 * nitida gia' in mano (D17). Puro, sulla JVM (P2: `FaceAligner`). [landmarks] sono i cinque punti **in pixel di [image]**.
 */
fun interface FaceAlignment {
  fun align(image: FaceImage, landmarks: FloatArray): AlignedFace
}

/**
 * Il giudizio di qualita' di un volto (D19): livello, motivi (`FaceReasons`), imbardata stimata dai cinque punti (gradi con segno) e
 * nitidezza (0..255). [sidePx] e' il lato corto del riquadro **nella sorgente dell'allineamento**.
 */
data class QualityVerdict(val tier: FaceTier, val reasons: Int, val yaw: Int, val sharpness: Int) {
  init {
    require(yaw in -90..90 && sharpness in 0..255 && reasons >= 0) { "Qualita' fuori intervallo." }
  }
}

/** La qualita' (D19): solo dimensione, punteggio, imbardata e nitidezza; **nessun attributo della persona**. Pura (P2: `FaceQuality`). */
fun interface FaceQualityRule {
  fun assess(face: DetectedFace, sidePx: Int, aligned: AlignedFace?): QualityVerdict
}

/** I quattro pezzi dei volti insieme, come li lega :app (P13) o un fake: il calcolo (P4) li riceve da qui, mai dalle classi di P2. */
class FaceEngines(
  val detector: FaceDetector,
  val embedder: FaceEmbedder,
  val aligner: FaceAlignment,
  val quality: FaceQualityRule,
) {
  suspend fun release() {
    detector.release()
    embedder.release()
  }

  override fun toString(): String = "FaceEngines(${embedder.space.code})"
}

/** I numeri dei motori dei volti (§2.2, D7, D17, D19). P2 li usa, P4 e P11 li leggono; un cambio passa dal progetto. */
object FaceEngineLimits {
  /** L'ingresso di YuNet e il lato dell'anteprima di analisi. */
  const val DETECT_SIDE: Int = 1024

  /** Il lato dell'HQ per la seconda passata e il tetto di [FaceImage]. */
  const val HQ_SIDE: Int = 2048

  /** I tasselli della seconda passata e la loro sovrapposizione. */
  const val TILE_SIDE: Int = 1024
  const val TILE_OVERLAP: Int = 128

  /** La seconda passata serve con un volto sotto 40 px nell'anteprima o con almeno 5 volti (D17). */
  const val HQ_SMALL_FACE_PX: Int = 40
  const val HQ_MIN_FACES: Int = 5

  /** Il lato del volto allineato per SFace. */
  const val ALIGNED_SIDE: Int = 112

  /** Cinque punti, x e y. */
  const val LANDMARK_VALUES: Int = 10

  /** Volti per immagine (come per corpo `faces`). */
  const val MAX_FACES: Int = 64

  /** Il fotogramma piu' alto (copertina + 8). */
  const val MAX_FRAME: Int = 8

  /** Fotogrammi analizzati per video oltre la copertina (D18). */
  const val FRAMES_PER_VIDEO: Int = 3

  /** Soglie del rilevatore (§2.2). */
  const val DETECT_THRESHOLD: Float = 0.6f
  const val NMS_THRESHOLD: Float = 0.3f

  /** Livelli (D19): CORE da 48 px, WEAK da 24 px; punteggio e imbardata. */
  const val CORE_MIN_SIDE_PX: Int = 48
  const val WEAK_MIN_SIDE_PX: Int = 24
  const val CORE_MIN_SCORE: Float = 0.80f
  const val CORE_MAX_YAW: Int = 35
  const val WEAK_MAX_YAW: Int = 60

  /** I thread di LiteRT sulla CPU (D6). */
  const val CPU_THREADS: Int = 4

  /** La tolleranza della norma di un vettore (A6: 1 +- 0,01). */
  const val NORM_TOLERANCE: Float = 0.01f
}

/**
 * D17: il buffer di analisi (`DiskAiBuffer`) non butta un'anteprima che serve **ancora** ai volti. `ComputeSession.pruneFinished` chiede
 * a [stillWanted] quali delle chiavi che sta per togliere sono ancora chieste dal calcolo dei volti (P4 la lega li', :app la compone sul
 * calcolo dei volti di questo dispositivo, P13). Di serie [NONE]: nessuna (il comportamento della Fase 6).
 */
fun interface BufferKeepers {
  suspend fun stillWanted(keys: Collection<ContentKey>): Set<ContentKey>

  companion object {
    /** Nessuno tiene niente: il buffer si svuota come nella Fase 6. */
    val NONE: BufferKeepers = BufferKeepers { emptySet() }
  }
}
