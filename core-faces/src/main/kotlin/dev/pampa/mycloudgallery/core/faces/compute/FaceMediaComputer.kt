package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

/** Com'e' finito il calcolo di un media (mai un'eccezione per un caso previsto). */
internal sealed interface MediaOutcome {
  /** Il corpo `faces` da salvare ([hqUsed]: la seconda passata e' stata fatta; [frames]: fotogrammi analizzati di un video). */
  class Done(val body: FaceBody, val hqUsed: Boolean, val frames: Int) : MediaOutcome {
    override fun toString(): String = "MediaOutcome.Done($body)"
  }

  /** Formato non decodificabile o ingresso rifiutato dal motore: "non supportato", non si riprova finche' la ricetta non cambia. */
  data object Refused : MediaOutcome

  /** Nessuna anteprima adesso (D17): il media aspetta. */
  data class Waiting(val reason: InputWait) : MediaOutcome

  /** La sorgente e' cambiata (dimensione o `qh1` diversi dal catalogo): il media si salta. */
  data object Changed : MediaOutcome

  /** Un errore passeggero del motore (memoria, guasto della libreria): il media si riprova alla tappa dopo (IX-7). */
  data object Failed : MediaOutcome

  /** La tappa non puo' continuare: i modelli non tornano o il motore non parte. */
  data class Halt(val stop: FacesStop) : MediaOutcome
}

/**
 * Il lavoro su UN media (§2.3, righe "Ingressi", "Prima passata", "Seconda passata", "Qualita' e vettori", "Video"): l'anteprima di analisi →
 * YuNet → (solo se serve) l'HQ in tasselli → qualita' e allineamento dalla sorgente piu' nitida → SFace → il corpo `faces`.
 *
 * - **Seconda passata** (D17) solo con un volto sotto [FaceEngineLimits.HQ_SMALL_FACE_PX] nell'anteprima o con almeno
 *   [FaceEngineLimits.HQ_MIN_FACES] volti; l'HQ non si chiede se non serve. Senza HQ si tengono i volti della prima passata (i piccoli
 *   escono WEAK o NONE dalla qualita', che li misura sulla sorgente dell'allineamento).
 * - **Video** (D18): copertina e fino a [FacesComputeLimits.FRAMES_PER_VIDEO] fotogrammi; i loro volti sono **al piu' WEAK** (e portano il
 *   motivo `FRAME`): non fanno nascere persone da soli (E-F17).
 * - **Vettori**: uno per volto CORE o WEAK, validato qui (128 componenti finite, norma 1 +- 0,01); un vettore non valido lascia il volto
 *   senza vettore (NONE, `FACES_VECTOR_INVALID`), mai un'eccezione per un volto solo.
 * - **Nessuna immagine esce di qui**: i volti allineati vivono il tempo del calcolo del vettore (D25, C36); il corpo ha solo numeri.
 */
