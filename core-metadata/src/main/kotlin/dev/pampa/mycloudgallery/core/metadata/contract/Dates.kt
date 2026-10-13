package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// La data di scatto (docs/fase3-progetto.md §2.2, tabella "Fonte della data"). Le firme sono il contratto che
// `core-mediastore` e le schermate usano da P0; i corpi di CaptureTimeResolver e FileNameDates sono di P3.

/**
 * Tutto cio' che il file e il MediaStore dicono sulla data, **non ancora interpretato**. Il resolver sceglie la
 * fonte migliore e il fuso. Ogni campo puo' mancare.
 *
 * @property exifLocal `DateTimeOriginal`: l'ora locale che segnava la fotocamera, senza fuso.
 * @property exifOffsetMinutes `OffsetTimeOriginal`, se c'e' (in minuti).
 * @property exifSubSecondMs `SubSecTimeOriginal`, per l'ordine delle raffiche.
 * @property gpsUtc `GPSDateStamp` + `GPSTimeStamp`: un istante UTC affidabile, da cui si ricava il fuso.
 * @property gps il punto GPS (sensibile), per il fuso del luogo.
 * @property videoUtcMs la data UTC del contenitore di un video (E-M14).
 * @property videoGps il punto ISO 6709 del video, se c'e'.
 * @property fileName la data letta dal nome del file.
 * @property mediaStoreDateTakenMs `DATE_TAKEN` di MediaStore.
 * @property fileModifiedMs la data di modifica del file: l'ultimo ripiego (E-M8).
 * @property scanZone il fuso del telefono alla lettura (E-P5).
 * @property nowMs l'orologio di riferimento: le date oltre "ora + 48 h" sono assurde (E-M9).
 */
class CaptureTimeInputs(
  val exifLocal: LocalDateTime? = null,
  val exifOffsetMinutes: Int? = null,
  val exifSubSecondMs: Int? = null,
  val gpsUtc: Instant? = null,
  val gps: GeoPoint? = null,
  val videoUtcMs: Long? = null,
  val videoGps: GeoPoint? = null,
  val fileName: FileNameDate? = null,
  val mediaStoreDateTakenMs: Long? = null,
  val fileModifiedMs: Long? = null,
  val scanZone: ZoneId,
  val nowMs: Long,
) {
  // Contiene punti GPS: niente nel toString.
  override fun toString(): String = "CaptureTimeInputs([dati nascosti])"
}

/**
 * Il fuso dei file "vicini" (stessa cartella e stesso modello, entro 6 ore, con fuso esatto): per un'immagine senza
 * fuso e senza GPS e' la miglior ipotesi, meglio di quella del telefono di oggi (E-P5).
 */
data class NeighborOffsets(val offsetMinutes: Int) {
  init {
    require(offsetMinutes in -18 * 60..18 * 60) { "Fuso orario fuori dall'intervallo ±18 h." }
  }
}

/** Da quale schema di nome viene una data (tabella dei nomi dei file, §2.2). */
enum class FileNamePattern {
  WHATSAPP,
  SCREENSHOT,
  SCREEN_RECORDING,
  SAMSUNG_CAMERA,
  AOSP_CAMERA,
  PIXEL,
  SIGNAL,
  TELEGRAM,
}

/**
 * La data letta da un nome di file. [time] manca per WhatsApp (il nome porta il giorno della **ricezione**, quindi
 * la data e' `ESTIMATED`); [isUtc] vale per i Pixel (`PXL_...` e' in UTC) e per nessun altro.
 */
data class FileNameDate(
  val date: LocalDate,
  val time: LocalTime?,
  val isUtc: Boolean,
  val pattern: FileNamePattern,
)

