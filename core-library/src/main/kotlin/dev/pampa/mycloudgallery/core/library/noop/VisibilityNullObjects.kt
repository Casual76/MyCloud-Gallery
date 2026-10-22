package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 7 (P0, D22): i null-object della visibilita'. Falliscono CHIUSI: senza catalogo niente e' visibile e tutto e' nascosto, cosi' una
// superficie legata per sbaglio a un null-object non mostra mai un nascosto.

/** Nessun catalogo: nessuna istantanea, nessun media visibile. */
object NoVisibleMedia : VisibleMedia {
  override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(null)

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = emptySet()
}

/** Nessun catalogo: ogni media si tratta come nascosto. */
object AllHiddenMask : HiddenMask {
  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
}

/** Nessun widget: niente da mostrare. */
object NoWidgetFeed : WidgetFeed {
  override suspend fun next(size: Int): List<MediaId> = emptyList()
}
