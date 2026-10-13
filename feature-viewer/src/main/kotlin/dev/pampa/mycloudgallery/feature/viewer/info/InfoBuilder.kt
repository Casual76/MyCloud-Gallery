package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
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
 * @property exif l'EXIF letto su richiesta; null = non ancora letto o non leggibile (la sezione non c'e').
 * @property place il luogo dal dataset offline (regione e paese), se si conosce.
 * @property placeName il nome per la UI ("Oslo").
 * @property motionUnplayable E-M16: una motion photo il cui video non si riconosce.
 */
internal class InfoInputs(
  val kind: MediaKind,
  val details: MediaDetails?,
  val exif: ExifDetails? = null,
  val place: Place? = null,
  val placeName: String? = null,
  val motionUnplayable: Boolean = false,
)

/**
 * Le sezioni del pannello: **solo cio' che si sa**. Una riga senza dato non c'e' (niente "Sconosciuto" a righe): data e
 * ora con il fuso dello scatto e "Stimata" quando serve; il luogo con regione, paese, coordinate e "Apri in Mappe"
 * (D18, un'azione esplicita); fotocamera ed esposizione; dimensioni, formato e peso; la cartella sul telefono. Persone,
 * album e versioni compaiono solo quando esistono (Fasi 5, 7 e 8).
 *
 * @param onOpenMaps null = niente riga "Apri in Mappe" (nessuna app per aprirla, o contesto riservato).
 */
internal fun buildInfoSections(
  format: ViewerFormat,
  inputs: InfoInputs,
  onOpenMaps: (() -> Unit)?,
): List<InfoSectionModel> {
  val res = format.resources
  val details = inputs.details
  val sections = mutableListOf<InfoSectionModel>()

  // Data e ora: la riga che apre il pannello.
  val capture = details?.captureTime
  if (capture != null) {
    sections += InfoSectionModel(
      header = null,
      rows = listOf(
        GalleryInfoRow(
          title = format.dateTimeLine(capture),
          caption = format.zoneCaption(capture, inputs.placeName),
          icon = Icons.Rounded.CalendarMonth,
          pill = if (capture.isEstimated) res.getString(R.string.viewer_estimated_pill) else null,
        ),
      ),
    )
  }

  // Luogo.
  val point = details?.location
  if (point != null || inputs.placeName != null) {
    val place = inputs.place
    val title = when {
      place != null -> listOfNotNull(place.name, place.admin1.takeIf { it != place.name }, place.countryName).distinct().take(PLACE_PARTS).joinToString(", ")
      else -> inputs.placeName.orEmpty()
    }
    val rows = mutableListOf<GalleryInfoRow>()
    if (title.isNotBlank()) {
      rows += GalleryInfoRow(
        title = title,
        caption = point?.let { format.coordinates(it) }.orEmpty(),
        icon = Icons.Rounded.Place,
        tone = FluidTone.Primary,
      )
    }
    if (point != null && onOpenMaps != null) {
      rows += GalleryInfoRow(
        title = res.getString(R.string.viewer_place_open_maps),
        caption = res.getString(R.string.viewer_place_open_maps_caption),
        icon = Icons.Rounded.Map,
        onClick = onOpenMaps,
      )
    }
    if (rows.isNotEmpty()) sections += InfoSectionModel(res.getString(R.string.viewer_info_h_place), rows)
  }

  // Fotocamera ed esposizione (EXIF, su richiesta).
  val exif = inputs.exif
  if (exif != null) {
    val rows = mutableListOf<GalleryInfoRow>()
    val camera = format.camera(exif)
    val lens = format.lensLine(exif)
    if (camera != null) {
      rows += GalleryInfoRow(title = camera, caption = lens.orEmpty(), icon = Icons.Rounded.CameraAlt)
    }
    val exposure = format.exposure(exif)
    if (exposure != null) {
      rows += GalleryInfoRow(title = exposure, caption = if (camera == null) lens.orEmpty() else "", icon = Icons.Rounded.Tune)
    }
    if (rows.isNotEmpty()) sections += InfoSectionModel(res.getString(R.string.viewer_info_h_camera), rows)
  }

  // Il file: dimensioni, formato, peso, durata, tratti.
  if (details != null) {
    val rows = mutableListOf<GalleryInfoRow>()
    val dimensions = format.dimensions(details)
    val formatName = format.formatName(details.mime)
    val size = details.sizeBytes?.let { format.fileSize(it) }
    val caption = listOfNotNull(formatName, size).joinToString(" · ")
    if (dimensions != null || caption.isNotEmpty()) {
      rows += GalleryInfoRow(
        title = dimensions ?: caption,
        caption = if (dimensions != null) caption else "",
        icon = if (inputs.kind == MediaKind.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Image,
        pill = if (details.isEdited) res.getString(R.string.viewer_edited_pill) else null,
        pillTone = FluidTone.Info,
      )
    }
    details.durationMs?.takeIf { it > 0L && inputs.kind == MediaKind.VIDEO }?.let { duration ->
      rows += GalleryInfoRow(
        title = GalleryVideoTime.format(duration),
        caption = res.getString(R.string.viewer_duration),
        icon = Icons.Rounded.Timer,
      )
    }
    traitsLine(format, details.traits, inputs.kind)?.let { line ->
      rows += GalleryInfoRow(title = line, icon = Icons.Rounded.Info)
    }
    if (inputs.motionUnplayable) {
      rows += GalleryInfoRow(
        title = res.getString(R.string.viewer_motion_unplayable_title),
        caption = res.getString(R.string.viewer_motion_unplayable_caption),
        icon = Icons.Rounded.Info,
        tone = FluidTone.Warning,
      )
    }
    if (rows.isNotEmpty()) sections += InfoSectionModel(res.getString(R.string.viewer_info_h_file), rows)

    // Dove sta sul telefono.
    val folder = details.folder?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val fileName = details.fileName
    if (folder != null || fileName != null) {
      sections += InfoSectionModel(
        header = res.getString(R.string.viewer_info_h_phone),
        rows = listOf(
          GalleryInfoRow(
            title = folder ?: fileName.orEmpty(),
            caption = if (folder != null) fileName.orEmpty() else "",
            icon = Icons.Rounded.Folder,
          ),
        ),
      )
    }
  }
  return sections
}

private fun traitsLine(format: ViewerFormat, traits: Set<MediaTrait>, kind: MediaKind): String? {
  val res = format.resources
  val parts = buildList {
    if (MediaTrait.ULTRA_HDR in traits) add(res.getString(R.string.viewer_trait_ultra_hdr))
    if (MediaTrait.HDR_VIDEO in traits) add(res.getString(R.string.viewer_trait_hdr_video))
    if (MediaTrait.PANORAMA in traits) add(res.getString(R.string.viewer_trait_panorama))
    if (MediaTrait.SPHERE in traits) add(res.getString(R.string.viewer_trait_sphere))
    if (MediaTrait.SCREEN_RECORDING in traits) add(res.getString(R.string.viewer_trait_screen_recording))
    if (kind == MediaKind.MOTION_PHOTO) add(res.getString(R.string.viewer_trait_motion))
    if (kind == MediaKind.RAW_PHOTO) add(res.getString(R.string.viewer_trait_raw))
    if (kind == MediaKind.ANIMATED_IMAGE) add(res.getString(R.string.viewer_trait_animated))
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private const val PLACE_PARTS = 3
