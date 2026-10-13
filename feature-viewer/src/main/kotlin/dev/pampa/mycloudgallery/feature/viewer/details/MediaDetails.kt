package dev.pampa.mycloudgallery.feature.viewer.details

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull

/**
 * Tutto cio' che il pannello informazioni sa di un media **senza aprire il file**: viene dal grafo del catalogo (la
 * versione corrente, i suoi metadati, la copia sul telefono). L'EXIF (marca, obiettivo, esposizione) si legge su
 * richiesta, solo quando il pannello e' aperto, e non sta qui.
 *
 * E' dato personale: il nome del file, la cartella, le coordinate e il luogo non finiscono mai in un log. Per questo
 * [toString] non li scrive, e `GeoPoint` e `PlaceRef` sono gia' `@Sensitive`.
 *
 * @property width larghezza **come memorizzata** (prima dell'orientamento); [orientedWidth] e' quella che si vede.
 */
@Immutable
class MediaDetails(
  val id: MediaId,
  val kind: MediaKind,
  val fileName: String? = null,
  val folder: String? = null,
  val sizeBytes: Long? = null,
  val mime: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val orientationDegrees: Int = 0,
  val durationMs: Long? = null,
  val captureTime: CaptureTime? = null,
  val location: GeoPoint? = null,
  val place: PlaceRef? = null,
  val traits: Set<MediaTrait> = emptySet(),
  val isEdited: Boolean = false,
) {

  private val swaps: Boolean get() = orientationDegrees == 90 || orientationDegrees == 270

  /** La larghezza che si vede (dopo l'orientamento). */
  val orientedWidth: Int? get() = if (swaps) height else width

  /** L'altezza che si vede (dopo l'orientamento). */
  val orientedHeight: Int? get() = if (swaps) width else height

  /** I megapixel, o null se le dimensioni non sono note. */
  val megapixels: Double? get() {
    val w = width ?: return null
    val h = height ?: return null
    return w.toDouble() * h.toDouble() / MEGA
  }

  override fun toString(): String = "MediaDetails(kind=$kind)"

  private companion object {
    const val MEGA = 1_000_000.0
  }
}

/** Da dove il viewer prende i [MediaDetails]. Un'interfaccia: nei test e negli screenshot sono finti. */
interface MediaDetailsSource {

  /** I dettagli di [id], o null se il catalogo non lo conosce (piu'). Mai sul thread principale. */
  suspend fun details(id: MediaId): MediaDetails?
}

/**
 * I dettagli letti dal catalogo vero: `MediaCatalog.media` per le copie e le versioni, e la riga dei metadati della
 * versione corrente (`version_metadata`, che il catalogo non espone) dal database.
 */
class CatalogMediaDetailsSource(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
) : MediaDetailsSource {

  override suspend fun details(id: MediaId): MediaDetails? {
    val media = catalog.media(id) ?: return null
    val version = media.currentVersion
    val meta = database.versionDao().metadata(version.id.value)
    val phone = media.locationsOf(version.id).firstOrNull { it.isPhone && it.isPresent }
      ?: media.locations.firstOrNull { it.isPhone && it.isPresent }
    val phoneLocation = phone?.location as? Location.Phone
    val takenAt = meta?.takenAtUtc
    val takenOffset = meta?.takenOffsetMinutes
    val confidence = enumByCodeOrNull<CaptureTimeConfidence>(meta?.takenConfidence)
    val source = enumByCodeOrNull<CaptureTimeSource>(meta?.takenSource)
    val capture = if (takenAt != null && takenOffset != null && confidence != null && source != null) {
      CaptureTime(takenAt, takenOffset, confidence, source)
    } else {
      null
    }
    val latitude = meta?.latitude
    val longitude = meta?.longitude
    val placeId = meta?.placeId
    val placeDataset = meta?.placeDataset
    return MediaDetails(
      id = media.id,
      kind = media.kind,
      fileName = phoneLocation?.displayName,
      folder = phoneLocation?.relativePath,
      sizeBytes = phone?.stamp?.size,
      mime = meta?.mimeType,
      width = meta?.width,
      height = meta?.height,
      orientationDegrees = meta?.orientation ?: 0,
      durationMs = meta?.durationMs,
      captureTime = capture,
      location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude, meta?.altitude) else null,
      place = if (placeId != null && placeDataset != null) PlaceRef(placeId, placeDataset) else null,
      traits = MediaTrait.fromBits(meta?.traits ?: 0),
      isEdited = version.kind != VersionKind.ORIGINAL,
    )
  }
}

/** Nessun dettaglio: il pannello mostra solo cio' che il viewer sa gia' (il tipo e la posizione nella vista). */
object NoMediaDetails : MediaDetailsSource {
  override suspend fun details(id: MediaId): MediaDetails? = null
}
