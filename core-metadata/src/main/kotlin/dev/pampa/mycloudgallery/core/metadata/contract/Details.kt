package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.metadata.format.MotionCandidates
import dev.pampa.mycloudgallery.core.metadata.format.ThumbHashAlgorithm
import dev.pampa.mycloudgallery.core.metadata.format.XmpFacts
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.ThumbHash

// Segnaposto sfocato, motion photo, dettagli EXIF e video (docs/fase3-progetto.md §1.2 e §2.2). Contratti di P0; i
// corpi di ThumbHashCodec e MotionPhotoLocator sono di P3.

/** Un segnaposto decodificato con le sue dimensioni: pixel ARGB non premoltiplicati, riga per riga ([argb] ha `width * height` elementi). */
class ThumbHashImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * Il segnaposto sfocato di un'immagine: ThumbHash, un'implementazione propria dell'algoritmo pubblico (nessuna
 * libreria; i byte sono quelli del formato pubblico). Una stringa di al massimo 48 caratteri nell'indice; la cella
 * lo decodifica in una piccola bitmap finche' la miniatura vera non arriva, cosi' una griglia in scorrimento veloce non
 * mostra mai buchi (D5).
 *
 * Due modi di decodificare, perche' una bitmap ha due dimensioni:
 * - [decode] restituisce un quadrato `maxSide x maxSide` (l'array ha `maxSide * maxSide` elementi, come il segnaposto
 *   di P0): l'anteprima e' lo stesso disegno **stirato** sul quadrato, e la cella la stira ancora sul suo rettangolo.
 *   E' l'uso della griglia: la cella quadrata ritaglia al centro la miniatura, il segnaposto sfocato no, e a quella
 *   scala non si vede;
 * - [decodeImage] rispetta la proporzione dell'immagine originale (lato lungo `maxSide`) e porta le dimensioni.
 */
object ThumbHashCodec {

  /** Il segnaposto di un'immagine di cui non si sa niente: un grigio medio uniforme. */
  val NEUTRAL: ThumbHash = ThumbHash.of(ThumbHashAlgorithm.encode(1, 1, byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0xFF.toByte())))

  /**
   * Codifica un'immagine [w] x [h] in pixel RGBA, riga per riga. Il formato vuole al massimo 100 px per lato: un'immagine
   * piu' grande si riduce prima, senza errori.
   */
  fun encode(w: Int, h: Int, rgba: ByteArray): ThumbHash = ThumbHash.of(ThumbHashAlgorithm.encode(w, h, rgba))

  /** Decodifica in ARGB (come `Bitmap.setPixels`) un quadrato [maxSide] x [maxSide]. Mai vuoto: almeno un pixel. */
  fun decode(h: ThumbHash, maxSide: Int): IntArray {
    val side = maxSide.coerceAtLeast(1)
    return ThumbHashAlgorithm.decode(h.bytes(), side, side)
  }

  /** Come [decode], ma con la proporzione dell'immagine originale: il lato lungo e' [maxSide]. */
  fun decodeImage(h: ThumbHash, maxSide: Int): ThumbHashImage {
    val side = maxSide.coerceAtLeast(1)
    val ratio = ThumbHashAlgorithm.aspectRatio(h.bytes())
    val w = Math.round(if (ratio > 1) side.toDouble() else side * ratio).toInt().coerceAtLeast(1)
    val ht = Math.round(if (ratio > 1) side / ratio else side.toDouble()).toInt().coerceAtLeast(1)
    return ThumbHashImage(w, ht, ThumbHashAlgorithm.decode(h.bytes(), w, ht))
  }

  /** Il rapporto larghezza / altezza approssimato dell'immagine originale, dalla sola intestazione. */
  fun aspectRatio(h: ThumbHash): Double = ThumbHashAlgorithm.aspectRatio(h.bytes())
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
 * Trova la parte video di una motion photo (E-M5): Google v1 (directory del contenitore XMP, `Item:Length` della voce
 * `MotionPhoto`), MicroVideo (`MicroVideoOffset`, byte dalla fine) e trailer SEF di Samsung (`MotionPhoto_Data`).
 * Riceve l'XMP (se c'e'), gli ultimi byte del file e la dimensione, e restituisce l'intervallo del video. Null = formato
 * non riconosciuto (E-M16): la UI mostra la foto ferma e scrive "parte video non riproducibile" nelle info.
 *
 * **Non verifica i byte del video**: l'XMP puo' restare dopo che il video e' stato tolto. Chi ha il file (l'estrattore)
 * controlla che nel punto indicato ci sia un MP4 (`ftyp`) e, per SEF, corregge la partenza leggendo il nome vero del blocco.
 * L'ordine delle preferenze e': Google v1, MicroVideo, SEF. Il trailer SEF si legge dalla sola [tail], che deve quindi
 * contenere almeno la directory (qualche centinaio di byte in fondo; 64 KiB bastano sempre).
 */
object MotionPhotoLocator {

  fun locate(xmp: String?, tail: ByteArray, size: Long): MotionPart? =
    MotionCandidates.of(XmpFacts.parse(xmp), tail, size).firstOrNull()?.part
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
