package dev.pampa.mycloudgallery.core.metadata.strip

import android.media.MediaMetadataRetriever
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.PreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import dev.pampa.mycloudgallery.core.metadata.write.FileMediaBytes
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5, §6.1 "Posizione che esce nelle condivisioni"): il RICONTROLLO. "Il file condiviso non contiene
// GPS (verificato)": si dimostra, non si presume. Una copia senza posizione si rilegge con piu' lettori **indipendenti**, e la condivisione
// parte solo se tutti dicono "nessuna posizione":
//  - l'estrattore dell'app (lo stesso che legge il telefono e il NAS) e, per le foto, anche il decodificatore: la copia deve essere ancora un media;
//  - `ExifInterface` per le foto (un altro lettore dell'EXIF, scritto da altri) e `MediaMetadataRetriever` per i video (il lettore del sistema);
//  - una scansione grezza dei byte (`RawLocationRecheck`): l'XMP e le scatole dei video, che gli altri non guardano.
// Un lettore che non riesce a leggere la copia non assolve: e' un "non so", e la condivisione si ferma (si fallisce chiusi).

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

  override fun check(file: File, mime: String): LocationVerdict = when (ImageLocationStrip.exifHasGps(file)) {
    true -> LocationVerdict.HAS_LOCATION
    false -> LocationVerdict.CLEAR
    null -> LocationVerdict.UNREADABLE
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
 * Il lettore grezzo, sempre in coda a quelli iniettati: guarda i byte, non si fida di nessun parser. Foto: l'XMP (in ogni suo pacchetto) e
 * le scatole dei video incorporati; video: le scatole di posizione, le chiavi di QuickTime, l'XMP e le tracce di metadati a tempo.
 */
class RawLocationRecheck : LocationRecheck {

  override fun appliesTo(mime: String): Boolean = true

  override fun check(file: File, mime: String): LocationVerdict = try {
    RandomAccessFile(file, "r").use { raf ->
      val raw = RawFile(raf)
      val head = raw.read(0, SNIFF_BYTES)
      val sniffed = Sniffer.sniff(head, mime)
      when (sniffed.type) {
        ContainerType.JPEG, ContainerType.PNG, ContainerType.WEBP -> imageVerdict(raw, sniffed.type)
        ContainerType.MP4 -> when {
          !IsoLocation.plan(raw, 0, raw.length).isClean -> LocationVerdict.HAS_LOCATION
          // Un video senza indice (`moov`) non si riproduce: non e' una copia da consegnare.
          !IsoLocation.hasMovie(raw, 0, raw.length) -> LocationVerdict.UNREADABLE
          else -> LocationVerdict.CLEAR
        }
        else -> LocationVerdict.UNREADABLE
      }
    }
  } catch (_: IOException) {
    LocationVerdict.UNREADABLE
  } catch (_: SecurityException) {
    LocationVerdict.UNREADABLE
  }

  private fun imageVerdict(raw: RawFile, type: ContainerType): LocationVerdict {
    if (ImageXmp.hasGps(raw, type)) return LocationVerdict.HAS_LOCATION
    for (region in EmbeddedIso.regions(raw)) {
      if (!IsoLocation.plan(raw, region.start, region.end).isClean) return LocationVerdict.HAS_LOCATION
    }
    return LocationVerdict.CLEAR
  }

  private companion object {
    const val SNIFF_BYTES = 256
  }
}

/** I lettori di serie: l'estrattore dell'app, `ExifInterface` per le foto, `MediaMetadataRetriever` per i video. Li lega `:app`. */
object LocationRechecks {

  fun standard(extractor: MetadataExtractor, decoder: PreviewDecoder?, zone: ZoneId): List<LocationRecheck> =
    listOf(ExtractorLocationRecheck(extractor, decoder, zone), ExifInterfaceLocationRecheck(), MediaMetadataLocationRecheck())
}
