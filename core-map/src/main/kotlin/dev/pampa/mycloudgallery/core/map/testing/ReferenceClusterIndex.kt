package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * L'indice di riferimento (Fase 9, P0, D28, §5.2): **forza bruta**, ogni punto nella sua cella a ogni chiamata, nessun ordinamento, nessuna
 * cache. L'indice vero di P3 (Morton a prefissi) deve dare gli stessi conteggi, centri, copertine e luoghi su 1.000 viste casuali per livello,
 * antimeridiano e poli compresi (§5.2). I punti non validi (E-MAP7) non entrano.
 */
class ReferenceClusterIndex(points: List<MapPoint>) : GeoClusterIndex {

  private val points: List<MapPoint> = points.filter { MapPointValidity.isValid(it.latE6, it.lonE6) }.distinctBy { it.id }

  override val size: Int get() = points.size

  override fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster> {
    val level = GeoGrid.levelFor(zoom, cellPx)
    val cells = points.groupBy { GeoGrid.cellOf(it.latE6, it.lonE6, level) }
    return cells.filterKeys { GeoGrid.touches(it, view) }
      .map { (key, members) -> cluster(key, members) }
      .sortedBy { it.key.prefix }
  }

  override fun members(key: CellKey, limit: Int): List<MediaId> {
    require(limit >= 0) { "Limite negativo." }
    return points.filter { GeoGrid.cellOf(it.latE6, it.lonE6, key.level) == key }
      .sortedWith(compareByDescending<MapPoint> { it.day }.thenBy { it.id.value })
      .take(limit)
      .map { it.id }
  }

  override fun boundsOfAll(): GeoBox? {
    if (points.isEmpty()) return null
    return GeoBox(points.minOf { it.lonE6 }, points.minOf { it.latE6 }, points.maxOf { it.lonE6 }, points.maxOf { it.latE6 })
  }

  companion object {
    /** La bolla di una cella con i suoi punti, con le regole del contratto (`GeoClusterIndex`). */
    fun cluster(key: CellKey, members: List<MapPoint>): GeoCluster {
      val lat = Math.floorDiv(members.sumOf { it.latE6.toLong() }, members.size.toLong()).toInt()
      val lon = Math.floorDiv(members.sumOf { it.lonE6.toLong() }, members.size.toLong()).toInt()
      val cover = members.sortedWith(compareByDescending<MapPoint> { it.score }.thenByDescending { it.day }.thenBy { it.id.value }).first()
      val place = members.filter { it.placeId > 0 }.groupingBy { it.placeId }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
      return GeoCluster(key, GeoE6(lat, lon), members.size, cover.id, place)
    }
  }
}
