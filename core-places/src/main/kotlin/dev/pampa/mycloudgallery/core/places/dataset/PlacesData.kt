package dev.pampa.mycloudgallery.core.places.dataset

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlaceMatch
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Le distanze che decidono cosa si puo' dire di un punto (docs/fase3-progetto.md §2.3). Contano dal luogo
 * **qualificato** scelto dalla ricerca: almeno 1000 abitanti, oppure sede amministrativa (`PPLA*`) o capitale. La ricerca
 * sceglie il luogo con la classifica pesata (distanza meno raggio d'influenza della popolazione, con le enclave dei
 * micro-stati); la distanza vera dal luogo scelto decide la precisione.
 */
object PlacesLimits {
  /** Entro questa distanza si dice il nome del luogo: [PlacePrecision.CITY]. */
  const val CITY_M: Double = 15_000.0

  /** Entro questa distanza si dice "vicino a ...": [PlacePrecision.NEAR]. */
  const val NEAR_M: Double = 50_000.0

  /** Entro questa distanza si conosce la regione ma non il luogo: [PlacePrecision.REGION_ONLY]. */
  const val REGION_M: Double = 150_000.0

  /** Entro questa distanza si conosce solo il paese: [PlacePrecision.COUNTRY_ONLY]. Oltre, nessun risultato (oceano). */
  const val COUNTRY_M: Double = 300_000.0

  /** Il fuso di un punto si prende dal luogo scelto se e' entro questa distanza (altrimenti il chiamante ripiega). */
  const val ZONE_M: Double = 150_000.0

  /** Il raggio d'influenza di un luogo cresce come la radice della popolazione: questi metri per radice di abitante... */
  const val INFLUENCE_K_M: Double = Mcgp1Format.INFLUENCE_K_M

  /** ...fino a questo tetto (Roma, 2,9 milioni di abitanti, ha 8 km; una citta' di 80.000 abitanti 1,4 km). */
  const val INFLUENCE_MAX_M: Double = Mcgp1Format.INFLUENCE_MAX_M

  /** Un punto a meno di questa distanza dal confine di un micro-stato (Vaticano, San Marino, Monaco, Liechtenstein) conta come dentro. */
  const val ENCLAVE_MARGIN_M: Double = MicroStates.MARGIN_M
}

/**
 * Il dataset dei luoghi aperto: un solo oggetto che e' insieme il geocoder inverso ([ReverseGeocoder]), il cercatore di
 * fusi ([TimeZoneLookup]) e i nomi per la UI ([PlaceNames]), tutti sullo stesso file MCGP1 mappato in memoria.
 *
 * **Niente rete, niente log.** Il punto cercato non viene conservato e non finisce in nessuna stringa. Si puo'
 * chiamare da piu' thread insieme; la prima chiamata di [place] o di [labelOfId] costruisce un indice per id (circa
 * 1,4 MB per 170 mila luoghi) e le altre lo riusano.
 *
 * Si ottiene da [PlacesDataset.open] (il file dell'app) o da [PlacesDataset.openForTest] (byte in memoria).
 */
class PlacesData internal constructor(private val file: Mcgp1) : ReverseGeocoder, TimeZoneLookup, PlaceNames {

  override val dataset: Int get() = file.version

  /** Quanti luoghi ha il dataset (conteggio, nessun dato personale). */
  val placeCount: Int get() = file.placeCount

  /** La dimensione del file in byte. */
  val sizeBytes: Int get() = file.sizeBytes

  private val countryNames = arrayOfNulls<String>(file.countryCount)
  private val regionNames = arrayOfNulls<String>(file.regionCount)
  private val zoneIds = arrayOfNulls<ZoneId>(file.zoneCount)
  private val zoneUnknown = BooleanArray(file.zoneCount)

  // ---- ReverseGeocoder ------------------------------------------------------------------------------------------

  override fun nearest(p: GeoPoint): PlaceMatch? {
    val hit = file.nearest(p.latitude, p.longitude, PlacesLimits.COUNTRY_M) ?: return null
    val distance = hit.distanceM
    val place: Place
    val precision: PlacePrecision
    when {
      distance <= PlacesLimits.CITY_M -> {
        precision = PlacePrecision.CITY
        place = placeOfRecord(hit.index, hit.cell)
      }
      distance <= PlacesLimits.NEAR_M -> {
        precision = PlacePrecision.NEAR
        place = placeOfRecord(hit.index, hit.cell)
      }
      distance <= PlacesLimits.REGION_M && file.regionOf(hit.index) != Mcgp1Format.NONE -> {
        precision = PlacePrecision.REGION_ONLY
        place = regionPlace(file.regionOf(hit.index), zoneOf(hit.index))
      }
      else -> {
        precision = PlacePrecision.COUNTRY_ONLY
        place = countryPlace(file.countryOf(hit.index), zoneOf(hit.index))
      }
    }
    return PlaceMatch(place, distance.roundToInt(), precision)
  }

