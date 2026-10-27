package dev.pampa.mycloudgallery.core.map.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapPoints
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import java.util.UUID
import org.junit.Test

/**
 * La suite di contratto dell'indice della mappa (Fase 9, P0; `docs/fase9-progetto.md` D27, D28, E-MAP5, E-MAP7, E-MAP9, E-MAP10, §5.2). Gira sul
 * riferimento a forza bruta e, da P3, sull'indice vero; P3 aggiunge la parita' **esatta** con il riferimento su 1.000 viste casuali per livello
 * ([assertSameAs]: conteggi, centri, copertine, luoghi, membri).
 */
abstract class ClusterIndexContract {

  protected abstract fun index(points: List<MapPoint>): GeoClusterIndex

  private val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  private fun point(n: Long, lat: Int, lon: Int, day: Int = 20250101, score: Short = 0, place: Int = 0) =
    MapPoint(MediaId(UUID(9L, n).toString()), lat, lon, day, score, place)

  /** E-MAP7: i punti non validi non ci sono; vista sul mondo intero = tutti i punti validi, una volta sola. */
  @Test
  fun ilMondoInteroContaOgniPuntoValidoUnaVolta() {
    val points = SyntheticMapPoints.generate(3_000)
    val valid = points.count { MapPointValidity.isValid(it.latE6, it.lonE6) }
    val index = index(points)
    assertThat(index.size).isEqualTo(valid)
    for (zoom in listOf(0.0, 2.5, 5.0, 9.0, 14.0)) {
      val clusters = index.clusters(world, zoom, 72)
      assertWithMessage("zoom $zoom").that(clusters.sumOf { it.count }).isEqualTo(valid)
      assertThat(clusters.map { it.key }).containsNoDuplicates()
      assertThat(clusters.map { it.key.prefix }).isInOrder()
    }
  }

  /** D28: copertina col punteggio piu' alto, poi la piu' recente, poi l'id; luogo il piu' frequente; centro la media; membri dal piu' recente. */
  @Test
  fun copertinaLuogoCentroEMembri() {
    val points = listOf(
      point(1, 43_000_000, 11_000_000, day = 20240101, score = 5, place = 7),
      point(2, 43_000_010, 11_000_010, day = 20250101, score = 5, place = 7),
      point(3, 43_000_020, 11_000_020, day = 20230101, score = 1, place = 9),
      point(4, 43_000_030, 11_000_030, day = 20260101, score = 2, place = 0),
    )
    val index = index(points)
    val cluster = index.clusters(world, 3.0, 72).single()
    assertThat(cluster.count).isEqualTo(4)
    assertThat(cluster.cover).isEqualTo(points[1].id)
    assertThat(cluster.placeId).isEqualTo(7)
    assertThat(cluster.center).isEqualTo(GeoE6(43_000_015, 11_000_015))
    assertThat(index.members(cluster.key, 10)).containsExactly(points[3].id, points[1].id, points[0].id, points[2].id).inOrder()
    assertThat(index.members(cluster.key, 2)).hasSize(2)
  }

  /** E-MAP9: migliaia di foto nello stesso punto sono una bolla sola a ogni livello. */
  @Test
  fun stessoPuntoUnaBollaSola() {
    val points = List(2_000) { point(it.toLong(), 45_123_456, 9_123_456) }
    val index = index(points)
    for (zoom in listOf(0.0, 8.0, 16.0, 22.0)) assertThat(index.clusters(world, zoom, 72).map { it.count }).containsExactly(2_000)
  }

  /** E-MAP10: una vista che attraversa l'antimeridiano vede i due lati, e solo celle che la toccano. */
  @Test
  fun antimeridianoEPoli() {
    val east = point(1, 10_000_000, 179_900_000)
    val west = point(2, 10_000_000, -179_900_000)
    val far = point(3, 10_000_000, 0)
    val polar = point(4, 84_999_000, 10_000_000)
    val index = index(listOf(east, west, far, polar))
    val view = GeoBox(179_000_000, 0, -179_000_000, 20_000_000)
    val seen = index.clusters(view, 8.0, 72).flatMap { index.members(it.key, 10) }
    assertThat(seen).containsExactly(east.id, west.id)
    assertThat(index.clusters(world, 8.0, 72).sumOf { it.count }).isEqualTo(4)
    for (c in index.clusters(view, 8.0, 72)) assertThat(GeoGrid.touches(c.key, view)).isTrue()
  }

  /** Il riquadro di tutti i punti per la prima camera (D29); null senza punti (E-MAP5). */
  @Test
  fun ilRiquadroDiTutti() {
    assertThat(index(emptyList()).boundsOfAll()).isNull()
    val box = index(listOf(point(1, 10, 20), point(2, -30, 40))).boundsOfAll()!!
    assertThat(box).isEqualTo(GeoBox(20, -30, 40, 10))
  }

  /** P3: la parita' esatta con il riferimento su viste casuali (qui il riferimento con se' stesso, come prova della prova). */
  @Test
  fun parita() {
    val points = SyntheticMapPoints.generate(2_000, seed = 3)
    assertSameAs(index(points), ReferenceClusterIndex(points), views = 200)
  }

  protected fun assertSameAs(subject: GeoClusterIndex, reference: GeoClusterIndex, views: Int, seed: Long = 1) {
    val random = Random(seed)
    repeat(views) {
      val south = random.nextInt(2 * 85_000_000) - 85_000_000
      val north = (south + random.nextInt(40_000_000)).coerceAtMost(GeoGrid.LAT_E6)
      val west = random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6
      val east = random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6
      val view = GeoBox(west, south, east, north)
      val zoom = random.nextInt(45) / 2.0
      val a = subject.clusters(view, zoom, 72)
      val b = reference.clusters(view, zoom, 72)
      assertWithMessage("vista $it, zoom $zoom").that(a).isEqualTo(b)
      a.firstOrNull()?.let { c -> assertThat(subject.members(c.key, 50)).isEqualTo(reference.members(c.key, 50)) }
    }
  }
}

/** La suite sul riferimento (§5.2). */
class ReferenceClusterIndexContractTest : ClusterIndexContract() {
  override fun index(points: List<MapPoint>): GeoClusterIndex = ReferenceClusterIndex(points)
}
