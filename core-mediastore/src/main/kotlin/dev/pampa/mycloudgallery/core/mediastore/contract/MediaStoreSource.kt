package dev.pampa.mycloudgallery.core.mediastore.contract

import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Se una riga del MediaStore e' un'immagine o un video (`MEDIA_TYPE_IMAGE`, `MEDIA_TYPE_VIDEO`). */
enum class RowType { IMAGE, VIDEO }

/**
 * Una riga del MediaStore, ridotta a cio' che la Libreria usa (docs/fase3-progetto.md §2.1). E' l'unico punto in cui
 * i nomi delle colonne di Android entrano nel programma: tutto il resto parla di [MediaStoreRow].
 *
 * **Non e' un'identita'.** [id] (`_ID`) vale solo finche' `MediaStore.getVersion` resta quella con cui la riga e'
 * stata letta (E-P12, E-P22): chi la conserva conserva anche la versione ([phoneLocation]). L'identita' stabile di
 * un media e' `MediaId`, assegnata dal catalogo con le regole di collegamento.
 *
 * Il nome e il percorso sono dati personali: niente nel `toString` ne' nei log.
 *
 * @property generationModified `GENERATION_MODIFIED` (Android 11+/S), null prima: la scansione incrementale e'
 *   per generazione, su API 29 si usa il confronto completo.
 * @property ownerPackage `OWNER_PACKAGE_NAME` (WhatsApp, Quick Share...), se noto.
 */
class MediaStoreRow(
  val volume: String,
  val id: Long,
  val type: RowType,
  val displayName: String,
  val relativePath: String,
  val mime: String?,
  val size: Long,
  val dateModifiedMs: Long,
  val dateTakenMs: Long? = null,
  val width: Int? = null,
  val height: Int? = null,
  val orientation: Int? = null,
  val durationMs: Long? = null,
  val generationModified: Long? = null,
  val ownerPackage: String? = null,
) {

  init {
    require(id > 0) { "Un _ID MediaStore e' positivo." }
  }

  /** Il timbro del file: dimensione, data di modifica e generazione (la "scorciatoia del timbro" della scansione). */
  fun stamp(): FileStamp = FileStamp(size = size.coerceAtLeast(0), modifiedAtMillis = dateModifiedMs, generation = generationModified)

  /** Il posto sul telefono, con la cache `_ID` + versione con cui questa riga e' stata letta. */
  fun phoneLocation(mediaStoreVersion: String): Location.Phone =
    Location.Phone(volume, relativePath, displayName, mediaStoreId = id, mediaStoreVersion = mediaStoreVersion)

  /** Gli indizi per l'estrattore dei metadati; [scanZone] e' il fuso del telefono **adesso** (E-P5). */
  fun toExtractHints(scanZone: ZoneId): ExtractHints = ExtractHints(
    mime = mime,
    displayName = displayName,
    relativePath = relativePath,
    msDateTakenMs = dateTakenMs,
    msWidth = width,
    msHeight = height,
    msOrientation = orientation,
    msDurationMs = durationMs,
    scanZone = scanZone,
  )

  override fun toString(): String = "MediaStoreRow($type)"
}

/** Una riga di `deleted_files` (estensione S ≥ 23): solo un'accelerazione, la verita' e' la riconciliazione. */
data class DeletedRow(val volume: String, val id: Long, val generationModified: Long?)

/**
 * Il MediaStore del telefono visto dalla sincronizzazione. **Sottile di proposito**: l'adattatore vero
 * (`AndroidMediaStoreSource`, P2) traduce queste chiamate in `ContentResolver.query` con `Bundle`, `call` per
 * versione e generazione, e `openFile`; `FakeMediaStore` (package `testing`) le simula con generazioni, versioni,
 * rinumerazioni, cestino, accesso parziale e volumi, per provare tutta la sincronizzazione senza dispositivo.
 *
 * Tutte le chiamate di lettura sono `suspend` e girano su IO. Un volume sparito (microSD tolta) non e' un errore del
 * programma: [volumes] non lo elenca piu' e la sincronizzazione lo segna `UNAVAILABLE`, mai assente (E-P6).
 */
interface MediaStoreSource {

  /** I volumi attualmente montati (`external_primary`, l'id di una microSD). */
  suspend fun volumes(): Set<String>

  /** `MediaStore.getVersion` del volume: se cambia, gli `_ID` e le generazioni non valgono piu' (E-P22). */
  suspend fun version(volume: String): String

  /** `MediaStore.getGeneration` del volume, o null su API 29. Si legge **prima** della query (il giro e' idempotente). */
  suspend fun generation(volume: String): Long?

  /**
   * Le righe visibili (non `IS_PENDING`, non cestinate) del volume, dalle piu' recenti. Con [sinceGeneration] solo
   * quelle con `GENERATION_MODIFIED` maggiore; con [afterId] si riprende dopo quella riga, nell'ordine di scansione
   * (ripresa dopo un riavvio, E-P4); [limit] e' la dimensione del lotto (500).
   */
  suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow>

  /**
   * Le righe tolte dal MediaStore dopo [generation] (`content://media/<volume>/deleted_files`), o null se la
   * piattaforma non le offre. Una accelerazione: ogni riga diventa "assente", e la riconciliazione quotidiana
   * confronta comunque gli id presenti con la cache.
   */
  suspend fun deletedSince(volume: String, generation: Long): List<DeletedRow>?

  /** Tutti gli `_ID` presenti nel volume, per la riconciliazione (solo con accesso completo). */
  suspend fun allIds(volume: String): LongArray

  /** Apre i byte di una riga (con `setRequireOriginal` per le foto, D9): lo chiude il chiamante dopo l'uso. */
  suspend fun open(row: MediaStoreRow): MediaBytes

  /**
   * Emette quando il MediaStore cambia (`ContentObserver` su immagini e video, con i discendenti, accorpato a 1,5 s
   * finche' il processo vive). Un segnale senza dati: la sincronizzazione rilegge per generazione.
   */
  fun changes(): Flow<Unit>
}
