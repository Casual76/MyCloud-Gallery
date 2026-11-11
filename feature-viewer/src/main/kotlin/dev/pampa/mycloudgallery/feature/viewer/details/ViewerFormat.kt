package dev.pampa.mycloudgallery.feature.viewer.details

import android.content.Context
import android.text.format.Formatter
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Le frasi del viewer, costruite dai dati: la data, l'ora con il fuso dello scatto, il peso, i megapixel, l'esposizione.
 * Niente di Compose qui: una classe sola, con la lingua ([locale]) e le risorse esplicite, cosi' si prova con dati veri
 * e si legge in un colpo solo come il viewer parla.
 *
 * **Il fuso e' quello dello scatto, non quello del telefono** (E-M14): "ora di Oslo, UTC+1" dice a che ora e' stata
 * scattata la foto dove l'ha scattata. [CaptureTime] porta lo scarto in minuti; il nome del posto, se c'e', lo mette
 * il chiamante.
 */
internal class ViewerFormat(
  private val context: Context,
  // P11: l'app e' solo in italiano. Le date passano da `GalleryDates` e i numeri (megapixel, coordinate) usano la stessa
  // lingua, anche con il telefono in inglese.
  private val locale: Locale = GalleryDates.LOCALE,
  /** Il fuso del telefono: il pannello lo nomina solo se lo scatto ne ha un altro (CU-5). */
  private val phoneZone: ZoneId = ZoneId.systemDefault(),
) {

  internal val resources get() = context.resources

  /** La data per il titolo della barra: "12 marzo 2024". */
  fun dateTitle(time: CaptureTime): String = GalleryDates.date(time.localDateTime)

  /** La data del giorno `aaaammgg` dell'indice, quando i dettagli non sono ancora arrivati. */
  fun dateTitle(localDay: Int): String? = parseLocalDay(localDay)?.let { GalleryDates.date(it) }

  /** L'ora: "12:41". */
  fun time(time: CaptureTime): String = GalleryDates.time(time.localDateTime)

  /** La riga del pannello: "mercoledì 12 marzo 2024, 12:41". */
  fun dateTimeLine(time: CaptureTime): String = GalleryDates.weekdayDate(time.localDateTime) + ", " + GalleryDates.time(time.localDateTime)

  /** Il sottotitolo della barra: "12:41 · Oslo", o solo l'ora se il luogo non e' noto. */
  fun subtitle(time: CaptureTime?, placeName: String?): String? = when {
    time != null && placeName != null -> resources.getString(R.string.viewer_subtitle_time_place, time(time), placeName)
    time != null -> time(time)
    else -> placeName
  }

  /**
   * La riga sotto la data nel pannello (CU-5): "12:41". Il fuso si dice **solo se e' diverso da quello del telefono** in quel momento ("12:41 ·
   * ora locale, UTC+9"), senza ripetere il luogo che sta nella riga sotto; per una data stimata, da dove viene la stima.
   */
  fun panelTimeLine(time: CaptureTime): String {
    val clock = time(time)
    if (time.isEstimated) return resources.getString(R.string.viewer_info_time_line, clock, estimatedReason(time.source))
    val phoneMinutes = phoneZone.rules.getOffset(Instant.ofEpochMilli(time.utcMillis)).totalSeconds / SECONDS_PER_MINUTE
    if (phoneMinutes == time.offsetMinutes) return clock
    return resources.getString(R.string.viewer_info_time_local, clock, offsetLabel(time.offsetMinutes))
  }

  /**
   * La didascalia sotto la data: "ora di Oslo, UTC+1", o solo "UTC+1" senza luogo. Per una data **stimata** dice da dove
   * viene la stima (il nome del file, la data del sistema, quella di modifica): non e' un'ora dello scatto.
   */
  fun zoneCaption(time: CaptureTime, placeName: String?): String {
    if (time.isEstimated) return estimatedReason(time.source)
    val offset = offsetLabel(time.offsetMinutes)
    return if (placeName != null) resources.getString(R.string.viewer_zone_place, placeName, offset) else offset
  }

  /** "UTC+1", "UTC+5:30", "UTC-5", "UTC". */
  fun offsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes > 0) "+" else "-"
    val hours = abs(minutes) / MINUTES_PER_HOUR
    val rest = abs(minutes) % MINUTES_PER_HOUR
    return if (rest == 0) "UTC$sign$hours" else "UTC$sign$hours:" + String.format(Locale.ROOT, "%02d", rest)
  }

  private fun estimatedReason(source: CaptureTimeSource): String = resources.getString(
    when (source) {
      CaptureTimeSource.FILE_NAME -> R.string.viewer_estimated_name
      CaptureTimeSource.MEDIASTORE -> R.string.viewer_estimated_system
      CaptureTimeSource.FILE_MODIFIED -> R.string.viewer_estimated_modified
      else -> R.string.viewer_estimated_generic
    },
  )

  /** "4000 × 3000 · 12 MP". Senza le dimensioni, null. */
  fun dimensions(details: MediaDetails): String? {
    val w = details.orientedWidth ?: return null
    val h = details.orientedHeight ?: return null
    val size = resources.getString(R.string.viewer_dimensions, w, h)
    val mp = details.megapixels ?: return size
    val text = if (mp >= 10.0) mp.roundToInt().toString() else String.format(locale, "%.1f", mp)
    return size + " · " + resources.getString(R.string.viewer_megapixels, text)
  }

  /** Il peso: "3,4 MB" (con la virgola italiana anche se il telefono e' in un'altra lingua). */
  fun fileSize(bytes: Long): String = Formatter.formatFileSize(localized, bytes)

  /** Il contesto nella lingua della galleria, per i formattatori di Android che leggono la lingua dal contesto. */
  private val localized: Context by lazy {
    val configuration = android.content.res.Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    context.createConfigurationContext(configuration)
  }

  /** Il formato per un lettore umano: JPEG, HEIC, PNG, WebP, GIF, DNG, MP4, MOV. */
  fun formatName(mime: String?): String? {
    val lower = mime?.lowercase(Locale.ROOT) ?: return null
    return when (lower) {
      "image/jpeg", "image/jpg" -> "JPEG"
      "image/heic", "image/heif" -> "HEIC"
      "image/png" -> "PNG"
      "image/webp" -> "WebP"
      "image/gif" -> "GIF"
      "image/avif" -> "AVIF"
      "image/x-adobe-dng", "image/dng" -> "DNG"
      "video/mp4" -> "MP4"
      "video/quicktime" -> "MOV"
      "video/x-matroska" -> "MKV"
      "video/3gpp" -> "3GP"
      "video/webm" -> "WebM"
      else -> lower.substringAfter('/').uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }
  }

  /** "Samsung Galaxy S25": marca e modello senza ripetere la marca se il modello la contiene gia'. */
  fun camera(exif: ExifDetails): String? {
    val make = exif.make?.trim()?.takeIf { it.isNotEmpty() }
    val model = exif.model?.trim()?.takeIf { it.isNotEmpty() }
    return when {
      make != null && model != null ->
        if (model.startsWith(make, ignoreCase = true)) model else "${prettyMake(make)} $model"
      else -> model ?: make?.let(::prettyMake)
    }
  }

  /** Le marche si scrivono in maiuscolo nell'EXIF ("SAMSUNG", "SONY"): si portano alla forma normale ("Samsung", "Sony"). */
  private fun prettyMake(make: String): String =
    if (make == make.uppercase(locale)) make.lowercase(locale).replaceFirstChar { it.titlecase(locale) } else make

  /** "f/1,8 · 1/250 s · ISO 50": l'esposizione, solo i pezzi che la fotocamera ha scritto. */
  fun exposure(exif: ExifDetails): String? {
    val parts = listOfNotNull(
      exif.fNumber?.let { resources.getString(R.string.viewer_exposure_f, trimmed(it)) },
      exif.exposureTime?.takeIf { it.isNotBlank() }?.let { resources.getString(R.string.viewer_exposure_time, it) },
      exif.iso?.let { resources.getString(R.string.viewer_exposure_iso, it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  /**
   * H2: i dati di scatto come pillole del pannello, solo quelli che la fotocamera ha scritto: "Obiettivo principale", "f/1,8", "1/250 s",
   * "ISO 50", "6,3 mm", "Flash". Il flash si dice solo se e' scattato ("Senza flash" e' rumore).
   */
  fun shotPills(exif: ExifDetails): List<String> = listOfNotNull(
    exif.lens?.trim()?.takeIf { it.isNotEmpty() },
    exif.fNumber?.let { resources.getString(R.string.viewer_exposure_f, trimmed(it)) },
    exif.exposureTime?.takeIf { it.isNotBlank() }?.let { resources.getString(R.string.viewer_exposure_time, it) },
    exif.iso?.let { resources.getString(R.string.viewer_exposure_iso, it) },
    exif.focalLengthMm?.let { resources.getString(R.string.viewer_focal_mm, trimmed(it)) },
    resources.getString(R.string.viewer_flash_on).takeIf { exif.flashFired == true },
  )

  /** H2: le misure come pillole: "4000 × 3000" e "12 MP". */
  fun dimensionPills(details: MediaDetails): List<String> {
    val w = details.orientedWidth ?: return emptyList()
    val h = details.orientedHeight ?: return emptyList()
    val size = resources.getString(R.string.viewer_dimensions, w, h)
    val mp = details.megapixels ?: return listOf(size)
    val text = if (mp >= 10.0) mp.roundToInt().toString() else String.format(locale, "%.1f", mp)
    return listOf(size, resources.getString(R.string.viewer_megapixels, text))
  }

  /** H2: il titolo del pannello, "Martedì 12 marzo 2024" (il giorno con la maiuscola: e' l'inizio di un titolo). */
  fun panelDate(time: CaptureTime): String = GalleryDates.weekdayDate(time.localDateTime).replaceFirstChar { it.titlecase(locale) }

  /** H2: lo stesso dal giorno dell'indice, finche' i dettagli non arrivano: "12 marzo 2024". */
  fun panelDate(localDay: Int): String? = parseLocalDay(localDay)?.let { GalleryDates.weekdayDate(it.atStartOfDay()).replaceFirstChar { c -> c.titlecase(locale) } }

  /** "6,3 mm · Flash": la focale e il flash, se noti. */
  fun lensLine(exif: ExifDetails): String? {
    val parts = listOfNotNull(
      exif.lens?.trim()?.takeIf { it.isNotEmpty() },
      exif.focalLengthMm?.let { resources.getString(R.string.viewer_focal_mm, trimmed(it)) },
      exif.flashFired?.let { resources.getString(if (it) R.string.viewer_flash_on else R.string.viewer_flash_off) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  /** "59,9127° N, 10,7461° E". Dato sensibile: si scrive solo nel pannello, mai in un log. */
  fun coordinates(point: GeoPoint): String {
    val lat = String.format(locale, "%.4f", abs(point.latitude))
    val lon = String.format(locale, "%.4f", abs(point.longitude))
    val ns = resources.getString(if (point.latitude >= 0) R.string.viewer_coord_north else R.string.viewer_coord_south)
    val ew = resources.getString(if (point.longitude >= 0) R.string.viewer_coord_east else R.string.viewer_coord_west)
    return resources.getString(R.string.viewer_coordinates, lat, ns, lon, ew)
  }

  /** La descrizione di una pagina per il lettore di schermo: "Foto, 12 marzo 2024, Oslo". */
  fun pageDescription(kind: MediaKind, localDay: Int, placeName: String?): String {
    val kindText = resources.getString(
      when (kind) {
        MediaKind.VIDEO -> R.string.viewer_kind_video
        MediaKind.MOTION_PHOTO -> R.string.viewer_kind_motion
        MediaKind.ANIMATED_IMAGE -> R.string.viewer_kind_animated
        else -> R.string.viewer_kind_photo
      },
    )
    return listOfNotNull(kindText, dateTitle(localDay), placeName).joinToString(", ")
  }

  private fun trimmed(value: Double): String {
    val rounded = (value * TENTH).roundToInt() / TENTH
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else String.format(locale, "%.1f", rounded)
  }

  private fun parseLocalDay(day: Int): LocalDate? = try {
    LocalDate.of(day / YEAR_DIVISOR, (day / MONTH_DIVISOR) % MONTH_DIVISOR, day % MONTH_DIVISOR)
  } catch (_: DateTimeException) {
    null
  }

  private companion object {
    const val MINUTES_PER_HOUR = 60
    const val TENTH = 10.0
    const val YEAR_DIVISOR = 10_000
    const val MONTH_DIVISOR = 100
  }
}

private const val SECONDS_PER_MINUTE = 60
