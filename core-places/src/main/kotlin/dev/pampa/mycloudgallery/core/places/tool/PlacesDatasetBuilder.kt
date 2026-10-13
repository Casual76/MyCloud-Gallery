package dev.pampa.mycloudgallery.core.places.tool

import dev.pampa.mycloudgallery.core.places.dataset.DatasetData
import dev.pampa.mycloudgallery.core.places.dataset.PlaceRow
import dev.pampa.mycloudgallery.core.places.dataset.RegionRow

/** I numeri del resoconto dello strumento: conteggi, mai nomi o coordinate. */
internal class BuildStats(
  val placesFromCities: Int,
  val placesFromItaly: Int,
  val placesKept: Int,
  val droppedNotPopulatedPlace: Int,
  val droppedByCode: Int,
  val droppedTooSmall: Int,
  val droppedMalformed: Int,
  val italianPlaceNames: Int,
  val regions: Int,
  val italianRegionNames: Int,
  val version: Int,
  val dumpDate: String,
)

internal class BuiltDataset(val data: DatasetData, val stats: BuildStats)

/**
 * Da GeoNames al [DatasetData]: **la politica del dataset** sta tutta qui (docs/luoghi.md).
 *
 * 1. Si leggono `cities1000` (mondo) e `IT` (tutta l'Italia); un luogo presente in entrambi si tiene una volta, con i
 *    dati di `IT`.
 * 2. Si tengono solo i luoghi abitati (classe `P`) con un codice che e' un luogo vero:
 *    - `PPLC` (capitale) -> classe 3; `PPLA`, `PPLA2`..`PPLA5`, `PPLG` (sedi amministrative: in Italia anche i comuni
 *      sotto i 1000 abitanti, che sono `PPLA3`) -> classe 2;
 *    - `PPL`, `PPLL`, `PPLS` con almeno 1000 abitanti -> classe 1;
 *    - fuori: `PPLX` (quartieri e frazioni di una citta': darebbero "Brera" invece di "Milano"), `PPLH`/`PPLQ`/`PPLW`
 *      (storici, abbandonati, distrutti), `PPLF`/`PPLR`, e i `PPL` sotto i 1000 abitanti (non potrebbero mai
 *      essere scelti: il piu' vicino conta solo fra i luoghi con almeno 1000 abitanti o sede amministrativa).
 * 3. Il nome e' quello italiano di `alternateNamesV2` (`isolanguage` = `it`: preferito, poi non abbreviato, poi non
 *    colloquiale, poi il piu' vecchio) se esiste, altrimenti quello di GeoNames. Lo stesso per le regioni.
 * 4. Il fuso e' quello di GeoNames. La versione del dataset e' la data di modifica piu' recente fra i luoghi tenuti,
 *    come aaaammgg: dipende solo dall'input, quindi lo stesso input da' lo stesso file.
 */
internal object PlacesDatasetBuilder {

