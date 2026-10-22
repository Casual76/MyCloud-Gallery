package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextVector
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il motore del testo delle query (docs/fase6-progetto.md D3, D5, D35, E-Q8, E-Q9): EmbeddingGemma 2 **270M su CPU**, con LiteRT-LM,
 * su ogni dispositivo che cerca, anche sugli indicizzatori (stessi vettori di query ovunque).
 *
 * - **Una richiesta alla volta**: le chiamate si mettono in fila (mutex); il motore ha un thread suo.
 * - **Si carica quando serve** (`warmUp` o il primo `embed`) e si rilascia con `release`; dopo un rilascio un `embed` lo riscalda da
 *   solo. Quando rilasciare (3 minuti dopo l'uscita da Cerca, memoria bassa: D35) lo decide chi lo usa (P8, P11).
 * - **Il modello viene da `ModelFiles.open`**: dimensione e SHA-256 ricalcolati a ogni apertura, sullo stesso descrittore che si
 *   passa al motore (D16). Senza modello (assente, rifiutato) `warmUp` risponde `Failed(MODEL_UNAVAILABLE)` e `embed` lancia lo stesso.
 * - **Testo**: troncato a `AiRecipe.maxQueryChars` caratteri senza spezzare una coppia di surrogati, controlli ISO sostituiti da uno
 *   spazio, nessun prefisso (D5); un testo vuoto o fatto di soli spazi e' `BAD_INPUT` (il motore non lo vede). Il testo non va mai nei
 *   log (D40).
 * - **Il vettore** deve avere 768 componenti finite e norma 1 +- 0,01: altrimenti `NON_FINITE` (mai un vettore sporco ai chiamanti).
 * - Ogni guasto e' un [AiEngineException] con un codice (`INIT_FAILED`, `OUT_OF_MEMORY`, `NON_FINITE`, `BAD_INPUT`,
 *   `MODEL_UNAVAILABLE`); la cancellazione del chiamante passa com'e'.
 *
 * @param diag riceve i codici della Diagnostica (`AI_ENGINE_INIT_FAILED`): solo il codice, mai testo (D40).
 */
class LiteRtLmTextEmbedder internal constructor(
  private val sessions: SessionFactory,
  private val recipe: AiRecipe,
  private val nanos: () -> Long,
  private val diag: (DiagCode) -> Unit,
) : TextEmbedder {

  /**
   * @param files i modelli verificati di core-models (`ModelFiles.open` a ogni caricamento).
   * @param cacheRoot `noBackupFilesDir/models/cache`: i programmi compilati di questo modello (`<sha256>-cpu/`), tolti da core-models
   *   quando il modello cambia.
   */
  constructor(
    files: ModelFiles,
    cacheRoot: File,
    recipe: AiRecipe = AiRecipe.CURRENT,
    diag: (DiagCode) -> Unit = {},
    access: ModelAccess = ModelAccess.PATH_CHECKED,
  ) : this(
    sessions = LiteRtLmSessions(files, ModelCatalog.QUERY_270M, cacheRoot, recipe, withVision = false, access = access),
    recipe = recipe,
    nanos = System::nanoTime,
    diag = diag,
  )

  private val mutex = Mutex()
  private var session: VectorSession? = null

  override suspend fun warmUp(): WarmUpResult = mutex.withLock {
    try {
      WarmUpResult.Ready(ensureLocked())
    } catch (e: AiEngineException) {
      WarmUpResult.Failed(e.error)
    }
  }

  override suspend fun embed(text: String): TextVector = mutex.withLock {
    val query = QueryText.prepare(text, recipe.maxQueryChars) ?: throw AiEngineException(AiEngineError.BAD_INPUT)
    ensureLocked()
    val active = session ?: throw AiEngineException(AiEngineError.INIT_FAILED)
    val raw = try {
      active.embedText(query)
    } catch (t: Throwable) {
      EngineErrors.rethrowCancellation(t)
      throw EngineErrors.map(t, whileInitializing = false)
    }
    val space = SpaceCode.CURRENT.space
    val vector = VectorChecks.normalizedOrNull(raw, space.dimension) ?: throw AiEngineException(AiEngineError.NON_FINITE)
    TextVector(space, vector)
  }

  override suspend fun release() = mutex.withLock { closeLocked() }

  /** Carica il motore se non c'e': i millisecondi dell'avvio (0 se era gia' caldo). Sotto il mutex. */
  private suspend fun ensureLocked(): Long {
    if (session != null) return 0
    val start = nanos()
    try {
      session = sessions.open(AiBackend.CPU)
    } catch (t: Throwable) {
      EngineErrors.rethrowCancellation(t)
      val failure = EngineErrors.map(t, whileInitializing = true)
      // Senza modello non e' un guasto del motore: lo dice la pagina Ricerca e modelli (E-Q11), non la Diagnostica.
      if (failure.error != AiEngineError.MODEL_UNAVAILABLE) diag(AiDiag.AI_ENGINE_INIT_FAILED)
      throw failure
    }
    return (nanos() - start) / 1_000_000
  }

  private suspend fun closeLocked() {
    val old = session ?: return
    session = null
    old.close()
  }
}

/** Il testo di una query come lo vede il motore (D5, E-Q9). */
internal object QueryText {

  /** Il testo da dare al motore, o null se non c'e' niente da cercare. */
  fun prepare(text: String, maxChars: Int): String? {
    if (text.isEmpty()) return null
    var end = minOf(text.length, maxChars)
    // Non si spezza una coppia di surrogati: un surrogato alto solo in fondo si butta.
    if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) end--
    val cut = text.substring(0, end)
    val clean = buildString(cut.length) {
      for (c in cut) append(if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') ' ' else c)
    }.trim()
    return clean.takeIf { it.isNotEmpty() }
  }
}
