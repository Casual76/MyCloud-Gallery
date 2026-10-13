package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import java.time.ZoneId

/**
 * Cio' che il MediaStore sa gia' di un file e aiuta a leggerlo (docs/fase3-progetto.md §1.2, `core-metadata`).
 * Sono **indizi**, mai verita': un `DATE_TAKEN` puo' essere di un altro fuso, una larghezza puo' essere prima della
 * rotazione. L'estrattore li usa per le date che l'EXIF non da' e per le dimensioni se il file e' troppo rovinato.
 *
 * @property relativePath `RELATIVE_PATH` di MediaStore (`DCIM/Camera/`): serve alla classificazione e al
 *   raggruppamento; non finisce mai in un log.
 * @property scanZone il fuso del telefono **al momento della lettura**: per le date senza fuso si salva questo e
 *   cambiare fuso dopo non sposta nulla (E-P5).
 */
data class ExtractHints(
  val mime: String?,
  val displayName: String,
  val relativePath: String,
  val msDateTakenMs: Long?,
  val msWidth: Int?,
  val msHeight: Int?,
  val msOrientation: Int?,
  val msDurationMs: Long?,
  val scanZone: ZoneId,
) {
  // Il nome del file e il percorso sono dati personali: nessuno dei due nel toString.
  override fun toString(): String = "ExtractHints(mime=$mime)"
}

/**
 * Perche' un file non ha dato metadati. Codici stabili: finiscono nella Diagnostica (solo il codice, mai il file).
 */
enum class ExtractCode {
  /** Il file e' vuoto (E-M12). */
  ZERO_BYTES,

  /** I primi byte non sono quelli di un media (E-M12): non entra in Libreria. */
  NOT_A_MEDIA_FILE,

  /** Il formato e' riconosciuto ma non si decodifica (E-M10): la cella e' "non leggibile". */
  DECODE_FAILED,

  /** Il file finisce prima del previsto. */
  TRUNCATED,

  /** Un formato che non sappiamo leggere (ancora): foto o video si mostrano lo stesso, senza metadati. */
  UNSUPPORTED_FORMAT,
}

/** Un suggerimento di raggruppamento letto dal file: il nome base e il suffisso di raffica. */
data class GroupHint(
  /** Il nome senza suffisso (`IMG_20240312_101530`), per riconoscere scatti dello stesso gruppo. */
  val baseName: String,
  /** Il numero di scatto in una raffica, se il nome ne ha uno (`_BURST003`, `_003`, `(3)`). */
  val burstIndex: Int?,
  /** Se il nome dice che questa e' la copertina (`_COVER`). */
  val isCover: Boolean,
  /** I millisecondi dell'EXIF `SubSecTimeOriginal`, per ordinare scatti dello stesso secondo. */
  val subSecondMs: Int?,
  /** Il modello della fotocamera: una raffica e' sempre di un solo modello. */
  val cameraModel: String?,
)

/**
 * L'esito della lettura di un file. Mai un'eccezione per un file rovinato: un file troncato o falso e' un caso
 * normale della libreria di un telefono (E-M10, E-M12), non un errore del programma.
 */
sealed interface Extraction {

  /**
   * Letto. [metadata] e' quello della versione, **con** i tratti (panorama, sfera, Ultra HDR, video HDR, registrazione
   * dello schermo), il luogo dal GPS (sensibile) e il segnaposto sfocato dell'immagine, se si e' potuta decodificare
   * piccola; [kind] e' il tipo **definitivo** (`PHOTO`, `MOTION_PHOTO`, `RAW_PHOTO`, `ANIMATED_IMAGE`, `VIDEO`), che il
   * catalogo scrive con `updateMetadata(..., kind)`.
   *
   * @property motion l'intervallo di byte della parte video di una motion photo (E-M5), se trovato; null per una
   *   motion photo in un formato non riconosciuto (E-M16: si mostra la foto ferma).
   * @property groupHint cio' che serve a proporre raffiche e coppie RAW+JPEG.
   */
  data class Read(
    val metadata: MediaMetadata,
    val kind: MediaKind,
    val motion: ByteRange? = null,
    val groupHint: GroupHint? = null,
  ) : Extraction

  /** Non e' un media (zero byte, byte iniziali sbagliati): ignorato e contato (E-M12). */
  data class NotMedia(val code: ExtractCode) : Extraction

  /** Un media rovinato: dopo 5 tentativi lo stato di lavoro va a `GAVE_UP` e la cella e' "non leggibile" (E-M10). */
  data class Corrupt(val code: ExtractCode) : Extraction

  /** Un formato non supportato: non e' un errore, e' un limite dichiarato. */
  data class Unsupported(val code: ExtractCode) : Extraction
}

/**
 * Legge un media e ne ricava metadati, tipo, tratti, luogo, segnaposto e indizi di gruppo. E' la fase META di
 * `docs/fase3-progetto.md` §2.1 e §2.2; la scrive nel catalogo `core-mediastore`.
 *
 * Gira su un thread di lavoro (decodifica di un'anteprima piccola per il ThumbHash) e non tiene il file aperto:
 * `bytes` si usa e basta. Un file da 200 MP non si decodifica mai intero.
 */
interface MetadataExtractor {
  suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction
}
