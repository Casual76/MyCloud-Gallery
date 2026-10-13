package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Format
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1FormatException
import dev.pampa.mycloudgallery.core.places.dataset.RegionRow
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il formato MCGP1: scrittura, lettura, determinismo e rifiuto dei file danneggiati. */
class Mcgp1FormatTest {

  private val regions = listOf(
    RegionRow("IT.16", 3_165_361, "IT", "Toscana"),
    RegionRow("GB.ENG", 6_269_131, "GB", "Inghilterra"),
  )

  private val places = listOf(
    TestData.place(10, 43.7696, 11.2558, "Firenze", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 380_000),
    TestData.place(11, 43.8056, 11.2931, "Fiesole", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 14_000),
    TestData.place(20, 51.5085, -0.1257, "Londra", "GB", "GB.ENG", "Europe/London", cls = 3, pop = 8_900_000),
    TestData.place(30, 64.1355, -21.8954, "Reykjavík", "IS", null, "Atlantic/Reykjavik", cls = 3, pop = 130_000),
    TestData.place(31, -33.8679, 151.2073, "Sydney", "AU", null, null, cls = 2, pop = 5_000_000),
  )

  @Test
  fun scritturaELetturaConservanoTuttiICampi() {
    val file = TestData.open(TestData.bytes(places, regions, version = 20260314))
    assertThat(file.version).isEqualTo(20260314)
    assertThat(file.placeCount).isEqualTo(places.size)
    assertThat(file.regionCount).isEqualTo(2)
    assertThat(file.zoneCount).isEqualTo(3)
    assertThat(file.countryCount).isEqualTo(4)

    for (p in places) {
      val index = file.indexOfPlaceId(p.id)
      assertThat(index).isAtLeast(0)
      val cell = file.cellOfRecord(index)
      assertThat(file.placeId(index)).isEqualTo(p.id)
      assertThat(file.nameOf(index, cell)).isEqualTo(p.name)
      assertThat(file.countryCode(file.countryOf(index))).isEqualTo(p.countryCode)
      assertThat(file.classOf(index)).isEqualTo(p.cls)
      assertThat(file.populationBucketOf(index)).isEqualTo(Mcgp1Format.populationBucket(p.population))
      val zone = file.zoneOf(index)
      if (p.zone == null) assertThat(zone).isEqualTo(Mcgp1Format.NONE) else assertThat(file.zoneName(zone)).isEqualTo(p.zone)
      val region = file.regionOf(index)
      if (p.regionKey == null) assertThat(region).isEqualTo(Mcgp1Format.NONE)
      else assertThat(file.regionName(region)).isEqualTo(regions.first { it.key == p.regionKey }.name)

      // La posizione, a 1e-5 gradi.
      val row = file.rowOfCell(cell)
      val col = file.colOfCell(cell)
      val lat = file.latUnitsOf(index, row) / 100_000.0 - 90.0
      var lon = file.lonUnitsOf(index, col) / 100_000.0 - 180.0
      if (lon > 180.0) lon -= 360.0
      assertThat(lat).isWithin(0.000006).of(p.latitude)
      assertThat(lon).isWithin(0.000006).of(p.longitude)
    }
    assertThat(file.indexOfPlaceId(999_999)).isEqualTo(-1)
    assertThat(file.regionId(file.regionIndexOfId(3_165_361))).isEqualTo(3_165_361)
    assertThat(file.regionIndexOfId(1)).isEqualTo(-1)
    assertThat(file.countryIndexOf("GB")).isAtLeast(0)
    assertThat(file.countryIndexOf("ZZ")).isEqualTo(-1)
  }

  @Test
  fun lOrdineDellInputNonCambiaIByte() {
    val first = TestData.bytes(places, regions)
    val second = TestData.bytes(places.shuffled(Random(1)), regions.reversed())
    assertThat(second).isEqualTo(first)
  }

  @Test
  fun unDistrettoNelFileNonCompete() {
    // Un distretto e' nel file (si risolve per id, ha la sua classe) ma la ricerca lo salta, anche se e' il piu' vicino.
    val district = TestData.place(1, 45.0, 9.0, "Distretto", pop = 2_000_000, cls = 2).asSecondary()
    val city = TestData.place(2, 45.1, 9.0, "Citta", pop = 5_000_000, cls = 2)
    val file = TestData.open(TestData.bytes(listOf(district, city)))
    val index = file.indexOfPlaceId(1)
    assertThat(file.isSecondary(index)).isTrue()
    assertThat(file.classOf(index)).isEqualTo(2)
    assertThat(file.isSecondary(file.indexOfPlaceId(2))).isFalse()
    assertThat(file.placeId(file.nearest(45.0, 9.0, 100_000.0)!!.index)).isEqualTo(2)
    assertThat(TestData.open(TestData.bytes(listOf(district))).nearest(45.0, 9.0, 100_000.0)).isNull()
    // Il formato non cambia: il bit sta nel byte di classe e un luogo senza il bit e' quello di sempre.
    assertThat(String(TestData.bytes(listOf(district)), 0, 5)).isEqualTo("MCGP1")
  }

  @Test
  fun unDatasetVuotoSiScriveESiLegge() {
    val file = TestData.open(TestData.bytes(emptyList()))
    assertThat(file.placeCount).isEqualTo(0)
    assertThat(file.nearest(45.0, 9.0, 300_000.0)).isNull()
  }

