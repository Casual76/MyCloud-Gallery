package dev.pampa.mycloudgallery.core.model

// Fase 6 (`docs/fase6-progetto.md` §1.3, §2.3, D4, D6, D13): i codici stabili dei dati AI. Firme di P0; le tabelle `ai_vector` e
// `ai_text` di P1 li salvano con [StableCode.code], il formato `ai` di P2 con [wire] (un byte nei corpi `vec` e `ocr`). Entrambi
// sono contratti: si **aggiungono**, non si cambiano e non si riusano (`StableCodesTest`, `AiCodesTest`). Un codice sconosciuto
// letto da un'altra app e' "non per me": il corpo si rifiuta (E-I20), il database non lo scrive mai.

/**
 * Lo **spazio** dei vettori (D4, E-I4) come codice del formato: modello + dimensione + precisione di salvataggio. Due vettori si
 * confrontano solo se hanno lo stesso spazio; una **ricetta** nuova (come si preparano gli ingressi) resta nello stesso spazio.
 *
 * I vettori si calcolano in float32, si normalizzano e si salvano e pubblicano in **float16** (D4): la precisione dello spazio e'
 * quella salvata.
 */
enum class SpaceCode(val wire: Int, override val code: String, val space: EmbeddingSpace) : StableCode {
  /** EmbeddingGemma 2 (270M per le query, 440M per indicizzare: stesso spazio, D3), 768 dimensioni, float16. */
  EMBEDDINGGEMMA2_FLOAT_V1(1, "embeddinggemma2_float_v1", EmbeddingSpace(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1, 768, VectorPrecision.FLOAT16)),
  ;

  companion object {
    /** Lo spazio corrente: quello che il motore delle query e gli indicizzatori producono oggi. */
    val CURRENT: SpaceCode = EMBEDDINGGEMMA2_FLOAT_V1

    fun fromWire(wire: Int): SpaceCode? = entries.firstOrNull { it.wire == wire }

    fun of(space: EmbeddingSpace): SpaceCode? = entries.firstOrNull { it.space == space }
  }
}

/** Da dove viene un vettore (corpo `vec`, byte 6). */
enum class VecSource(val wire: Int, override val code: String) : StableCode {
  /** L'anteprima di analisi (`analysis`, 1024 px): un vettore. */
  ANALYSIS(1, "analysis"),

  /** I fotogrammi di un video (`frames`): un vettore per fotogramma, al massimo 9 (D11). */
  FRAMES(2, "frames"),

  /** I byte originali di un media del telefono di questo dispositivo (preparazione locale, D10): un vettore. */
  PHONE_ORIGINAL(3, "phone_original"),
  ;

  /** Le sorgenti con un vettore solo (le immagini); [FRAMES] ne ha da 1 a 9 con l'istante di ognuno. */
  val singleVector: Boolean get() = this != FRAMES

  companion object {
    fun fromWire(wire: Int): VecSource? = entries.firstOrNull { it.wire == wire }
  }
}

/** Da dove viene il testo letto (corpo `ocr`, byte 3). */
enum class OcrSource(val wire: Int, override val code: String) : StableCode {
  /** L'anteprima di analisi (1024 px): la prima passata. */
  ANALYSIS(1, "analysis"),

  /** L'anteprima HQ (2048 px): la seconda passata per il testo fitto e gli screenshot (D12). */
  HQ(2, "hq"),

  /** Copertina e fotogramma centrale di un video, righe senza doppioni (D11). */
  FRAMES(3, "frames"),

  /** I byte originali di un media del telefono di questo dispositivo. */
  PHONE_ORIGINAL(4, "phone_original"),
  ;

  companion object {
    fun fromWire(wire: Int): OcrSource? = entries.firstOrNull { it.wire == wire }
  }
}

/** Il motore che ha letto il testo (corpo `ocr`, byte 1). */
enum class OcrEngineCode(val wire: Int, override val code: String) : StableCode {
  /** ML Kit Text Recognition v2, script latino, versione integrata (D12). */
  MLKIT_V2_LATIN(1, "mlkit_v2_latin"),
  ;

  companion object {
    fun fromWire(wire: Int): OcrEngineCode? = entries.firstOrNull { it.wire == wire }
  }
}
