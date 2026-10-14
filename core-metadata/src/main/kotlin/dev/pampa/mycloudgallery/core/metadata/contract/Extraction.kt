package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import java.time.ZoneId

/**
 * Cio' che chi elenca un file sa gia' di lui e aiuta a leggerlo (docs/fase3-progetto.md §1.2, `core-metadata`): sul
 * telefono la riga del MediaStore, sul NAS (Fase 4) l'elenco della cartella. Sono **indizi**, mai verita': un
 * `DATE_TAKEN` puo' essere di un altro fuso, una larghezza puo' essere prima della rotazione. L'estrattore li usa per le
 * date che l'EXIF non da' e per le dimensioni se il file e' troppo rovinato.
 *
 * Fase 4 (P2): i nomi sono **neutri** ([dateTakenMs], [width], ...; prima `msDateTakenMs`, `msWidth`, ...), perche' gli
 * indizi non vengono piu' solo dal MediaStore. I nomi vecchi restano come alias deprecati (costruttore e proprieta'),
 * cosi' nessun chiamante si rompe; il significato non cambia.
 *
 * @property relativePath la cartella del file, relativa e con `/` finale (`DCIM/Camera/` sul telefono, `Foto/2024/` sul
 *   NAS): serve alla classificazione e al raggruppamento; non finisce mai in un log.
 * @property scanZone il fuso del telefono **al momento della lettura**: per le date senza fuso si salva questo e
 *   cambiare fuso dopo non sposta nulla (E-P5).
 * @property dateTakenMs una data di scatto gia' nota a chi elenca (`DATE_TAKEN` del MediaStore; sul NAS di norma nessuna).
 * @property dateModifiedMs la data di modifica del file, in millisecondi (`DATE_MODIFIED` del MediaStore, `LastWriteTime`
 *   del NAS): l'ultimo ripiego per la data (`FILE_MODIFIED`, E-M8). Senza, e senza EXIF, nome e [dateTakenMs], la data
 *   cade sul ripiego finale "adesso" (sempre stimata).
 * @property width, height, orientation, durationMs dimensioni, rotazione in gradi e durata gia' note a chi elenca.
 */
data class ExtractHints(
  val mime: String?,
  val displayName: String,
  val relativePath: String,
  val scanZone: ZoneId,
  val dateTakenMs: Long? = null,
  val dateModifiedMs: Long? = null,
  val width: Int? = null,
  val height: Int? = null,
  val orientation: Int? = null,
  val durationMs: Long? = null,
) {

  /** Il costruttore con i nomi della Fase 3 (`ms*`), nello stesso ordine: resta per i chiamanti esistenti. */
  @Deprecated("Nomi neutri dalla Fase 4: dateTakenMs, width, height, orientation, durationMs, dateModifiedMs.")
  constructor(
    mime: String?,
    displayName: String,
    relativePath: String,
    msDateTakenMs: Long?,
    msWidth: Int?,
    msHeight: Int?,
    msOrientation: Int?,
    msDurationMs: Long?,
    scanZone: ZoneId,
    msDateModifiedMs: Long? = null,
  ) : this(mime, displayName, relativePath, scanZone, msDateTakenMs, msDateModifiedMs, msWidth, msHeight, msOrientation, msDurationMs)

  @Deprecated("Usa dateTakenMs.", ReplaceWith("dateTakenMs"))
  val msDateTakenMs: Long? get() = dateTakenMs

  @Deprecated("Usa width.", ReplaceWith("width"))
  val msWidth: Int? get() = width

  @Deprecated("Usa height.", ReplaceWith("height"))
  val msHeight: Int? get() = height

  @Deprecated("Usa orientation.", ReplaceWith("orientation"))
  val msOrientation: Int? get() = orientation

  @Deprecated("Usa durationMs.", ReplaceWith("durationMs"))
  val msDurationMs: Long? get() = durationMs

  @Deprecated("Usa dateModifiedMs.", ReplaceWith("dateModifiedMs"))
  val msDateModifiedMs: Long? get() = dateModifiedMs

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
) {
  // Il nome del file e' un dato personale: nel toString (e quindi nei log) solo il numero di scatto.
  override fun toString(): String = "GroupHint(burstIndex=$burstIndex, isCover=$isCover)"
}

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
