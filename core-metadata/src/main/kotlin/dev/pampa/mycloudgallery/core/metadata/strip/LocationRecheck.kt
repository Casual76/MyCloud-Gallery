package dev.pampa.mycloudgallery.core.metadata.strip

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.PreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.write.FileMediaBytes
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5, §6.1 "Posizione che esce nelle condivisioni"), con le correzioni 5B (R1, R2): il RICONTROLLO. "Il
// file condiviso non contiene GPS (verificato)": si dimostra, non si presume. Una copia senza posizione si rilegge con piu' lettori, e la
// condivisione parte solo se tutti dicono "nessuna posizione":
//  - per primo, sempre, il rilevatore indipendente (`LocationTraceRecheck`, `LocationTraces.kt`): scritto da zero, non riusa niente dello stripper,
//    guarda i byte del file finale (EXIF GPS, nomi XMP con gps/latitude/longitude, ISO 6709, `©xyz`, `loci`, tracce non audio/video);
//  - l'estrattore dell'app (lo stesso che legge il telefono e il NAS) e, per le foto, anche il decodificatore: la copia deve essere ancora un media;
//  - `ExifInterface` per le foto (un altro lettore dell'EXIF, scritto da altri) e `MediaMetadataRetriever` per i video (il lettore del sistema).
// Prima i "lettori indipendenti" non lo erano: per i video due erano lo stesso `MediaMetadataRetriever` e il lettore grezzo era il piano dello
// stripper, quindi dove lo stripper non guardava non guardava nessuno (R1). Un lettore che non riesce a leggere la copia non assolve: e' un "non
// so", e la condivisione si ferma (si fallisce chiusi).

/** Il parere di un lettore su una copia. */
enum class LocationVerdict {
  /** Nessuna posizione, e la copia si legge (e si decodifica o si riproduce). */
  CLEAR,

  /** Una posizione c'e' ancora. */
  HAS_LOCATION,

  /** Il lettore non e' riuscito a leggere la copia: non si puo' dire che sia senza posizione. */
  UNREADABLE,
}

/** Un lettore che ricontrolla una copia da cui si e' tolta la posizione. Sincrono e bloccante: il chiamante lo gira su un dispatcher di I/O. */
interface LocationRecheck {

  /** Se questo lettore sa leggere il tipo [mime] (un'immagine o un video). Un lettore che non si applica non conta fra i due richiesti. */
  fun appliesTo(mime: String): Boolean

  fun check(file: File, mime: String): LocationVerdict
}

private fun isVideo(mime: String): Boolean = mime.lowercase(Locale.ROOT).startsWith("video/")

/**
 * Il primo lettore: l'estrattore dell'app. Una copia vale se l'estrattore la legge come media, non trova una posizione e (per una foto) il
 * decodificatore ne ricava un'anteprima: file ancora decodificabile.
 */
class ExtractorLocationRecheck(
  private val extractor: MetadataExtractor,
  private val decoder: PreviewDecoder?,
  private val zone: ZoneId,
) : LocationRecheck {

  override fun appliesTo(mime: String): Boolean = true

  override fun check(file: File, mime: String): LocationVerdict {
    val bytes = try {
      FileMediaBytes(file)
    } catch (_: IOException) {
      return LocationVerdict.UNREADABLE
    }
    return bytes.use {
      val read = try {
        runBlocking { extractor.extract(bytes, ExtractHints(mime = mime, displayName = "copia", relativePath = "", scanZone = zone)) }
      } catch (_: Exception) {
        return@use LocationVerdict.UNREADABLE
      }
      when (read) {
        is Extraction.Read -> when {
          read.metadata.location != null -> LocationVerdict.HAS_LOCATION
          read.kind.isVideo -> LocationVerdict.CLEAR
          decoder != null && decoder.decode(bytes, DECODE_SIDE) == null -> LocationVerdict.UNREADABLE
          else -> LocationVerdict.CLEAR
        }
        else -> LocationVerdict.UNREADABLE
      }
    }
  }

  private companion object {
    /** Un'anteprima piccola basta a dire che i pixel si decodificano. */
    const val DECODE_SIDE = 64
  }
}

/** Il secondo lettore delle foto: `ExifInterface`, che non e' il nostro lettore di EXIF. Qualunque etichetta GPS presente e' una posizione. */
class ExifInterfaceLocationRecheck : LocationRecheck {

  override fun appliesTo(mime: String): Boolean = !isVideo(mime)

  override fun check(file: File, mime: String): LocationVerdict = try {
    val exif = ExifInterface(file)
    if (GPS_TAGS.any { exif.getAttribute(it) != null } || exif.latLong != null) LocationVerdict.HAS_LOCATION else LocationVerdict.CLEAR
  } catch (_: IOException) {
    LocationVerdict.UNREADABLE
  } catch (_: RuntimeException) {
    // Un file che ExifInterface non sa leggere non e' un file di cui si possa dire "senza posizione": il chiamante fallisce chiuso.
    LocationVerdict.UNREADABLE
  }

  private companion object {
    /** Tutte le etichette del blocco GPS dell'EXIF: coordinate, quota, direzione, velocita', area, ora e data GPS, metodo, errore. */
    val GPS_TAGS: List<String> = listOf(
      ExifInterface.TAG_GPS_VERSION_ID,
      ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
      ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
      ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
      ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
      ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
      ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
      ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK,
      ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
      ExifInterface.TAG_GPS_MAP_DATUM,
      ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
      ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
      ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
      ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
      ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
      ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )
  }
}

/**
 * Il secondo lettore dei video: `MediaMetadataRetriever`, il lettore del sistema (quello che usa anche l'estrattore, ma qui chiesto
 * direttamente). Il video deve aprirsi e avere una durata, e non deve dichiarare `METADATA_KEY_LOCATION`.
 */
class MediaMetadataLocationRecheck : LocationRecheck {

  override fun appliesTo(mime: String): Boolean = isVideo(mime)

  override fun check(file: File, mime: String): LocationVerdict {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.path)
      val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
      val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
      when {
        duration == null -> LocationVerdict.UNREADABLE
        // Qualunque stringa di posizione, valida o no, e' un resto: non si interpreta, si rifiuta.
        !location.isNullOrBlank() -> LocationVerdict.HAS_LOCATION
        else -> LocationVerdict.CLEAR
      }
    } catch (_: Exception) {
      LocationVerdict.UNREADABLE
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {
        // Un rilascio fallito non cambia l'esito.
      }
    }
  }
}

/**
 * I lettori di serie: l'estrattore dell'app, `ExifInterface` per le foto, `MediaMetadataRetriever` per i video. Li lega `:app`; il rilevatore
 * indipendente non sta qui perche' lo stripper lo mette sempre davanti da solo.
 */
object LocationRechecks {

  fun standard(extractor: MetadataExtractor, decoder: PreviewDecoder?, zone: ZoneId): List<LocationRecheck> =
    listOf(ExtractorLocationRecheck(extractor, decoder, zone), ExifInterfaceLocationRecheck(), MediaMetadataLocationRecheck())
}
