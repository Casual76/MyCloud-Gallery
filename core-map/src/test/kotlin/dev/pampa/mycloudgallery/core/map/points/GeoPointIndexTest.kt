package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.ClusterIndexContract
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapPoints
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import java.util.UUID
import org.junit.Test

/** La suite di contratto dell'indice (P0) sull'indice **vero** (P3): le stesse regole del riferimento a forza bruta. */
class GeoPointIndexContractTest : ClusterIndexContract() {
  override fun index(points: List<MapPoint>): GeoClusterIndex = GeoPointIndex.of(points)
}

/**
 * Fase 9 (P3; D27, D28, §5.2): l'indice vero e' **uguale** al riferimento a forza bruta (conteggi, centri, copertine, luoghi, membri) su 1.000
 * viste casuali a **ogni** livello della griglia (0..48), antimeridiano e poli compresi, con le due strade di [GeoPointIndex.clusters] (finestra
 * di celle e camminata dell'array) e con la cache delle celle pesanti; e gli edge case dei punti.
 */
class GeoPointIndexTest {

  /**
   * Il riferimento di P0 con il lavoro che non cambia fatto una volta sola: le chiavi dei punti e, per livello, le celle con i loro contenuti
   * (`ReferenceClusterIndex.cluster`, la regola di P0) e le loro colonne e righe (`GeoGrid.ranges`). A ogni chiamata resta il confronto di ogni
   * cella con la vista, con **lo stesso criterio** di `GeoGrid.touches`; il primo test di questa classe prova che coincide con `ReferenceClusterIndex`.
   */
  private class KeyedReference(points: List<MapPoint>) : GeoClusterIndex {
    private val valid = points.filter { MapPointValidity.isValid(it.latE6, it.lonE6) }.distinctBy { it.id }
    private val keys = LongArray(valid.size) { GeoGrid.key(valid[it].latE6, valid[it].lonE6) }
    override val size: Int get() = valid.size

    private class Cell(val cluster: GeoCluster, val xs: LongRange, val ys: LongRange)

    private val levels = HashMap<Int, List<Cell>>()

    private fun cells(level: Int): List<Cell> = levels.getOrPut(level) {
      val groups = HashMap<Long, MutableList<MapPoint>>()
      for (i in valid.indices) groups.getOrPut(keys[i] ushr (GeoGrid.MAX_LEVEL - level)) { ArrayList() } += valid[i]
      groups.entries.sortedBy { it.key }.map {
        val key = CellKey(level, it.key)
        val (xs, ys) = GeoGrid.ranges(key)
        Cell(ReferenceClusterIndex.cluster(key, it.value), xs, ys)
      }
    }

    override fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster> {
      val level = GeoGrid.levelFor(zoom, cellPx)
      val (cols, rows) = GeoGrid.viewRanges(view)
      return cells(level).filter { cell ->
        !(cell.ys.last < rows.first || cell.ys.first > rows.last) && cols.any { !(cell.xs.last < it.first || cell.xs.first > it.last) }
      }.map { it.cluster }
    }

    override fun members(key: CellKey, limit: Int): List<MediaId> = valid.indices.filter { keys[it] ushr (GeoGrid.MAX_LEVEL - key.level) == key.prefix }
      .map { valid[it] }.sortedWith(compareByDescending<MapPoint> { it.day }.thenBy { it.id.value }).take(limit).map { it.id }

    override fun boundsOfAll(): GeoBox? = ReferenceClusterIndex(valid).boundsOfAll()
  }

  private fun point(n: Long, lat: Int, lon: Int, day: Int = 20250101, score: Short = 0, place: Int = 0) =
    MapPoint(MediaId(UUID(9L, n).toString()), lat, lon, day, score, place)

  /** Lo zoom per cui `levelFor(zoom, cellPx)` da' [level] (a meta' dell'intervallo di quel livello). */
  private fun zoomFor(level: Int, cellPx: Int): Double =
    StrictMath.log(cellPx.toDouble() / GeoGrid.TILE_PX * StrictMath.pow(2.0, (level + 0.5) / 2.0)) / StrictMath.log(2.0)

