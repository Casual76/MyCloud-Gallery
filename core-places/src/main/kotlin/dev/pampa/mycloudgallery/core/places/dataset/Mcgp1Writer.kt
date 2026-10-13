package dev.pampa.mycloudgallery.core.places.dataset

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Scrive un file MCGP1. **Deterministico**: lo stesso [DatasetData] (anche con le liste in ordine diverso) da' sempre
 * gli stessi byte, quindi lo stesso SHA-256. Niente orologio, niente ordine di una mappa hash, niente `log`.
 */
internal object Mcgp1Writer {

  private class Prepared(
    val place: PlaceRow,
    val cell: Int,
    val latUnits: Int,
    val lonUnits: Int,
    val nameBytes: ByteArray,
  )

  fun write(data: DatasetData): ByteArray {
    require(data.version > 0) { "La versione del dataset deve essere positiva." }

    // Regioni: per id, e ogni chiave una volta sola.
    val regions = data.regions.sortedWith(compareBy({ it.id }, { it.key }))
    val regionIndexByKey = HashMap<String, Int>()
    regions.forEachIndexed { index, region ->
      require(region.id > 0 && region.id < Mcgp1Format.COUNTRY_ID_BASE) { "Id di regione fuori intervallo." }
      require(regionIndexByKey.put(region.key, index) == null) { "Chiave di regione ripetuta." }
      if (index > 0) require(regions[index - 1].id != region.id) { "Id di regione ripetuto." }
      requireCountry(region.countryCode)
    }
    require(regions.size < Mcgp1Format.NONE) { "Troppe regioni." }

    // Paesi e fusi: insiemi ordinati, l'indice e' la posizione.
    val countries = sortedSetOf<String>()
    val zones = sortedSetOf<String>()
    regions.forEach { countries += it.countryCode }
    data.places.forEach { place ->
      requireCountry(place.countryCode)
      countries += place.countryCode
      place.zone?.let {
        require(it.isNotBlank()) { "Fuso vuoto." }
        zones += it
      }
    }
    require(countries.size < Mcgp1Format.NONE && zones.size < Mcgp1Format.NONE) { "Troppi paesi o fusi." }
    val countryIndex = countries.withIndex().associate { it.value to it.index }
    val zoneIndex = zones.withIndex().associate { it.value to it.index }

    // Luoghi: per cella e poi per id.
    val seenIds = HashSet<Int>()
    val prepared = data.places.map { place ->
      require(place.id > 0 && place.id < Mcgp1Format.COUNTRY_ID_BASE) { "Id di luogo fuori intervallo." }
      require(seenIds.add(place.id)) { "Id di luogo ripetuto." }
      require(place.cls in 0..3) { "Classe di luogo non valida." }
      require(place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0) { "Coordinate fuori intervallo." }
      require(place.name.isNotBlank()) { "Nome di luogo vuoto." }
      place.regionKey?.let { require(it in regionIndexByKey) { "Regione sconosciuta." } }
      val latUnits = Mcgp1Format.latUnits(place.latitude)
      val lonUnits = Mcgp1Format.lonUnits(place.longitude)
      Prepared(
        place = place,
        cell = Mcgp1Format.rowOf(latUnits) * Mcgp1Format.COLS + Mcgp1Format.colOf(lonUnits),
        latUnits = latUnits,
        lonUnits = lonUnits,
        nameBytes = utf8Limited(place.name, Mcgp1Format.MAX_NAME_BYTES),
      )
    }.sortedWith(compareBy({ it.cell }, { it.place.id }))

    // Celle non vuote.
    val cellKeys = ArrayList<Int>()
    val cellFirst = ArrayList<Int>()
    val cellNameBase = ArrayList<Int>()
    var namesSize = 0
    prepared.forEachIndexed { index, item ->
      if (cellKeys.isEmpty() || cellKeys.last() != item.cell) {
        cellKeys += item.cell
        cellFirst += index
        cellNameBase += namesSize
      }
      namesSize += item.nameBytes.size
    }
    val cellCount = cellKeys.size

    // Pool delle stringhe di regioni e fusi.
    val pool = java.io.ByteArrayOutputStream()
    val regionPool = regions.map { region ->
      val bytes = utf8Limited(region.name.ifBlank { region.key }, Mcgp1Format.MAX_NAME_BYTES)
      val offset = pool.size()
      pool.write(bytes)
      offset to bytes.size
    }
    val zonePool = zones.map { zone ->
      val bytes = zone.toByteArray(Charsets.UTF_8)
      require(bytes.size <= 0xFFFF) { "Fuso troppo lungo." }
      val offset = pool.size()
      pool.write(bytes)
      offset to bytes.size
    }
    val poolBytes = pool.toByteArray()

    val layout = Mcgp1Layout(
      placeCount = prepared.size,
      cellCount = cellCount,
      regionCount = regions.size,
      zoneCount = zones.size,
      countryCount = countries.size,
      namesSize = namesSize,
      poolSize = poolBytes.size,
    )
    require(layout.totalSize < Int.MAX_VALUE) { "Dataset troppo grande." }

    val buffer = ByteBuffer.allocate(layout.totalSize.toInt()).order(Mcgp1Format.ORDER)

    // Intestazione.
    putBytes(buffer, 0, Mcgp1Format.MAGIC)
    buffer.putInt(8, 0)
    buffer.putInt(12, data.version)
    buffer.putInt(16, layout.placeCount)
    buffer.putInt(20, layout.cellCount)
    buffer.putInt(24, layout.regionCount)
    buffer.putInt(28, layout.zoneCount)
    buffer.putInt(32, layout.countryCount)
    buffer.putInt(36, layout.namesSize)
    buffer.putInt(40, layout.poolSize)
    buffer.putInt(44, layout.offRowStart.toInt())
    buffer.putInt(48, layout.offCellCol.toInt())
    buffer.putInt(52, layout.offCellFirst.toInt())
    buffer.putInt(56, layout.offCellName.toInt())
    buffer.putInt(60, layout.offPlaces.toInt())
    buffer.putInt(64, layout.offNames.toInt())
    buffer.putInt(68, layout.offRegions.toInt())
    buffer.putInt(72, layout.offZones.toInt())
    buffer.putInt(76, layout.offCountries.toInt())
    buffer.putInt(80, layout.offPool.toInt())
    buffer.putInt(84, layout.totalSize.toInt())

    // Indice delle fasce e delle celle.
    val rowStart = IntArray(Mcgp1Format.ROWS + 1)
    cellKeys.forEach { rowStart[it / Mcgp1Format.COLS + 1]++ }
    for (r in 1..Mcgp1Format.ROWS) rowStart[r] += rowStart[r - 1]
    rowStart.forEachIndexed { r, value -> buffer.putInt(layout.offRowStart.toInt() + r * 4, value) }
    cellKeys.forEachIndexed { i, key ->
      buffer.putShort(layout.offCellCol.toInt() + i * 2, (key % Mcgp1Format.COLS).toShort())
      buffer.putInt(layout.offCellFirst.toInt() + i * 4, cellFirst[i])
      buffer.putInt(layout.offCellName.toInt() + i * 4, cellNameBase[i])
    }
    buffer.putInt(layout.offCellFirst.toInt() + cellCount * 4, prepared.size)

    // Luoghi e nomi.
    var nameOffset = 0
    prepared.forEachIndexed { i, item ->
      val at = layout.offPlaces.toInt() + i * Mcgp1Format.PLACE_SIZE
      val row = item.cell / Mcgp1Format.COLS
      val col = item.cell % Mcgp1Format.COLS
      buffer.putInt(at, item.place.id)
      buffer.putShort(at + 4, (item.latUnits - row * Mcgp1Format.CELL_UNITS).toShort())
      buffer.putShort(at + 6, (item.lonUnits - col * Mcgp1Format.CELL_UNITS).toShort())
      buffer.putShort(at + 8, (item.place.regionKey?.let { regionIndexByKey.getValue(it) } ?: Mcgp1Format.NONE).toShort())
      buffer.putShort(at + 10, countryIndex.getValue(item.place.countryCode).toShort())
      buffer.putShort(at + 12, (item.place.zone?.let { zoneIndex.getValue(it) } ?: Mcgp1Format.NONE).toShort())
      buffer.put(at + 14, (item.place.cls or (if (item.place.secondary) Mcgp1Format.FLAG_SECONDARY else 0)).toByte())
      buffer.put(at + 15, Mcgp1Format.populationBucket(item.place.population).toByte())
      buffer.put(at + 16, item.nameBytes.size.toByte())
      putBytes(buffer, layout.offNames.toInt() + nameOffset, item.nameBytes)
      nameOffset += item.nameBytes.size
    }

    // Regioni, fusi, paesi, pool.
    regions.forEachIndexed { i, region ->
      val at = layout.offRegions.toInt() + i * Mcgp1Format.REGION_SIZE
      buffer.putInt(at, region.id)
      buffer.putShort(at + 4, countryIndex.getValue(region.countryCode).toShort())
      buffer.putInt(at + 6, regionPool[i].first)
      buffer.putShort(at + 10, regionPool[i].second.toShort())
    }
    zonePool.forEachIndexed { i, (offset, length) ->
      val at = layout.offZones.toInt() + i * Mcgp1Format.ZONE_SIZE
      buffer.putInt(at, offset)
      buffer.putShort(at + 4, length.toShort())
    }
    countries.forEachIndexed { i, code ->
      val at = layout.offCountries.toInt() + i * Mcgp1Format.COUNTRY_SIZE
      buffer.put(at, code[0].code.toByte())
      buffer.put(at + 1, code[1].code.toByte())
    }
    putBytes(buffer, layout.offPool.toInt(), poolBytes)

    // CRC-32 di tutto il resto.
    val crc = CRC32()
    crc.update(buffer.array(), 0, layout.totalSize.toInt() - 4)
    buffer.putInt(layout.totalSize.toInt() - 4, crc.value.toInt())
    return buffer.array()
  }

  /** Copia [bytes] a partire da [at] (la put assoluta di array non esiste prima di Java 13 / Android 14). */
  private fun putBytes(buffer: ByteBuffer, at: Int, bytes: ByteArray) {
    for (i in bytes.indices) buffer.put(at + i, bytes[i])
  }

  private fun requireCountry(code: String) {
    require(code.length == 2 && code[0] in 'A'..'Z' && code[1] in 'A'..'Z') { "Codice paese non valido." }
  }

  /** UTF-8 di [text], troncato a [max] byte su un confine di carattere. */
  fun utf8Limited(text: String, max: Int): ByteArray {
    val bytes = text.trim().toByteArray(Charsets.UTF_8)
    if (bytes.size <= max) return bytes
    var end = max
    // Se il primo byte escluso e' una continuazione, il carattere era a meta': si torna indietro fino al suo inizio.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end)
  }
}
