package dev.pampa.mycloudgallery.bench

import kotlin.math.abs
import kotlin.math.cos

/**
 * Le coordinate del dataset di misura (Fase 9, P12; `docs/fase9-progetto.md` §5.4: "il 60% con coordinate"). Le righe che hanno gia' un luogo (`place_id`: il
 * 60% della Libreria, id veri di GeoNames) ricevono anche una posizione **plausibile**: il centro della citta' di quell'id piu' uno scarto. Servono alla
 * mappa: l'indice dei punti (P3) e le bolle (P4) si misurano su 60 mila punti veri, raggruppati come le foto di una famiglia (molte nella citta', alcune
 * nei dintorni, poche lontano).
 *
 * - **Dati sintetici**: i centri sono quelli pubblici di 19 citta' (nessuna casa, nessun luogo dell'utente); lo scarto viene da un hash della riga, **mai
 *   dalla sequenza dei numeri casuali** del 13/10 (che resta la stessa: date, flag e luoghi di ogni riga non cambiano, quindi la griglia delle guardie e'
 *   quella di sempre).
 * - **Scarto**: 82% entro 0,04 gradi (un quartiere: le celle grandi della mappa), 15% entro 0,35 (i dintorni), 3% entro 2,5 (una gita). Triangolare, cosi'
 *   le foto si addensano al centro.
 */
internal object BenchGeo {

  /** Latitudine e longitudine, in gradi. */
  class Spot(val latitude: Double, val longitude: Double)

  /** La posizione della riga [row] che ha il luogo [placeId], o `null` se l'id non e' uno dei 19 (o e' 0: nessun luogo, nessuna posizione). */
  fun position(row: Int, placeId: Int): Spot? {
    val center = CENTERS[placeId] ?: return null
    val pick = unit(row, SALT_RADIUS)
    val radius = when {
      pick < NEIGHBOURHOOD_SHARE -> NEIGHBOURHOOD_DEG
      pick < NEIGHBOURHOOD_SHARE + SURROUNDINGS_SHARE -> SURROUNDINGS_DEG
      else -> TRIP_DEG
    }
    val dLat = (unit(row, SALT_LAT_A) + unit(row, SALT_LAT_B) - 1.0) * radius
    val dLon = (unit(row, SALT_LON_A) + unit(row, SALT_LON_B) - 1.0) * radius / cos(Math.toRadians(center.latitude)).coerceAtLeast(MIN_COS)
    val latitude = (center.latitude + dLat).coerceIn(-MAX_LAT, MAX_LAT)
    var longitude = center.longitude + dLon
    if (abs(longitude) > MAX_LON) longitude = if (longitude > 0) longitude - 2 * MAX_LON else longitude + 2 * MAX_LON
    return Spot(latitude, longitude)
  }

  /** Un numero in `[0, 1)` dalla riga e da un "sale": indipendente dalla sequenza casuale del piano. */
  private fun unit(row: Int, salt: Int): Double {
    var x = (row.toLong() + 1L) * -7046029254386353131L + salt * 0x5851F42D4C957F2DL
    x = x xor (x ushr 31)
    x *= -4658895280553007687L
    x = x xor (x ushr 29)
    return (x ushr 11) / (1L shl 53).toDouble()
  }

  private const val SALT_RADIUS = 21
  private const val SALT_LAT_A = 22
  private const val SALT_LAT_B = 23
  private const val SALT_LON_A = 24
  private const val SALT_LON_B = 25
  private const val NEIGHBOURHOOD_SHARE = 0.82
  private const val SURROUNDINGS_SHARE = 0.15
  private const val NEIGHBOURHOOD_DEG = 0.04
  private const val SURROUNDINGS_DEG = 0.35
  private const val TRIP_DEG = 2.5
  private const val MIN_COS = 0.2
  private const val MAX_LAT = 89.0
  private const val MAX_LON = 180.0

  /** I 19 id di GeoNames di `BenchSeeder.PLACES` con il centro della citta' (Roma, Milano, Napoli, Torino, Firenze, Bologna, Venezia, Palermo, Genova, Bari, Oslo, Londra, Parigi, Lisbona, Monaco, Madrid, Barcellona, Tokyo, New York). */
  private val CENTERS: Map<Int, Spot> = mapOf(
    3169070 to Spot(41.8919, 12.5113),
    3173435 to Spot(45.4643, 9.1895),
    3172394 to Spot(40.8518, 14.2681),
    3165524 to Spot(45.0705, 7.6868),
    3176959 to Spot(43.7714, 11.2542),
    3181928 to Spot(44.4939, 11.3430),
    3164603 to Spot(45.4375, 12.3358),
    2523920 to Spot(38.1157, 13.3615),
    3176219 to Spot(44.4056, 8.9463),
    3182351 to Spot(41.1171, 16.8719),
    3143244 to Spot(59.9127, 10.7461),
    2643743 to Spot(51.5072, -0.1276),
    2988507 to Spot(48.8566, 2.3522),
    2267057 to Spot(38.7223, -9.1393),
    2867714 to Spot(48.1374, 11.5755),
    3117735 to Spot(40.4168, -3.7038),
    3128760 to Spot(41.3874, 2.1686),
    1850147 to Spot(35.6762, 139.6503),
    5128581 to Spot(40.7128, -74.0060),
  )
}