  /** Una vista casuale di un genere a caso: del mondo, di una schermata attorno a un punto, che attraversa l'antimeridiano, ai poli. */
  private fun randomView(random: Random, points: List<MapPoint>): GeoBox {
    val around = points[random.nextInt(points.size)]
    return when (random.nextInt(6)) {
      0 -> GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)
      1 -> {
        val half = 200_000 * (1 shl random.nextInt(8))
        GeoBox(
          (around.lonE6 - half).coerceIn(-GeoGrid.LON_E6, GeoGrid.LON_E6), (around.latE6 - half / 2).coerceIn(-GeoGrid.LAT_E6, GeoGrid.LAT_E6),
          (around.lonE6 + half).coerceIn(-GeoGrid.LON_E6, GeoGrid.LON_E6), (around.latE6 + half / 2).coerceIn(-GeoGrid.LAT_E6, GeoGrid.LAT_E6),
        )
      }
      2 -> GeoBox(150_000_000 + random.nextInt(30_000_000), random.nextInt(60_000_000) - 30_000_000, -150_000_000 - random.nextInt(30_000_000), 40_000_000 + random.nextInt(40_000_000))
      3 -> GeoBox(random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, 80_000_000, random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, GeoGrid.LAT_E6)
      4 -> GeoBox(random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, -GeoGrid.LAT_E6, random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, -80_000_000)
      else -> {
        val south = random.nextInt(2 * 85_000_000) - 85_000_000
        GeoBox(
          random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, south, random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6,
          (south + random.nextInt(60_000_000)).coerceAtMost(GeoGrid.LAT_E6),
        )
      }
    }
  }

  @Test
  fun ilRiferimentoVeloceEUgualeAQuelloDiP0() {
    val points = SyntheticMapPoints.generate(1_500, seed = 21)
    val slow = ReferenceClusterIndex(points)
    val fast = KeyedReference(points)
    val random = Random(5)
    repeat(120) {
      val view = randomView(random, points)
      val zoom = random.nextInt(48) / 2.0
      assertThat(fast.clusters(view, zoom, 72)).isEqualTo(slow.clusters(view, zoom, 72))
    }
    assertThat(fast.boundsOfAll()).isEqualTo(slow.boundsOfAll())
  }

  /** §5.2: 1.000 viste casuali per **ogni** livello (0..48), uguale al riferimento: conteggi, centri, copertine, luoghi e membri. */
  @Test
  fun milleVisteCasualiPerOgniLivelloSonoUgualiAlRiferimento() {
    val points = SyntheticMapPoints.generate(3_000, seed = 31)
    val index = GeoPointIndex.of(points)
    val reference = KeyedReference(points)
    val random = Random(9)
    for (level in 0..GeoGrid.MAX_LEVEL) {
      for (n in 0 until 1_000) {
        val view = randomView(random, points)
        val cellPx = if (n % 5 == 0) 36 else 72
        val zoom = zoomFor(level, cellPx)
        if (n == 0) assertThat(GeoGrid.levelFor(zoom, cellPx)).isEqualTo(level)
        val got = index.clusters(view, zoom, cellPx)
        val want = reference.clusters(view, zoom, cellPx)
        if (got != want) assertWithMessage("livello $level, vista $n").that(got).isEqualTo(want)
        if (got.isNotEmpty() && n % 10 == 0) {
          val cell = got[random.nextInt(got.size)]
          val limit = 1 + random.nextInt(60)
          assertWithMessage("membri, livello $level, vista $n").that(index.members(cell.key, limit)).isEqualTo(reference.members(cell.key, limit))
        }
      }
    }
  }

  /** Un insieme grande, con celle pesanti (la cache) e una vista di schermata come nell'uso vero: uguale al riferimento a ogni livello. */
  @Test
  fun conLeCellePesantiELaCacheLIndiceRestaUgualeAlRiferimento() {
    val points = SyntheticMapPoints.generate(30_000, seed = 41)
    val index = GeoPointIndex.of(points)
    val reference = KeyedReference(points)
    val random = Random(13)
    for (level in 0..GeoGrid.MAX_LEVEL) {
      repeat(60) { n ->
        val view = randomView(random, points)
        val zoom = zoomFor(level, 72)
        // Due volte: la seconda trova le celle pesanti nella cache e deve dire lo stesso.
        val first = index.clusters(view, zoom, 72)
        assertThat(index.clusters(view, zoom, 72)).isEqualTo(first)
        if (first != reference.clusters(view, zoom, 72)) assertWithMessage("livello $level, vista $n").that(first).isEqualTo(reference.clusters(view, zoom, 72))
      }
    }
    assertThat(index.clusters(GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6), 0.0, 72).sumOf { it.count })
      .isEqualTo(points.count { MapPointValidity.isValid(it.latE6, it.lonE6) })
  }

  /** E-MAP7: i punti che non entrano (limiti, (0, 0) esatto, oltre +-85 gradi) non sono nell'indice e si contano; E-MAP10: i punti a 85 gradi si'. */
  @Test
  fun lePosizioniAssurdeNonEntranoESiContano() {
    val builder = GeoPointIndexBuilder()
    val good = listOf(point(1, 84_999_999, 10), point(2, -85_000_000, -180_000_000), point(3, 1, 0), point(4, 0, 1), point(5, 10, 180_000_000))
    val bad = listOf(
      point(6, 0, 0), point(7, 85_000_001, 0), point(8, -85_000_001, 0), point(9, 0, 180_000_001), point(10, 0, -180_000_001),
      point(11, Int.MAX_VALUE, 5), point(12, 5, Int.MIN_VALUE),
    )
    builder.addAll(good + bad)
    assertThat(builder.size).isEqualTo(good.size)
    assertThat(builder.invalid).isEqualTo(bad.size)
    val index = builder.build()
    assertThat(index.size).isEqualTo(good.size)
    val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)
    assertThat(index.clusters(world, 20.0, 72).flatMap { index.members(it.key, 10) }).containsExactlyElementsIn(good.map { it.id })
  }

  /** Due punti con lo stesso id: vale il primo, come `distinctBy` del riferimento; il doppione si conta. */
  @Test
  fun ilPrimoDiDuePuntiConLoStessoIdVale() {
    val first = point(1, 10_000_000, 10_000_000, score = 3)
    val again = point(1, -20_000_000, 50_000_000, score = 9)
    val builder = GeoPointIndexBuilder(expected = 4)
    builder.addAll(listOf(first, point(2, 10_000_001, 10_000_001), again))
    assertThat(builder.duplicates).isEqualTo(1)
    assertThat(builder.size).isEqualTo(2)
    val index = builder.build()
    assertThat(index.size).isEqualTo(2)
    assertThat(index.boundsOfAll()).isEqualTo(GeoBox(10_000_000, 10_000_000, 10_000_001, 10_000_001))
    // Cresce oltre la capacita' iniziale senza perdere i doppioni gia' visti.
    val many = GeoPointIndexBuilder(expected = 1)
    val points = List(5_000) { point(it.toLong(), 1_000_000 + it, 2_000_000 + it) }
    many.addAll(points + points)
    assertThat(many.size).isEqualTo(5_000)
    assertThat(many.duplicates).isEqualTo(5_000)
  }

  @Test
  fun unIndiceVuotoNonHaBolleNeMembriNeRiquadro() {
    val index = GeoPointIndex.of(emptyList())
    assertThat(index.size).isEqualTo(0)
    assertThat(index.clusters(GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6), 3.0, 72)).isEmpty()
    assertThat(index.members(CellKey(0, 0), 10)).isEmpty()
    assertThat(index.boundsOfAll()).isNull()
    assertThat(index.approxBytes).isEqualTo(0L)
  }

  /** I membri: dal piu' recente, al piu' `limit`; con `limit` 0 niente; con `limit` oltre la cella, tutta la cella; una cella senza punti e' vuota. */
  @Test
  fun iMembriDalPiuRecenteConIlLimite() {
    val points = (1..40).map { point(it.toLong(), 43_000_000 + it, 11_000_000, day = 20240100 + it % 7) }
    val index = GeoPointIndex.of(points)
    val cell = index.clusters(GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6), 2.0, 72).single()
    val expected = points.sortedWith(compareByDescending<MapPoint> { it.day }.thenBy { it.id.value }).map { it.id }
    assertThat(index.members(cell.key, 0)).isEmpty()
    for (limit in listOf(1, 2, 7, 39, 40, 41, 1_000)) {
      assertThat(index.members(cell.key, limit)).containsExactlyElementsIn(expected.take(limit)).inOrder()
    }
    assertThat(index.members(CellKey(48, 5), 10)).isEmpty()
    assertThat(index.members(CellKey(0, 0), 10)).hasSize(10)
    val negative = runCatching { index.members(cell.key, -1) }
    assertThat(negative.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  /** Una vista piu' piccola di una cella, o a cavallo del bordo di due celle, vede le celle che tocca (anche una sola) e nessun'altra. */
  @Test
  fun unaVistaPiccolaVedeSoloLeCelleCheTocca() {
    val points = SyntheticMapPoints.generate(4_000, seed = 3, withEdges = false)
    val index = GeoPointIndex.of(points)
    val reference = KeyedReference(points)
    val random = Random(2)
    repeat(300) {
      val around = points[random.nextInt(points.size)]
      val view = GeoBox(around.lonE6 - 10, around.latE6 - 10, around.lonE6 + 10, around.latE6 + 10)
      val zoom = random.nextInt(40) / 2.0
      val got = index.clusters(view, zoom, 72)
      assertThat(got).isEqualTo(reference.clusters(view, zoom, 72))
      assertThat(got).isNotEmpty()
      for (cluster in got) assertThat(GeoGrid.touches(cluster.key, view)).isTrue()
    }
  }

  /** Una vista che attraversa l'antimeridiano quasi tutto il mondo: le due metà dividono una colonna di celle, e ogni cella esce una volta sola. */
  @Test
  fun unaVistaChePassaLAntimeridianoNonDuplicaLeCelle() {
    val points = SyntheticMapPoints.generate(5_000, seed = 8)
    val index = GeoPointIndex.of(points)
    val reference = KeyedReference(points)
    for (zoom in listOf(0.0, 1.0, 2.0, 3.5, 6.0)) {
      val view = GeoBox(-20_000_000, -40_000_000, -40_000_000, 60_000_000)
      assertThat(view.crossesAntimeridian).isTrue()
      val got = index.clusters(view, zoom, 72)
      assertThat(got.map { it.key }).containsNoDuplicates()
      assertThat(got).isEqualTo(reference.clusters(view, zoom, 72))
    }
  }

  /** E-MAP9: ventimila foto nello stesso punto sono una bolla sola a ogni livello (anche con la cache), con la copertina e i membri giusti. */
  @Test
  fun ventimilaFotoNelloStessoPuntoSonoUnaBollaSola() {
    val random = Random(17)
    val points = List(20_000) { point(it.toLong() + 1, 45_123_456, 9_123_456, day = 20200101 + random.nextInt(2_000), score = random.nextInt(30).toShort(), place = 3_000_000 + random.nextInt(3)) }
    val index = GeoPointIndex.of(points + point(99_999, -33_000_000, 151_000_000))
    val reference = KeyedReference(points + point(99_999, -33_000_000, 151_000_000))
    val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)
    for (zoom in listOf(0.0, 6.0, 12.0, 22.0)) {
      repeat(2) {
        val clusters = index.clusters(world, zoom, 72)
        assertThat(clusters).isEqualTo(reference.clusters(world, zoom, 72))
        if (zoom >= 6.0) assertThat(clusters.map { it.count }.sorted()).containsExactly(1, 20_000).inOrder()
      }
    }
    val big = index.clusters(world, 22.0, 72).first { it.count == 20_000 }
    val best = points.maxWith(compareBy<MapPoint> { it.score }.thenBy { it.day }.thenByDescending { it.id.value })
    assertThat(big.cover).isEqualTo(best.id)
    val newest = points.sortedWith(compareByDescending<MapPoint> { it.day }.thenBy { it.id.value }).take(30).map { it.id }
    assertThat(index.members(big.key, 30)).isEqualTo(newest)
  }

  @Test
  fun nessunaStringaMostraCoordinateOId() {
    val index = GeoPointIndex.of(listOf(point(1, 43_123_456, 11_654_321)))
    val cluster = index.clusters(GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6), 4.0, 72).single()
    val texts = listOf(index.toString(), cluster.toString(), cluster.center.toString(), GeoPointIndexBuilder().toString(), PlaceTally().toString())
    for (text in texts) {
      assertThat(text).doesNotContain("43123456")
      assertThat(text).doesNotContain("11654321")
      assertThat(text).doesNotContain(UUID(9L, 1L).toString())
    }
    assertThat(cluster.center).isEqualTo(GeoE6(43_123_456, 11_654_321))
  }

  /** Il luogo di una cella grande (oltre la tabella iniziale del conteggio): il piu' frequente, a parita' l'id minore, mai lo 0. */
  @Test
  fun ilLuogoPiuFrequenteAncheConMolteLocalita() {
    val random = Random(6)
    val points = ArrayList<MapPoint>()
    var n = 0L
    // 3.000 punti nella stessa cella con 400 luoghi diversi: il 7 ricorre di piu' (a pari merito col 3, vince l'id minore).
    repeat(3_000) { points += point(n++, 43_000_000 + random.nextInt(1_000), 11_000_000 + random.nextInt(1_000), place = if (random.nextInt(4) == 0) 0 else 1_000 + random.nextInt(400)) }
    repeat(30) { points += point(n++, 43_000_500, 11_000_500, place = 7) }
    repeat(30) { points += point(n++, 43_000_501, 11_000_501, place = 3) }
    val index = GeoPointIndex.of(points)
    val reference = KeyedReference(points)
    val view = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)
    for (zoom in listOf(0.0, 5.0, 12.0)) {
      val got = index.clusters(view, zoom, 72)
      assertThat(got).isEqualTo(reference.clusters(view, zoom, 72))
      assertThat(got.single().placeId).isEqualTo(3)
    }
  }
}
