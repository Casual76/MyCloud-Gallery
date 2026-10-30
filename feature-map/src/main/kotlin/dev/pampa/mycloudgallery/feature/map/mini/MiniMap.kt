package dev.pampa.mycloudgallery.feature.map.mini

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// LA MINI-MAPPA DELLE INFO DEL VIEWER (Fase 9, P4; docs/fase9-progetto.md §1.5 `MiniMapState`, §2.10 "Mini-mappa del viewer", D29, D30, E-MAP16, E-H9). Un'immagine statica di 320 x 160 dp con lo stile **solo
// offline** (la carta di base: nessuna sorgente online nello stile, quindi **nessuna richiesta di rete**, anche con la mappa dettagliata accesa) e un segno sul punto. Il viewer non vede core-map ne' MapLibre:
// `:app` (P13) lega [MiniMapService] alla porta `ViewerPlaceMap` del viewer (la mini-mappa e' un `Bitmap`, "Mostra sulla mappa" apre `MapDestination(focus)`). **Mai per un media aperto dai Nascosti**: il viewer
// non chiama la porta (`ViewerRequest.secure`), e qui il filtro finale dei Nascosti lo ripete (due difese: un media nascosto non ha posizione ne' mini-mappa).

/** La mini-mappa di un media (§1.5 `MiniMapState`): si sta preparando, nessuna posizione (o nascosto), pronta (la bitmap manca se la carta di base non e' ancora disponibile). */
sealed interface MiniMapState {
  data object Loading : MiniMapState

  data object NoPosition : MiniMapState

  class Ready(val bitmap: Bitmap?) : MiniMapState {
    override fun toString(): String = "MiniMapState.Ready(${bitmap != null})"
  }
}

/** Come disegnare: tema scuro, colore del segno sul punto e del suo anello (interi ARGB dal tema: nessun colore scritto a mano) e densita' dello schermo. */
class MiniMapLook(val dark: Boolean, val marker: Int, val ring: Int, val density: Float) {
  override fun toString(): String = "MiniMapLook(dark=$dark)"
}

/** Chi disegna la carta: la vera con `MapSnapshotter` ([MapLibreMiniMap]), o niente. Disegna [point] a [widthPx] x [heightPx] pixel, o null se non puo'. Mai in rete (lo stile non ha sorgenti online). */
fun interface MiniMapRenderer {
  suspend fun render(point: GeoE6, widthPx: Int, heightPx: Int, look: MiniMapLook): Bitmap?
}

/** Nessuna mappa: la riga "Mostra sulla mappa" resta, la mini-mappa no. */
object NoMiniMapRenderer : MiniMapRenderer {
  override suspend fun render(point: GeoE6, widthPx: Int, heightPx: Int, look: MiniMapLook): Bitmap? = null
}

class MiniMapService(
  private val positions: MapMediaPositions,
  private val filter: FinalFilter,
  private val renderer: MiniMapRenderer,
  private val look: () -> MiniMapLook,
) {
  private val cache = object : LinkedHashMap<String, Bitmap>(CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > CACHE_SIZE
  }

  /**
   * La mini-mappa di [media] alla larghezza [widthPx] (l'altezza e' la meta': 320 x 160 dp). Emette [MiniMapState.Loading], poi [MiniMapState.NoPosition] (nessuna posizione, coordinate scartate, **o un
   * nascosto**) o [MiniMapState.Ready]. Le immagini si ricordano **solo in memoria** (le ultime sei: mai su disco, mai nei log).
   */
  fun place(media: MediaId, widthPx: Int): Flow<MiniMapState> = flow {
    require(widthPx > 0) { "Larghezza non valida." }
    emit(MiniMapState.Loading)
    val at = positions.positionOf(media)
    if (at == null || media in filter.hiddenAmong(setOf(media))) {
      emit(MiniMapState.NoPosition)
      return@flow
    }
    val current = look()
    val key = "${media.value}/$widthPx/${current.dark}"
    synchronized(cache) { cache[key] }?.let { emit(MiniMapState.Ready(it)); return@flow }
    val bitmap = renderer.render(at, widthPx, (widthPx / ASPECT).coerceAtLeast(1), current)
    if (bitmap != null) synchronized(cache) { cache[key] = bitmap }
    emit(MiniMapState.Ready(bitmap))
  }

  /** Dimentica le immagini (si chiama al blocco dell'app e quando cambia la Libreria: nessuna mini-mappa resta dopo che il media e' diventato nascosto). */
  fun clear() = synchronized(cache) { cache.clear() }

  private companion object {
    const val CACHE_SIZE = 6
    const val ASPECT = 2
  }
}
