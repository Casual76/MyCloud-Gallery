package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

// Le SESSIONI dei motori dei vettori (docs/fase6-progetto.md D2, D3, E-A12): una sessione e' un motore caricato (modello verificato,
// motore nativo, thread suo) su UN backend. I motori veri ([LiteRtLmTextEmbedder], [LiteRtLmImageEmbedder]) tengono la POLITICA
// (una richiesta alla volta, ripiego da GPU a CPU, controlli sui vettori, errori come codici) e parlano con una [VectorSession]:
// quella vera sta in LiteRtLmSessions.kt (solo sul dispositivo: le classi di LiteRT-LM sono Java 21), quelle finte nei test JVM.

/** Un motore caricato su un backend. Ogni chiamata gira sul thread del motore; una alla volta (la serializza chi la usa). */
internal interface VectorSession {
  val backend: AiBackend

  /** Il vettore (normalizzato dal motore) di un testo. Puo' lanciare qualunque eccezione della libreria: la mappa [EngineErrors]. */
  suspend fun embedText(text: String): FloatArray

  /** Il vettore (normalizzato dal motore) di un'immagine JPEG preparata da [ModelInputs]. */
  suspend fun embedImage(jpeg: ByteArray): FloatArray

  /** Chiude motore, descrittore e modello e ferma il thread. Idempotente, non lancia. */
  suspend fun close()
}

/** Apre sessioni. [open] lancia [AiEngineException] (`MODEL_UNAVAILABLE` se il modello manca o non passa la verifica) o l'eccezione della libreria. */
internal fun interface SessionFactory {
  suspend fun open(backend: AiBackend): VectorSession
}

/** Un thread solo per motore: la GPU preferisce restare sul thread che l'ha creata e il motore serializza comunque (spike di Fase 0). */
internal class EngineThread(name: String) {
  private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }
  val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

  fun shutdown() {
    executor.shutdown()
  }
}

/** I codici d'errore dei motori (D40: mai il messaggio di una libreria nei log). */
internal object EngineErrors {

  /**
   * Un'eccezione di qualunque libreria come [AiEngineException] con un codice stabile. La cancellazione non e' un guasto: passa
   * com'e' (chi chiama deve rilanciarla, vedi [rethrowCancellation]).
   */
  fun map(t: Throwable, whileInitializing: Boolean): AiEngineException = when (t) {
    is AiEngineException -> t
    is OutOfMemoryError -> AiEngineException(AiEngineError.OUT_OF_MEMORY, t)
    // L'AAR non carica (libreria nativa assente, ABI): il motore non partira' mai su questo dispositivo.
    is LinkageError -> AiEngineException(AiEngineError.INIT_FAILED, t)
    else -> AiEngineException(if (whileInitializing) AiEngineError.INIT_FAILED else AiEngineError.BAD_INPUT, t)
  }

  fun rethrowCancellation(t: Throwable) {
    if (t is CancellationException) throw t
  }
}
