package dev.pampa.mycloudgallery.core.places.tool

import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Format
import dev.pampa.mycloudgallery.core.places.dataset.PlaceRow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I **distretti**: i borough, le circoscrizioni e le citta' satellite grandi che stanno dentro l'area d'influenza di una
 * citta' molto piu' grande. Times Square e' a Manhattan (1,5 milioni di abitanti, raggio d'influenza 6 km), ma il nome
 * principale deve essere New York (8,8 milioni, raggio 8 km): con la sola classifica pesata, Manhattan batte New York di
 * pochi centinaia di metri perche' il suo centroide e' piu' vicino. Un distretto resta nel file (si risolve per id) ma
 * **non compete**: lo segna il bit "secondario" del byte di classe.
 *
 * La regola, per principio e senza elenchi scritti a mano. Un luogo Q e' un distretto di un luogo C se:
 *
 * 1. sono dello stesso paese;
 * 2. C ha almeno [MIN_POPULATION_RATIO] volte gli abitanti di Q ("molto piu' grande");
 * 3. Q e' abbastanza grande da competere, almeno [MIN_DISTRICT_POPULATION] abitanti: i luoghi piu' piccoli li gestisce gia'
 *    la classifica pesata (raggio d'influenza piccolo), e un comune di 36.000 abitanti come Segrate resta Segrate;
 * 4. le loro aree d'influenza si toccano: la distanza fra i centroidi e' al piu' la somma dei due raggi
 *    ([Mcgp1Format.INFLUENCE_RADIUS_M]).
 *
 * Per Manhattan, Brooklyn, Queens e il Bronx (1,4-2,7 milioni contro gli 8,8 di New York) fa si' che vinca New York. Per
 * Sesto San Giovanni (80.000 abitanti) o Fiesole (14.000), troppo piccoli, non cambia niente; per Monza (123.000) nemmeno.
 */
internal object DistrictAbsorption {

  /** Sotto questa popolazione un luogo non puo' essere un distretto: gia' non compete con una citta' grande. */
  const val MIN_DISTRICT_POPULATION = 150_000L

  /** La citta' deve avere almeno tante volte gli abitanti del distretto. */
  const val MIN_POPULATION_RATIO = 3L

  /** Gli id dei luoghi di [places] che sono distretti di un altro. */
  fun districts(places: List<PlaceRow>): Set<Int> {
    val candidates = places.filter { it.population >= MIN_DISTRICT_POPULATION && it.cls in 1..3 }
    val radius = DoubleArray(candidates.size) { Mcgp1Format.INFLUENCE_RADIUS_M[Mcgp1Format.populationBucket(candidates[it].population)] }
    val maxRadius = Mcgp1Format.INFLUENCE_MAX_M * 2
    val result = HashSet<Int>()
    for (q in candidates.indices) {
      val district = candidates[q]
      for (c in candidates.indices) {
        val city = candidates[c]
        if (c == q || city.countryCode != district.countryCode) continue
        if (city.population < district.population * MIN_POPULATION_RATIO) continue
        // Scarto veloce: due luoghi a piu' di 16 km di latitudine non si toccano.
        if (kotlin.math.abs(city.latitude - district.latitude) * METERS_PER_DEGREE > maxRadius) continue
        if (distanceM(district, city) <= radius[c] + radius[q]) {
          result += district.id
          break
        }
      }
    }
    return result
  }

  private const val METERS_PER_DEGREE = Mcgp1Format.EARTH_RADIUS_M * Math.PI / 180.0

  private fun distanceM(a: PlaceRow, b: PlaceRow): Double {
    val phi1 = Math.toRadians(a.latitude)
    val phi2 = Math.toRadians(b.latitude)
    val s1 = sin((phi2 - phi1) / 2)
    val s2 = sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val hav = s1 * s1 + cos(phi1) * cos(phi2) * s2 * s2
    return 2.0 * Mcgp1Format.EARTH_RADIUS_M * asin(minOf(1.0, sqrt(hav)))
  }
}
