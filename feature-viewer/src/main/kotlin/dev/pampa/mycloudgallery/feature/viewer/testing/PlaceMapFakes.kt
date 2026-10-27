package dev.pampa.mycloudgallery.feature.viewer.testing

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fase 9 (P0): la mappa finta del viewer, per i test della riga "Mostra sulla mappa" e della mini-mappa (P4). Le posizioni per media si
 * impostano con [locate] (senza: [ViewerMapPlace.NoPosition]); [asked] registra ogni media chiesto (un test controlla che dai Nascosti non se
 * ne chieda nessuno, E-H9); [shown] i media aperti sulla mappa.
 */
class FakeViewerPlaceMap : ViewerPlaceMap {
  private val places = MutableStateFlow<Map<MediaId, ViewerMapPlace>>(emptyMap())

  val asked: MutableList<MediaId> = mutableListOf()
  val shown: MutableList<MediaId> = mutableListOf()

  fun locate(media: MediaId, place: ViewerMapPlace = ViewerMapPlace.Located(null)): FakeViewerPlaceMap = apply {
    places.value = places.value + (media to place)
  }

  override fun place(media: MediaId, sizePx: Int): Flow<ViewerMapPlace> {
    require(sizePx > 0) { "Larghezza non valida." }
    asked += media
    return places.map { it[media] ?: ViewerMapPlace.NoPosition }
  }

  override fun show(media: MediaId) {
    shown += media
  }
}
