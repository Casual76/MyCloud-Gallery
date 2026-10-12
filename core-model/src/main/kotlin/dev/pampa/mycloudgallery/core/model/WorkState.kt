package dev.pampa.mycloudgallery.core.model

/**
 * Le fasi di lavoro su un media (piano 1.3 "stati per fase di lavoro", 4.3, 6.2, 8.1). Ognuna ha il
 * suo stato perche' sono fatte da attivita' diverse, con regole e dispositivi diversi: la miniatura la
 * fa chi carica, l'embedding solo un indicizzatore, i volti solo chi li ha attivati.
 */
enum class WorkStage(override val code: String) : StableCode {
  /** Lettura dei metadati (data, GPS, dimensioni, durata). */
  META("meta"),

  /** Miniatura per la griglia. */
  THUMB("thumb"),

  /** Anteprima di analisi (~768 px) da cui lavorano embedding e OCR: mai gli originali (6.2). */
  ANALYSIS_COPY("analysis_copy"),

  /** Vettore di ricerca (EmbeddingGemma 2). */
  EMBED("embed"),

  /** Testo nelle foto. */
  OCR("ocr"),

  /** Volti: dato biometrico e personale, solo se l'utente l'ha attivato (proposta 13). */
  FACES("faces"),
  ;

  /** Se il risultato dipende dai pixel (e quindi va rifatto quando i pixel cambiano). */
  val dependsOnPixels: Boolean get() = this != META
}

/** Lo stato di una fase per un media. */
enum class WorkStatus(override val code: String) : StableCode {
  PENDING("pending"),
  IN_PROGRESS("in_progress"),
  DONE("done"),

  /** Fallita, si riprova (con [WorkState.attempts] che cresce). */
  FAILED("failed"),

  /** Fallita troppe volte (file corrotto, E-M10): segnalata, mai bloccante. */
  GAVE_UP("gave_up"),

  /** Questo dispositivo non sa farla (codec, APV, 8K): la fa un altro (E-M13, 4.3). */
  UNSUPPORTED("unsupported"),
}

private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")

/**
 * Il motivo di un fallimento, come **codice** (es. `DECODE_FAILED`, `NAS_UNREACHABLE`) e mai come
 * testo libero: un messaggio d'eccezione puo' contenere percorsi o nomi di file, che non devono finire
 * nella Diagnostica esportata (piano 2.7, "senza dati personali").
 */
@JvmInline
value class WorkErrorCode(val value: String) {
  init {
    require(ERROR_CODE.matches(value)) { "Un codice d'errore e' MAIUSCOLO_CON_TRATTINI_BASSI." }
  }

  override fun toString(): String = value
}

/**
 * Lo stato di una fase per un media. [versionId] e' la versione su cui il lavoro e' stato fatto o va
 * fatto: quando la versione corrente cambia, le fasi che dipendono dai pixel tornano [WorkStatus.PENDING]
 * (`domain.WorkRules`). [updatedAtMillis] e' l'ora dell'ultimo cambio di stato.
 */
data class WorkState(
  val stage: WorkStage,
  val status: WorkStatus,
  val versionId: VersionId?,
  val attempts: Int = 0,
  val lastError: WorkErrorCode? = null,
  val updatedAtMillis: Long,
) {
  init {
    require(attempts >= 0) { "Tentativi negativi." }
  }
}
