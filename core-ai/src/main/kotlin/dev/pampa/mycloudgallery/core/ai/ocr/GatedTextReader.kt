package dev.pampa.mycloudgallery.core.ai.ocr

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode

/**
 * Il **lettore dietro il cancello** (docs/fase6-progetto.md D41, §4.3 "Lettura del testo"): il `TextReader` che il resto dell'app
 * riceve. Da spenti (lettura spenta per l'account, o questo dispositivo escluso) [read] lancia `TEXT_READING_OFF` e **non crea il
 * motore**: [engine] (di solito `{ MlKitTextReader(MlKitBootstrap(context, state)) }`) si chiama solo la prima volta che la lettura e'
 * attiva, cosi' nessuna classe di ML Kit si carica prima. Dopo lo spegnimento ([release]) il motore e' chiuso e la prossima lettura
 * attiva ne costruisce uno **nuovo**: un motore rilasciato non si riapre mai (una lettura che lo teneva in mano lancia `TEXT_READING_OFF`).
 *
 * @param state lo stato della lettura visto da questo dispositivo, letto a ogni [read].
 * @param engine il costruttore del motore vero; chiamato al piu' una volta per "accensione", mai da spenti.
 */
class GatedTextReader(
  private val state: () -> TextReadingState,
  private val engine: () -> ReleasableTextReader,
) : TextReader {

  private val lock = Any()
  private var created: ReleasableTextReader? = null

  override suspend fun read(image: OcrImage): OcrResult {
    if (!state().activeHere) throw AiEngineException(AiEngineError.TEXT_READING_OFF)
    return engineLocked().read(image)
  }

  /** Vero se il motore e' stato creato (e non rilasciato): da spenti resta falso. */
  val isEngineCreated: Boolean get() = synchronized(lock) { created != null }

  /** Chiude il motore se c'e' (spegnimento, E-A18): non lo crea se non c'e'. */
  fun release() {
    val old = synchronized(lock) { created.also { created = null } } ?: return
    old.release()
  }

  /**
   * Il motore da usare, **ricontrollando il cancello sotto il lucchetto del rilascio** (R7 della revisione di sicurezza della Fase 6): una
   * lettura che ha passato il primo controllo un istante prima dello spegnimento, e arriva qui dopo [release], non trova piu' un motore da
   * ricreare e non ne costruisce uno nuovo. Lo spegnimento cambia lo stato (in modo sincrono) prima di rilasciare, quindi qui lo vede.
   */
  private fun engineLocked(): ReleasableTextReader = synchronized(lock) {
    if (!state().activeHere) throw AiEngineException(AiEngineError.TEXT_READING_OFF)
    created ?: engine().also { created = it }
  }

  companion object {
    /**
     * Il lettore dell'app: ML Kit dietro il cancello. **Il modo di costruirlo che P11 usa**: il motore vero e il suo avviatore
     * ([MlKitTextReader], [MlKitBootstrap]) si costruiscono solo dentro la lambda, che da spenti non viene mai chiamata. La prova
     * strumentata (`TextReadingOffDeviceTest`) dimostra che cosi' nessuna classe di ML Kit si carica da spenti, mentre costruire
     * `MlKitTextReader` a freddo ne carica 13 (le API della libreria, con la verifica del bytecode): non farlo mai fuori da qui.
     *
     * @param state lo stato della lettura visto da questo dispositivo (`TextReadingSwitch.state.value`), letto a ogni lettura.
     */
    fun create(context: Context, state: () -> TextReadingState, diag: (DiagCode) -> Unit = {}): GatedTextReader =
      GatedTextReader(state) { MlKitTextReader(MlKitBootstrap(context, state), diag) }
  }
}
