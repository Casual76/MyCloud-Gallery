package dev.pampa.mycloudgallery.core.places.dataset

import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Il luogo scelto da [Mcgp1.nearest]: indice del record, cella, distanza in metri e punteggio (distanza meno raggio
 * d'influenza, in metri: puo' essere negativo).
 */
internal class Mcgp1Hit(val index: Int, val cell: Int, val distanceM: Double, val score: Double)

/**
 * Un file MCGP1 aperto in sola lettura. Si legge con accessi assoluti sul buffer (che di solito e' un file mappato):
 * niente copie, niente allocazioni nella ricerca, e quindi piu' thread possono cercare insieme.
 *
 * **Privacy.** Questa classe non scrive in nessun log e non conserva i punti cercati. I suoi `toString` sono quelli
 * di `Any`: non stampano ne' coordinate ne' nomi.
 */
internal class Mcgp1 private constructor(
  private val b: ByteBuffer,
  private val layout: Mcgp1Layout,
  /** La versione del dataset: la data del dump GeoNames come aaaammgg. */
  val version: Int,
) {
  val placeCount: Int get() = layout.placeCount
  val cellCount: Int get() = layout.cellCount
  val regionCount: Int get() = layout.regionCount
  val zoneCount: Int get() = layout.zoneCount
  val countryCount: Int get() = layout.countryCount
  val sizeBytes: Int get() = layout.totalSize.toInt()

  private val offRowStart = layout.offRowStart.toInt()
  private val offCellCol = layout.offCellCol.toInt()
  private val offCellFirst = layout.offCellFirst.toInt()
  private val offCellName = layout.offCellName.toInt()
  private val offPlaces = layout.offPlaces.toInt()
  private val offNames = layout.offNames.toInt()
  private val offRegions = layout.offRegions.toInt()
  private val offZones = layout.offZones.toInt()
  private val offCountries = layout.offCountries.toInt()
  private val offPool = layout.offPool.toInt()

  /** Per ogni paese del file, se e' un micro-stato trattato come enclave ([MicroStates]). */
  private val microCountry = BooleanArray(layout.countryCount) { MicroStates.CODES.contains(countryCode(it)) }

  // ---- Accessi di basso livello ---------------------------------------------------------------------------------

  private fun u8(at: Int): Int = b.get(at).toInt() and 0xFF

  private fun u16(at: Int): Int = b.getShort(at).toInt() and 0xFFFF

  private fun i32(at: Int): Int = b.getInt(at)

  private fun rowStart(row: Int): Int = i32(offRowStart + row * 4)

  private fun cellCol(cell: Int): Int = u16(offCellCol + cell * 2)

  private fun cellFirst(cell: Int): Int = i32(offCellFirst + cell * 4)

  private fun recordAt(index: Int): Int = offPlaces + index * Mcgp1Format.PLACE_SIZE

  private fun text(at: Int, length: Int): String {
    val bytes = ByteArray(length)
    for (i in 0 until length) bytes[i] = b.get(at + i)
    return String(bytes, Charsets.UTF_8)
  }

  // ---- Campi di un luogo ----------------------------------------------------------------------------------------

  fun placeId(index: Int): Int = i32(recordAt(index))

  /** Indice (nella tabella delle regioni) della regione del luogo, o [Mcgp1Format.NONE]. */
  fun regionOf(index: Int): Int = u16(recordAt(index) + 8)

  /** Indice (nella tabella dei paesi) del paese del luogo. */
  fun countryOf(index: Int): Int = u16(recordAt(index) + 10)

  /** Indice (nella tabella dei fusi) del fuso del luogo, o [Mcgp1Format.NONE]. */
  fun zoneOf(index: Int): Int = u16(recordAt(index) + 12)

  /** La classe del luogo (1 abitato, 2 sede amministrativa, 3 capitale; 0 minore), senza i flag. */
  fun classOf(index: Int): Int = u8(recordAt(index) + 14) and Mcgp1Format.CLASS_MASK

  /** Vero se il luogo e' un distretto di una citta' piu' grande: c'e' nel file ma non compete. */
  fun isSecondary(index: Int): Boolean = (u8(recordAt(index) + 14) and Mcgp1Format.FLAG_SECONDARY) != 0

  fun populationBucketOf(index: Int): Int = u8(recordAt(index) + 15)

  /** Il nome del luogo [index], che sta nella cella [cell]. */
  fun nameOf(index: Int, cell: Int): String {
    var offset = i32(offCellName + cell * 4)
    for (j in cellFirst(cell) until index) offset += u8(recordAt(j) + 16)
    return text(offNames + offset, u8(recordAt(index) + 16))
  }

  /** La cella (indice nella tabella delle celle) che contiene il luogo [index]. */
  fun cellOfRecord(index: Int): Int {
    var lo = 0
    var hi = cellCount - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (cellFirst(mid) <= index) lo = mid else hi = mid - 1
    }
    return lo
  }

  /** Latitudine e longitudine del luogo [index] (della fascia [row] e della colonna [col]) in 1e-5 gradi dal polo sud / dall'antimeridiano. */
  fun latUnitsOf(index: Int, row: Int): Int = row * Mcgp1Format.CELL_UNITS + u16(recordAt(index) + 4)

  fun lonUnitsOf(index: Int, col: Int): Int = col * Mcgp1Format.CELL_UNITS + u16(recordAt(index) + 6)

  /** La fascia (riga) di una cella: l'ultima fascia il cui `rowStart` e' <= alla cella. Per i test e per gli strumenti. */
  fun rowOfCell(cell: Int): Int {
    var lo = 0
    var hi = Mcgp1Format.ROWS - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (rowStart(mid) <= cell) lo = mid else hi = mid - 1
    }
    return lo
  }

  fun colOfCell(cell: Int): Int = cellCol(cell)

  // ---- Tabelle ---------------------------------------------------------------------------------------------------

  fun regionId(regionIndex: Int): Int = i32(offRegions + regionIndex * Mcgp1Format.REGION_SIZE)

  fun regionCountry(regionIndex: Int): Int = u16(offRegions + regionIndex * Mcgp1Format.REGION_SIZE + 4)

  fun regionName(regionIndex: Int): String {
    val at = offRegions + regionIndex * Mcgp1Format.REGION_SIZE
    return text(offPool + i32(at + 6), u16(at + 10))
  }

  fun zoneName(zoneIndex: Int): String {
    val at = offZones + zoneIndex * Mcgp1Format.ZONE_SIZE
    return text(offPool + i32(at), u16(at + 4))
  }

  fun countryCode(countryIndex: Int): String {
    val at = offCountries + countryIndex * Mcgp1Format.COUNTRY_SIZE
    return String(charArrayOf(u8(at).toChar(), u8(at + 1).toChar()))
  }

  /** L'indice del paese con questo codice, o -1. I paesi sono in ordine alfabetico. */
  fun countryIndexOf(code: String): Int {
    if (code.length != 2) return -1
    var lo = 0
    var hi = countryCount - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val c = countryCode(mid).compareTo(code)
      when {
        c < 0 -> lo = mid + 1
        c > 0 -> hi = mid - 1
        else -> return mid
      }
    }
    return -1
  }

  /** L'indice della regione con questo id GeoNames, o -1. Le regioni sono ordinate per id. */
  fun regionIndexOfId(id: Int): Int {
    var lo = 0
    var hi = regionCount - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val v = regionId(mid)
      when {
        v < id -> lo = mid + 1
        v > id -> hi = mid - 1
        else -> return mid
      }
    }
    return -1
  }

  /** (id << 32) | indice, ordinato per id: si costruisce alla prima richiesta per id e non serve alla ricerca. */
  private val idIndex: LongArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val all = LongArray(placeCount) { (placeId(it).toLong() shl 32) or it.toLong() }
    all.sort()
    all
  }

  /** L'indice del luogo con questo id GeoNames, o -1. */
  fun indexOfPlaceId(id: Int): Int {
    val keys = idIndex
    var lo = 0
    var hi = keys.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val v = (keys[mid] shr 32).toInt()
      when {
        v < id -> lo = mid + 1
        v > id -> hi = mid - 1
        else -> return (keys[mid] and 0xFFFFFFFFL).toInt()
      }
    }
    return -1
  }

  // ---- Ricerca ad anelli ----------------------------------------------------------------------------------------

  /**
   * Il luogo **qualificato** (classe >= 1) che meglio descrive ([latitude], [longitude]), a non piu' di [capM] metri, o
   * null. "Meglio" vuol dire **punteggio piu' basso**, dove punteggio = distanza - raggio d'influenza del luogo
   * ([Mcgp1Format.INFLUENCE_RADIUS_M]): senza il raggio vincerebbe il centroide piu' vicino, e il centroide di Roma sta
   * a est del centro storico. A parita' di punteggio vince l'id piu' piccolo.
   *
   * **Enclave.** Se il punto sta in un micro-stato ([MicroStates]) si guardano solo i luoghi di quel paese (se il file ne
   * ha); altrimenti i luoghi dei micro-stati non competono.
   *
   * **Ricerca ad anelli.** La griglia e' di 0,25 gradi. Si guarda prima la cella del punto (anello 0), poi le 8 celle
   * intorno (anello 1), poi le 16 successive, e cosi' via: l'anello k e' l'insieme delle celle a distanza k nella griglia
   * (la colonna si riavvolge all'antimeridiano; le fasce non si riavvolgono, quindi ai poli l'anello si limita a quelle
   * che esistono). Dopo ogni anello si calcola un LIMITE INFERIORE alla distanza di tutto cio' che non si e' ancora
   * visto; un luogo non visto ha un punteggio di almeno "limite - raggio massimo", quindi la ricerca finisce quando il
   * punteggio migliore e' <= limite - raggio massimo: la ricerca resta **esatta**, solo si allarga di 8 km in piu'.
   * Il limite e' esatto sulla sfera (non fidarsi di "un grado di longitudine = 111 km": vicino ai poli e' quasi zero) e
   * per questo i poli e l'antimeridiano non sono casi speciali.
   */
  fun nearest(latitude: Double, longitude: Double, capM: Double): Mcgp1Hit? {
    if (placeCount == 0 || capM <= 0.0) return null
    val enclave = MicroStates.enclaveAt(latitude, longitude)
    if (enclave != null) {
      val country = countryIndexOf(enclave.countryCode)
      if (country >= 0) {
        val cap = min(capM, ENCLAVE_SEARCH_M)
        // Dentro un micro-stato risponde l'unita' amministrativa (la capitale, il castello, il comune), non un villaggio:
        // a San Marino, Valdragone (7.000 abitanti, un villaggio) non deve battere la citta' di San Marino (4.500).
        Probe(latitude, longitude, cap, country, MIN_CLASS_IN_ENCLAVE).run()?.let { return it }
        Probe(latitude, longitude, cap, country, 1).run()?.let { return it }
      }
    }
    return Probe(latitude, longitude, capM, ALL_BUT_MICRO_STATES, 1).run()
  }

  /**
   * [onlyCountry]: l'indice del paese a cui limitare la ricerca (un'enclave), oppure [ALL_BUT_MICRO_STATES]: tutti i
   * luoghi tranne quelli dei micro-stati.
   */
  private inner class Probe(
    latitude: Double,
    longitude: Double,
    private val capM: Double,
    private val onlyCountry: Int,
    private val minClass: Int,
  ) {
    private val qLatU = Mcgp1Format.latUnits(latitude)
    private val qLonU = Mcgp1Format.lonUnits(longitude)
    private val r0 = Mcgp1Format.rowOf(qLatU)
    private val c0 = Mcgp1Format.colOf(qLonU)
    private val phiQ = (qLatU - 9_000_000) * Mcgp1Format.RAD_PER_UNIT
    private val cosQ = cos(phiQ)

    // Lo stato della ricerca: il migliore finora (punteggio = distanza - raggio d'influenza, in metri).
    private var bestIndex = -1
    private var bestCell = -1
    private var bestScore = Double.POSITIVE_INFINITY
    private var bestDistance = 0.0
    private var bestId = Int.MAX_VALUE

    fun run(): Mcgp1Hit? {
      val metersPerRow = Mcgp1Format.CELL_UNITS * METERS_PER_UNIT
      val maxDr = min(Mcgp1Format.ROWS, ceil(capM / metersPerRow).toInt()) + 2
      val rowLow = max(0, r0 - maxDr)
      val rowHigh = min(Mcgp1Format.ROWS - 1, r0 + maxDr)

      var k = 0
      while (true) {
        if (k == 0) {
          scanCellAt(r0, c0)
        } else {
          // Le due fasce estreme dell'anello: tutte le colonne da c0-k a c0+k.
          if (r0 - k in rowLow..rowHigh) scanSpan(r0 - k, c0 - k, c0 + k)
          if (r0 + k in rowLow..rowHigh) scanSpan(r0 + k, c0 - k, c0 + k)
          // Le fasce in mezzo: solo le due colonne laterali (una sola quando coincidono, a meta' del giro).
          val from = max(rowLow, r0 - k + 1)
          val to = min(rowHigh, r0 + k - 1)
          val east = (c0 + k) % Mcgp1Format.COLS
          val west = ((c0 - k) % Mcgp1Format.COLS + Mcgp1Format.COLS) % Mcgp1Format.COLS
          for (r in from..to) {
            scanCellAt(r, east)
            if (west != east) scanCellAt(r, west)
          }
        }
        val bound = unseenBound(k)
        // Un luogo non visto dista almeno "bound" e il suo raggio e' al massimo INFLUENCE_MAX_M.
        if (bestIndex >= 0 && bestScore <= bound - Mcgp1Format.INFLUENCE_MAX_M) break
        if (bound > capM) break
        if (k >= Mcgp1Format.COLS / 2) break
        k++
      }
      if (bestIndex < 0) return null
      return Mcgp1Hit(bestIndex, bestCell, bestDistance, bestScore)
    }

    /**
     * Un limite inferiore (in metri) alla distanza di ogni luogo fuori dagli anelli 0..[k]: vale il minore fra il
     * limite in latitudine (le fasce piu' lontane di k fasce) e quello in longitudine (le colonne piu' lontane di k
     * colonne, dall'una o dall'altra parte).
     */
    private fun unseenBound(k: Int): Double {
      // Latitudine: ogni fascia non vista dista almeno "il tratto fino al suo bordo" di meridiano.
      var latGapUnits = Double.POSITIVE_INFINITY
      if (r0 + k + 1 <= Mcgp1Format.ROWS - 1) {
        latGapUnits = min(latGapUnits, ((r0 + k + 1).toLong() * Mcgp1Format.CELL_UNITS - qLatU).toDouble())
      }
      if (r0 - k - 1 >= 0) {
        latGapUnits = min(latGapUnits, (qLatU - (r0 - k).toLong() * Mcgp1Format.CELL_UNITS).toDouble())
      }
      val latBound = if (latGapUnits.isInfinite()) Double.POSITIVE_INFINITY else max(0.0, latGapUnits - 1.0) * METERS_PER_UNIT

      // Longitudine: tutto cio' che ha una differenza di longitudine >= theta sta oltre il meridiano a theta dal
      // punto, e la distanza dal punto a quel meridiano e' asin(cos(lat) * sin(theta)) (per theta <= 90 gradi).
      val lonBound: Double
      if (k >= Mcgp1Format.COLS / 2) {
        lonBound = Double.POSITIVE_INFINITY
      } else {
        val offsetInCell = (qLonU % Mcgp1Format.CELL_UNITS).toDouble()
        val gapEast = (k + 1).toDouble() * Mcgp1Format.CELL_UNITS - offsetInCell
        val gapWest = k.toDouble() * Mcgp1Format.CELL_UNITS + offsetInCell
        val theta = min(min(gapEast, gapWest) - 1.0, 9_000_000.0).coerceAtLeast(0.0) * Mcgp1Format.RAD_PER_UNIT
        lonBound = Mcgp1Format.EARTH_RADIUS_M * asin(min(1.0, cosQ * sin(theta)))
      }
      return min(latBound, lonBound)
    }

    /** Scorre le colonne [colFrom]..[colTo] (anche fuori da 0..1439: si riavvolgono) della fascia [row]. */
    private fun scanSpan(row: Int, colFrom: Int, colTo: Int) {
      val count = colTo - colFrom + 1
      if (count >= Mcgp1Format.COLS) {
        scanSegment(row, 0, Mcgp1Format.COLS - 1)
        return
      }
      val start = ((colFrom % Mcgp1Format.COLS) + Mcgp1Format.COLS) % Mcgp1Format.COLS
      val end = start + count - 1
      if (end < Mcgp1Format.COLS) {
        scanSegment(row, start, end)
      } else {
        scanSegment(row, start, Mcgp1Format.COLS - 1)
        scanSegment(row, 0, end - Mcgp1Format.COLS)
      }
    }

    /** Le celle non vuote della fascia [row] con colonna in [col1]..[col2] (senza riavvolgimento). */
    private fun scanSegment(row: Int, col1: Int, col2: Int) {
      val hi = rowStart(row + 1)
      var cell = firstCellAtOrAfter(rowStart(row), hi, col1)
      while (cell < hi) {
        val col = cellCol(cell)
        if (col > col2) break
        scanCell(cell, row, col)
        cell++
      }
    }

    private fun scanCellAt(row: Int, col: Int) {
      val hi = rowStart(row + 1)
      val cell = firstCellAtOrAfter(rowStart(row), hi, col)
      if (cell < hi && cellCol(cell) == col) scanCell(cell, row, col)
    }

    /** Il primo indice di cella in [lo, hi) con colonna >= [col] (o [hi]). */
    private fun firstCellAtOrAfter(lo0: Int, hi: Int, col: Int): Int {
      var lo = lo0
      var h = hi
      while (lo < h) {
        val mid = (lo + h) ushr 1
        if (cellCol(mid) < col) lo = mid + 1 else h = mid
      }
      return lo
    }

    /**
     * Guarda tutti i luoghi di una cella. Un luogo si scarta senza nessuna funzione trigonometrica se un limite inferiore
     * alla sua distanza (con sin(x) >= x(1 - x^2/6), asin(x) >= x e il coseno della latitudine piu' sfavorevole della
     * fascia) meno il suo raggio e' gia' peggiore del punteggio migliore; solo gli altri si calcolano esattamente con la
     * formula di haversine.
     */
    private fun scanCell(cell: Int, row: Int, col: Int) {
      val first = cellFirst(cell)
      val last = cellFirst(cell + 1)
      val latBase = row * Mcgp1Format.CELL_UNITS
      val lonBase = col * Mcgp1Format.CELL_UNITS
      val south = (latBase - 9_000_000) * Mcgp1Format.RAD_PER_UNIT
      val north = (latBase + Mcgp1Format.CELL_UNITS - 9_000_000) * Mcgp1Format.RAD_PER_UNIT
      val cosProd = cosQ * cos(max(kotlin.math.abs(south), kotlin.math.abs(north)).coerceAtMost(Math.PI / 2))
      val twoR = 2.0 * Mcgp1Format.EARTH_RADIUS_M
      for (i in first until last) {
        val at = recordAt(i)
        val flags = u8(at + 14)
        // Fuori: i luoghi minori, quelli sotto la classe richiesta e i distretti di una citta' piu' grande.
        if ((flags and Mcgp1Format.CLASS_MASK) < minClass || (flags and Mcgp1Format.FLAG_SECONDARY) != 0) continue
        val country = u16(at + 10)
        if (onlyCountry >= 0) {
          if (country != onlyCountry) continue
        } else if (microCountry[country]) {
          continue
        }
        val radius = Mcgp1Format.INFLUENCE_RADIUS_M[u8(at + 15)]
        val pLatU = latBase + u16(at + 4)
        var dLonU = lonBase + u16(at + 6) - qLonU
        if (dLonU > Mcgp1Format.LON_UNITS / 2) dLonU -= Mcgp1Format.LON_UNITS
        else if (dLonU < -Mcgp1Format.LON_UNITS / 2) dLonU += Mcgp1Format.LON_UNITS
        val dPhi = (pLatU - qLatU) * Mcgp1Format.RAD_PER_UNIT
        val dLam = dLonU * Mcgp1Format.RAD_PER_UNIT
        val hx = min(kotlin.math.abs(dPhi) * 0.5, 0.5)
        val hy = min(kotlin.math.abs(dLam) * 0.5, 0.5)
        val lower = hx * hx * (1.0 - hx * hx / 3.0) + cosProd * hy * hy * (1.0 - hy * hy / 3.0)
        if (twoR * sqrt(lower) - radius > bestScore) continue
        val s1 = sin(dPhi * 0.5)
        val s2 = sin(dLam * 0.5)
        val hav = s1 * s1 + cosQ * cos((pLatU - 9_000_000) * Mcgp1Format.RAD_PER_UNIT) * s2 * s2
        val distance = twoR * asin(min(1.0, sqrt(hav)))
        if (distance > capM) continue
        val score = distance - radius
        val id = i32(at)
        if (score < bestScore || (score == bestScore && id < bestId)) {
          bestScore = score
          bestDistance = distance
          bestId = id
          bestIndex = i
          bestCell = cell
        }
      }
    }
  }

  companion object {
    /** Metri per unita' (1e-5 gradi) di meridiano. */
    private const val METERS_PER_UNIT = Mcgp1Format.EARTH_RADIUS_M * Mcgp1Format.RAD_PER_UNIT

    /** Il valore di `onlyCountry` che vuol dire "tutti i paesi tranne i micro-stati". */
    private const val ALL_BUT_MICRO_STATES = -1

    /** Dentro un'enclave rispondono solo le sedi amministrative e le capitali (classe 2 e 3), se ce ne sono. */
    private const val MIN_CLASS_IN_ENCLAVE = 2

    /** Dentro un'enclave si cerca al massimo a questa distanza (le enclave sono piu' piccole di 20 km). */
    private const val ENCLAVE_SEARCH_M = 50_000.0

    /**
     * Apre [buffer] (da cima a fondo: il file intero). Controlla intestazione, dimensioni, indici e, se richiesto,
     * il CRC-32 di tutti i byte. Non copia il buffer.
     */
    fun open(buffer: ByteBuffer, verifyChecksum: Boolean): Mcgp1 {
      val b = buffer.duplicate().order(Mcgp1Format.ORDER)
      b.clear()
      val size = b.capacity()
      if (size < Mcgp1Format.HEADER_SIZE + 4) fail(Mcgp1FormatException.Reason.TOO_SMALL, "File troppo corto.")
      for (i in Mcgp1Format.MAGIC.indices) {
        if (b.get(i) != Mcgp1Format.MAGIC[i]) fail(Mcgp1FormatException.Reason.BAD_MAGIC, "Non e' un file MCGP1.")
      }
      val version = b.getInt(12)
      val placeCount = b.getInt(16)
      val cellCount = b.getInt(20)
      val regionCount = b.getInt(24)
      val zoneCount = b.getInt(28)
      val countryCount = b.getInt(32)
      val namesSize = b.getInt(36)
      val poolSize = b.getInt(40)
      if (version <= 0 || placeCount < 0 || cellCount < 0 || regionCount !in 0 until Mcgp1Format.NONE ||
        zoneCount !in 0 until Mcgp1Format.NONE || countryCount !in 0 until Mcgp1Format.NONE || namesSize < 0 || poolSize < 0 ||
        cellCount > Mcgp1Format.ROWS * Mcgp1Format.COLS || cellCount > placeCount || (placeCount > 0 && cellCount == 0)
      ) {
        fail(Mcgp1FormatException.Reason.BAD_COUNTS, "Conteggi non validi.")
      }
      val layout = Mcgp1Layout(placeCount, cellCount, regionCount, zoneCount, countryCount, namesSize, poolSize)
      val expected = longArrayOf(
        layout.offRowStart, layout.offCellCol, layout.offCellFirst, layout.offCellName, layout.offPlaces,
        layout.offNames, layout.offRegions, layout.offZones, layout.offCountries, layout.offPool,
      )
      for (i in expected.indices) {
        if (b.getInt(44 + i * 4).toLong() != expected[i]) fail(Mcgp1FormatException.Reason.BAD_LAYOUT, "Sezioni non coerenti.")
      }
      if (layout.totalSize != size.toLong() || b.getInt(84).toLong() != layout.totalSize) {
        fail(Mcgp1FormatException.Reason.BAD_SIZE, "Dimensione non coerente.")
      }
      if (verifyChecksum) {
        val crc = CRC32()
        val chunk = ByteArray(64 * 1024)
        val reader = b.duplicate().order(Mcgp1Format.ORDER)
        reader.clear()
        val end = size - 4
        reader.limit(end)
        while (reader.hasRemaining()) {
          val n = min(chunk.size, reader.remaining())
          reader.get(chunk, 0, n)
          crc.update(chunk, 0, n)
        }
        if (crc.value.toInt() != b.getInt(end)) fail(Mcgp1FormatException.Reason.BAD_CHECKSUM, "Somma di controllo errata.")
      }
      val file = Mcgp1(b, layout, version)
      file.checkIndex()
      return file
    }

    private fun fail(reason: Mcgp1FormatException.Reason, message: String): Nothing = throw Mcgp1FormatException(reason, message)
  }

  /**
   * Controlla tutto cio' che la lettura usa come indice o come offset: se qui passa, nessun accesso della ricerca o dei
   * campi puo' uscire dal file o dalle tabelle (R3 della revisione di sicurezza della Fase 3: un file alterato con il
   * CRC-32 ricalcolato, che il CRC non vede, deve dare un [Mcgp1FormatException] e non un `IndexOutOfBounds` a meta' ricerca).
   *
   * Un solo passaggio lineare (fasce, celle, luoghi, tabelle: nel file vero qualche centinaio di migliaia di record, pochi
   * millisecondi accanto al CRC-32 che gia' legge tutto il file). Si paga **una volta all'apertura**: la ricerca, che e' il
   * percorso caldo, non cambia di una riga. Controlla:
   *
   * - le fasce: bordi e ordine di `rowStart`; le celle: `cellCol` in `0..COLS-1` e crescente dentro la fascia, `cellFirst`
   *   crescente e dentro `0..placeCount`;
   * - ogni luogo: id positivo (sotto [Mcgp1Format.COUNTRY_ID_BASE]), paese `< countryCount`, regione `< regionCount` oppure [Mcgp1Format.NONE], fuso `< zoneCount` oppure
   *   NONE, e i nomi di ogni cella dentro la tabella dei nomi;
   * - le tabelle: paesi di due lettere maiuscole in ordine stretto (si cerca per bisezione), regioni con id crescenti, paese
   *   valido e nome dentro il pool, fusi con il nome dentro il pool.
   */
  private fun checkIndex() {
    if (rowStart(0) != 0 || rowStart(Mcgp1Format.ROWS) != cellCount) {
      throw Mcgp1FormatException(Mcgp1FormatException.Reason.BAD_INDEX, "Indice delle fasce non valido.")
    }
    for (r in 0 until Mcgp1Format.ROWS) {
      if (rowStart(r) > rowStart(r + 1)) throw Mcgp1FormatException(Mcgp1FormatException.Reason.BAD_INDEX, "Indice delle fasce non ordinato.")
    }
    if (cellFirst(0) != 0 || cellFirst(cellCount) != placeCount) {
      throw Mcgp1FormatException(Mcgp1FormatException.Reason.BAD_INDEX, "Indice delle celle non valido.")
    }
    checkCells()
    checkPlaces()
    checkTables()
  }

  private fun badIndex(message: String): Nothing = throw Mcgp1FormatException(Mcgp1FormatException.Reason.BAD_INDEX, message)

  private fun badRecord(message: String): Nothing = throw Mcgp1FormatException(Mcgp1FormatException.Reason.BAD_RECORD, message)

  /** Le colonne delle celle (in range e crescenti dentro la fascia) e i primi luoghi (crescenti, dentro il file). */
  private fun checkCells() {
    for (r in 0 until Mcgp1Format.ROWS) {
      var previous = -1
      for (cell in rowStart(r) until rowStart(r + 1)) {
        val col = cellCol(cell)
        if (col >= Mcgp1Format.COLS || col <= previous) badIndex("Colonne delle celle non valide.")
        previous = col
      }
    }
    for (cell in 0 until cellCount) {
      val first = cellFirst(cell)
      val last = cellFirst(cell + 1)
      if (first < 0 || last < first || last > placeCount) badIndex("Primi luoghi delle celle non validi.")
    }
  }

  /** L'id, gli indici di paese, regione e fuso di ogni luogo, e i nomi di ogni cella dentro la tabella dei nomi. */
  private fun checkPlaces() {
    val namesSize = layout.namesSize.toLong()
    for (cell in 0 until cellCount) {
      val base = i32(offCellName + cell * 4)
      if (base < 0) badRecord("Nomi delle celle non validi.")
      var namesEnd = base.toLong()
      for (i in cellFirst(cell) until cellFirst(cell + 1)) {
        val at = recordAt(i)
        // L'id diventa un `PlaceId` (positivo) e non deve entrare nell'intervallo dei "luoghi" di un intero paese.
        val id = i32(at)
        if (id <= 0 || id >= Mcgp1Format.COUNTRY_ID_BASE) badRecord("Id di un luogo non valido.")
        if (u16(at + 10) >= countryCount) badRecord("Paese di un luogo non valido.")
        val region = u16(at + 8)
        if (region != Mcgp1Format.NONE && region >= regionCount) badRecord("Regione di un luogo non valida.")
        val zone = u16(at + 12)
        if (zone != Mcgp1Format.NONE && zone >= zoneCount) badRecord("Fuso di un luogo non valido.")
        namesEnd += u8(at + 16)
      }
      if (namesEnd > namesSize) badRecord("Nomi dei luoghi fuori dalla tabella.")
    }
  }

  /** Paesi, regioni e fusi: forma, ordine (dove si cerca per bisezione) e stringhe dentro il pool. */
  private fun checkTables() {
    var previousCode = -1
    for (c in 0 until countryCount) {
      val first = u8(offCountries + c * Mcgp1Format.COUNTRY_SIZE)
      val second = u8(offCountries + c * Mcgp1Format.COUNTRY_SIZE + 1)
      if (first !in 'A'.code..'Z'.code || second !in 'A'.code..'Z'.code) badRecord("Codice di un paese non valido.")
      val code = first * 256 + second
      if (code <= previousCode) badRecord("Paesi non in ordine.")
      previousCode = code
    }
    var previousId = 0
    for (r in 0 until regionCount) {
      val at = offRegions + r * Mcgp1Format.REGION_SIZE
      val id = i32(at)
      if (id <= previousId) badRecord("Regioni non in ordine.")
      previousId = id
      if (u16(at + 4) >= countryCount) badRecord("Paese di una regione non valido.")
      checkPoolString(i32(at + 6), u16(at + 10))
    }
    for (z in 0 until zoneCount) {
      val at = offZones + z * Mcgp1Format.ZONE_SIZE
      checkPoolString(i32(at), u16(at + 4))
    }
  }

  private fun checkPoolString(offset: Int, length: Int) {
    if (offset < 0 || offset.toLong() + length > layout.poolSize) badRecord("Stringa fuori dal pool.")
  }
}
