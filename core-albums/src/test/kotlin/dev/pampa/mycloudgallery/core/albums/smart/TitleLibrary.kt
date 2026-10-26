package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import java.time.LocalDate

/**
 * La Libreria sintetica della tabella pubblica dei titoli (P6, `resources/smart/titoli-*.txt`): luoghi pubblici, viaggi inventati, nessun dato
 * personale. Ogni luogo ha le sue foto proprie col GPS, nei giorni scritti qui: la tabella dei titoli ne deduce le serie di giorni. Gli id GeoNames
 * dei luoghi veri sono quelli veri; gli altri (omonimi e luoghi minori) sono inventati.
 */
internal object TitleLibrary {

  /** Un luogo: id, nome, regione, paese, centro. */
  class Spot(val id: Int, val name: String, val admin1: String, val country: String, val center: GeoPoint)

  private val spots = listOf(
    Spot(3143244, "Oslo", "Oslo", "Norvegia", GeoPoint(59.9139, 10.7522)),
    Spot(5036001, "Oslo", "Minnesota", "Stati Uniti", GeoPoint(48.19, -97.13)),
    Spot(3161732, "Bergen", "Vestland", "Norvegia", GeoPoint(60.3913, 5.3221)),
    Spot(3169070, "Roma", "Lazio", "Italia", GeoPoint(41.9028, 12.4964)),
    Spot(3176959, "Firenze", "Toscana", "Italia", GeoPoint(43.7696, 11.2558)),
    Spot(3170647, "Pisa", "Toscana", "Italia", GeoPoint(43.7228, 10.4017)),
    Spot(3174530, "Lecce", "Puglia", "Italia", GeoPoint(40.3515, 18.1750)),
    Spot(3182351, "Bari", "Puglia", "Italia", GeoPoint(41.1171, 16.8719)),
    Spot(3173435, "Gallipoli", "Puglia", "Italia", GeoPoint(40.0558, 17.9923)),
    Spot(2267057, "Lisbona", "Lisbona", "Portogallo", GeoPoint(38.7223, -9.1393)),
    Spot(5128581, "New York", "New York", "Stati Uniti", GeoPoint(40.7128, -74.0060)),
    Spot(2867714, "Monaco di Baviera", "Baviera", "Germania", GeoPoint(48.1351, 11.5820)),
    Spot(6001, "Santa Maria", "Campania", "Italia", GeoPoint(41.0, 14.3)),
    Spot(6002, "Santa Maria", "Lazio", "Italia", GeoPoint(41.6, 13.1)),
    Spot(6003, "Santa Maria", "Puglia", "Italia", GeoPoint(40.2, 17.9)),
    Spot(6004, "San Marco", "Toscana", "Italia", GeoPoint(43.4, 10.9)),
    Spot(6005, "San Marco", "Veneto", "Italia", GeoPoint(45.4, 12.3)),
    Spot(6006, "Reggio Emilia", "Emilia-Romagna", "Italia", GeoPoint(44.6983, 10.6312)),
    Spot(6007, "Reggio Calabria", "Calabria", "Italia", GeoPoint(38.1113, 15.6619)),
    Spot(6008, "Cortina d'Ampezzo", "Veneto", "Italia", GeoPoint(46.5405, 12.1357)),
    Spot(6009, "Rio de Janeiro", "Rio de Janeiro", "Brasile", GeoPoint(-22.9068, -43.1729)),
    Spot(6010, "Buenos Aires", "Buenos Aires", "Argentina", GeoPoint(-34.6037, -58.3816)),
    Spot(6011, "Parigi", "Île-de-France", "Francia", GeoPoint(48.8566, 2.3522)),
    Spot(6012, "Madrid", "Madrid", "Spagna", GeoPoint(40.4168, -3.7038)),
    Spot(6013, "Barcellona", "Catalogna", "Spagna", GeoPoint(41.3874, 2.1686)),
    Spot(6014, "Stoccolma", "Stoccolma", "Svezia", GeoPoint(59.3293, 18.0686)),
    Spot(6015, "Palermo", "Sicilia", "Italia", GeoPoint(38.1157, 13.3615)),
    Spot(6016, "Catania", "Sicilia", "Italia", GeoPoint(37.5079, 15.0830)),
    Spot(6017, "Milano", "Lombardia", "Italia", GeoPoint(45.4642, 9.1900)),
  )

