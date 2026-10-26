package dev.pampa.mycloudgallery.core.model

// Fase 8 (`docs/fase8-progetto.md` §1.2, §2.4, D7, D12, D18, D19, D23, D26, D27): i codici dei corpi dei volti e i riferimenti a un
// volto. I [wire] sono byte del corpo `faces` (§2.4) scritto da P3; i [StableCode.code] li salva il database dei volti (P6). Si
// aggiungono, non si cambiano (`StableCodesTest`, `Phase8TypesTest`). Un codice sconosciuto letto da un'altra app non si interpreta.

/**
 * Lo **spazio** dei vettori dei volti (D7): modello + dimensione + precisione salvata. Due vettori si confrontano solo nello stesso
 * spazio (E-F24, come E-I4): un modello o uno spazio nuovo ricalcola da capo. I vettori si calcolano in float32, si normalizzano e si
 * salvano e pubblicano in **float16** (256 byte a volto).
 */
enum class FaceSpaceCode(val wire: Int, override val code: String, val space: EmbeddingSpace) : StableCode {
  /** SFace 2021dec (OpenCV Zoo, Apache-2.0), convertito per LiteRT con pesi float16 (P1): 128 dimensioni, float16 salvato. */
  SFACE_2021DEC_V1(1, "sface_2021dec_v1", EmbeddingSpace(SFACE_SPACE_ID, 128, VectorPrecision.FLOAT16)),
  ;

  val dimension: Int get() = space.dimension

  companion object {
    /** Lo spazio che i motori producono oggi. */
    val CURRENT: FaceSpaceCode = SFACE_2021DEC_V1

    fun fromWire(wire: Int): FaceSpaceCode? = entries.firstOrNull { it.wire == wire }
  }
}

/** L'etichetta dello spazio di SFace (`EmbeddingSpace.id`). */
const val SFACE_SPACE_ID: String = "sface-2021dec-v1"

/**
 * Il **livello di qualita'** di un volto (D19): decide se ha un vettore e se puo' far nascere una persona. **Nessun attributo della
 * persona** (eta', genere, etnia, emozioni, accessori): solo dimensione, punteggio, imbardata e nitidezza del riquadro (C20).
 * [wire] sono i 2 bit bassi del byte "livello e motivi" del corpo `faces`.
 */
enum class FaceTier(val wire: Int, override val code: String) : StableCode {
  /** Sotto 24 px o oltre 60 gradi: nessun vettore, solo "1 volto non riconoscibile" nelle info. */
  NONE(0, "none"),

  /** Da 24 a 48 px, profilo fino a 60 gradi, sfocato, fotogramma di un video: si unisce solo con una somiglianza piu' alta, non fa nascere. */
  WEAK(1, "weak"),

  /** Lato corto >= 48 px, punteggio >= 0,80, imbardata <= 35 gradi, nitido: il solo che fa nascere una persona. */
  CORE(2, "core"),
  ;

  /** Solo CORE e WEAK hanno un vettore (corpo `faces`: 256 byte presenti solo per loro). */
  val hasVector: Boolean get() = this != NONE

  companion object {
    fun fromWire(wire: Int): FaceTier? = entries.firstOrNull { it.wire == wire }
  }
}

/** I **motivi** di un livello (bit alti del byte "livello e motivi", §2.4): perche' un volto non e' CORE. Mai un attributo della persona. */
object FaceReasons {
  const val SMALL: Int = 1
  const val PROFILE: Int = 2
  const val BLURRY: Int = 4
  const val FRAME: Int = 8

  /** Tutti i bit noti: un bit sconosciuto rifiuta il corpo (P3). */
  const val ALL: Int = SMALL or PROFILE or BLURRY or FRAME

  fun isValid(reasons: Int): Boolean = reasons and ALL.inv() == 0
}

/** Da dove vengono i volti di un contenuto (corpo `faces`, byte 4; D17, D18). */
enum class FaceSource(val wire: Int, override val code: String) : StableCode {
  /** L'anteprima di analisi (1024 px): la prima passata. */
  ANALYSIS(1, "analysis"),

  /** L'anteprima HQ (2048 px) in quattro tasselli da 1024 con 128 px di sovrapposizione: la seconda passata (D17). */
  HQ_TILES(2, "hq_tiles"),

  /** Copertina e fino a 3 fotogrammi del blob `frames` di un video: volti al piu' WEAK (D18, E-F17). */
  FRAMES(3, "frames"),

  /** I byte originali di un media del telefono di questo dispositivo. */
  PHONE_ORIGINAL(4, "phone_original"),
  ;

  companion object {
    fun fromWire(wire: Int): FaceSource? = entries.firstOrNull { it.wire == wire }
  }
}

/** Il rilevatore che ha trovato i volti (corpo `faces`, byte 2; D7: la scelta fra le due versioni di YuNet e' di P1). */
enum class FaceDetectorCode(val wire: Int, override val code: String) : StableCode {
  YUNET_2023MAR(1, "yunet_2023mar"),
  YUNET_2026MAY(2, "yunet_2026may"),
  ;

  companion object {
    fun fromWire(wire: Int): FaceDetectorCode? = entries.firstOrNull { it.wire == wire }
  }
}

/**
 * Come si combinano piu' persone in una richiesta (D26, D27): **una qualunque** ("Marco o Anna") o **tutte** ("Marco e Anna"). Lo usano
 * il chip "Persona" di core-search, la regola `smart:people` degli album smart di core-albums (`any:`/`all:`) e `PeopleIndex` di
 * core-faces, che non si vedono fra loro: per questo sta qui.
 */
enum class PeopleMode(override val code: String) : StableCode {
  ANY("any"),
  ALL("all"),
  ;

  companion object {
    fun fromCode(code: String?): PeopleMode? = enumByCodeOrNull<PeopleMode>(code)
  }
}

/**
 * Il riquadro di un volto in **1/65535** dell'immagine raddrizzata (§2.4: 4 x 2 byte), con `x0 < x1` e `y0 < y1`. Lo stesso riquadro su
 * ogni sorgente (anteprima, HQ, originale): e' la base dei localizzatori (D23).
 */
data class FaceBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
  init {
    require(x0 in 0..MAX && y0 in 0..MAX && x1 in 0..MAX && y1 in 0..MAX) { "Riquadro fuori dall'immagine." }
    require(x0 < x1 && y0 < y1) { "Riquadro vuoto o rovesciato." }
  }

  private val area: Long get() = (x1 - x0).toLong() * (y1 - y0).toLong()

  /** L'intersezione sull'unione con [other] (0..1). */
  fun iou(other: FaceBox): Double {
    val w = minOf(x1, other.x1) - maxOf(x0, other.x0)
    val h = minOf(y1, other.y1) - maxOf(y0, other.y0)
    if (w <= 0 || h <= 0) return 0.0
    val inter = w.toLong() * h.toLong()
    return inter.toDouble() / (area + other.area - inter).toDouble()
  }

  /** D37: mai un riquadro nei log. */
  override fun toString(): String = "FaceBox"

  companion object {
    const val MAX: Int = 65_535
  }
}
