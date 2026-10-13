package dev.pampa.mycloudgallery.core.metadata.format

import dev.pampa.mycloudgallery.core.model.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cio' che serve dell'EXIF, gia' interpretato ma **non ancora giudicato**: una data assurda resta una data (la
 * giudica `CaptureTimeResolver`, E-M9), un GPS fuori scala o a (0, 0) invece e' gia' scartato qui, perche' non
 * si puo' nemmeno costruire un [GeoPoint].
 *
 * Le date seguono la regola della Fase 3 (§2.2): `DateTimeOriginal` con il suo `OffsetTimeOriginal`; se manca,
 * `DateTimeDigitized` (`CreateDate`) con il suo `OffsetTimeDigitized`. Il `DateTime` dell'IFD0 non si usa mai: e' la data di
 * ultima modifica del programma che ha riscritto il file, non quella dello scatto.
 */
internal class ExifFacts(
  val make: String?,
  val model: String?,
  val lens: String?,
  /** Il valore EXIF `Orientation` (1..8), se c'e' ed e' valido. */
  val orientation: Int?,
  val local: LocalDateTime?,
  val offsetMinutes: Int?,
  val subSecondMs: Int?,
  val gpsUtc: Instant?,
  val gps: GeoPoint?,
  val fNumber: Double?,
  val exposureTime: String?,
  val iso: Int?,
  val focalLengthMm: Double?,
  val flashFired: Boolean?,
  /** Larghezza e altezza dichiarate dal file (per i TIFF/DNG sono le uniche; per gli altri formati valgono dopo quelle del contenitore). */
  val declaredSize: Pair<Int, Int>?,
  /** Un DNG: ha il tag `DNGVersion`. */
  val isDng: Boolean,
  /** Il JPEG di anteprima dell'IFD1, se richiesto e presente. */
  val thumbnail: ByteArray?,
) {
  companion object {

    val EMPTY = ExifFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, null)

    // Tag dell'IFD Exif.
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_DATE_ORIGINAL = 0x9003
    private const val TAG_DATE_DIGITIZED = 0x9004
    private const val TAG_OFFSET_ORIGINAL = 0x9011
    private const val TAG_OFFSET_DIGITIZED = 0x9012
    private const val TAG_SUBSEC_ORIGINAL = 0x9291
    private const val TAG_SUBSEC_DIGITIZED = 0x9292
    private const val TAG_FLASH = 0x9209
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_PIXEL_X = 0xA002
    private const val TAG_PIXEL_Y = 0xA003
    private const val TAG_LENS_MODEL = 0xA434
    private const val TAG_ISO_SPEED = 0x8833

    // Tag dell'IFD GPS.
    private const val GPS_LAT_REF = 0x0001
    private const val GPS_LAT = 0x0002
    private const val GPS_LON_REF = 0x0003
    private const val GPS_LON = 0x0004
    private const val GPS_ALT_REF = 0x0005
    private const val GPS_ALT = 0x0006
    private const val GPS_TIME = 0x0007
    private const val GPS_DATE = 0x001D

    private const val MAX_THUMBNAIL = 256 * 1024
    private const val MAX_STRIP_PREVIEW = 1024 * 1024
    private const val TAG_NEW_SUBFILE_TYPE = 0x00FE
    private const val TAG_COMPRESSION = 0x0103

    suspend fun read(tiff: TiffFile, withThumbnail: Boolean = false): ExifFacts {
      val ifd0 = tiff.ifd0
      val exif = tiff.exif
      val orientation = tiff.int(ifd0[TiffFile.TAG_ORIENTATION])?.toInt()?.takeIf { it in 1..8 }

      var local: LocalDateTime? = null
      var offsetTag: TiffEntry? = null
      var subSecTag: TiffEntry? = null
      if (exif != null) {
        local = parseDateTime(tiff.ascii(exif[TAG_DATE_ORIGINAL]))
        if (local != null) {
          offsetTag = exif[TAG_OFFSET_ORIGINAL]
          subSecTag = exif[TAG_SUBSEC_ORIGINAL]
        } else {
          local = parseDateTime(tiff.ascii(exif[TAG_DATE_DIGITIZED]))
          if (local != null) {
            offsetTag = exif[TAG_OFFSET_DIGITIZED]
            subSecTag = exif[TAG_SUBSEC_DIGITIZED]
          }
        }
      }
      val offset = parseOffset(tiff.ascii(offsetTag))
      val subSec = parseSubSecond(tiff.ascii(subSecTag))

      val gpsIfd = tiff.gps
      val gps = if (gpsIfd != null) readPoint(tiff, gpsIfd) else null
      val gpsUtc = if (gpsIfd != null) readGpsUtc(tiff, gpsIfd) else null

      val flash = tiff.int(exif?.get(TAG_FLASH))?.let { it and 1L == 1L }
      var iso = tiff.int(exif?.get(TAG_ISO))?.toInt()?.takeIf { it in 1..4_000_000 && it != 65535 }
      if (iso == null) iso = tiff.int(exif?.get(TAG_ISO_SPEED))?.toInt()?.takeIf { it in 1..4_000_000 }

      var size = declaredSize(tiff)
      val isDng = ifd0[TiffFile.TAG_DNG_VERSION] != null
      if (isDng) cropSize(tiff)?.let { size = it }

      return ExifFacts(
        make = tiff.ascii(ifd0[TiffFile.TAG_MAKE]),
        model = tiff.ascii(ifd0[TiffFile.TAG_MODEL]),
        lens = tiff.ascii(exif?.get(TAG_LENS_MODEL)),
        orientation = orientation,
        local = local,
        offsetMinutes = offset,
        subSecondMs = subSec,
        gpsUtc = gpsUtc,
        gps = gps,
        fNumber = tiff.rational(exif?.get(TAG_F_NUMBER))?.takeIf { it > 0 }?.let { (it * 10).roundToInt() / 10.0 },
        exposureTime = tiff.rational(exif?.get(TAG_EXPOSURE_TIME))?.takeIf { it > 0 }?.let { formatExposure(it) },
        iso = iso,
        focalLengthMm = tiff.rational(exif?.get(TAG_FOCAL_LENGTH))?.takeIf { it > 0 }?.let { (it * 10).roundToInt() / 10.0 },
        flashFired = flash,
        declaredSize = size,
        isDng = isDng,
        thumbnail = if (withThumbnail) readThumbnail(tiff) else null,
      )
    }

    /** "YYYY:MM:DD HH:MM:SS" (e le varianti con `-` o `/`); null per le date vuote, a zero o impossibili (E-M9). */
    fun parseDateTime(text: String?): LocalDateTime? {
      if (text == null) return null
      val m = DATE_TIME.find(text) ?: return null
      return try {
        val (y, mo, d, h, mi, s) = m.destructured
        LocalDateTime.of(LocalDate.of(y.toInt(), mo.toInt(), d.toInt()), LocalTime.of(h.toInt(), mi.toInt(), s.toInt()))
      } catch (_: Exception) {
        null
      }
    }

    /** "+01:00", "-05:30", "+0100": minuti dall'UTC, entro ±15 ore; altro = assente. */
    fun parseOffset(text: String?): Int? {
      if (text == null) return null
      val m = OFFSET.matchEntire(text.trim()) ?: return null
      val sign = if (m.groupValues[1] == "-") -1 else 1
      val hours = m.groupValues[2].toInt()
      val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
      if (minutes > 59) return null
      val total = sign * (hours * 60 + minutes)
      return total.takeIf { it in -15 * 60..15 * 60 }
    }

    /** Le cifre dopo la virgola dei secondi ("12" = 120 ms), da 0 a 999. */
    fun parseSubSecond(text: String?): Int? {
      if (text == null) return null
      val digits = text.trim().takeWhile { it.isDigit() }.take(6)
      if (digits.isEmpty()) return null
      return (("0.$digits").toDouble() * 1000).toInt().coerceIn(0, 999)
    }

    private val DATE_TIME = Regex("""^\s*(\d{4})[:\-/](\d{1,2})[:\-/](\d{1,2})[ T](\d{1,2}):(\d{1,2}):(\d{1,2})""")
    private val OFFSET = Regex("""^([+-])(\d{1,2}):?(\d{2})?$""")

    private suspend fun readPoint(tiff: TiffFile, gps: Ifd): GeoPoint? {
      val lat = degrees(tiff.rationals(gps[GPS_LAT])) ?: return null
      val lon = degrees(tiff.rationals(gps[GPS_LON])) ?: return null
      val latSign = if (tiff.ascii(gps[GPS_LAT_REF])?.startsWith("S", ignoreCase = true) == true) -1 else 1
      val lonSign = if (tiff.ascii(gps[GPS_LON_REF])?.startsWith("W", ignoreCase = true) == true) -1 else 1
      val latitude = latSign * lat
      val longitude = lonSign * lon
      // (0, 0) e' cio' che molte fotocamere scrivono senza un fix: non e' un posto.
      if (latitude == 0.0 && longitude == 0.0) return null
      if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null
      val alt = tiff.rational(gps[GPS_ALT])?.let { value ->
        val below = tiff.int(gps[GPS_ALT_REF]) == 1L
        if (below) -value else value
      }?.takeIf { abs(it) < 100_000 }
      return GeoPoint(latitude, longitude, alt)
    }

    private fun degrees(parts: List<Double>): Double? {
      if (parts.isEmpty() || parts.any { it.isNaN() || it.isInfinite() }) return null
      val deg = parts[0]
      val min = parts.getOrElse(1) { 0.0 }
      val sec = parts.getOrElse(2) { 0.0 }
      if (deg < 0 || min < 0 || min >= 60.0001 || sec < 0 || sec >= 60.0001) return null
      return deg + min / 60.0 + sec / 3600.0
    }

    private suspend fun readGpsUtc(tiff: TiffFile, gps: Ifd): Instant? {
      val dateText = tiff.ascii(gps[GPS_DATE]) ?: return null
      val dm = GPS_DATE_RE.find(dateText) ?: return null
      val time = tiff.rationals(gps[GPS_TIME])
      if (time.size < 3 || time.any { it.isNaN() || it.isInfinite() }) return null
      return try {
        val date = LocalDate.of(dm.groupValues[1].toInt(), dm.groupValues[2].toInt(), dm.groupValues[3].toInt())
        val h = time[0].toInt()
        val m = time[1].toInt()
        val s = time[2]
        val whole = s.toInt().coerceAtMost(59)
        val millis = ((s - s.toInt()) * 1000).roundToInt().coerceIn(0, 999)
        date.atTime(h, m, whole).toInstant(ZoneOffset.UTC).plusMillis(millis.toLong())
      } catch (_: Exception) {
        null
      }
    }

    private val GPS_DATE_RE = Regex("""^\s*(\d{4})[:\-/](\d{1,2})[:\-/](\d{1,2})""")

    private suspend fun declaredSize(tiff: TiffFile): Pair<Int, Int>? {
      var best: Pair<Int, Int>? = null
      fun consider(candidate: Pair<Int, Int>?) {
        if (candidate == null) return
        val current = best
        if (current == null || candidate.first.toLong() * candidate.second > current.first.toLong() * current.second) best = candidate
      }
      consider(tiff.sizeOf(tiff.ifd0))
      for (sub in tiff.subIfds) consider(tiff.sizeOf(sub))
      val exif = tiff.exif
      if (exif != null) {
        val w = tiff.int(exif[TAG_PIXEL_X])
        val h = tiff.int(exif[TAG_PIXEL_Y])
        if (w != null && h != null && w in 1..TiffFile.MAX_SIDE && h in 1..TiffFile.MAX_SIDE) consider(w.toInt() to h.toInt())
      }
      return best
    }

    /** `DefaultCropSize` di un DNG (SHORT, LONG o RATIONAL a due valori): la dimensione dell'immagine sviluppata. */
    private suspend fun cropSize(tiff: TiffFile): Pair<Int, Int>? {
      for (ifd in listOf(tiff.ifd0) + tiff.subIfds) {
        val entry = ifd[TiffFile.TAG_DEFAULT_CROP_SIZE] ?: continue
        val values = if (entry.type == TiffFile.TYPE_RATIONAL) {
          tiff.rationals(entry, 2).map { it.roundToInt().toLong() }
        } else {
          tiff.ints(entry, 2)
        }
        if (values.size == 2 && values[0] in 1..TiffFile.MAX_SIDE && values[1] in 1..TiffFile.MAX_SIDE) return values[0].toInt() to values[1].toInt()
      }
      return null
    }

    /** Una miniatura JPEG: quella dell'IFD1 (Exif) o, in un DNG, l'anteprima ridotta in una striscia sola. */
    private suspend fun readThumbnail(tiff: TiffFile): ByteArray? = readIfd1Thumbnail(tiff) ?: readStripPreview(tiff)

    private suspend fun readIfd1Thumbnail(tiff: TiffFile): ByteArray? {
      val ifd1 = tiff.ifd1 ?: return null
      val offset = tiff.int(ifd1[TiffFile.TAG_JPEG_OFFSET]) ?: return null
      val length = tiff.int(ifd1[TiffFile.TAG_JPEG_LENGTH]) ?: return null
      return jpegAt(tiff, offset, length)
    }

    /**
     * L'anteprima di un DNG: un IFD di "immagine ridotta" (`NewSubfileType` con il bit 0), compressione JPEG (6 o 7), una sola
     * striscia di al massimo [MAX_STRIP_PREVIEW] byte che comincia con `FF D8`. I dati grezzi (JPEG senza perdite, molto piu' grandi,
     * e quasi sempre a tessere) restano fuori.
     */
    private suspend fun readStripPreview(tiff: TiffFile): ByteArray? {
      for (ifd in listOf(tiff.ifd0) + tiff.subIfds) {
        val subfile = tiff.int(ifd[TAG_NEW_SUBFILE_TYPE]) ?: continue
        if (subfile and 1L == 0L) continue
        val compression = tiff.int(ifd[TAG_COMPRESSION]) ?: continue
        if (compression != 6L && compression != 7L) continue
        val offsets = tiff.ints(ifd[TiffFile.TAG_STRIP_OFFSETS], 2)
        val counts = tiff.ints(ifd[TiffFile.TAG_STRIP_BYTE_COUNTS], 2)
        if (offsets.size != 1 || counts.size != 1) continue
        jpegAt(tiff, offsets[0], counts[0], MAX_STRIP_PREVIEW)?.let { return it }
      }
      return null
    }

    private suspend fun jpegAt(tiff: TiffFile, offset: Long, length: Long, max: Int = MAX_THUMBNAIL): ByteArray? {
      if (offset <= 0 || length < 4 || length > max) return null
      val bytes = tiff.ra.read(tiff.base + offset, length.toInt())
      if (bytes.size != length.toInt() || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
      return bytes
    }

    /** 1/250, 1/3, 2, 2.5, 30: il tempo di posa come lo scrivono le fotocamere. */
    fun formatExposure(seconds: Double): String {
      if (seconds >= 1.0) {
        val rounded = (seconds * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
      }
      return "1/" + (1.0 / seconds).roundToInt()
    }
  }
}
