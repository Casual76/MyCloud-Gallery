package dev.pampa.mycloudgallery.core.metadata.write

import androidx.exifinterface.media.ExifInterface
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataWriter
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Fase 5B, P8 (docs/fase5-progetto.md §1.2 "core-metadata", §2.4 "Correzioni dall'app", D19, D28, Q6 (a), L40): lo SCRITTORE vero delle
// correzioni di data, luogo e rotazione, con `ExifInterface` (gia' nell'APK, nessuna dipendenza nuova). Solo JPEG, PNG e WebP: per
// HEIC, RAW e video la correzione resta nell'app e nell'indice e il file non si tocca (risposta Q6 (a) dell'utente).
//
// **Mai sul file vero** (contratto `MetadataWriter`): si copia il sorgente in una destinazione NUOVA (creata in esclusiva: mai
// sostituire), si scrive li', si rilegge con `ExifInterface` che i valori ci siano; se qualcosa va storto la destinazione si toglie e il
// sorgente resta com'era. Il chiamante (le correzioni di core-backup, P8) non si fida comunque: rilegge la copia con l'estrattore dell'app
// e la decodifica ([ExtractingFileCheck]) prima che valga. Nessun log con dati dell'utente (date, coordinate, nomi).

/**
 * Lo scrittore dei metadati per JPEG, PNG e WebP (D19, Q6 (a)). Il tipo si legge dai **byte** del sorgente (`Sniffer`), mai dal nome ne' dal
 * MIME dichiarato: un `.jpg` che dentro e' altro non si scrive. Sincrono e bloccante (file locali): il chiamante lo gira su un dispatcher di
 * I/O.
 *
 * @param maxBytes il tetto del sorgente (prima di allocare o copiare): un "JPEG" da GB non e' una foto da correggere.
 */
class ExifMetadataWriter(private val maxBytes: Long = MAX_SOURCE_BYTES) : MetadataWriter {

  override fun canWrite(mime: String): Boolean = mime.lowercase(Locale.ROOT) in WRITABLE_MIMES

  override fun apply(src: File, dst: File, change: MetadataChange): WriteOutcome {
    val type = sniff(src) ?: return WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (type !in WRITABLE_TYPES) return WriteOutcome.Unsupported(WriteUnsupported.FORMAT)
    if (change.isEmpty) return WriteOutcome.Unsupported(WriteUnsupported.NOTHING_TO_DO)
    if (src.length() > maxBytes) return WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    // La destinazione nasce qui, in esclusiva: una che c'e' gia' non si tocca (nessuna sostituzione).
    val created = try {
      dst.createNewFile()
    } catch (_: IOException) {
      return WriteOutcome.Failed(WriteFailure.IO)
    } catch (_: SecurityException) {
      return WriteOutcome.Failed(WriteFailure.IO)
    }
    if (!created) return WriteOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    var keep = false
    try {
      try {
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
      } catch (_: IOException) {
        return WriteOutcome.Failed(WriteFailure.IO)
      }
      val exif = try {
        ExifInterface(dst)
      } catch (_: IOException) {
        return WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
      } catch (_: RuntimeException) {
        // ExifInterface su un file malformato puo' lanciare altro che IOException: per noi e' un sorgente che non si legge.
        return WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
      }
      val written = try {
        write(exif, change)
        exif.saveAttributes()
        true
      } catch (_: IOException) {
        false
      } catch (_: RuntimeException) {
        false
      }
      if (!written) return WriteOutcome.Failed(WriteFailure.IO)
      // Rilettura con ExifInterface: i valori chiesti devono esserci. Se non ci sono, questa variante del formato non si scrive (la
      // correzione resta nell'app, come per i formati della Q6).
      if (!readsBack(dst, change)) return WriteOutcome.Unsupported(WriteUnsupported.FORMAT)
      keep = true
      return WriteOutcome.Written(dst.length())
    } finally {
      if (!keep) dst.delete()
    }
  }

  /** Il contenitore dai primi byte, o null se il sorgente non si legge. */
  private fun sniff(src: File): ContainerType? = try {
    if (!src.isFile) {
      null
    } else {
      val head = ByteArray(HEAD_BYTES)
      val n = src.inputStream().use { input -> input.readNBytesCompat(head) }
      Sniffer.sniff(head.copyOf(n), null).type
    }
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }

  private fun write(exif: ExifInterface, change: MetadataChange) {
    change.takenAtUtcMillis?.let { utc -> writeDate(exif, utc, change.offsetMinutes) }
    when (val geo = change.geo) {
      GeoChange.Keep -> Unit
      GeoChange.Clear -> LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
      is GeoChange.Set -> {
        exif.setLatLong(geo.point.latitude, geo.point.longitude)
        val altitude = geo.point.altitudeMeters
        if (altitude != null) {
          exif.setAltitude(altitude)
        } else {
          // L'altitudine di prima era del luogo di prima.
          exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
          exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
        }
      }
    }
    change.orientation?.let { exif.setAttribute(ExifInterface.TAG_ORIENTATION, it.exif.toString()) }
  }