  @Test
  fun gliEstremiDelGlobo() {
    val extremes = listOf(
      TestData.place(1, 90.0, 0.0),
      TestData.place(2, -90.0, 0.0),
      TestData.place(3, 0.0, 180.0),
      TestData.place(4, 0.0, -180.0),
      TestData.place(5, 89.99999, 179.99999),
      TestData.place(6, -89.99999, -179.99999),
    )
    val file = TestData.open(TestData.bytes(extremes))
    assertThat(file.placeCount).isEqualTo(6)
    // +180 e -180 sono lo stesso meridiano: il polo nord si trova anche da qualunque longitudine.
    assertThat(file.placeId(file.nearest(90.0, 123.0, 1_000.0)!!.index)).isEqualTo(1)
    assertThat(file.placeId(file.nearest(-90.0, -77.0, 1_000.0)!!.index)).isEqualTo(2)
    val onAntimeridian = file.nearest(0.0, 180.0, 1_000.0)!!
    assertThat(file.placeId(onAntimeridian.index)).isIn(listOf(3, 4))
    assertThat(onAntimeridian.distanceM).isLessThan(1.0)
  }

  @Test
  fun inomiLunghiSiTroncanoSuUnConfineDiCarattere() {
    val long = "è".repeat(200) // 400 byte in UTF-8
    val file = TestData.open(TestData.bytes(listOf(TestData.place(1, 45.0, 9.0, long))))
    val name = file.nameOf(0, file.cellOfRecord(0))
    assertThat(name.toByteArray(Charsets.UTF_8).size).isAtMost(255)
    assertThat(name).isEqualTo("è".repeat(127)) // 254 byte: il 128-esimo carattere non entra
  }

  @Test
  fun iNomiDiUnaCellaAffollataSiTrovanoAlloStessoModo() {
    // 400 luoghi nella stessa cella da 0,25 gradi, nomi di lunghezze diverse.
    val crowded = (1..400).map { i ->
      TestData.place(i, 45.0 + (i % 20) * 0.01, 9.0 + (i / 20) * 0.01, "N".repeat(1 + i % 37) + i)
    }
    val file = TestData.open(TestData.bytes(crowded))
    assertThat(file.cellCount).isEqualTo(1)
    for (p in crowded) {
      val index = file.indexOfPlaceId(p.id)
      assertThat(file.nameOf(index, file.cellOfRecord(index))).isEqualTo(p.name)
    }
  }

  @Test
  fun laPopolazioneInUnByteCresceInModoMonotono() {
    var last = -1
    var population = 0L
    while (population < 2_000_000_000L) {
      val bucket = Mcgp1Format.populationBucket(population)
      assertThat(bucket).isAtLeast(last)
      assertThat(bucket).isIn(0..255)
      last = bucket
      population = if (population == 0L) 1 else population + population / 7 + 1
    }
    assertThat(Mcgp1Format.populationBucket(0)).isEqualTo(0)
    assertThat(Mcgp1Format.populationBucket(1_000)).isIn(78..82)
    assertThat(Mcgp1Format.populationBucket(1_000_000)).isIn(156..162)
  }

  @Test
  fun unFileDanneggiatoSiRifiutaConIlMotivoGiusto() {
    val good = TestData.bytes(places, regions)

    fun reasonOf(bytes: ByteArray): Mcgp1FormatException.Reason =
      assertThrows(Mcgp1FormatException::class.java) { TestData.open(bytes) }.reason

    assertThat(reasonOf(ByteArray(10))).isEqualTo(Mcgp1FormatException.Reason.TOO_SMALL)
    assertThat(reasonOf(good.copyOf().also { it[0] = 'X'.code.toByte() })).isEqualTo(Mcgp1FormatException.Reason.BAD_MAGIC)
    assertThat(reasonOf(good.copyOf(good.size - 1))).isEqualTo(Mcgp1FormatException.Reason.BAD_SIZE)
    assertThat(reasonOf(good + byteArrayOf(0))).isEqualTo(Mcgp1FormatException.Reason.BAD_SIZE)
    // Un byte cambiato nel corpo: la struttura regge, il CRC no.
    val namesAt = ByteBuffer.wrap(good).order(Mcgp1Format.ORDER).getInt(64)
    val corrupted = good.copyOf().also { it[namesAt] = (it[namesAt] + 1).toByte() }
    assertThat(reasonOf(corrupted)).isEqualTo(Mcgp1FormatException.Reason.BAD_CHECKSUM)
    // ...e senza verifica del CRC passa (e' il costo della mappatura veloce: l'integrita' l'ha controllata l'estrazione).
    TestData.open(corrupted, verify = false)
    // Un conteggio assurdo.
    val absurd = good.copyOf()
    ByteBuffer.wrap(absurd).order(Mcgp1Format.ORDER).putInt(16, Int.MAX_VALUE)
    assertThat(reasonOf(absurd)).isIn(listOf(Mcgp1FormatException.Reason.BAD_COUNTS, Mcgp1FormatException.Reason.BAD_LAYOUT))
  }

  @Test
  fun loScrittoreRifiutaIDatiNonValidi() {
    assertThrows(IllegalArgumentException::class.java) { TestData.bytes(listOf(TestData.place(1, 45.0, 9.0), TestData.place(1, 46.0, 9.0))) }
    assertThrows(IllegalArgumentException::class.java) { TestData.bytes(listOf(TestData.place(1, 95.0, 9.0))) }
    assertThrows(IllegalArgumentException::class.java) { TestData.bytes(listOf(TestData.place(1, 45.0, 9.0, cc = "it"))) }
    assertThrows(IllegalArgumentException::class.java) { TestData.bytes(listOf(TestData.place(1, 45.0, 9.0, region = "IT.99"))) }
    assertThrows(IllegalArgumentException::class.java) { TestData.bytes(listOf(TestData.place(1, 45.0, 9.0, name = "  "))) }
  }
}
