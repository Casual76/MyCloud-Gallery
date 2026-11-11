package dev.pampa.mycloudgallery.map

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionMapImage
import dev.pampa.mycloudgallery.feature.library.collections.CollectionMapPin
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsMapPreview
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapService
import dev.pampa.mycloudgallery.feature.map.places.PlacesPreviewService
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapState
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * I punti della mappa con i loro aggiornamenti (P3: "`rebuild(SNAPSHOT_CHANGED)` a ogni nuovo `seq`, `rebuild(HIDDEN_CHANGED)` quando cambiano i nascosti";
 * "P13 non lo chiami dall'avvio"). Finche' nessuno apre la mappa o la sezione Luoghi ([rebuild] con `OPEN`) non si legge niente e non si guarda niente;
 * dalla prima apertura si seguono l'istantanea (una ricostruzione per l'ultima di una raffica: la calma di 2 s di `GeoMapPoints`, con `collectLatest`) e i
 * "Nascondi" fatti qui ([hiddenChanged], senza calma: E-MAP15).
 */
class WatchedMapPoints(
  private val inner: MapPoints,
  private val snapshot: StateFlow<TimelineSnapshot?>,
  private val scope: CoroutineScope,
) : MapPoints {

  private val watching = AtomicBoolean(false)

  override val index: StateFlow<GeoIndexState> get() = inner.index

  /** Vero dopo la prima apertura in questo processo. */
  val opened: Boolean get() = watching.get()

  override suspend fun rebuild(reason: RebuildReason) {
    if (reason == RebuildReason.OPEN) startWatching()
    inner.rebuild(reason)
  }

  /** Un nascosto (o un "Mostra") fatto qui: subito, ma solo se la mappa e' gia' stata aperta (prima non c'e' un indice da rifare). */
  fun hiddenChanged() {
    if (!watching.get()) return
    scope.launch { inner.rebuild(RebuildReason.HIDDEN_CHANGED) }
  }

  private fun startWatching() {
    if (!watching.compareAndSet(false, true)) return
    scope.launch {
      snapshot.map { it?.seq }.distinctUntilChanged().drop(1).collectLatest { inner.rebuild(RebuildReason.SNAPSHOT_CHANGED) }
    }
  }

  override fun toString(): String = "WatchedMapPoints"
}

/**
 * I1 (fase I): la porta della mappa in anteprima delle Raccolte ([CollectionsMapPreview], feature-library) sopra il servizio di feature-map
 * ([PlacesPreviewService]): i due moduli non si conoscono. [service] si costruisce alla prima richiesta (la scheda della mappa sullo schermo), mai prima.
 * Escono solo la carta, l'id dei luoghi e le loro posizioni sulla carta: mai una coordinata.
 */
class AppCollectionsMapPreview(private val service: () -> PlacesPreviewService) : CollectionsMapPreview {
  override fun preview(places: List<Int>, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Flow<CollectionMapImage?> =
    service().preview(places, widthDp, heightDp, density, dark).map { ready ->
      ready?.let { p -> CollectionMapImage(p.bitmap, p.pins.map { CollectionMapPin(it.placeId, it.x, it.y) }, p.attribution) }
    }

  override fun toString(): String = "AppCollectionsMapPreview"
}

/**
 * La porta `ViewerPlaceMap` del viewer sopra la mini-mappa di feature-map (P4: "`place(media, sizePx)` = `MiniMapService(...).place(media, sizePx)` tradotta in
 * `ViewerMapPlace`, `show(media)` = `nav.openMapAt(media)`"). Il viewer non la chiama mai per un media aperto dai Nascosti (`ViewerRequest.secure`); la
 * mini-mappa ripete il filtro finale. [map] si costruisce alla prima chiamata (la prima mini-mappa avvia MapLibre, mai l'avvio dell'app). [show] lo serve
 * la navigazione, legata quando la galleria e' visibile ([onShow]).
 */
class AppViewerPlaceMap(private val map: () -> MiniMapService) : ViewerPlaceMap {

  @Volatile
  var onShow: (MediaId) -> Unit = {}

  override fun place(media: MediaId, sizePx: Int): Flow<ViewerMapPlace> = map().place(media, sizePx).map { state ->
    when (state) {
      MiniMapState.Loading -> ViewerMapPlace.Loading
      MiniMapState.NoPosition -> ViewerMapPlace.NoPosition
      is MiniMapState.Ready -> ViewerMapPlace.Located(state.bitmap)
    }
  }

  override fun show(media: MediaId) = onShow(media)

  override fun toString(): String = "AppViewerPlaceMap"
}
