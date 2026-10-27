package dev.pampa.mycloudgallery.feature.map.noop

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapActions

/** Fase 9 (P0): nessuna azione (finche' :app non lega le schermate, P13). Non apre niente. */
object NoMapActions : MapActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) = Unit

  override fun openZone(key: CellKey) = Unit

  override fun openPlace(placeId: Int) = Unit

  override fun openMap() = Unit

  override fun openMapAt(media: MediaId) = Unit

  override fun openMapSettings() = Unit

  override fun openRegions() = Unit
}
