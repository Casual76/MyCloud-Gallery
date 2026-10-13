package dev.pampa.mycloudgallery.core.places.testing

import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlaceMatch
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// I fake di core-places (D6 di Fase 2, D23 di Fase 3): in src/main, cosi' i moduli a valle (core-library,
// core-mediastore, feature-*) li riusano nei loro test senza cicli. Mai nella release: tools/check.ps1 fallisce se
// una classe di un package `testing` compare nel dex dell'APK di release.

/** Un luogo di prova con le sue coordinate. Gli id sono di prova: non coincidono necessariamente con il dump. */
class FakePlace(val place: Place, val latitude: Double, val longitude: Double)

/** Il mini-dataset di prova (dodici luoghi, quattro fusi) che la Libreria sintetica campiona (§5.2). */
object FakePlaceData {
  private fun place(id: Int, name: String, admin1: String?, cc: String, country: String, zone: String, lat: Double, lon: Double) =
    FakePlace(Place(PlaceId(id), name, admin1, cc, country, zone), lat, lon)

  val FIRENZE = place(3176959, "Firenze", "Toscana", "IT", "Italia", "Europe/Rome", 43.7696, 11.2558)
  val FIESOLE = place(3176885, "Fiesole", "Toscana", "IT", "Italia", "Europe/Rome", 43.8056, 11.2931)
  val MILANO = place(3173435, "Milano", "Lombardia", "IT", "Italia", "Europe/Rome", 45.4643, 9.1895)
  val SESTO = place(3166526, "Sesto San Giovanni", "Lombardia", "IT", "Italia", "Europe/Rome", 45.5342, 9.2339)
  val ROMA = place(3169070, "Roma", "Lazio", "IT", "Italia", "Europe/Rome", 41.8919, 12.5113)
  val OSLO = place(3143244, "Oslo", "Oslo", "NO", "Norvegia", "Europe/Oslo", 59.9127, 10.7461)
  val LONDRA = place(2643743, "Londra", "Inghilterra", "GB", "Regno Unito", "Europe/London", 51.5085, -0.1257)
  val MONACO = place(2867714, "Monaco di Baviera", "Baviera", "DE", "Germania", "Europe/Berlin", 48.1374, 11.5755)
  val LISBONA = place(2267057, "Lisbona", "Lisbona", "PT", "Portogallo", "Europe/Lisbon", 38.7167, -9.1333)
  val PARIGI = place(2988507, "Parigi", "Île-de-France", "FR", "Francia", "Europe/Paris", 48.8534, 2.3488)
  val TOKYO = place(1850147, "Tokyo", "Tokyo", "JP", "Giappone", "Asia/Tokyo", 35.6895, 139.6917)
  val NEW_YORK = place(5128581, "New York", "New York", "US", "Stati Uniti", "America/New_York", 40.7143, -74.0060)

  val ALL: List<FakePlace> = listOf(FIRENZE, FIESOLE, MILANO, SESTO, ROMA, OSLO, LONDRA, MONACO, LISBONA, PARIGI, TOKYO, NEW_YORK)

  /** Gli id, nell'ordine di [ALL]: la Libreria sintetica li campiona come `place_id`. */
  val IDS: IntArray = ALL.map { it.place.id.value }.toIntArray()
}

/**
 * Un geocoder, un cercatore di fusi e i nomi dei luoghi, tutti sopra il mini-dataset di prova ([FakePlaceData]).
 * La ricerca e' la piu' semplice possibile (distanza sulla sfera, luogo piu' vicino): non e' l'algoritmo di P4, e'
 * quanto basta per far vedere nomi e fusi veri nei test e negli screenshot.
 */
class FakePlaces(
  override val dataset: Int = 1,
  private val places: List<FakePlace> = FakePlaceData.ALL,
) : ReverseGeocoder, TimeZoneLookup, PlaceNames {

  private val byId: Map<Int, FakePlace> = places.associateBy { it.place.id.value }

  override fun nearest(p: GeoPoint): PlaceMatch? {
    val best = places.minByOrNull { distanceMeters(p.latitude, p.longitude, it.latitude, it.longitude) } ?: return null
    val distance = distanceMeters(p.latitude, p.longitude, best.latitude, best.longitude)
    val precision = when {
      distance <= CITY_M -> PlacePrecision.CITY
      distance <= NEAR_M -> PlacePrecision.NEAR
      distance <= REGION_M -> PlacePrecision.REGION_ONLY
      else -> return null
    }
    return PlaceMatch(best.place, distance.toInt(), precision)
  }

  override fun place(id: PlaceId): Place? = byId[id.value]?.place

  override fun zoneAt(p: GeoPoint): ZoneId? {
    val match = nearest(p) ?: return null
    return match.place.zone?.let { ZoneId.of(it) }
  }

  override fun label(ref: PlaceRef): String? = labelOfId(ref.geonameId)

  override fun labelOfId(geonameId: Int): String? = byId[geonameId]?.place?.name

  override fun summary(top: IntArray, distinct: Int): String {
    val names = top.toList().mapNotNull { labelOfId(it) }
    if (names.isEmpty() || distinct <= 0) return ""
    return when {
      distinct == 1 -> names[0]
      distinct == 2 && names.size >= 2 -> "${names[0]} e ${names[1]}"
      else -> "${names[0]} e altri ${distinct - 1} luoghi"
    }
  }

  private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1 * PI / 180
    val phi2 = lat2 * PI / 180
    val dPhi = (lat2 - lat1) * PI / 180
    val dLambda = (lon2 - lon1) * PI / 180
    val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
  }

  private companion object {
    const val EARTH_RADIUS_M = 6_371_000.0
    const val CITY_M = 15_000.0
    const val NEAR_M = 50_000.0
    const val REGION_M = 300_000.0
  }
}