  val places: Map<Int, PlaceName> = spots.associate { it.id to PlaceName(it.name, it.admin1, it.country) }

  val lookup = PlaceLookup { places[it] }

  private fun spot(id: Int): Spot = spots.first { it.id == id }

  private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

  private fun range(from: LocalDate, to: LocalDate): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

  /** I giorni con foto di ogni luogo: (id, giorni, foto per giorno). */
  private val trips: List<Triple<Int, List<LocalDate>, Int>> = listOf(
    Triple(3143244, range(d(2026, 3, 14), d(2026, 3, 18)) + range(d(2026, 12, 3), d(2026, 12, 5)), 4),
    Triple(5036001, listOf(d(2022, 8, 20)), 3),
    Triple(3161732, range(d(2025, 7, 10), d(2025, 7, 13)), 4),
    Triple(3169070, range(d(2025, 12, 24), d(2025, 12, 28)) + range(d(2024, 3, 8), d(2024, 3, 10)), 4),
    Triple(3176959, range(d(2025, 4, 2), d(2025, 4, 4)) + range(d(2024, 10, 25), d(2024, 10, 27)), 4),
    Triple(3170647, listOf(d(2025, 4, 5)), 4),
    Triple(3174530, range(d(2024, 8, 10), d(2024, 8, 14)) + range(d(2025, 7, 20), d(2025, 7, 23)), 4),
    Triple(3182351, range(d(2024, 8, 15), d(2024, 8, 16)), 4),
    Triple(3173435, listOf(d(2024, 8, 17)), 4),
    Triple(2267057, range(d(2025, 8, 3), d(2025, 8, 9)), 4),
    Triple(5128581, range(d(2023, 7, 2), d(2023, 7, 9)), 4),
    Triple(2867714, range(d(2023, 10, 1), d(2023, 10, 4)), 4),
    Triple(6001, listOf(d(2021, 5, 1)), 3),
    Triple(6002, listOf(d(2021, 6, 1)), 9),
    Triple(6003, listOf(d(2021, 7, 1)), 1),
    Triple(6004, listOf(d(2022, 5, 1)), 6),
    Triple(6005, listOf(d(2022, 6, 1)), 12),
    Triple(6006, range(d(2025, 2, 2), d(2025, 2, 3)), 4),
    Triple(6007, range(d(2025, 9, 5), d(2025, 9, 6)), 4),
    Triple(6008, range(d(2025, 1, 2), d(2025, 1, 5)), 4),
    Triple(6009, range(d(2022, 11, 10), d(2022, 11, 14)), 4),
    Triple(6010, range(d(2019, 3, 1), d(2019, 3, 4)), 4),
    Triple(6011, range(d(2024, 6, 14), d(2024, 6, 16)), 4),
    Triple(6012, range(d(2025, 9, 12), d(2025, 9, 14)), 4),
    Triple(6013, range(d(2025, 9, 15), d(2025, 9, 16)), 4),
    Triple(6014, range(d(2024, 12, 20), d(2024, 12, 23)) + range(d(2024, 12, 29), d(2025, 1, 3)), 4),
    Triple(6015, range(d(2024, 6, 10), d(2024, 6, 14)), 4),
    Triple(6016, range(d(2024, 6, 15), d(2024, 6, 17)), 4),
    // Milano, la citta' di casa: una foto ogni tre giorni, da dicembre 2024 a fine 2025.
    Triple(6017, generateSequence(d(2024, 12, 20)) { it.plusDays(3) }.takeWhile { !it.isAfter(d(2025, 12, 31)) }.toList(), 1),
  )

  /** Il mondo: le foto proprie col GPS entro 3 km dal centro di ogni luogo (quattro per giorno, se non detto altrimenti). */
  fun world(): MemoryWorld {
    val world = MemoryWorld(emptyList(), places)
    var n = 1
    for ((id, days, perDay) in trips) {
      val spot = spot(id)
      for (day in days) {
        repeat(perDay) { k ->
          val at = Spots.north(spot.center, 0.5 + k * 0.7)
          world.put(cand(n++, day, minutes = 8 * 60 + k * 40, gps = at, placeId = id))
        }
      }
    }
    world.publish()
    return world
  }
}
