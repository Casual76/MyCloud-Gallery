package dev.pampa.mycloudgallery.core.metadata.extract

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.model.GeoPoint
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * L'adattatore vero di [VideoProbe]: `MediaMetadataRetriever` su una `MediaDataSource` (`MediaBytes.dataSource()`), cosi'
 * funziona su un file del telefono e, in Fase 4, sul NAS. Non gira su JVM (il fake e' `FakeVideoProbe`); la lettura delle
 * stringhe che restituisce e' in [VideoMetadataParsing], provata su JVM.
 *
 * Blocca il thread che lo chiama: l'estrattore lo esegue fuori dal thread principale. Un contenitore che non si apre
 * da' null, mai un'eccezione.
 */
class MediaMetadataVideoProbe : VideoProbe {

  override fun probe(bytes: MediaBytes): VideoFacts? {
    val retriever = MediaMetadataRetriever()
    return try {
      // Mai un'eccezione verso il codice nativo (stack trace su System.err in release): la sorgente non lancia ([QuietMediaDataSource]).
      retriever.setDataSource(QuietMediaDataSource(bytes.dataSource()))
      val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      val utc = VideoMetadataParsing.parseCreationDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
      val point = VideoMetadataParsing.parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
      val transfer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
      } else {
        null
      }
      VideoFacts(
        durationMs = duration.coerceAtLeast(0),
        rotation = VideoMetadataParsing.normalizeRotation(rotation),
        utcMs = utc,
        iso6709 = point,
        hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
        codecMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
      )
    } catch (_: Exception) {
      null
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {
        // Un rilascio fallito non cambia l'esito.
      }
    }
  }
}

/** Le stringhe che `MediaMetadataRetriever` restituisce, lette senza Android. */
object VideoMetadataParsing {

  private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.(\d{1,3}))?Z?$""")
  private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?/?$""")

  /**
   * `METADATA_KEY_DATE`: "20240312T091530.000Z" (UTC). Un contenitore senza data (o a zero, che vale il 1904) da' null: una data
   * prima del 1970 non e' una data di ripresa.
   */
  fun parseCreationDate(text: String?): Long? {
    if (text == null) return null
    val m = DATE.matchEntire(text.trim()) ?: return null
    return try {
      val g = m.groupValues
      val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
      val millis = g[7].padEnd(3, '0').ifEmpty { "0" }.toInt()
      val time = LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].toInt(), millis * 1_000_000)
      val ms = date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
      ms.takeIf { it > 0 }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * `METADATA_KEY_LOCATION`, ISO 6709: "+37.7749-122.4194/" (gradi decimali), "+3746.49-12225.16/" (gradi e minuti) o con i secondi.
   * (0, 0) e valori fuori scala danno null: molte fotocamere scrivono (0, 0) senza un fix.
   */
  fun parseIso6709(text: String?): GeoPoint? {
    if (text == null) return null
    val m = ISO_6709.matchEntire(text.trim()) ?: return null
    val lat = degrees(m.groupValues[1], latitude = true) ?: return null
    val lon = degrees(m.groupValues[2], latitude = false) ?: return null
    if (lat == 0.0 && lon == 0.0) return null
    if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
    val altitude = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { abs(it) < 100_000 }
    return GeoPoint(lat, lon, altitude)
  }

  /** 0, 90, 180, 270; qualunque altro valore (anche negativo) si riporta al multiplo di 90 piu' vicino. */
  fun normalizeRotation(degrees: Int): Int {
    val d = ((degrees % 360) + 360) % 360
    return ((d + 45) / 90 * 90) % 360
  }

  private fun degrees(token: String, latitude: Boolean): Double? {
    val sign = if (token.startsWith("-")) -1.0 else 1.0
    val body = token.substring(1)
    val integerDigits = body.substringBefore('.').length
    val degreeDigits = if (latitude) 2 else 3
    val value = body.toDoubleOrNull() ?: return null
    return when (integerDigits) {
      degreeDigits -> sign * value
      degreeDigits + 2 -> {
        val deg = (value / 100).toInt()
        sign * (deg + (value - deg * 100) / 60.0)
      }
      degreeDigits + 4 -> {
        val deg = (value / 10_000).toInt()
        val rest = value - deg * 10_000
        val min = (rest / 100).toInt()
        sign * (deg + min / 60.0 + (rest - min * 100) / 3600.0)
      }
      else -> null
    }
  }
}
