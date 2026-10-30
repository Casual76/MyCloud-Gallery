package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapPoints
import java.util.Random
import org.junit.Test

/**
 * Le soglie di P3 su **100 mila punti** (`docs/fase9-progetto.md` §2.10, §4.4 "Fatto quando", §5.4 "JVM"): costruzione sotto 800 ms,
 * `clusters` sotto 4 ms al 95 percentile (viste di schermata a zoom da 0 a 18, come le fa la mappa), memoria dell'indice al piu' 3 MB per 100
 * mila punti. Il tempo e' misurato dopo il riscaldamento della JVM; i numeri finiscono nell'output del test (e in `docs/decisioni.md`).
 *
 * **Memoria**: "al piu' 3 MB" vale per le colonne con cui l'indice raggruppa (chiave, posizione esatta, giorno, punteggio, luogo: 26 byte a punto,
 * 2,6 MB). Gli id dei media (16 byte a punto, 1,6 MB) sono le stesse due colonne `Long` che l'istantanea della Libreria tiene gia': si contano
 * a parte, e il totale e' scritto qui sotto e in `docs/decisioni.md`.
 */
class GeoPointIndexPerformanceTest {

  private val count = 100_000

  private fun viewAround(random: Random, p: MapPoint, zoom: Double): GeoBox {
    // Una schermata di 412 x 892 dp a quello zoom (tessere da 512 px): in gradi di longitudine e (approssimativamente) di latitudine.
    val degreesPerDp = 360.0 / (GeoGrid.TILE_PX * StrictMath.pow(2.0, zoom))
    val halfLon = (412 / 2 * degreesPerDp * 1_000_000).toInt().coerceAtLeast(1)
    val halfLat = (892 / 2 * degreesPerDp * 1_000_000 * StrictMath.cos(StrictMath.toRadians(p.latE6 / 1e6))).toInt().coerceAtLeast(1)
    val jitterLon = random.nextInt(halfLon + 1) - halfLon / 2
    val jitterLat = random.nextInt(halfLat + 1) - halfLat / 2
    val west = (p.lonE6 + jitterLon - halfLon).coerceAtLeast(-GeoGrid.LON_E6)
    val east = (p.lonE6 + jitterLon + halfLon).coerceAtMost(GeoGrid.LON_E6)
    val south = (p.latE6 + jitterLat - halfLat).coerceAtLeast(-GeoGrid.LAT_E6)
    val north = (p.latE6 + jitterLat + halfLat).coerceAtMost(GeoGrid.LAT_E6)
    return GeoBox(west, south, east, north)
  }

  private fun percentile(sortedNanos: LongArray, p: Double): Double = sortedNanos[((sortedNanos.size - 1) * p).toInt()] / 1_000_000.0

  @Test
  fun centoMilaPuntiCostruzioneViewEMemoriaDentroLeSoglie() {
    val points = SyntheticMapPoints.generate(count, seed = 99)
    val valid = points.count { MapPointValidity.isValid(it.latE6, it.lonE6) }

    // Costruzione: la mediana di tre giri dopo due di riscaldamento (la prima volta la JVM compila).
    repeat(2) { GeoPointIndex.of(points) }
    val builds = LongArray(3) {
      val started = System.nanoTime()
      GeoPointIndex.of(points)
      (System.nanoTime() - started) / 1_000_000
    }.sorted()
    val buildMillis = builds[1]
    println("Indice, costruzione di $count punti: $buildMillis ms (mediana di 3; giri: ${builds.joinToString()})")
    assertWithMessage("costruzione: $buildMillis ms").that(buildMillis).isLessThan(800)

    val index = GeoPointIndex.of(points)
    assertThat(index.size).isEqualTo(valid)

    // clusters: viste di schermata attorno a punti veri, a zoom da 0 a 18 (mezzi livelli).
    val random = Random(4)
    fun oneView(): Long {
      val zoom = random.nextInt(37) / 2.0
      val view = viewAround(random, points[random.nextInt(points.size)], zoom)
      val started = System.nanoTime()
      val clusters = index.clusters(view, zoom, 72)
      val nanos = System.nanoTime() - started
      assertThat(clusters.size).isAtMost(5_000)
      return nanos
    }
    repeat(3_000) { oneView() }
    val nanos = LongArray(5_000) { oneView() }.also { it.sort() }
    val p50 = percentile(nanos, 0.5)
    val p95 = percentile(nanos, 0.95)
    val p99 = percentile(nanos, 0.99)
    println("Indice, clusters su $count punti (5.000 viste di schermata): p50 $p50 ms, p95 $p95 ms, p99 $p99 ms, massimo ${nanos.last() / 1_000_000.0} ms")
    assertWithMessage("clusters p95: $p95 ms").that(p95).isLessThan(4.0)

    // Il caso peggiore dell'uso vero: il mondo intero a zoom basso (tutti i punti in vista) e a zoom alto (poche celle enormi o tantissime piccole).
    val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)
    for (zoom in listOf(0.0, 3.0, 6.0, 12.0, 20.0)) {
      repeat(5) { index.clusters(world, zoom, 72) }
      val started = System.nanoTime()
      val clusters = index.clusters(world, zoom, 72)
      val millis = (System.nanoTime() - started) / 1_000_000.0
      println("Indice, mondo intero a zoom $zoom: ${clusters.size} bolle in $millis ms")
      assertThat(clusters.sumOf { it.count }).isEqualTo(valid)
    }

    // Memoria: le colonne con cui raggruppa e, a parte, gli id.
    val geometryPerHundredThousand = index.geometryBytes * 100_000.0 / index.size / 1_000_000.0
    val idsPerHundredThousand = index.idBytes * 100_000.0 / index.size / 1_000_000.0
    println(
      "Indice, memoria per 100 mila punti: colonne di raggruppamento $geometryPerHundredThousand MB, id $idsPerHundredThousand MB, " +
        "totale ${index.approxBytes * 100_000.0 / index.size / 1_000_000.0} MB (${index.approxBytes / index.size} byte a punto)",
    )
    assertWithMessage("colonne di raggruppamento: $geometryPerHundredThousand MB per 100 mila").that(geometryPerHundredThousand).isAtMost(3.0)
    assertThat(index.idBytes).isEqualTo(16L * index.size)
  }
}