internal class FaceMediaComputer(
  private val engines: FaceEngines,
  private val inputs: FaceInputs,
  private val deviceId: String,
  private val detectorCode: FaceDetectorCode,
  private val digest: FaceBodyDigest,
  private val recipe: Int,
  private val counters: FacesComputeCounters,
) {

  suspend fun compute(item: FacesItem): MediaOutcome = try {
    if (item.kind == MediaKind.VIDEO) video(item) else photo(item)
  } catch (e: CancellationException) {
    throw e
  } catch (e: AiEngineException) {
    engineFailure(e)
  } catch (e: IOException) {
    MediaOutcome.Waiting(InputWait.NAS)
  } catch (e: Exception) {
    // Un guasto che il contratto non prevede (non un caso previsto): vale come un errore passeggero del motore, mai la tappa intera.
    counters.engineFailures.incrementAndGet()
    MediaOutcome.Failed
  }

  private fun engineFailure(e: AiEngineException): MediaOutcome = when (e.error) {
    AiEngineError.INIT_FAILED -> MediaOutcome.Halt(FacesStop.ENGINE_FAILED)
    AiEngineError.MODEL_UNAVAILABLE -> MediaOutcome.Halt(FacesStop.MODELS_UNAVAILABLE)
    AiEngineError.BAD_INPUT -> MediaOutcome.Refused
    else -> {
      counters.engineFailures.incrementAndGet()
      MediaOutcome.Failed
    }
  }

  // ---- Foto, RAW, panorami, foto in movimento ------------------------------------------------------------------------------------

  private suspend fun photo(item: FacesItem): MediaOutcome {
    val first = inputs.preview(item)
    if (first !is FaceInput.Ready) return notReady(first)
    counters.detected.incrementAndGet()
    var faces = engines.detector.detect(first.image, DetectPass.PREVIEW)
    var used: FaceInput.Ready = first
    var hqUsed = false
    if (needsSecondPass(faces)) {
      when (val hq = inputs.hq(item)) {
        is FaceInput.Ready -> {
          counters.hqPasses.incrementAndGet()
          faces = engines.detector.detect(hq.image, DetectPass.HQ_TILES)
          used = hq
          hqUsed = true
        }
        FaceInput.Changed -> return MediaOutcome.Changed
        is FaceInput.Waiting, FaceInput.Unsupported -> counters.hqMissing.incrementAndGet()
      }
    }
    if (faces.isEmpty()) return MediaOutcome.Done(body(item.contentKey, used.source, emptyList()), hqUsed, 0)
    val align = if (hqUsed) used else sharpest(item, used)
    val found = faces.map { Located(it, used.image, align.image, used.image.frame) }
    return MediaOutcome.Done(body(item.contentKey, used.source, describe(found, fromFrames = false)), hqUsed, 0)
  }

  /** D17: un volto sotto 40 px nell'anteprima o almeno 5 volti. */
  private fun needsSecondPass(faces: List<DetectedFace>): Boolean =
    faces.size >= FaceEngineLimits.HQ_MIN_FACES || faces.any { it.box.shortSide < FaceEngineLimits.HQ_SMALL_FACE_PX }

  /** La sorgente piu' nitida gia' in mano per l'allineamento (D17); se non c'e' o non e' la stessa immagine, la prima passata. */
  private suspend fun sharpest(item: FacesItem, fallback: FaceInput.Ready): FaceInput.Ready {
    val better = inputs.sharpest(item) as? FaceInput.Ready ?: return fallback
    return if (sameShape(better.image, fallback.image)) better else fallback
  }

  /** Due immagini della stessa foto hanno la stessa proporzione (a meno dell'arrotondamento): altrimenti i punti non si traducono. */
  private fun sameShape(a: FaceImage, b: FaceImage): Boolean {
    val ra = a.width.toFloat() / a.height
    val rb = b.width.toFloat() / b.height
    return abs(ra - rb) / rb <= SHAPE_TOLERANCE
  }

  // ---- Video -------------------------------------------------------------------------------------------------------------------

  private suspend fun video(item: FacesItem): MediaOutcome {
    val list = inputs.frames(item).take(1 + FacesComputeLimits.FRAMES_PER_VIDEO)
    val ready = list.filterIsInstance<FaceInput.Ready>()
    if (ready.isEmpty()) return notReadyAny(list)
    counters.detected.incrementAndGet()
    val found = ArrayList<Located>()
    for (frame in ready) {
      counters.videoFrames.incrementAndGet()
      engines.detector.detect(frame.image, DetectPass.PREVIEW).mapTo(found) { Located(it, frame.image, frame.image, frame.image.frame) }
    }
    return MediaOutcome.Done(body(item.contentKey, FaceSource.FRAMES, describe(found, fromFrames = true)), false, ready.size)
  }

  private fun notReady(input: FaceInput): MediaOutcome = when (input) {
    is FaceInput.Ready -> error("Un ingresso pronto non e' un'attesa.")
    is FaceInput.Waiting -> MediaOutcome.Waiting(input.reason)
    FaceInput.Unsupported -> MediaOutcome.Refused
    FaceInput.Changed -> MediaOutcome.Changed
  }

  /** Nessun fotogramma pronto: aspetta se uno aspetta, si salta se la sorgente e' cambiata, altrimenti non e' supportato. */
  private fun notReadyAny(list: List<FaceInput>): MediaOutcome = when {
    list.isEmpty() -> MediaOutcome.Waiting(InputWait.NO_PREVIEW)
    list.any { it is FaceInput.Waiting } -> MediaOutcome.Waiting(list.filterIsInstance<FaceInput.Waiting>().first().reason)
    list.any { it === FaceInput.Changed } -> MediaOutcome.Changed
    else -> MediaOutcome.Refused
  }

  // ---- Qualita', allineamento, vettori ----------------------------------------------------------------------------------------

  /** Un volto trovato, con l'immagine su cui e' stato trovato e quella su cui si allinea (stessa foto, risoluzione che puo' essere diversa). */
  private class Located(val face: DetectedFace, val detection: FaceImage, val align: FaceImage, val frame: Int)

  private class Assessed(
    val located: Located,
    val sidePx: Int,
    val aligned: AlignedFace?,
    var tier: FaceTier,
    val reasons: Int,
    val yaw: Int,
    val sharpness: Int,
  ) {
    var vector: ShortArray? = null
  }

  private suspend fun describe(found: List<Located>, fromFrames: Boolean): List<FaceEntry> {
    // Un corpo ha al piu' 64 volti (§2.4): i meglio rilevati.
    val kept = if (found.size > FaceRef.MAX_FACES) found.sortedByDescending { it.face.score }.take(FaceRef.MAX_FACES) else found
    val assessed = kept.map { assess(it, fromFrames) }
    val wanted = assessed.filter { it.tier != FaceTier.NONE }
    if (wanted.isNotEmpty()) {
      val vectors = engines.embedder.embed(wanted.map { it.aligned!! })
      check(vectors.size == wanted.size) { "Un vettore per volto." }
      wanted.forEachIndexed { i, face ->
        val valid = validVector(vectors[i])
        if (valid == null) {
          counters.vectorsInvalid.incrementAndGet()
          face.tier = FaceTier.NONE
        } else {
          face.vector = Float16.encode(valid)
        }
      }
    }
    val ordered = assessed.sortedWith(
      compareBy<Assessed>({ it.located.frame }, { -FaceGeometry.score(it.located.face.score) }, { it.located.face.box.x0 }, { it.located.face.box.y0 }),
    )
    return ordered.mapIndexed { index, face -> entryOf(index, face) }
  }

  private suspend fun assess(found: Located, fromFrames: Boolean): Assessed {
    val sx = found.align.width.toFloat() / found.detection.width
    val sy = found.align.height.toFloat() / found.detection.height
    val box = found.face.box
    val side = min((box.x1 - box.x0) * sx, (box.y1 - box.y0) * sy)
    val sidePx = side.roundToInt().coerceIn(1, 65_535)
    val aligned = if (sidePx >= FaceEngineLimits.WEAK_MIN_SIDE_PX) alignOrNull(found, sx, sy) else null
    val verdict = engines.quality.assess(found.face, sidePx, aligned)
    // Senza il volto allineato non c'e' un vettore: nessun livello oltre NONE (D19: sotto 24 px niente vettore).
    var tier = if (aligned == null) FaceTier.NONE else verdict.tier
    var reasons = verdict.reasons
    if (fromFrames) {
      // D18, E-F17: i fotogrammi di un video non fanno nascere persone: al piu' WEAK, con il motivo.
      reasons = reasons or FaceReasons.FRAME
      if (tier == FaceTier.CORE) tier = FaceTier.WEAK
    }
    return Assessed(found, sidePx, aligned, tier, reasons, verdict.yaw, verdict.sharpness)
  }

  private fun alignOrNull(found: Located, sx: Float, sy: Float): AlignedFace? {
    val points = found.face.landmarks
    val scaled = FloatArray(points.size) { i -> if (i % 2 == 0) points[i] * sx else points[i] * sy }
    return try {
      engines.aligner.align(found.align, scaled)
    } catch (e: IllegalArgumentException) {
      // Cinque punti degeneri: il volto resta senza vettore (NONE), gli altri vanno avanti.
      null
    }
  }

  private fun entryOf(index: Int, face: Assessed): FaceEntry {
    val det = face.located.detection
    val found = face.located.face
    return FaceEntry(
      index = index,
      frame = face.located.frame,
      box = FaceGeometry.box(found.box, det.width, det.height),
      landmarks = FaceGeometry.landmarks(found.landmarks, det.width, det.height),
      score = FaceGeometry.score(found.score),
      tier = face.tier,
      reasons = face.reasons,
      sidePx = face.sidePx,
      yaw = face.yaw,
      sharpness = face.sharpness,
      vector = if (face.tier.hasVector) face.vector else null,
    )
  }

  /** 128 componenti finite e norma 1 +- 0,01 (A6); altrimenti null. */
  private fun validVector(vector: FloatArray?): FloatArray? {
    if (vector == null || vector.size != engines.embedder.space.dimension || vector.any { !it.isFinite() }) return null
    var sum = 0.0
    for (x in vector) sum += x.toDouble() * x
    return if (abs(sqrt(sum) - 1.0) <= FaceEngineLimits.NORM_TOLERANCE) vector else null
  }

  private fun body(key: ContentKey, source: FaceSource, faces: List<FaceEntry>): FaceBody {
    val space = engines.embedder.space
    val draft = FaceBodyDraft(key, space, detectorCode, recipe, source, faces)
    return FaceBody(key, space, detectorCode, recipe, source, deviceId, faces, digest.digestOf(draft))
  }

  private companion object {
    /** Due proporzioni uguali a meno di un arrotondamento del lato lungo. */
    const val SHAPE_TOLERANCE = 0.03f
  }
}
