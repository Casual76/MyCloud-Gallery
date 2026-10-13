package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.PlaceRow
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import java.util.zip.Deflater
import kotlin.math.cos
import kotlin.random.Random
import org.junit.Test

/**
 * "100 mila ricerche in meno di 2 s su JVM" (docs/fase3-progetto.md §4.3), misurate su un dataset delle dimensioni di
 * quello vero: circa 170 mila luoghi raggruppati in citta', come i luoghi veri (non sparsi a caso).
 */
class PlacesPerformanceTest {

  /** 170 mila luoghi in 1500 "citta" sparse sul globo, piu' fitte al centro. Nomi di lunghezza realistica. */
  private fun realisticSize(random: Random): List<PlaceRow> {
    val centers = (0 until 1500).map {
      val lat = Math.toDegrees(Math.asin(random.nextDouble(-0.95, 0.95)))
      lat to random.nextDouble(-180.0, 180.0)
    }
    val syllables = listOf("San", "Santa", "Monte", "Villa", "Ponte", "Borgo", "Castel", "Campo", "Val", "Porto", "Torre", "Rocca")
    val tails = listOf("lupo", "marino", "bianco", "alto", "basso", "vecchio", "nuovo", "del Garda", "di Sotto", "sul Mare", "Maggiore", "dei Marsi")
    return (1..170_000).map { id ->
      val (cLat, cLon) = centers[random.nextInt(centers.size)]
      val radius = random.nextDouble() * random.nextDouble() * 1.2 // piu' fitti al centro
      val lat = (cLat + random.nextDouble(-radius, radius)).coerceIn(-89.9, 89.9)
      var lon = cLon + random.nextDouble(-radius, radius) / cos(Math.toRadians(lat)).coerceAtLeast(0.05)
      while (lon > 180) lon -= 360
      while (lon < -180) lon += 360
      TestData.place(
        id = id,
        lat = lat,
        lon = lon,
        name = syllables[random.nextInt(syllables.size)] + " " + tails[random.nextInt(tails.size)] + if (id % 3 == 0) " $id" else "",
        cc = "IT",
        zone = "Europe/Rome",
        cls = 1 + random.nextInt(3),
        pop = random.nextLong(1_000, 3_000_000),
      )
    }
  }

  @Test
  fun centomilaRicercheInMenoDiDueSecondi() {
    val random = Random(2026)
    val places = realisticSize(random)
    val bytes = TestData.bytes(places)
    val data = PlacesDataset.openForTest(bytes)

    // Le interrogazioni: 90 mila vicino a luoghi veri (come le foto di una vita), 10 mila sparse sul globo.
    val queries = (0 until 100_000).map { i ->
      if (i % 10 == 0) {
        GeoPoint(Math.toDegrees(Math.asin(random.nextDouble(-1.0, 1.0))), random.nextDouble(-180.0, 180.0))
      } else {
        val p = places[random.nextInt(places.size)]
        GeoPoint((p.latitude + random.nextDouble(-0.2, 0.2)).coerceIn(-90.0, 90.0), (p.longitude + random.nextDouble(-0.2, 0.2)).coerceIn(-180.0, 180.0))
      }
    }

    val started = System.nanoTime()
    var found = 0
    for (q in queries) if (data.nearest(q) != null) found++
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    val zip = Deflater(Deflater.BEST_COMPRESSION).run {
      setInput(bytes)
      finish()
      val buffer = ByteArray(1 shl 16)
      var total = 0
      while (!finished()) total += deflate(buffer)
      end()
      total
    }
    println(
      "170000 luoghi -> ${bytes.size} byte (${bytes.size / 170_000.0} B per luogo, ${zip / 1024} KiB compressi); " +
        "100000 ricerche in $elapsedMs ms (${found} con un luogo)",
    )
    assertThat(found).isGreaterThan(80_000)
    assertThat(elapsedMs).isLessThan(2_000)
    // 17 byte per luogo + il nome + l'indice delle celle. I nomi di prova sono piu' lunghi (17 caratteri in media) di quelli
    // di GeoNames (circa 10): con i nomi veri il file sta nei 4-5 MB previsti dal progetto (circa 2,5 MB compressi).
    assertThat(bytes.size / 170_000.0).isLessThan(36.0)
  }

  /**
   * L'apertura controlla gli indici di ogni luogo e di ogni cella (R3: un file alterato col CRC rifatto non deve far
   * uscire un'eccezione di indice dalla ricerca). E' un passaggio lineare fatto una volta, accanto al CRC-32 che gia'
   * legge tutto il file: su 170 mila luoghi deve restare nell'ordine delle decine di millisecondi, molto sotto il secondo.
   */
  @Test
  fun lAperturaConLaValidazioneDegliIndiciCostaPoco() {
    val bytes = TestData.bytes(realisticSize(Random(2027)))
    PlacesDataset.openForTest(bytes) // riscaldamento della JIT
    val runs = 5
    val started = System.nanoTime()
    repeat(runs) { PlacesDataset.openForTest(bytes) }
    val perOpenMs = (System.nanoTime() - started) / 1_000_000.0 / runs
    println("apertura di 170000 luoghi (CRC-32 e validazione degli indici): %.1f ms".format(perOpenMs))
    assertThat(perOpenMs).isLessThan(1_000.0)
  }

  @Test
  fun centomilaRicercheSulMiniDataset() {
    val data = PlacesDataset.openForTest(TestData.miniBytes)
    val random = Random(1)
    val queries = (0 until 100_000).map { GeoPoint(random.nextDouble(-60.0, 70.0), random.nextDouble(-180.0, 180.0)) }
    val started = System.nanoTime()
    var found = 0
    for (q in queries) if (data.nearest(q) != null) found++
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    println("mini-dataset: 100000 ricerche sparse in $elapsedMs ms ($found con un luogo)")
    assertThat(elapsedMs).isLessThan(2_000)
  }

  @Test
  fun iFusiSiCercanoVelocementeQuantoIlResto() {
    val data = PlacesDataset.openForTest(TestData.miniBytes)
    val random = Random(2)
    val started = System.nanoTime()
    repeat(100_000) { data.zoneAt(GeoPoint(random.nextDouble(35.0, 47.0), random.nextDouble(6.0, 18.0))) }
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
  }
}