  /**
   * La data di scatto: `DateTimeOriginal` e `DateTimeDigitized` nell'ora del luogo, con il loro fuso (`OffsetTime*`) e i millisecondi
   * (`SubSecTime*`). Senza un fuso nella correzione vale quello che il file aveva, altrimenti l'UTC. Se il file ha l'ora GPS, si sposta
   * anche quella (l'estrattore la usa per il fuso: due date in disaccordo darebbero un'ora sbagliata).
   */
  private fun writeDate(exif: ExifInterface, utcMillis: Long, offsetMinutes: Int?) {
    val offset = offsetMinutes ?: parseOffset(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) ?: 0
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(utcMillis), ZoneOffset.ofTotalSeconds(offset * 60))
    val date = EXIF_DATE.format(local)
    val offsetText = formatOffset(offset)
    val subSec = "%03d".format(Locale.ROOT, Math.floorMod(utcMillis, 1000L))
    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetText)
    exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)
    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetText)
    exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSec)
    if (exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP) != null || exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP) != null) {
      val utc = LocalDateTime.ofInstant(Instant.ofEpochMilli(utcMillis), ZoneOffset.UTC)
      exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, GPS_DATE.format(utc))
      exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "%02d:%02d:%02d".format(Locale.ROOT, utc.hour, utc.minute, utc.second))
    }
  }

  /** I valori chiesti si rileggono dalla destinazione (un `ExifInterface` nuovo, sul file scritto). */
  private fun readsBack(dst: File, change: MetadataChange): Boolean = try {
    val exif = ExifInterface(dst)
    val dateOk = change.takenAtUtcMillis == null || exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null
    val geoOk = when (val geo = change.geo) {
      GeoChange.Keep -> true
      GeoChange.Clear -> exif.latLong == null
      is GeoChange.Set -> exif.latLong?.let { abs(it[0] - geo.point.latitude) < GEO_TOLERANCE && abs(it[1] - geo.point.longitude) < GEO_TOLERANCE } == true
    }
    val orientationOk = change.orientation == null ||
      exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) == change.orientation!!.exif
    dateOk && geoOk && orientationOk
  } catch (_: IOException) {
    false
  } catch (_: RuntimeException) {
    false
  }

  companion object {
    /** I tipi che si scrivono nel file (Q6 (a)): gli altri restano corretti solo nell'app e nell'indice (L40). */
    val WRITABLE_MIMES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    private val WRITABLE_TYPES = setOf(ContainerType.JPEG, ContainerType.PNG, ContainerType.WEBP)

    /** Una foto da correggere sta ben sotto questo tetto (un JPEG da 200 MP pesa ~60 MB). */
    const val MAX_SOURCE_BYTES: Long = 256L shl 20

    private const val HEAD_BYTES = 64

    /** ~1 m: i razionali EXIF del GPS hanno una precisione molto migliore. */
    private const val GEO_TOLERANCE = 1e-5

    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
    private val GPS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT)

    /**
     * Le etichette della posizione che "togli il luogo" svuota: coordinate, altitudine, direzione, destinazione, velocita', area e metodo.
     * L'ora GPS resta (non e' un luogo).
     */
    private val LOCATION_TAGS: List<String> = listOf(
      ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
      ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
      ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
      ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_PROCESSING_METHOD,
      ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
      ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
      ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
      ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
      ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
      ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
      ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
      ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    /** "+01:00" → 60; null se assente o malformato. */
    internal fun parseOffset(text: String?): Int? {
      val m = text?.trim()?.let { OFFSET.matchEntire(it) } ?: return null
      val sign = if (m.groupValues[1] == "-") -1 else 1
      val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
      return (sign * minutes).takeIf { abs(it) <= MetadataChange.MAX_OFFSET_MINUTES }
    }

    internal fun formatOffset(minutes: Int): String {
      val sign = if (minutes < 0) "-" else "+"
      val a = abs(minutes)
      return "%s%02d:%02d".format(Locale.ROOT, sign, a / 60, a % 60)
    }

    private val OFFSET = Regex("^([+-])(\\d{2}):(\\d{2})$")
  }
}

/** Legge fino a riempire [buffer] (o alla fine del flusso): i byte letti. `readNBytes` non c'e' su tutte le API Android. */
private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
  var total = 0
  while (total < buffer.size) {
    val n = read(buffer, total, buffer.size - total)
    if (n < 0) break
    total += n
  }
  return total
}
