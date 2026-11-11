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
 * @property exifPending l'EXIF si sta leggendo (il pannello si e' appena aperto su una foto): la scheda dello scatto mostra i segnaposto al
 *   posto dei valori, e quando arrivano niente salta.
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
  val exifPending: Boolean = false,
)

/**
 * Il pannello informazioni di un media, **compatto e con una gerarchia** (H2; giro di correzione 1: "identita'"). In testa **la data, unico
 * titolo**, sotto l'ora (il fuso solo se diverso da quello del telefono) e il luogo; il backup in una capsula; poi **una sola scheda dello
 * scatto**: la fotocamera (o il tipo di media) con il formato, l'obiettivo, le misure e il peso in una riga, i tratti, e in fondo **la striscia
 * dei valori** dello scatto (ISO · focale · apertura · tempo) come una riga tipografica divisa da filetti. In fondo, piccoli, il nome del file
 * e la cartella. **Solo cio' che si sa**: un dato che non c'e' non occupa posto.
 */
@Immutable
internal data class InfoModel(
  /** "Martedì 12 marzo 2024"; null solo se non si sa nemmeno il giorno dell'indice. */
  val date: String?,
  /** "12:41", "12:41 · ora locale, UTC+9", o da dove viene la data stimata. */
  val timeLine: String?,
  val estimated: Boolean,
  /** "Oslo, Ostlandet, Norvegia". */
  val place: String?,
  /** La testa della scheda dello scatto: "Samsung Galaxy S25". */
  val camera: String?,
  /** "Obiettivo principale". */
  val lens: String?,
  /** La striscia dei valori dello scatto: "ISO 50", "6,3 mm", "f/1,8", "1/250 s" (solo quelli noti, in quest'ordine). */
  val strip: List<String>,
  /** Il formato del file: "JPEG", "HEIC", "MP4". */
  val format: String?,
  /** Misure e peso, nella riga della scheda: "12 MP", "4000 × 3000", "3,60 MB"; per un video "4K", "3840 × 2160", "2:14", "120 MB". */
  val specs: List<String>,
  /** I tratti, come capsule: Ultra HDR, Panorama, Foto in movimento, Flash. */
  val traits: List<String>,
  val edited: Boolean,
  val video: Boolean,
  /** L'EXIF si sta leggendo: segnaposto al posto dei valori dello scatto. */
  val shotPending: Boolean,
  val fileName: String?,
  /** L'ultima cartella del percorso sul telefono ("Camera"), non il percorso. */
  val folder: String?,
  /** Una nota da leggere (la foto in movimento il cui video non si riconosce). */
  val note: String?,
) {
  val hasShot: Boolean get() = camera != null || lens != null || strip.isNotEmpty()

  /** La scheda dello scatto ha qualcosa da dire (o lo sta per dire). */
  val hasCard: Boolean get() = hasShot || shotPending || format != null || specs.isNotEmpty() || traits.isNotEmpty() || edited
}

/** Costruisce il pannello da [inputs]. Funzione pura (le risorse vengono da [format]). */
internal fun buildInfoModel(format: ViewerFormat, inputs: InfoInputs): InfoModel {
  val res = format.resources
  val details = inputs.details
  val capture = details?.captureTime
  val date = capture?.let { format.panelDate(it) } ?: inputs.localDay.takeIf { it > 0 }?.let { format.panelDate(it) }
  val timeLine = capture?.let { format.panelTimeLine(it) }

  val place = inputs.place
  val placeLine = when {
    place != null -> listOfNotNull(place.name, place.admin1.takeIf { it != place.name }, place.countryName).distinct().take(PLACE_PARTS).joinToString(", ")
    else -> inputs.placeName
  }?.takeIf { it.isNotBlank() }

  val video = inputs.kind == MediaKind.VIDEO
  val exif = inputs.exif
  val camera = exif?.let { format.camera(it) }
  val lens = exif?.lens?.trim()?.takeIf { it.isNotEmpty() }
  val strip = exif?.let { format.shotStrip(it) }.orEmpty()

  val specs = buildList {
    if (details != null) {
      val dims = format.dimensionPills(details, video = video)
      // La foto: prima i megapixel (si leggono come "che foto e'"), poi le misure. Il video: prima il nome della risoluzione (gia' cosi').
      addAll(if (video) dims else dims.asReversed())
      details.durationMs?.takeIf { it > 0L && video }?.let { add(GalleryVideoTime.format(it)) }
      details.sizeBytes?.let { add(format.fileSize(it)) }
    }
  }
  val traits = buildList {
    if (details != null) addAll(traits(format, details.traits, inputs.kind))
    if (exif?.flashFired == true) add(res.getString(R.string.viewer_flash_on))
  }

  val folder = details?.folder?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
  return InfoModel(
    date = date,
    timeLine = timeLine,
    estimated = capture?.isEstimated == true,
    place = placeLine,
    camera = camera,
    lens = lens,
    strip = strip,
    format = details?.mime?.let { format.formatName(it) },
    specs = specs,
    traits = traits,
    edited = details?.isEdited == true,
    video = video,
    shotPending = inputs.exifPending && exif == null && !video,
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
