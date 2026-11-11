package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryVideoTime
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat

/**
 * Cio' che serve a costruire il pannello informazioni di un media.
 *
 * @property exif l'EXIF letto su richiesta; null = non ancora letto o non leggibile (le pillole dello scatto non ci sono).
 * @property place il luogo dal dataset offline (regione e paese), se si conosce.
 * @property placeName il nome per la UI ("Oslo").
 * @property motionUnplayable E-M16: una motion photo il cui video non si riconosce.
 * @property backup Fase 5A (D15, D16): lo stato del backup del media (`MediaBackupState`); null = nessuna fonte o pannello non ancora
 *   risposto: la capsula del backup non c'e'.
 * @property onPhone H2: il media ha una copia sul telefono (la capsula del backup dice "Telefono e NAS" o "Sul NAS").
 * @property localDay il giorno dell'indice (`aaaammgg`), per la data in testa finche' i dettagli non arrivano: il pannello non salta.
 */
internal class InfoInputs(
  val kind: MediaKind,
  val details: MediaDetails?,
  val exif: ExifDetails? = null,
  val place: Place? = null,
  val placeName: String? = null,
  val motionUnplayable: Boolean = false,
  val backup: MediaBackupState? = null,
  val onPhone: Boolean = true,
  val localDay: Int = 0,
)

/**
 * Il pannello informazioni di un media, **compatto** (H2): niente righe "etichetta: valore" tutte uguali. In testa la data grande e sotto
 * l'ora con il fuso (o da dove viene una data stimata), poi il luogo; il backup in una capsula (un'icona e due parole); la fotocamera e i
 * dati di scatto in pillole, poi le pillole del file (misure, peso, formato, durata, tratti); in fondo, piu' piccoli, il nome del file e la
 * cartella. **Solo cio' che si sa**: un dato che non c'e' non occupa posto.
 */
@Immutable
internal data class InfoModel(
  /** "Martedì 12 marzo 2024"; null solo se non si sa nemmeno il giorno dell'indice. */
  val date: String?,
  /** "12:41 · ora di Oslo, UTC+1", o da dove viene la data stimata. */
  val timeLine: String?,
  val estimated: Boolean,
  /** "Oslo, Ostlandet, Norvegia". */
  val place: String?,
  /** "Samsung Galaxy S25". */
  val camera: String?,
  /** I dati di scatto: obiettivo, apertura, tempo, ISO, focale, flash. */
  val shot: List<String>,
  /** Il file: misure, megapixel, peso, formato, durata, tratti (Ultra HDR, Panorama...). */
  val file: List<String>,
  val edited: Boolean,
  val fileName: String?,
  /** L'ultima cartella del percorso sul telefono ("Camera"), non il percorso. */
  val folder: String?,
  /** Una nota da leggere (la foto in movimento il cui video non si riconosce). */
  val note: String?,
) {
  val hasShot: Boolean get() = camera != null || shot.isNotEmpty()
}

/** Costruisce il pannello da [inputs]. Funzione pura (le risorse vengono da [format]). */
internal fun buildInfoModel(format: ViewerFormat, inputs: InfoInputs): InfoModel {
  val res = format.resources
  val details = inputs.details
  val capture = details?.captureTime
  val date = capture?.let { format.panelDate(it) } ?: inputs.localDay.takeIf { it > 0 }?.let { format.panelDate(it) }
  val timeLine = capture?.let { res.getString(R.string.viewer_info_time_line, format.time(it), format.zoneCaption(it, inputs.placeName)) }

  val place = inputs.place
  val placeLine = when {
    place != null -> listOfNotNull(place.name, place.admin1.takeIf { it != place.name }, place.countryName).distinct().take(PLACE_PARTS).joinToString(", ")
    else -> inputs.placeName
  }?.takeIf { it.isNotBlank() }

  val exif = inputs.exif
  val camera = exif?.let { format.camera(it) }
  val shot = exif?.let { format.shotPills(it) }.orEmpty()

  val file = buildList {
    if (details != null) {
      addAll(format.dimensionPills(details))
      details.sizeBytes?.let { add(format.fileSize(it)) }
      format.formatName(details.mime)?.let { add(it) }
      details.durationMs?.takeIf { it > 0L && inputs.kind == MediaKind.VIDEO }?.let { add(GalleryVideoTime.format(it)) }
      addAll(traits(format, details.traits, inputs.kind))
    }
  }

  val folder = details?.folder?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
  return InfoModel(
    date = date,
    timeLine = timeLine,
    estimated = capture?.isEstimated == true,
    place = placeLine,
    camera = camera,
    shot = shot,
    file = file,
    edited = details?.isEdited == true,
    fileName = details?.fileName?.takeIf { it.isNotBlank() },
    folder = folder,
    note = if (inputs.motionUnplayable) res.getString(R.string.viewer_motion_unplayable_caption) else null,
  )
}

private fun traits(format: ViewerFormat, traits: Set<MediaTrait>, kind: MediaKind): List<String> {
  val res = format.resources
  return buildList {
    if (MediaTrait.ULTRA_HDR in traits) add(res.getString(R.string.viewer_trait_ultra_hdr))
    if (MediaTrait.HDR_VIDEO in traits) add(res.getString(R.string.viewer_trait_hdr_video))
    if (MediaTrait.PANORAMA in traits) add(res.getString(R.string.viewer_trait_panorama))
    if (MediaTrait.SPHERE in traits) add(res.getString(R.string.viewer_trait_sphere))
    if (MediaTrait.SCREEN_RECORDING in traits) add(res.getString(R.string.viewer_trait_screen_recording))
    if (kind == MediaKind.MOTION_PHOTO) add(res.getString(R.string.viewer_trait_motion))
    if (kind == MediaKind.RAW_PHOTO) add(res.getString(R.string.viewer_trait_raw))
    if (kind == MediaKind.ANIMATED_IMAGE) add(res.getString(R.string.viewer_trait_animated))
  }
}

private const val PLACE_PARTS = 3
