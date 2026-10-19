package dev.pampa.mycloudgallery.core.metadata.strip

import androidx.exifinterface.media.ExifInterface
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import java.io.File
import java.io.IOException

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5, L41): togliere la posizione da una COPIA di JPEG, PNG o WebP. Tre passi, nell'ordine:
//  1. l'EXIF, con `ExifInterface` (D28: nessuna dipendenza nuova): tutte le etichette del blocco GPS, la nota del produttore (che alcune
//     marche usano per le coordinate) e, se c'e', niente altro; il resto dell'EXIF (data, fotocamera, orientamento) resta;
//  2. l'XMP, sul posto (`ImageXmp`): `exif:GPSLatitude` e sorelle diventano spazi;
//  3. la parte VIDEO di una motion photo, se il file ne ha una (Samsung, Google): e' un MP4 dentro il file, con le sue scatole di posizione.
// Il risultato non vale finche' il ricontrollo (`LocationRecheck`) non l'ha letto con piu' lettori.

internal object ImageLocationStrip {

  /** Tutte le etichette del blocco GPS dell'EXIF: coordinate, quota, direzione, velocita', area, ora e data GPS, metodo, errore. */
  private val GPS_TAGS: List<String> = listOf(
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

  /** Il blocco GPS dell'EXIF ha ancora qualcosa? Per il secondo lettore. */
  fun exifHasGps(file: File): Boolean? = try {
    val exif = ExifInterface(file)
    GPS_TAGS.any { exif.getAttribute(it) != null } || exif.latLong != null
  } catch (_: IOException) {
    null
  } catch (_: RuntimeException) {
    // Un file che ExifInterface non sa leggere non e' un file di cui si possa dire "senza posizione": il chiamante fallisce chiuso.
    null
  }

  /** Esito di [run]. */
  enum class Result { DONE, UNSUPPORTED, IO }

  /**
   * Toglie la posizione da [file] (la copia) di tipo [type]. Lavora sul posto; la lunghezza puo' cambiare solo per l'EXIF (`ExifInterface`
   * lo riscrive), mai per l'XMP e per la parte video, che si puliscono sul posto.
   */
  fun run(file: File, type: ContainerType): Result {
    try {
      if (!clearExif(file)) return Result.IO
      java.io.RandomAccessFile(file, "rw").use { raf ->
        val raw = RawFile(raf)
        if (ImageXmp.scrub(raw, type).blocked) return Result.UNSUPPORTED
        // Una motion photo porta un MP4 incorporato: le sue scatole di posizione si tolgono come quelle di un video.
        for (region in EmbeddedIso.regions(raw)) {
          val plan = IsoLocation.plan(raw, region.start, region.end)
          if (plan.blockers.isNotEmpty()) return Result.UNSUPPORTED
          IsoLocation.apply(raw, plan)
        }
      }
      return Result.DONE
    } catch (_: IOException) {
      return Result.IO
    } catch (_: SecurityException) {
      return Result.IO
    }
  }

  /** Toglie dal file le etichette GPS e la nota del produttore con `ExifInterface`. Falso se il file non si legge o non si riscrive. */
  private fun clearExif(file: File): Boolean {
    val exif = try {
      ExifInterface(file)
    } catch (_: IOException) {
      return false
    } catch (_: RuntimeException) {
      return false
    }
    val dirty = GPS_TAGS.any { exif.getAttribute(it) != null } || exif.getAttribute(ExifInterface.TAG_MAKER_NOTE) != null
    // Niente da togliere: il file non si riscrive (meno modifiche, meno rischi).
    if (!dirty) return true
    return try {
      GPS_TAGS.forEach { exif.setAttribute(it, null) }
      exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, null)
      exif.saveAttributes()
      true
    } catch (_: IOException) {
      false
    } catch (_: RuntimeException) {
      false
    }
  }
}

/** Una regione `[start, end)` di un file che e' un MP4 incorporato (la parte video di una motion photo). */
internal class IsoRegion(val start: Long, val end: Long)

internal object EmbeddedIso {

  private const val BLOCK = 1 shl 20
  private const val MAX_FTYP = 1024L

  /**
   * Gli MP4 incorporati in un file: cerca `ftyp` con un riquadro davanti di dimensione plausibile e controlla che da li' i riquadri si
   * susseguano (almeno `moov` o `mdat`). Il video di una motion photo comincia cosi', qualunque sia il formato dichiarato dal produttore
   * (Google, MicroVideo, il trailer SEF di Samsung): non serve capire come lo annuncia.
   */
  fun regions(file: RawFile): List<IsoRegion> {
    val out = ArrayList<IsoRegion>()
    val length = file.length
    var base = 0L
    var lastEnd = 0L
    while (base < length) {
      val block = file.read(base, BLOCK + 8)
      if (block.size < 8) break
      var i = 0
      while (i + 4 <= block.size) {
        if (block[i] == 'f'.code.toByte() && block[i + 1] == 't'.code.toByte() && block[i + 2] == 'y'.code.toByte() && block[i + 3] == 'p'.code.toByte()) {
          val start = base + i - 4
          if (start >= lastEnd && start >= 0) {
            region(file, start, length)?.let {
              out += it
              lastEnd = it.end
            }
          }
        }
        i++
        if (i >= BLOCK) break
      }
      base += BLOCK
    }
    return out
  }

  private fun region(file: RawFile, start: Long, fileEnd: Long): IsoRegion? {
    val first = IsoLocation.readBox(file, start, fileEnd) ?: return null
    if (first.type != "ftyp" || first.end - first.start !in 12..MAX_FTYP) return null
    var end = first.end
    var seen = 0
    var movie = false
    var count = 0
    while (end + 8 <= fileEnd && count++ < 4096) {
      val box = IsoLocation.readBox(file, end, fileEnd) ?: break
      if (box.type == "moov" || box.type == "mdat" || box.type == "moof") movie = true
      seen++
      end = box.end
    }
    return if (movie && seen >= 1) IsoRegion(start, end) else null
  }
}
