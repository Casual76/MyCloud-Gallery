package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.database.OriginDevice
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// LA GEOMETRIA DEGLI ALBUM SMART (§2.2 "Raggio", regole A e S3; D13). Distanze, centro di un luogo, e il confronto di un candidato con il luogo
// di una regola. Mai coordinate nei log (D37): niente di quello che c'e' qui scrive o stampa un punto.

internal object SmartGeo {
  private const val EARTH_KM = 6371.0088

  /** La distanza fra due punti in chilometri (haversine). */
  fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_KM * asin(min(1.0, sqrt(h)))
  }

  /**
   * Il centro di un gruppo di punti: la mediana delle coordinate (robusta ai pochi scatti lontani), con le longitudini misurate dal primo punto
   * cosi' un luogo a cavallo dell'antimeridiano non si spezza. Null senza punti.
   */
  fun medianCenter(points: List<GeoPoint>): GeoPoint? {
    if (points.isEmpty()) return null
    val lon0 = points.first().longitude
    val lats = points.map { it.latitude }.sorted()
    val lons = points.map { wrap(it.longitude - lon0) }.sorted()
    return GeoPoint(median(lats), wrap(median(lons) + lon0))
  }

  /** Il punto "in mezzo" a piu' centri (la media dei vettori sulla sfera): il centro di un viaggio in piu' luoghi. */
  fun middle(points: List<GeoPoint>): GeoPoint {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    for (p in points) {
      val lat = Math.toRadians(p.latitude)
      val lon = Math.toRadians(p.longitude)
      x += cos(lat) * cos(lon)
      y += cos(lat) * sin(lon)
      z += sin(lat)
    }
    val lon = atan2(y, x)
    val lat = atan2(z, sqrt(x * x + y * y))
    return GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon))
  }

  private fun median(sorted: List<Double>): Double =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

  private fun wrap(degrees: Double): Double {
    var d = degrees % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
  }
}

/** Dove sta un candidato rispetto al luogo di una regola. */
internal enum class PlaceFit {
  /** Entro il raggio (o nella regione, o nel paese): regola A. */
  IN,

  /** Fra 1 e 2 volte il raggio: S3. */
  NEAR,

  /** Il GPS contraddice il luogo. */
  FAR,
}

/**
 * Il confronto di un candidato **con GPS** con il luogo di una regola (§2.2). `AROUND`: la distanza dal centro; `REGION`/`COUNTRY`: il luogo
 * del candidato ([AlbumCandidate.placeId]) sta nella regione o nel paese. La regione si riconosce dai luoghi della regola e, se [lookup] li
 * conosce, anche da un luogo nuovo della stessa regione (un viaggio in un paese di Puglia mai visto prima non resta fuori).
 */
internal class PlaceMatcher(private val rule: PlaceRule, private val lookup: PlaceLookup?) {
  private val ids: Set<Int> = rule.placeIds.toSet()
  private val areas: Set<Pair<String?, String>> = when (rule.scope) {
    PlaceScope.AROUND -> emptySet()
    PlaceScope.REGION -> rule.placeIds.mapNotNull { lookup?.place(it) }.mapTo(HashSet()) { it.admin1 to it.country }
    PlaceScope.COUNTRY -> rule.placeIds.mapNotNull { lookup?.place(it) }.mapTo(HashSet()) { null to it.country }
  }
  private val known = HashMap<Int, Boolean>()

  /** Il confronto di [c], che deve avere il GPS. */
  fun fit(c: AlbumCandidate): PlaceFit {
    val gps = checkNotNull(c.gps) { "Il confronto col luogo vuole un candidato con il GPS." }
    return when (rule.scope) {
      PlaceScope.AROUND -> {
        val km = SmartGeo.distanceKm(rule.center, gps)
        when {
          km <= rule.radiusKm -> PlaceFit.IN
          km <= rule.radiusKm.toDouble() * SmartLimits.NEAR_MISS_FACTOR -> PlaceFit.NEAR
          else -> PlaceFit.FAR
        }
      }
      PlaceScope.REGION, PlaceScope.COUNTRY -> if (sameArea(c.placeId)) PlaceFit.IN else PlaceFit.FAR
    }
  }

  private fun sameArea(placeId: Int): Boolean {
    if (placeId == 0) return false
    if (placeId in ids) return true
    if (areas.isEmpty() || lookup == null) return false
    return known.getOrPut(placeId) {
      val place = lookup.place(placeId) ?: return@getOrPut false
      val key = if (rule.scope == PlaceScope.REGION) place.admin1 to place.country else null to place.country
      key in areas
    }
  }
}

/**
 * Il "dispositivo" di un media nel confronto della regola B (P1-7): il telefono ha due etichette (`phone` e `backup:<propria cartella>`: un media
 * che ha perso la copia sul telefono passa dall'una all'altra) e valgono lo stesso dispositivo. Il resto si confronta com'e'. Null = origine
 * sconosciuta (nessuna regola B per quel media). [ownBackupFolder] e' `AlbumFamily.backupFolderName()` (con o senza il prefisso `Backup/`).
 */
class DeviceNormalizer(ownBackupFolder: String?) {
  private val ownBackup: String? = ownBackupFolder?.let { folder ->
    val trimmed = folder.trim().trim('/').let { if (it.startsWith("Backup/", ignoreCase = true)) it.substring("Backup/".length) else it }
    Normalizer.normalize(trimmed.trim('/'), Normalizer.Form.NFC).lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
  }

  fun of(origin: String?): String? = when {
    origin == null -> null
    origin == OriginDevice.PHONE -> OriginDevice.PHONE
    ownBackup != null && origin == OriginDevice.backup(ownBackup) -> OriginDevice.PHONE
    else -> origin
  }
}
