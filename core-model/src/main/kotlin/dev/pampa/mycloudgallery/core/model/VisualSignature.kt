package dev.pampa.mycloudgallery.core.model

// Fase 5 (docs/fase5-progetto.md D36, §2.12): la firma visiva delle copie simili. Tipo fissato da P0 perche' lo usano in parallelo
// P1 (la tabella locale delle firme, schema v4) e P15 (il rilevatore in core-library, che ne decide l'algoritmo e le soglie).

/**
 * La **firma visiva** di una foto o di un video (D36): l'immagine ridotta a misura fissa dopo l'orientamento, in uno spazio di colore
 * percettivo (es. 32×32), che due copie dello stesso scatto con byte diversi (ricompressione, EXIF riscritto, HEIC → JPEG, riduzione)
 * hanno quasi uguale e due scatti diversi no. Per un video, le firme di due fotogrammi agli stessi istanti, una dopo l'altra
 * ([frames]).
 *
 * **Solo locale e solo per i candidati** (stessa ora di scatto e stessa proporzione): mai pubblicata sul NAS, il formato dell'indice
 * non cambia. Dal telefono si calcola sull'originale, dal NAS sulla miniatura dell'indice (la stessa su ogni dispositivo).
 *
 * Questo tipo e' un **contenitore**: [algorithm] dice come sono fatti i byte (P15 lo sceglie; una firma di un algoritmo diverso non
 * si confronta, si ricalcola), [frames] quante immagini contiene (1 per una foto, 2 per un video). Il confronto e le soglie
 * ("devono unirsi" / "non devono unirsi" del §2.12) sono del rilevatore. I byte non vanno mai nei log: [toString] dice solo forma e
 * dimensione.
 */
class VisualSignature private constructor(
  val algorithm: Int,
  val frames: Int,
  private val data: ByteArray,
) {

  /** Byte della firma (tutti i fotogrammi). */
  val size: Int get() = data.size

  /** Una copia dei byte (la firma resta immutabile). */
  fun bytes(): ByteArray = data.copyOf()

  /** Lo stesso algoritmo e la stessa forma: solo allora due firme si confrontano. */
  fun comparableWith(other: VisualSignature): Boolean =
    algorithm == other.algorithm && frames == other.frames && data.size == other.data.size

  override fun equals(other: Any?): Boolean =
    other is VisualSignature && other.algorithm == algorithm && other.frames == frames && other.data.contentEquals(data)

  override fun hashCode(): Int = (algorithm * 31 + frames) * 31 + data.contentHashCode()

  override fun toString(): String = "VisualSignature(v$algorithm, $frames fotogrammi, ${data.size} B)"

  companion object {
    /** Il tetto dei byte di una firma (anche di un video): sta in una riga del database senza pesare sull'indice. */
    const val MAX_BYTES: Int = 16 * 1024

    /** Fotogrammi al massimo (un video: due istanti, §2.12). */
    const val MAX_FRAMES: Int = 4

    /**
     * Una firma di [frames] fotogrammi lunghi uguali, calcolata con l'algoritmo [algorithm] (1..255). Rifiuta (con
     * `IllegalArgumentException`) una firma vuota, oltre [MAX_BYTES] o con i fotogrammi di lunghezze diverse: una firma letta dal
     * database rovinata non si confronta, si ricalcola.
     */
    fun of(algorithm: Int, frames: Int, bytes: ByteArray): VisualSignature {
      require(algorithm in 1..255) { "Algoritmo della firma fuori intervallo." }
      require(frames in 1..MAX_FRAMES) { "Numero di fotogrammi della firma non valido." }
      require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Dimensione della firma non valida." }
      require(bytes.size % frames == 0) { "I fotogrammi della firma devono avere la stessa lunghezza." }
      return VisualSignature(algorithm, frames, bytes.copyOf())
    }
  }
}
