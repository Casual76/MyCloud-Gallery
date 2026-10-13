package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.GeoPoint

// Segnaposto sfocato, motion photo, dettagli EXIF e video (docs/fase3-progetto.md §1.2 e §2.2). Contratti di P0; i
// corpi di ThumbHashCodec e MotionPhotoLocator sono di P3.

/**
 * Il segnaposto sfocato di un'immagine: ThumbHash, un'implementazione propria dall'algoritmo pubblico (nessuna
 * libreria). Una stringa di al massimo 48 caratteri nell'indice; la cella la decodifica in una bitmap 32x32 finche'
 * la miniatura vera non arriva, cosi' una griglia in scorrimento veloce non mostra mai buchi (D5).
 *
 * **Segnaposto di P0.** [encode] dice sempre "grigio uniforme" e [decode] disegna quel grigio: quanto basta per far
 * girare celle e screenshot. P3 lo sostituisce con l'algoritmo vero senza cambiare le firme.
 */
object ThumbHashCodec {

  /** Il segnaposto di un'immagine di cui non si sa niente: un grigio medio uniforme. */
  val NEUTRAL: ThumbHash = ThumbHash.of(ByteArray(NEUTRAL_BYTES) { 0x80.toByte() })

  /** Codifica un'immagine [w] x [h] (al massimo 100 px per lato) in pixel RGBA, riga per riga. */
  @Suppress("UNUSED_PARAMETER")
  fun encode(w: Int, h: Int, rgba: ByteArray): ThumbHash = NEUTRAL

  /**
   * Decodifica in ARGB (come `Bitmap.setPixels`) con il lato lungo di [maxSide] pixel e la proporzione dell'immagine
   * originale. Mai vuoto: restituisce almeno un pixel.
   */
  @Suppress("UNUSED_PARAMETER")
  fun decode(h: ThumbHash, maxSide: Int): IntArray {
    val side = maxSide.coerceAtLeast(1)
    return IntArray(side * side) { NEUTRAL_ARGB }
  }

  private const val NEUTRAL_BYTES: Int = 25

  private const val NEUTRAL_ARGB: Int = 0xFF808080.toInt()
}

/** Il formato in cui una motion photo porta il suo video. */
enum class MotionFormat {
  /** XMP `GCamera:MotionPhoto` e `Container:Directory` (Google, versione 1). */
  GOOGLE_V1,

  /** XMP `GCamera:MicroVideo` con `MicroVideoOffset` (formato precedente di Google). */
  MICRO_VIDEO,

  /** Trailer SEF di Samsung (`MotionPhoto_Data`). */
  SAMSUNG_SEF,
}

/** La parte video di una motion photo: dove sta e in che formato era dichiarata. */
data class MotionPart(
  val videoRange: ByteRange,
  val format: MotionFormat,
  /** Il fotogramma della foto ferma dentro il video, in microsecondi, se dichiarato. */
  val stillPresentationUs: Long?,
)

/**
 * Trova la parte video di una motion photo (E-M5): Google v1, MicroVideo e trailer SEF di Samsung. Riceve l'XMP
 * (se c'e'), gli ultimi byte del file e la dimensione, e restituisce l'intervallo del video. Null = formato non
 * riconosciuto (E-M16): la UI mostra la foto ferma e scrive "parte video non riproducibile" nelle info.
 *
 * **Segnaposto di P0.** Non riconosce nessun formato (sempre null), che e' l'esito sicuro. P3 lo sostituisce con i
 * tre localizzatori senza cambiare la firma.
 */
object MotionPhotoLocator {

  @Suppress("UNUSED_PARAMETER")
  fun locate(xmp: String?, tail: ByteArray, size: Long): MotionPart? = null
}

/**
 * I dettagli EXIF per il pannello info: marca, modello, obiettivo, apertura, tempo di posa, ISO, focale, flash.
 * Si leggono **su richiesta**, quando l'utente apre il pannello, e non si salvano: sono molti, cambiano da fotocamera a
 * fotocamera e non servono a nessuna ricerca o ordinamento.
 */
data class ExifDetails(
  val make: String? = null,
  val model: String? = null,
  val lens: String? = null,
  /** Apertura (f/numero), per esempio 1.8. */
  val fNumber: Double? = null,
  /** Tempo di posa come lo scrive la fotocamera ("1/250"). */
  val exposureTime: String? = null,
  val iso: Int? = null,
  val focalLengthMm: Double? = null,
  val flashFired: Boolean? = null,
)

/** Legge i [ExifDetails] di un'immagine. Un file senza EXIF da' un [ExifDetails] vuoto, mai un errore. */
interface ExifDetailsReader {
  suspend fun exifDetails(bytes: MediaBytes): ExifDetails
}

/**
 * I fatti di un video che servono all'indice e al viewer (E-M7, E-M13, E-M14).
 *
 * @property rotation 0, 90, 180 o 270 gradi.
 * @property utcMs la data di creazione del contenitore, in UTC; manca se il contenitore non la dichiara.
 * @property iso6709 il punto GPS del video (sensibile), se c'e'.
 * @property hdr trasferimento ST2084 (HDR10) o HLG.
 */
data class VideoFacts(
  val durationMs: Long,
  val rotation: Int,
  val utcMs: Long?,
  val iso6709: GeoPoint?,
  val hdr: Boolean,
  val codecMime: String?,
)

/**
 * Sonda un video. L'adattatore vero e' `MediaMetadataRetriever` (non gira su JVM): su JVM c'e' il fake. Null se il
 * contenitore non si apre.
 */
interface VideoProbe {
  fun probe(bytes: MediaBytes): VideoFacts?
}