/**
 * I nomi dei file che portano una data: la tabella "Nome del file" di `docs/fase3-progetto.md` §2.2. Il confronto non
 * distingue maiuscole e minuscole. Un nome che non e' in tabella, o una data impossibile (mese 13, ora 25), da' null:
 * il resolver passa alla fonte successiva. Gli anni assurdi (1970, 2099) li giudica il resolver (E-M9), non questa tabella.
 *
 * | Nome | Schema | Data |
 * |---|---|---|
 * | `IMG-20240312-WA0001.jpg`, `VID-...-WA...` | `WHATSAPP` | solo il giorno (e' la ricezione) |
 * | `Screenshot_20240312_101530_App.jpg`, `Screenshot_20240312-101530.png`, `Screenshot_2024-03-12-10-15-30-...` | `SCREENSHOT` | ora locale |
 * | `Screen_Recording_20240312-101530_App.mp4`, `Screenrecorder-2024-03-12-10-15-30-...` | `SCREEN_RECORDING` | ora locale |
 * | `20240312_101530.jpg`, `..._001.jpg`, `...(1).jpg` | `SAMSUNG_CAMERA` | ora locale |
 * | `IMG_20240312_101530.jpg`, `VID_...`, `PANO_...`, `MVIMG_...`, `BURST...` | `AOSP_CAMERA` | ora locale |
 * | `PXL_20240312_101530123.jpg` | `PIXEL` | **UTC** |
 * | `signal-2024-03-12-101530.jpg` | `SIGNAL` | ora locale |
 * | `photo_2024-03-12_10-15-30.jpg`, `video_...` | `TELEGRAM` | ora locale |
 */
object FileNameDates {

  private class Rule(
    val pattern: FileNamePattern,
    val regex: Regex,
    val hasTime: Boolean,
    val utc: Boolean = false,
    val millisGroup: Int = 0,
  )

  private val IC = RegexOption.IGNORE_CASE

  private val RULES: List<Rule> = listOf(
    Rule(FileNamePattern.WHATSAPP, Regex("""^(?:IMG|VID)-(\d{4})(\d{2})(\d{2})-WA\d+""", IC), hasTime = false),
    Rule(FileNamePattern.SCREENSHOT, Regex("""^Screenshot[_ ](\d{4})(\d{2})(\d{2})[-_](\d{2})(\d{2})(\d{2})(?!\d)""", IC), hasTime = true),
    Rule(FileNamePattern.SCREENSHOT, Regex("""^Screenshot[_ ](\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""", IC), hasTime = true),
    Rule(FileNamePattern.SCREEN_RECORDING, Regex("""^Screen[_ ]?Record(?:ing)?[_ ](\d{4})(\d{2})(\d{2})[-_](\d{2})(\d{2})(\d{2})(?!\d)""", IC), hasTime = true),
    Rule(FileNamePattern.SCREEN_RECORDING, Regex("""^Screenrecorder-(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""", IC), hasTime = true),
    Rule(FileNamePattern.PIXEL, Regex("""^PXL_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(\d{3})?""", IC), hasTime = true, utc = true, millisGroup = 7),
    Rule(FileNamePattern.SIGNAL, Regex("""^signal-(\d{4})-(\d{2})-(\d{2})-(\d{2})-?(\d{2})-?(\d{2})""", IC), hasTime = true),
    Rule(FileNamePattern.TELEGRAM, Regex("""^(?:photo|video|animation)_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})""", IC), hasTime = true),
    Rule(FileNamePattern.AOSP_CAMERA, Regex("""^(?:IMG|VID|PANO|MVIMG)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?!\d)""", IC), hasTime = true),
    Rule(FileNamePattern.AOSP_CAMERA, Regex("""(?:^|_)BURST(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{3})?""", IC), hasTime = true, millisGroup = 7),
    Rule(FileNamePattern.SAMSUNG_CAMERA, Regex("""^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?!\d)"""), hasTime = true),
  )

  fun parse(name: String): FileNameDate? {
    for (rule in RULES) {
      val m = rule.regex.find(name) ?: continue
      return try {
        val g = m.groupValues
        val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
        if (!rule.hasTime) {
          FileNameDate(date, null, isUtc = false, pattern = rule.pattern)
        } else {
          val millis = if (rule.millisGroup > 0 && g[rule.millisGroup].isNotEmpty()) g[rule.millisGroup].toInt() else 0
          val time = LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].toInt(), millis * 1_000_000)
          FileNameDate(date, time, isUtc = rule.utc, pattern = rule.pattern)
        }
      } catch (_: Exception) {
        null
      }
    }
    return null
  }
}