  override fun place(id: PlaceId): Place? {
    val v = id.value
    val index = file.indexOfPlaceId(v)
    if (index >= 0) return placeOfRecord(index, file.cellOfRecord(index))
    val region = file.regionIndexOfId(v)
    if (region >= 0) return regionPlace(region, zone = null)
    if (v >= Mcgp1Format.COUNTRY_ID_BASE && v < Mcgp1Format.COUNTRY_ID_BASE + Mcgp1Format.COUNTRY_ID_COUNT) {
      val offset = v - Mcgp1Format.COUNTRY_ID_BASE
      val code = String(charArrayOf('A' + offset / 26, 'A' + offset % 26))
      val country = file.countryIndexOf(code)
      if (country >= 0) return countryPlace(country, zone = null)
    }
    return null
  }

  // ---- TimeZoneLookup -------------------------------------------------------------------------------------------

  override fun zoneAt(p: GeoPoint): ZoneId? {
    val hit = file.nearest(p.latitude, p.longitude, PlacesLimits.ZONE_M) ?: return null
    val zoneIndex = file.zoneOf(hit.index)
    if (zoneIndex == Mcgp1Format.NONE) return null
    return zoneId(zoneIndex)
  }

  // ---- PlaceNames -----------------------------------------------------------------------------------------------

  override fun label(ref: PlaceRef): String? = labelOfId(ref.geonameId)

  override fun labelOfId(geonameId: Int): String? {
    if (geonameId <= 0) return null
    // Il caso comune (un luogo) non costruisce il Place intero: basta il nome.
    val index = file.indexOfPlaceId(geonameId)
    if (index >= 0) return file.nameOf(index, file.cellOfRecord(index))
    return place(PlaceId(geonameId))?.name
  }

  override fun summary(top: IntArray, distinct: Int): String {
    if (distinct <= 0) return ""
    val names = top.toList().mapNotNull { labelOfId(it) }
    if (names.isEmpty()) return ""
    return when {
      distinct == 1 -> names[0]
      distinct == 2 -> if (names.size >= 2) "${names[0]} e ${names[1]}" else "${names[0]} e un altro luogo"
      else -> "${names[0]} e altri ${distinct - 1} luoghi"
    }
  }

  // ---- Costruzione dei Place ------------------------------------------------------------------------------------

  private fun placeOfRecord(index: Int, cell: Int): Place {
    val regionIndex = file.regionOf(index)
    val zoneIndex = file.zoneOf(index)
    val countryIndex = file.countryOf(index)
    return Place(
      id = PlaceId(file.placeId(index)),
      name = file.nameOf(index, cell),
      admin1 = if (regionIndex == Mcgp1Format.NONE) null else regionName(regionIndex),
      countryCode = file.countryCode(countryIndex),
      countryName = countryName(countryIndex),
      zone = if (zoneIndex == Mcgp1Format.NONE) null else file.zoneName(zoneIndex),
    )
  }

  /** Il "luogo" di una ricerca che conosce solo la regione: nome e admin1 sono il nome della regione. */
  private fun regionPlace(regionIndex: Int, zone: String?): Place {
    val countryIndex = file.regionCountry(regionIndex)
    val name = regionName(regionIndex)
    return Place(
      id = PlaceId(file.regionId(regionIndex)),
      name = name,
      admin1 = name,
      countryCode = file.countryCode(countryIndex),
      countryName = countryName(countryIndex),
      zone = zone,
    )
  }

  /** Il "luogo" di una ricerca che conosce solo il paese: nome e countryName sono il nome del paese. */
  private fun countryPlace(countryIndex: Int, zone: String?): Place {
    val code = file.countryCode(countryIndex)
    return Place(
      id = PlaceId(Mcgp1Format.countryPlaceId(code)),
      name = countryName(countryIndex),
      admin1 = null,
      countryCode = code,
      countryName = countryName(countryIndex),
      zone = zone,
    )
  }

  private fun zoneOf(recordIndex: Int): String? {
    val zoneIndex = file.zoneOf(recordIndex)
    return if (zoneIndex == Mcgp1Format.NONE) null else file.zoneName(zoneIndex)
  }

  private fun regionName(regionIndex: Int): String =
    regionNames[regionIndex] ?: file.regionName(regionIndex).also { regionNames[regionIndex] = it }

  /** Il nome del paese in italiano (`Locale.ITALIAN`); se il sistema non lo conosce, il codice. */
  private fun countryName(countryIndex: Int): String {
    countryNames[countryIndex]?.let { return it }
    val code = file.countryCode(countryIndex)
    val display = try {
      Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ITALIAN)
    } catch (_: RuntimeException) {
      ""
    }
    val name = if (display.isBlank() || display == code) code else display
    countryNames[countryIndex] = name
    return name
  }

  private fun zoneId(zoneIndex: Int): ZoneId? {
    zoneIds[zoneIndex]?.let { return it }
    if (zoneUnknown[zoneIndex]) return null
    return try {
      ZoneId.of(file.zoneName(zoneIndex)).also { zoneIds[zoneIndex] = it }
    } catch (_: DateTimeException) {
      // Un fuso che questo sistema non conosce (database dei fusi piu' vecchio del dump): meglio nessun fuso che uno sbagliato.
      zoneUnknown[zoneIndex] = true
      null
    }
  }
}
