package dev.pampa.mycloudgallery.feature.map.map

import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.text.Normalizer
import kotlin.math.min

// LE BOLLE (Fase 9, P4; docs/fase9-progetto.md §2.10 "Bolle" ed "Etichette", D28, D31, E-MAP9, E-MAP15, E-MAP18, E-H9). Dalle celle dell'indice (`GeoClusterIndex`,
// P3) a **al piu' 60** bolle visibili: le piu' grandi, ognuna con la copertina **dopo il filtro finale dei Nascosti**, il conto e, per le celle grandi, il nome del luogo
// (mai "Casa"). Il calcolo non sta mai sul thread principale (lo chiama il controllore su un dispositivo di calcolo) e si rifa a camera ferma o al cambio di mezzo livello.

/** Una bolla con il punto della mappa dove sta (il centro delle sue foto): la posizione sullo schermo la dice la mappa a ogni fotogramma. Nessuna coordinata nei log. */
class PlacedBubble(val bubble: MapBubble, val at: GeoE6) {
  val key: CellKey get() = bubble.key

  /** Quante foto: 1 per la bolla singola. */
  val count: Int get() = (bubble as? MapBubble.Cluster)?.count ?: 1

  /** Il nome del luogo, solo per le bolle grandi e solo se non e' "Casa" (E-MAP18). */
  val label: String? get() = (bubble as? MapBubble.Cluster)?.label

  override fun toString(): String = "PlacedBubble($bubble)"
}

/**
 * I nomi delle bolle (E-MAP18, D29): **mai** un'etichetta "Casa". La casa dedotta non si usa ne' si mostra; se un nome di luogo dicesse "Casa" o "Home" (un
 * dizionario o un'etichetta dell'utente che arriva da un'altra porta), la bolla resta senza nome. Il confronto e' sulle **parole** (senza accenti e maiuscole):
 * "Casalecchio" non e' "casa".
 */
object MapLabels {
  private val FORBIDDEN_WORDS = setOf("casa", "home")

  fun clean(name: String?): String? {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return null
    val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    val words = plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return if (words.any { it in FORBIDDEN_WORDS }) null else text
  }
}

/** Cosa fa il tocco su una bolla (D28): avvicinarsi alla cella, o aprire la griglia della zona (sotto 30 elementi o al livello massimo). */
sealed interface BubbleTap {
  data class ZoomTo(val spot: CameraSpot) : BubbleTap {
    override fun toString(): String = "BubbleTap.ZoomTo"
  }

  data class OpenZone(val key: CellKey) : BubbleTap {
    override fun toString(): String = "BubbleTap.OpenZone"
  }

  companion object {
    /** Quanto ci si avvicina a ogni tocco: abbastanza da spezzare la cella senza saltare livelli. */
    const val ZOOM_STEP: Double = 2.0

    /** Vicino al livello massimo non ci si avvicina piu': si apre la griglia. */
    const val AT_MAX_MARGIN: Double = 0.25

    fun of(bubble: PlacedBubble, zoom: Double, maxZoom: Double): BubbleTap {
      val small = bubble.count <= MapPoints.GRID_BELOW
      val atMax = zoom >= maxZoom - AT_MAX_MARGIN
      if (small || atMax) return OpenZone(bubble.key)
      return ZoomTo(CameraSpot(bubble.at, min(maxZoom, zoom + ZOOM_STEP)))
    }
  }
}

class BubbleEngine(
  private val names: MapPlaceNames,
  private val filter: FinalFilter,
) {

  /**
   * Le bolle di [viewport] sull'indice [index]: le celle dell'indice (a [CELL_DP] dp), le [MapPoints.MAX_BUBBLES] piu' grandi (a parita' la chiave minore), con
   * la copertina ancora filtrata dai Nascosti. Una copertina nascosta un attimo fa (l'indice non lo sa ancora, E-MAP15) cede il posto al primo media visibile
   * della cella; se nessuno e' visibile, la bolla non c'e'. Una copertina che la Libreria non sa disegnare ([drawable] falso: il membro di un gruppo che non ne e'
   * la copertina non ha una cella) cede il posto, se si puo', a un altro media della cella. Sospende solo per il filtro; mai sul thread principale.
   */
  suspend fun compute(index: GeoClusterIndex, viewport: MapViewport, drawable: (MediaId) -> Boolean = { true }): List<PlacedBubble> {
    val clusters = index.clusters(viewport.box, viewport.zoom, CELL_DP)
    if (clusters.isEmpty()) return emptyList()
    val biggest = clusters.sortedWith(compareByDescending<GeoCluster> { it.count }.thenBy { it.key.level }.thenBy { it.key.prefix })
      .take(MapPoints.MAX_BUBBLES)
    val hidden = filter.hiddenAmong(biggest.map { it.cover })
    val labelled = biggest.filter { it.count >= MapPoints.GRID_BELOW && (it.placeId ?: 0) != 0 }.take(MAX_LABELS).map { it.key }.toSet()
    val out = ArrayList<PlacedBubble>(biggest.size)
    for (cluster in biggest) {
      val own = cluster.cover
      val cover = if (own !in hidden && drawable(own)) own else firstVisible(index, cluster, drawable) ?: own.takeIf { it !in hidden } ?: continue
      val label = if (cluster.key in labelled) MapLabels.clean(names.label(cluster.placeId ?: 0)) else null
      val bubble = if (cluster.count <= 1) MapBubble.Single(cluster.key, cover) else MapBubble.Cluster(cluster.key, cluster.count, cover, label)
      out += PlacedBubble(bubble, cluster.center)
    }
    return out.sortedWith(compareBy({ it.key.level }, { it.key.prefix }))
  }

  private suspend fun firstVisible(index: GeoClusterIndex, cluster: GeoCluster, drawable: (MediaId) -> Boolean): MediaId? {
    val candidates = index.members(cluster.key, ALTERNATIVES)
    if (candidates.isEmpty()) return null
    val hidden = filter.hiddenAmong(candidates)
    return candidates.firstOrNull { it !in hidden && drawable(it) }
  }

  companion object {
    /** Il lato di una cella in dp (§2.10: `clusters(view, zoom, cellPx = 72 dp)`). */
    const val CELL_DP: Int = 72

    /** Le bolle con il nome del luogo: le piu' grandi (le altre sarebbero rumore sopra la carta). */
    const val MAX_LABELS: Int = 12

    /** Quanti media di una cella si guardano per trovare un'altra copertina. */
    const val ALTERNATIVES: Int = 64
  }
}
