package dev.pampa.mycloudgallery.core.places.dataset

import java.nio.ByteOrder

// Il formato MCGP1: il dataset dei luoghi come un unico file binario, pensato per essere MAPPATO in memoria e letto
// con accessi assoluti (docs/fase3-progetto.md §2.3, docs/luoghi.md). Niente Android qui: lo usa anche lo strumento
// `buildPlacesDataset` su una JVM normale.
//
// Tutto little-endian. Il file e' una sequenza di sezioni, ognuna allineata a 4 byte:
//
//   intestazione (96 byte)  magic "MCGP1\0\0\0", versione del dataset, conteggi, offset di ogni sezione, dimensione
//   rowStart   int32[721]   per ognuna delle 720 fasce di 0,25 gradi di latitudine, l'indice della sua prima cella
//   cellCol    uint16[n]    la colonna (0..1439, 0,25 gradi di longitudine) di ogni cella NON vuota, crescente per fascia
//   cellFirst  int32[n+1]   l'indice del primo luogo di ogni cella; l'ultimo valore e' il numero di luoghi
//   cellName   int32[n]     l'offset (nella tabella dei nomi) del nome del primo luogo di ogni cella
//   places     17 B * m     i luoghi, ordinati per (cella, id): vedi sotto
//   names      byte         i nomi UTF-8, nello stesso ordine dei luoghi, SENZA separatori: la lunghezza sta nel luogo
//   regions    12 B * r     id GeoNames della regione (int32), paese (uint16), offset (int32) e lunghezza (uint16) del nome
//   zones      6 B * z      offset (int32) e lunghezza (uint16) dell'identificativo IANA del fuso
//   countries  2 B * c      il codice ISO 3166-1 alfa-2 (due lettere ASCII), in ordine alfabetico
//   pool       byte         le stringhe di regioni e fusi, UTF-8
//   crc32      uint32       il CRC-32 di tutti i byte precedenti
//
// Un luogo (17 byte): id GeoNames int32; dLat uint16 e dLon uint16, cioe' la distanza in 1e-5 gradi dallo spigolo
// sud-ovest della SUA cella (quindi la posizione ha la precisione di circa 1 m con 4 byte invece di 8); regione
// uint16 (0xFFFF = nessuna); paese uint16; fuso uint16 (0xFFFF = nessuno); classe uint8; popolazione uint8
// (logaritmica); lunghezza del nome uint8. Il nome di un luogo si trova sommando le lunghezze dei luoghi che lo
// precedono nella stessa cella, a partire da cellName: la ricerca (che e' il percorso caldo) non lo legge mai.
//
// Perche' una griglia sparsa e non un albero: la ricerca "ad anelli" intorno alla cella del punto tocca poche celle
// vicine, e ogni cella e' un intervallo contiguo di luoghi: accesso sequenziale, nessuna allocazione, nessun salto.

/** Un luogo da scrivere nel file. [regionKey] e' la chiave `CC.admin1` di [RegionRow], se nota. */
internal class PlaceRow(
  val id: Int,
  val latitude: Double,
  val longitude: Double,
  val name: String,
  val countryCode: String,
  val regionKey: String?,
  val zone: String?,
  /** 1 = abitato con almeno 1000 abitanti, 2 = sede amministrativa (PPLA*), 3 = capitale. 0 = minore (mai scelto). */
  val cls: Int,
  val population: Long,
)

/** Una regione (admin1 di GeoNames) da scrivere nel file: e' anche il "luogo" di una ricerca solo regionale. */
internal class RegionRow(
  val key: String,
  val id: Int,
  val countryCode: String,
  val name: String,
)

/** Tutto cio' che serve per scrivere un file: la versione (aaaammgg del dump), i luoghi e le regioni. */
internal class DatasetData(
  val version: Int,
  val places: List<PlaceRow>,
  val regions: List<RegionRow>,
)

