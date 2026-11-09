package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.runtime.EngineErrors
import dev.pampa.mycloudgallery.core.ai.runtime.EngineThread
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.rules.run.YieldToApp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// L'EMBEDDER DEI VOLTI (docs/fase8-progetto.md D6, D7, D10, §2.2 "Nel telefono"): SFace 2021dec con i pesi in float16 (P1), su LiteRT, CPU a
// 4 thread, ingresso 112x112 RGB 0-255 (la media e la scala sono dentro il modello), uscita 128 valori **non normalizzati** (norma circa
// 11-12). Qui si normalizza in float32 (`FaceVectors`); chi salva li passa per `Float16.encode` (spazio `sface-2021dec-v1`).
//
// **Un volto alla volta, sempre.** Ogni volto e' una corsa del modello a se': se l'interprete lancia su uno, quel volto resta senza vettore
// (null) e gli altri si calcolano lo stesso (§2.2: "un volto alla volta se uno fa lanciare il motore"). Un vettore che non e' valido (non
// finito, norma assurda) e' null: `FACES_VECTOR_INVALID`, mai un'eccezione per un volto solo. Se invece il motore non e' piu' buono
// (memoria finita, libreria che non carica, [MAX_CONSECUTIVE_FAILURES] corse di fila che lanciano) e' una [AiEngineException] per tutta la
// richiesta e la sessione si chiude: la prossima richiesta la riapre. Una richiesta alla volta (le chiamate si mettono in fila), thread
// suo, rilascio idempotente, riapertura da sola. Mai un pixel, un vettore o un messaggio di libreria nei log (D37).
//
// Review generale, OB-3 (come `YuNetDetector`): dentro un lavoro di fondo che si fa da parte (`YieldToApp`, l'app in uso) la sessione si apre
// con meno thread e a priorita' di fondo, e si riapre una volta quando lo stato dell'app cambia a meta' tappa.

class SFaceEmbedder internal constructor(
  private val opener: EmbedderOpener,
) : FaceEmbedder {

  override val space: FaceSpaceCode = FaceSpaceCode.CURRENT

  private val gate = Mutex()
  private var thread: EngineThread? = null
  private var session: EmbedderSession? = null
  private var input: FloatArray? = null
  private var failuresInARow = 0

  /** La sessione aperta e' nata mentre il lavoro si faceva da parte (meno thread, priorita' di fondo). */
  private var sessionYielding = false

  /** Quante sessioni si sono aperte (le prove del rilascio e della riapertura). */
  @Volatile
  internal var opened: Int = 0
    private set

  /** Quante corse del modello, e quante hanno lanciato o dato un vettore non valido (le prove e la Diagnostica di P4). */
  @Volatile
  internal var inferences: Int = 0
    private set

  @Volatile
  internal var thrown: Int = 0
    private set

  @Volatile
  internal var invalid: Int = 0
    private set

  override suspend fun embed(faces: List<AlignedFace>): List<FloatArray?> {
    if (faces.isEmpty()) return emptyList()
    return gate.withLock {
      var opening = false
      try {
        // OB-3: lo stato dell'app e' cambiato da quando la sessione e' nata: si riapre (con i thread e la priorita' di adesso).
        val yielding = YieldToApp.yielding(currentCoroutineContext())
        if (session != null && sessionYielding != yielding) closeEngine()
        val engine = thread ?: EngineThread(THREAD_NAME).also { thread = it }
        withContext(engine.dispatcher) {
          val current = session ?: run {
            opening = true
            opener.open().also {
              session = it
              sessionYielding = yielding
              opened++
            }
          }
          opening = false
          embedAll(current, faces)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        val error = EngineErrors.map(t, whileInitializing = opening)
        closeEngine()
        throw error
      }
    }
  }

  private suspend fun embedAll(s: EmbedderSession, faces: List<AlignedFace>): List<FloatArray?> {
    val buffer = input ?: FloatArray(FaceVectors.INPUT_VALUES).also { input = it }
    val out = ArrayList<FloatArray?>(faces.size)
    for (face in faces) {
      currentCoroutineContext().ensureActive()
      out += oneFace(s, face, buffer)
    }
    return out
  }

  private fun oneFace(s: EmbedderSession, face: AlignedFace, buffer: FloatArray): FloatArray? {
    val raw = try {
      inferences++
      s.run(FaceVectors.input(face, buffer))
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      // La memoria finita e le librerie che non caricano sono del motore intero, non di un volto.
      if (t is OutOfMemoryError || t is LinkageError) throw t
      thrown++
      if (++failuresInARow >= MAX_CONSECUTIVE_FAILURES) throw AiEngineException(AiEngineError.BAD_INPUT, t)
      return null
    }
    failuresInARow = 0
    val vector = FaceVectors.normalizedOrNull(raw, space)
    if (vector == null) invalid++
    return vector
  }

  /** Idempotente; dopo il rilascio un [embed] riapre il modello da solo. */
  override suspend fun release() {
    gate.withLock { withContext(NonCancellable) { closeEngine() } }
  }

  private suspend fun closeEngine() {
    val s = session
    val t = thread
    session = null
    thread = null
    input = null
    failuresInARow = 0
    if (t != null && s != null) {
      runCatching { withContext(t.dispatcher) { s.close() } }
    } else {
      runCatching { s?.close() }
    }
    t?.shutdown()
  }

  companion object {
    /** Il modello del catalogo (verificato a ogni apertura, D16), su CPU. */
    fun create(files: ModelFiles, spec: ModelSpec = ModelCatalog.FACE_SFACE, threads: Int = FaceEngineLimits.CPU_THREADS): SFaceEmbedder =
      SFaceEmbedder(FaceModelOpeners.embedder(files, spec, threads))

    /** Tante corse di fila che lanciano, senza un vettore buono in mezzo: il motore non e' piu' buono. */
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    private const val THREAD_NAME = "mcg-face-embed"
  }
}
