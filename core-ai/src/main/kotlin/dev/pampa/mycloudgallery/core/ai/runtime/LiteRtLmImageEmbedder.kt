package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il motore delle immagini (docs/fase6-progetto.md D2, D3, D11, E-A12): EmbeddingGemma 2 **440M, testo e visione, su GPU** con
 * LiteRT-LM, sugli indicizzatori; 70 token visivi per immagine, ingresso JPEG q95 a 768 px preparato da [ModelInputs] (D5).
 *
 * - **Ripiego su CPU per la sessione** (E-A12): se la GPU non si inizializza, se un'inferenza sulla GPU lancia o se il vettore che
 *   esce non e' valido (valori non finiti, norma fuori da 1 +- 0,01), si chiude il motore, si riapre su CPU e si **ripete la stessa
 *   immagine** (una norma sbagliata di pochi centesimi, come puo' darla un'attivazione float16, non e' un guasto: il vettore si
 *   rinormalizza, [VectorChecks.normalizedOrNull]); da li' in poi, fino alla fine del processo, resta su CPU (riprovare la GPU a
 *   ogni caricamento costerebbe un guasto ogni volta). [backend] dice dove gira; la Diagnostica riceve `AI_GPU_FALLBACK` una volta per ripiego. Sulla CPU un vettore
 *   non valido e' `NON_FINITE`: un'immagine che non passa non si salva ne' si pubblica.
 * - **Una richiesta alla volta** (mutex), motore con un thread suo; si carica al primo `embed`; `release` lo chiude (idempotente) e
 *   un `embed` dopo lo riapre.
 * - **Un vettore per immagine**, nello stesso ordine, float32 normalizzati: il chiamante li passa per float16 quando li salva.
 * - Il modello viene da `ModelFiles.open` (dimensione e SHA-256 a ogni caricamento, D16); senza, `MODEL_UNAVAILABLE`.
 *
 * @param diag riceve solo codici della Diagnostica (D40).
 */
class LiteRtLmImageEmbedder internal constructor(
  private val sessions: SessionFactory,
  private val diag: (DiagCode) -> Unit,
) : ImageEmbedder {

  /**
   * @param files i modelli verificati di core-models.
   * @param cacheRoot `noBackupFilesDir/models/cache`: i programmi compilati (`<sha256>-gpu/` e `<sha256>-cpu/`).
   */
  constructor(
    files: ModelFiles,
    cacheRoot: File,
    recipe: AiRecipe = AiRecipe.CURRENT,
    diag: (DiagCode) -> Unit = {},
  ) : this(
    sessions = LiteRtLmSessions(files, ModelCatalog.INDEX_440M, cacheRoot, recipe, withVision = true),
    diag = diag,
  )

  private val mutex = Mutex()
  private var session: VectorSession? = null

  /** Falso dopo il primo ripiego: la GPU non si riprova piu' in questo processo. */
  private var gpuAllowed = true

  private val mutableBackend = MutableStateFlow(AiBackend.GPU)
  override val backend: StateFlow<AiBackend> = mutableBackend

  override suspend fun embed(images: List<ModelImage>): List<FloatArray> = mutex.withLock {
    val out = ArrayList<FloatArray>(images.size)
    for (image in images) out += embedOne(image.jpeg)
    out
  }

  override suspend fun release() = mutex.withLock {
    val old = session ?: return@withLock
    session = null
    old.close()
  }

  private suspend fun embedOne(jpeg: ByteArray): FloatArray {
    val dimension = SpaceCode.CURRENT.space.dimension
    while (true) {
      val active = ensureLocked()
      val onGpu = active.backend == AiBackend.GPU
      val raw = try {
        active.embedImage(jpeg)
      } catch (t: Throwable) {
        EngineErrors.rethrowCancellation(t)
        if (onGpu) {
          fallBackToCpu()
          continue
        }
        throw EngineErrors.map(t, whileInitializing = false)
      }
      VectorChecks.normalizedOrNull(raw, dimension)?.let { return it }
      if (onGpu) {
        fallBackToCpu()
        continue
      }
      throw AiEngineException(AiEngineError.NON_FINITE)
    }
  }

  /** Il motore pronto, GPU se ancora ammessa, altrimenti CPU. Un'apertura GPU che fallisce passa a CPU (E-A12). */
  private suspend fun ensureLocked(): VectorSession {
    session?.let { return it }
    if (gpuAllowed) {
      try {
        return adopt(sessions.open(AiBackend.GPU))
      } catch (t: Throwable) {
        EngineErrors.rethrowCancellation(t)
        // Senza il modello e' inutile riprovare su CPU: e' lo stesso file.
        if (t is AiEngineException && t.error == AiEngineError.MODEL_UNAVAILABLE) throw t
        gpuAllowed = false
        mutableBackend.value = AiBackend.CPU
        diag(AiDiag.AI_GPU_FALLBACK)
      }
    }
    try {
      return adopt(sessions.open(AiBackend.CPU))
    } catch (t: Throwable) {
      EngineErrors.rethrowCancellation(t)
      val failure = EngineErrors.map(t, whileInitializing = true)
      if (failure.error != AiEngineError.MODEL_UNAVAILABLE) diag(AiDiag.AI_ENGINE_INIT_FAILED)
      throw failure
    }
  }

  private fun adopt(opened: VectorSession): VectorSession {
    session = opened
    mutableBackend.value = opened.backend
    return opened
  }

  /** Chiude il motore GPU e segna la CPU per il resto del processo; il chiamante ripete l'immagine. */
  private suspend fun fallBackToCpu() {
    val old = session
    session = null
    gpuAllowed = false
    mutableBackend.value = AiBackend.CPU
    diag(AiDiag.AI_GPU_FALLBACK)
    old?.close()
  }
}