/** Costanti e aritmetica del formato, condivise da scrittore e lettore. */
internal object Mcgp1Format {
  val MAGIC: ByteArray = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0, 0, 0)
  val ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

  const val HEADER_SIZE = 96
  const val ROWS = 720
  const val COLS = 1440
  const val CELL_UNITS = 25_000
  const val LAT_UNITS = 18_000_000
  const val LON_UNITS = 36_000_000
  const val PLACE_SIZE = 17
  const val REGION_SIZE = 12
  const val ZONE_SIZE = 6
  const val COUNTRY_SIZE = 2
  const val NONE = 0xFFFF
  const val MAX_NAME_BYTES = 255

  /** Gli id dei "luoghi" di un paese (ricerca solo per paese) stanno in questo intervallo, sopra ogni id GeoNames. */
  const val COUNTRY_ID_BASE = 2_000_000_000
  const val COUNTRY_ID_COUNT = 26 * 26

  /** Il raggio terrestre della formula di haversine, in metri. */
  const val EARTH_RADIUS_M = 6_371_000.0

  /** Un'unita' (1e-5 gradi) in radianti. */
  const val RAD_PER_UNIT = Math.PI / 180.0 / 100_000.0

  /** Latitudine in unita' dal polo sud: 0 = -90, 18.000.000 = +90. */
  fun latUnits(latitude: Double): Int =
    Math.round((latitude + 90.0) * 100_000.0).toInt().coerceIn(0, LAT_UNITS)

  /** Longitudine in unita' dall'antimeridiano ovest, riportata in [0, 36.000.000): +180 e -180 sono lo stesso punto. */
  fun lonUnits(longitude: Double): Int {
    val raw = Math.round((longitude + 180.0) * 100_000.0)
    val wrapped = ((raw % LON_UNITS) + LON_UNITS) % LON_UNITS
    return wrapped.toInt()
  }

  fun rowOf(latUnits: Int): Int = minOf(ROWS - 1, latUnits / CELL_UNITS)

  fun colOf(lonUnits: Int): Int = lonUnits / CELL_UNITS

  /**
   * La popolazione come un byte, solo con interi (niente `log`: lo stesso input deve dare gli stessi byte su ogni
   * JVM). Cresce con il logaritmo in base 2, otto gradini per raddoppio: 0 abitanti = 0, 1000 = circa 80,
   * un milione = circa 160.
   */
  fun populationBucket(population: Long): Int {
    if (population <= 0L) return 0
    val v = population + 1
    val whole = 63 - java.lang.Long.numberOfLeadingZeros(v)
    val fraction = if (whole >= 3) ((v shr (whole - 3)) and 7L).toInt() else ((v shl (3 - whole)) and 7L).toInt()
    return minOf(255, whole * 8 + fraction)
  }

  /**
   * Il **raggio d'influenza** di un luogo, in metri per ogni valore del byte di popolazione. La ricerca sceglie il luogo
   * con il punteggio piu' basso, dove punteggio = distanza - raggio: una grande citta' "si allarga" sui dintorni (il suo
   * centroide sta lontano dal centro storico, e i confini del comune ancora piu' lontano), un paese di 500 abitanti
   * quasi no. Il raggio cresce come la **radice quadrata** della popolazione (l'area di una citta' cresce come la
   * popolazione, il suo raggio come la radice) a [INFLUENCE_K_M] metri per radice di abitante, con un **tetto** di
   * [INFLUENCE_MAX_M]. Esempi: Roma (2,9 milioni) 8 km (il tetto), Milano (1,4 milioni) 5,8 km, una citta' di 80.000
   * abitanti 1,4 km, di 14.000 0,6 km, di 4.000 0,3 km, di 500 abitanti 0,1 km.
   *
   * Si calcola dal byte (non dalla popolazione esatta, che nel file non c'e'), solo con operazioni esatte: una tabella di
   * 256 valori uguale su ogni JVM, usata dalla ricerca e dalla forza bruta dei test.
   */
  const val INFLUENCE_K_M = 5.0

  /** Il tetto del raggio d'influenza: la ricerca ad anelli deve allargarsi di tanto oltre il migliore trovato. */
  const val INFLUENCE_MAX_M = 8_000.0

  /** La popolazione che un valore del byte rappresenta (il centro del suo intervallo). 0 = nessuna popolazione nota. */
  fun populationOfBucket(bucket: Int): Double {
    if (bucket <= 0) return 0.0
    val whole = bucket / 8
    val fraction = bucket % 8
    return (1L shl whole).toDouble() * (1.0 + (fraction + 0.5) / 8.0) - 1.0
  }

  /** Il raggio d'influenza (m) per ogni valore del byte di popolazione. */
  val INFLUENCE_RADIUS_M: DoubleArray = DoubleArray(256) { bucket ->
    if (bucket == 0) 0.0 else minOf(INFLUENCE_MAX_M, INFLUENCE_K_M * Math.sqrt(populationOfBucket(bucket)))
  }

  /** L'id del "luogo" che rappresenta un intero paese (ricerca che conosce solo il paese). */
  fun countryPlaceId(code: String): Int = COUNTRY_ID_BASE + (code[0] - 'A') * 26 + (code[1] - 'A')

  fun align4(n: Long): Long = (n + 3L) and 3L.inv()
}

/** Le posizioni delle sezioni, calcolate dai soli conteggi: lo scrittore le scrive, il lettore le ricalcola e le confronta. */
internal class Mcgp1Layout(
  val placeCount: Int,
  val cellCount: Int,
  val regionCount: Int,
  val zoneCount: Int,
  val countryCount: Int,
  val namesSize: Int,
  val poolSize: Int,
) {
  val offRowStart: Long = Mcgp1Format.HEADER_SIZE.toLong()
  val offCellCol: Long = offRowStart + (Mcgp1Format.ROWS + 1) * 4L
  val offCellFirst: Long = Mcgp1Format.align4(offCellCol + cellCount * 2L)
  val offCellName: Long = offCellFirst + (cellCount + 1) * 4L
  val offPlaces: Long = offCellName + cellCount * 4L
  val offNames: Long = Mcgp1Format.align4(offPlaces + placeCount.toLong() * Mcgp1Format.PLACE_SIZE)
  val offRegions: Long = Mcgp1Format.align4(offNames + namesSize)
  val offZones: Long = offRegions + regionCount.toLong() * Mcgp1Format.REGION_SIZE
  val offCountries: Long = offZones + zoneCount.toLong() * Mcgp1Format.ZONE_SIZE
  val offPool: Long = Mcgp1Format.align4(offCountries + countryCount.toLong() * Mcgp1Format.COUNTRY_SIZE)

  /** Con il CRC-32 finale. */
  val totalSize: Long = Mcgp1Format.align4(offPool + poolSize) + 4L
}

/** Il file non e' un MCGP1 valido. [reason] dice dove, mai con coordinate o nomi. */
internal class Mcgp1FormatException(val reason: Reason, message: String) : Exception(message) {
  enum class Reason { TOO_SMALL, BAD_MAGIC, BAD_COUNTS, BAD_LAYOUT, BAD_SIZE, BAD_INDEX, BAD_CHECKSUM }
}
