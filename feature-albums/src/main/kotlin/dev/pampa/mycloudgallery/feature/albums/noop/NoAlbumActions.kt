package dev.pampa.mycloudgallery.feature.albums.noop

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions

/** Fase 7 (P0): nessuna azione (finche' :app non lega le schermate, P13). Non apre niente. */
object NoAlbumActions : AlbumActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) = Unit

  override fun openAlbum(id: AlbumId) = Unit

  override fun pickMedia(forAlbum: AlbumId?, max: Int, onPicked: (List<MediaId>) -> Unit) = Unit

  override fun openDiagnostics() = Unit
}