  fun build(input: GeoNamesInput): BuiltDataset {
    var fromCities = 0
    var fromItaly = 0
    var notPopulated = 0
    var byCode = 0
    var tooSmall = 0
    var malformed = 0
    val kept = HashMap<Int, Pair<GeoPlace, Int>>()

    fun read(open: () -> java.io.InputStream, count: (Int) -> Unit) {
      var accepted = 0
      GeoNamesParser.reader(open()).use { reader ->
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isBlank()) continue
          val place = GeoNamesParser.parsePlace(line)
          if (place == null) {
            // Una riga di classe diversa da P e' normale in IT.txt; una riga rotta no.
            if (line.split('\t').size < 18) malformed++ else notPopulated++
            continue
          }
          if (place.countryCode.length != 2 || place.countryCode.any { it !in 'A'..'Z' }) {
            malformed++
            continue
          }
          when (val cls = classify(place.featureCode, place.population)) {
            CLASS_EXCLUDED -> byCode++
            CLASS_TOO_SMALL -> tooSmall++
            else -> {
              kept[place.id] = place to cls
              accepted++
            }
          }
        }
      }
      count(accepted)
    }
    read(input.cities) { fromCities = it }
    read(input.italy) { fromItaly = it }
    check(kept.isNotEmpty()) { "Nessun luogo valido nei file di input." }

    // Regioni.
    val admin1ByKey = HashMap<String, Admin1>()
    GeoNamesParser.reader(input.admin1()).use { reader ->
      while (true) {
        val line = reader.readLine() ?: break
        GeoNamesParser.parseAdmin1(line)?.let { admin1ByKey[it.key] = it }
      }
    }
    val usedKeys = HashSet<String>()
    kept.values.forEach { (place, _) ->
      val key = "${place.countryCode}.${place.admin1}"
      if (place.admin1.isNotEmpty() && key in admin1ByKey) usedKeys += key
    }

    // Nomi italiani: solo per i luoghi tenuti e per le regioni usate.
    val wanted = HashSet<Int>(kept.size * 2)
    wanted.addAll(kept.keys)
    usedKeys.forEach { wanted += admin1ByKey.getValue(it).id }
    val italian = GeoNamesParser.readItalianNames(input.alternateNames(), wanted)

    val places = ArrayList<PlaceRow>(kept.size)
    var italianPlaceNames = 0
    var latest = ""
    for ((place, cls) in kept.values) {
      val italianName = italian[place.id]
      if (italianName != null) italianPlaceNames++
      val key = "${place.countryCode}.${place.admin1}"
      places += PlaceRow(
        id = place.id,
        latitude = place.latitude,
        longitude = place.longitude,
        name = italianName?.name ?: place.name,
        countryCode = place.countryCode,
        regionKey = if (key in usedKeys) key else null,
        zone = place.timezone.ifEmpty { null },
        cls = cls,
        population = place.population,
      )
      if (place.modified > latest) latest = place.modified
    }

    var italianRegionNames = 0
    val regions = usedKeys.map { key ->
      val admin = admin1ByKey.getValue(key)
      val italianName = italian[admin.id]
      if (italianName != null) italianRegionNames++
      RegionRow(key = key, id = admin.id, countryCode = key.substring(0, 2), name = italianName?.name ?: admin.name)
    }

    val version = versionOf(latest)
    return BuiltDataset(
      data = DatasetData(version = version, places = places, regions = regions),
      stats = BuildStats(
        placesFromCities = fromCities,
        placesFromItaly = fromItaly,
        placesKept = places.size,
        droppedNotPopulatedPlace = notPopulated,
        droppedByCode = byCode,
        droppedTooSmall = tooSmall,
        droppedMalformed = malformed,
        italianPlaceNames = italianPlaceNames,
        regions = regions.size,
        italianRegionNames = italianRegionNames,
        version = version,
        dumpDate = latest,
      ),
    )
  }

  private const val CLASS_EXCLUDED = -1
  private const val CLASS_TOO_SMALL = 0

  /** La classe del formato (1 abitato, 2 sede amministrativa, 3 capitale), [CLASS_EXCLUDED] o [CLASS_TOO_SMALL]. */
  fun classify(featureCode: String, population: Long): Int = when (featureCode) {
    "PPLC" -> 3
    "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLG" -> 2
    "PPL", "PPLL", "PPLS" -> if (population >= MIN_POPULATION) 1 else CLASS_TOO_SMALL
    else -> CLASS_EXCLUDED
  }

  const val MIN_POPULATION = 1000L

  /** `2026-10-13` -> 20261013. */
  fun versionOf(date: String): Int {
    val match = DATE.matchEntire(date) ?: error("Nessuna data di modifica valida nei luoghi tenuti.")
    return match.groupValues[1].toInt() * 10_000 + match.groupValues[2].toInt() * 100 + match.groupValues[3].toInt()
  }

  private val DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
}
