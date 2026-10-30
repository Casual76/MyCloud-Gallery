package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPointCounts
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D31, E-H9, E-MAP15 (`docs/fase9-progetto.md` §2.11, riga `map-points`): **un nascosto perfetto non esce mai** dall'indice dei punti: ne' come
 * copertina di una bolla, ne' fra i membri di una cella, ne' nei conteggi, su 25 Librerie sintetiche casuali (nascosti, assenti, membri di
 * gruppo, gruppi con un nascosto solo a meta') e per centinaia di viste a ogni zoom. La catena e' quella vera: Libreria sintetica -> modello
 * della query -> `PagedMapPointSource` -> `GeoMapPoints` -> indice. (Il filtro SQL vero lo prova `MapPointsQueriesTest` in core-database, con
 * lo stesso predicato.) Una prova di segno contrario mostra che la verifica e' capace di fallire: con un paginatore **senza** il filtro i nascosti
 * escono, e il controllo li vede.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenNeverShownTest {

  private val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  /** Gli id che l'indice **mostra**: copertine delle bolle e membri di ogni cella, per un insieme di viste e livelli. */
  private fun shown(index: GeoClusterIndex, random: Random, views: Int): Set<MediaId> {
    val out = HashSet<MediaId>()
    repeat(views) {
      val south = random.nextInt(2 * 84_000_000) - 84_000_000
      val view = if (it % 7 == 0) world else GeoBox(
        random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6, south, random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6,
        (south + random.nextInt(70_000_000)).coerceAtMost(GeoGrid.LAT_E6),
      )
      val zoom = random.nextInt(44) / 2.0
      for (cluster in index.clusters(view, zoom, 72)) {
        out += cluster.cover
        out += index.members(cluster.key, 100_000)
      }
    }
    return out
  }

  @Test
  fun unNascostoPerfettoNonEsceMaiDallIndice() = runTest {
    for (seed in 1L..25L) {
      val library = SyntheticMapLibrary.generate(2_500, seed)
      val points = GeoMapPoints(PagedMapPointSource(library.pager(), pageSize = 400), StandardTestDispatcher(testScheduler))
      points.rebuild(RebuildReason.OPEN)
      val ready = points.index.value as GeoIndexState.Ready
      val index = ready.index

      assertThat(index.size).isEqualTo(library.expectedPoints().size)
      assertThat(ready.withoutPosition).isEqualTo(library.withoutPosition)
      assertThat(ready.invalid).isEqualTo(library.expectedInvalid())

      val leaked = shown(index, Random(seed), views = 150).intersect(library.neverShown)
      assertWithMessage("seme $seed: nascosti o assenti usciti dall'indice").that(leaked).isEmpty()

      // Il mondo intero, a ogni livello: ogni punto una volta, tutti e soli quelli visibili.
      val all = HashSet<MediaId>()
      for (zoom in listOf(0.0, 4.0, 9.0, 14.0, 22.0)) {
        val clusters = index.clusters(world, zoom, 72)
        assertThat(clusters.sumOf { it.count }).isEqualTo(index.size)
        if (zoom == 22.0) for (c in clusters) all += index.members(c.key, 100_000)
      }
      assertThat(all).containsExactlyElementsIn(library.expectedPoints().map { it.id })
      assertThat(all.intersect(library.neverShown)).isEmpty()

      // E uguale al riferimento a forza bruta sulla stessa Libreria.
      val reference = ReferenceClusterIndex(library.expectedPoints())
      val random = Random(seed + 100)
      repeat(15) {
        val zoom = random.nextInt(30) / 2.0
        assertThat(index.clusters(world, zoom, 72)).isEqualTo(reference.clusters(world, zoom, 72))
      }
    }
  }

  /** E-MAP15: un media che diventa nascosto con la mappa aperta esce all'aggiornamento dell'indice, e non rientra. */
  @Test
  fun unMediaCheDiventaNascostoEsceAlPassoDopo() = runTest {
    val library = SyntheticMapLibrary.generate(3_000, seed = 77)
    val visible = library.expectedPoints()
    val victim = visible[visible.size / 2].id

    class Source(var excluded: Set<MediaId>) : MapPointSource {
      override fun pages(): Flow<List<MapPoint>> = flow { emit(visible.filter { it.id !in excluded }) }

      override suspend fun counts(): MapPointCounts = MapPointCounts(0, 0)
    }

    val source = Source(emptySet())
    val points = GeoMapPoints(source, StandardTestDispatcher(testScheduler))
    points.rebuild(RebuildReason.OPEN)
    val before = (points.index.value as GeoIndexState.Ready).index
    assertThat(before.clusters(world, 22.0, 72).flatMap { before.members(it.key, 10) }).contains(victim)
    source.excluded = setOf(victim)
    points.rebuild(RebuildReason.HIDDEN_CHANGED)
    val after = (points.index.value as GeoIndexState.Ready).index
    assertThat(after.size).isEqualTo(visible.size - 1)
    assertThat(shown(after, Random(1), 200)).doesNotContain(victim)
    assertThat(after.clusters(world, 22.0, 72).flatMap { after.members(it.key, 10) }).doesNotContain(victim)
  }

  /** La verifica sa fallire: con un paginatore che **non** filtra, i nascosti escono e la prova li vede (la proprieta' e' "rossa" senza il filtro). */
  @Test
  fun senzaIlFiltroLaProvaFallisce() = runTest {
    val library = SyntheticMapLibrary.generate(2_500, seed = 3)
    val unfiltered = SyntheticMapLibrary(
      library.media.map {
        dev.pampa.mycloudgallery.core.map.testing.SyntheticMapMedia(
          it.id, it.latitude, it.longitude, it.localDay, it.flags and dev.pampa.mycloudgallery.core.model.CellFlags.GROUP_MEMBER.inv(),
          it.source, it.kind, it.durationMs, it.placeId, hidden = false, present = true, groupId = null,
        )
      },
    )
    val points = GeoMapPoints(PagedMapPointSource(unfiltered.pager()), StandardTestDispatcher(testScheduler))
    points.rebuild(RebuildReason.OPEN)
    val index = (points.index.value as GeoIndexState.Ready).index
    val leaked = shown(index, Random(3), views = 150).intersect(library.neverShown)
    assertThat(leaked).isNotEmpty()
  }
}