/**
 * Sceglie la data di scatto: l'ordine delle fonti, il fuso, le date assurde. E' la tabella del §2.2.
 *
 * | Fonte (in ordine) | Fuso | Esito |
 * |---|---|---|
 * | EXIF `DateTimeOriginal` + `OffsetTimeOriginal` | quello scritto | `EXACT`, `EXIF_WITH_OFFSET` |
 * | EXIF senza fuso + `GPSDateStamp`/`GPSTimeStamp` | ora locale meno UTC del GPS, a 15 minuti, entro ±14 h | `EXACT`, `EXIF_WITH_OFFSET` |
 * | EXIF senza fuso, con GPS | fuso del luogo (`TimeZoneLookup`) | `OFFSET_ASSUMED`, `EXIF_LOCAL` |
 * | EXIF senza fuso e senza GPS | vicini, poi telefono alla lettura | `OFFSET_ASSUMED`, `EXIF_LOCAL` |
 * | Video: data UTC del contenitore | GPS del video, poi vicini, poi telefono | `OFFSET_ASSUMED`, `VIDEO_UTC` |
 * | Nome del file | vicini, poi telefono | `ESTIMATED` se solo il giorno, `OFFSET_ASSUMED` se c'e' l'ora; `FILE_NAME` |
 * | `DATE_TAKEN` del MediaStore | vicini, poi telefono | `OFFSET_ASSUMED`, `MEDIASTORE` |
 * | Data del file | telefono | `ESTIMATED`, `FILE_MODIFIED` |
 *
 * **Date assurde** (E-M9): si scartano e si passa alla fonte successiva. Sono assurde le date vuote o a zero, prima del
 * 1900, a meno di un giorno dall'epoca, oltre "ora + 48 h"; per la data del file anche prima del 1995; per la data UTC di
 * un video anche prima del 1970 (un contenitore a zero vale 1904). Se nessuna fonte regge, l'ultimo ripiego e' "adesso",
 * sempre `ESTIMATED` (`FILE_MODIFIED`): una data c'e' sempre, ma e' dichiarata stimata.
 *
 * L'ora locale di un EXIF senza fuso e' **certa** (e' quella che segnava la fotocamera); cio' che si suppone e' il fuso.
 * Per un'ora che cade nel salto dell'ora legale si usa il fuso dopo il salto; nella sovrapposizione, il primo.
 * `SubSecTimeOriginal` si somma ai millisecondi dell'istante, per ordinare le raffiche.
 */
object CaptureTimeResolver {

