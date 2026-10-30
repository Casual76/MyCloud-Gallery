package dev.pampa.mycloudgallery.core.map.points

import dev.pampa.mycloudgallery.core.map.contract.MapCoverScore
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointCounts
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// DALLE RIGHE DELLA QUERY AI PUNTI (docs/fase9-progetto.md §2.10 "Lettura" e "Validita'", D27, E-MAP5, E-MAP7). `core-map` non vede il
// database: la query a pagine di `MapPointsQueries` (core-database) la lega :app (P13) con un adattatore **senza logica** che implementa
// `MapPointPager` (traduce i codici di tipo e di sorgente nelle enumerazioni e passa i numeri). La logica sta qui, in un posto solo e sotto
// test: la conversione in microgradi, le coordinate scartate e contate, il punteggio della copertina, la paginazione per chiave.

/**
 * Una riga della query dei punti: un media **visibile** con le sue coordinate effettive (gradi decimali, come stanno nel catalogo). Mai coordinate
 * ne' id in `toString()` (D33).
 */
class MapPointRaw(
  val id: MediaId,
  val latitude: Double,
  val longitude: Double,
  val localDay: Int,
  val flags: Int,
  val source: MediaSource,
  val kind: MediaKind,
  val durationMs: Long?,
  val placeId: Int?,
) {
  override fun toString(): String = "MapPointRaw"
}

/** La porta verso la query (`MapPointsQueries`): una pagina per chiave e il conteggio dei visibili senza posizione. */
interface MapPointPager {
  /**
   * Al piu' [limit] righe visibili con coordinate, **dopo** il media [after] (null = dall'inizio), in un ordine stabile fra una pagina e l'altra (quello
   * d'inserimento nel catalogo, o d'id nei fake): ogni riga una volta sola. Se [after] non c'e' piu' lancia [IllegalStateException] invece di
   * rispondere con una pagina vuota (sarebbe un indice monco).
   */
  suspend fun page(after: MediaId?, limit: Int): List<MapPointRaw>

  /** Quanti media visibili non hanno coordinate (E-MAP5). */
  suspend fun countWithoutPosition(): Int
}

/**
 * I punti della mappa dalla query a pagine (D27): legge [pager] a pagine di [pageSize] (2.000) finche' ne arriva una corta, scarta e conta le
 * coordinate che non entrano (E-MAP7: fuori dai limiti, esattamente (0, 0), latitudine oltre +-85 gradi: [MapPointValidity.toE6]) e calcola il
 * punteggio della copertina ([MapCoverScore], una definizione sola).
 *
 * Una lettura alla volta: [counts] riguarda l'ultima lettura di [pages] (la ricostruzione dell'indice le fa una dopo l'altra, `GeoMapPoints`).
 */
class PagedMapPointSource(
  private val pager: MapPointPager,
  private val pageSize: Int = MapPoints.PAGE_SIZE,
) : MapPointSource {

  init {
    require(pageSize > 0) { "Pagina vuota." }
  }

  @Volatile
  private var invalid: Int = 0

  override fun pages(): Flow<List<MapPoint>> = flow {
    var rejected = 0
    invalid = 0
    var after: MediaId? = null
    while (true) {
      val rows = pager.page(after, pageSize)
      if (rows.isEmpty()) break
      val points = ArrayList<MapPoint>(rows.size)
      for (row in rows) {
        val point = toPoint(row)
        if (point == null) rejected++ else points += point
      }
      invalid = rejected
      if (points.isNotEmpty()) emit(points)
      if (rows.size < pageSize) break
      after = rows.last().id
    }
    invalid = rejected
  }

  override suspend fun counts(): MapPointCounts = MapPointCounts(pager.countWithoutPosition(), invalid)

  private fun toPoint(row: MapPointRaw): MapPoint? {
    val e6 = MapPointValidity.toE6(row.latitude, row.longitude) ?: return null
    val score = MapCoverScore.of(row.flags, row.source, row.kind, row.durationMs ?: 0L)
    return MapPoint(row.id, e6.latE6, e6.lonE6, row.localDay, score, row.placeId ?: 0)
  }

  override fun toString(): String = "PagedMapPointSource"
}
