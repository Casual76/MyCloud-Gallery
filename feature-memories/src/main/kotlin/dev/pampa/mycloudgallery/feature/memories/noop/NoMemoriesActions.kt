package dev.pampa.mycloudgallery.feature.memories.noop

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions

/** Fase 9 (P0): nessuna azione (finche' :app non lega le schermate, P13). Non apre niente. */
object NoMemoriesActions : MemoriesActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) = Unit

  override fun openMemory(id: MemoryId) = Unit

  override fun openAllMemories() = Unit

  override fun openAlbum(album: AlbumId) = Unit

  override fun requestNotificationPermission() = Unit

  override fun openNotificationChannel() = Unit

  override fun pickOwnSong(onFailed: () -> Unit) = Unit
}