  fun resolve(c: CaptureTimeInputs, zones: TimeZoneLookup, neighbors: NeighborOffsets?): CaptureTime {
    val now = c.nowMs

    c.exifLocal?.let { local ->
      if (!isAbsurd(local.toInstant(ZoneOffset.UTC).toEpochMilli(), now)) {
        val sub = (c.exifSubSecondMs ?: 0).coerceIn(0, 999)
        val written = c.exifOffsetMinutes?.takeIf { it in -MAX_OFFSET_MIN..MAX_OFFSET_MIN }
        if (written != null) {
          return fromLocal(local, sub, written, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
        }
        val fromGps = c.gpsUtc?.let { offsetFromGps(local, it) }
        if (fromGps != null) {
          return fromLocal(local, sub, fromGps, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
        }
        val placeZone = c.gps?.let { zones.zoneAt(it) }
        val assumed = placeZone?.let { offsetAt(it, local) } ?: neighbors?.offsetMinutes ?: offsetAt(c.scanZone, local)
        return fromLocal(local, sub, assumed, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
      }
    }

    c.videoUtcMs?.let { utc ->
      if (utc > 0 && !isAbsurd(utc, now)) {
        val zone = c.videoGps?.let { zones.zoneAt(it) }
        val offset = zone?.let { offsetAtInstant(it, utc) } ?: neighbors?.offsetMinutes ?: offsetAtInstant(c.scanZone, utc)
        return CaptureTime(utc, offset, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.VIDEO_UTC)
      }
    }

    c.fileName?.let { name -> fromFileName(name, c, neighbors)?.let { return it } }

    c.mediaStoreDateTakenMs?.let { taken ->
      if (!isAbsurd(taken, now)) {
        val offset = neighbors?.offsetMinutes ?: offsetAtInstant(c.scanZone, taken)
        return CaptureTime(taken, offset, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.MEDIASTORE)
      }
    }

    val modified = c.fileModifiedMs?.takeIf { !isAbsurd(it, now) && it >= FILE_DATE_FLOOR_MS } ?: now
    return CaptureTime(modified, offsetAtInstant(c.scanZone, modified), CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
  }

  private fun fromFileName(name: FileNameDate, c: CaptureTimeInputs, neighbors: NeighborOffsets?): CaptureTime? {
    val time = name.time
    if (time == null) {
      // Solo il giorno: mezzogiorno locale, cosi' il giorno resta quello anche se il fuso supposto e' sbagliato.
      val local = name.date.atTime(12, 0)
      if (isAbsurd(local.toInstant(ZoneOffset.UTC).toEpochMilli(), c.nowMs)) return null
      val offset = neighbors?.offsetMinutes ?: offsetAt(c.scanZone, local)
      return fromLocal(local, 0, offset, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_NAME)
    }
    val local = name.date.atTime(time)
    val asUtc = local.toInstant(ZoneOffset.UTC).toEpochMilli()
    if (isAbsurd(asUtc, c.nowMs)) return null
    if (name.isUtc) {
      val offset = neighbors?.offsetMinutes ?: offsetAtInstant(c.scanZone, asUtc)
      return CaptureTime(asUtc, offset, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.FILE_NAME)
    }
    val offset = neighbors?.offsetMinutes ?: offsetAt(c.scanZone, local)
    return fromLocal(local, 0, offset, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.FILE_NAME)
  }

  /** Ora locale meno UTC del GPS, a multipli di 15 minuti; null se esce da ±14 h (il GPS non e' affidabile). */
  private fun offsetFromGps(local: LocalDateTime, gpsUtc: Instant): Int? {
    val minutes = (local.toEpochSecond(ZoneOffset.UTC) - gpsUtc.epochSecond) / 60.0
    val rounded = (Math.round(minutes / 15.0) * 15).toInt()
    return rounded.takeIf { it in -14 * 60..14 * 60 }
  }

  private fun fromLocal(
    local: LocalDateTime,
    subMs: Int,
    offsetMinutes: Int,
    confidence: CaptureTimeConfidence,
    source: CaptureTimeSource,
  ): CaptureTime {
    val utc = local.toInstant(ZoneOffset.UTC).toEpochMilli() - offsetMinutes * 60_000L + subMs
    return CaptureTime(utc, offsetMinutes, confidence, source)
  }

  /** Il fuso di [zone] all'ora locale [local] (nel salto dell'ora legale si usa quello dopo il salto). */
  private fun offsetAt(zone: ZoneId, local: LocalDateTime): Int = ZonedDateTime.ofLocal(local, zone, null).offset.totalSeconds / 60

  private fun offsetAtInstant(zone: ZoneId, utcMs: Long): Int {
    val clamped = utcMs.coerceIn(MIN_ZONE_INSTANT_MS, MAX_ZONE_INSTANT_MS)
    return zone.rules.getOffset(Instant.ofEpochMilli(clamped)).totalSeconds / 60
  }

  /** Assurda (E-M9): prima del 1900, a meno di un giorno dall'epoca (vuota, a zero, 1970), oltre "ora + 48 h". */
  private fun isAbsurd(ms: Long, nowMs: Long): Boolean =
    ms < FLOOR_1900_MS || kotlin.math.abs(ms) <= ONE_DAY_MS || ms > nowMs + 2 * ONE_DAY_MS

  private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
  private const val FLOOR_1900_MS = -2_208_988_800_000L
  private const val FILE_DATE_FLOOR_MS = 788_918_400_000L // 1995-01-01T00:00Z
  private const val MAX_OFFSET_MIN = 18 * 60
  private const val MIN_ZONE_INSTANT_MS = -2_208_988_800_000L
  private const val MAX_ZONE_INSTANT_MS = 32_503_680_000_000L // anno 3000
}
