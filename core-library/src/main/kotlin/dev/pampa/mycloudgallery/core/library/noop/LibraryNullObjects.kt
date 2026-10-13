package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object di core-library: cio' che :app lega finche' P5 non porta l'indice vero.
//
// Non stanno nel package `testing` di proposito: vanno nella release, mentre i fake no (tools/check.ps1 lo
// verifica). Falliscono chiusi: una Libreria vuota, mai una Libreria finta. Le azioni sui dati personali non
// scrivono niente.

/** L'indice vuoto. Chiedere un elemento e' un errore di programmazione: non c'e' nessun elemento. */
object EmptyTimelineSnapshot : TimelineSnapshot {
  override val size: Int = 0
  override val seq: Long = 0

  private fun none(): Nothing = throw IndexOutOfBoundsException("L'indice e' vuoto.")

  override fun idAt(i: Int): MediaId = none()

  override fun sortTimeAt(i: Int): Long = none()

  override fun localDayAt(i: Int): Int = none()

  override fun kindAt(i: Int): MediaKind = none()

  override fun sourceAt(i: Int): MediaSource = none()

  override fun flagsAt(i: Int): Int = none()

  override fun aspectMilliAt(i: Int): Int = none()

  override fun durationMsAt(i: Int): Long = none()

  override fun placeAt(i: Int): Int = none()

  override fun thumbHashAt(i: Int): ThumbHash? = none()

  override fun groupSizeAt(i: Int): Int = none()

  override fun indexOf(id: MediaId): Int = -1
}

/** La vista vuota, per qualunque filtro. */
class EmptyTimelineView(override val filter: LibraryFilter = LibraryFilter.ALL) : TimelineView {
  override val snapshot: TimelineSnapshot = EmptyTimelineSnapshot
  override val size: Int = 0

  override fun snapshotIndex(viewIndex: Int): Int = throw IndexOutOfBoundsException("La vista e' vuota.")

  override fun viewIndexOf(id: MediaId): Int = -1
}

/** Nessun catalogo: la Libreria e' vuota. */
object NoTimelineRepository : TimelineRepository {
  override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(EmptyTimelineSnapshot)

  override suspend fun start() = Unit

  override fun view(f: LibraryFilter): StateFlow<TimelineView> = MutableStateFlow(EmptyTimelineView(f))
}

/** Le preferenze di default, che non si ricordano: tutto, livello Giorni, celle quadrate. */
object NoLibraryPreferences : LibraryPreferences {
  override val filter: StateFlow<LibraryFilter> = MutableStateFlow(LibraryFilter.ALL)
  override val level: StateFlow<GridLevel> = MutableStateFlow(GridLevel.DAYS)
  override val style: StateFlow<GridStyle> = MutableStateFlow(GridStyle.SQUARE)

  override suspend fun setFilter(filter: LibraryFilter) = Unit

  override suspend fun setLevel(level: GridLevel) = Unit

  override suspend fun setStyle(style: GridStyle) = Unit
}

/** Preferiti e nascosti che non scrivono niente. */
object NoLocalMediaActions : LocalMediaActions {
  override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

  override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = Unit
}

/** Un layout senza righe. */
class EmptyGridLayout(override val spec: GridSpec) : GridLayout {
  override val rowCount: Int = 0
  override val itemCount: Int = 0
  override val totalHeight: Int = 0
  override val sections: List<SectionInfo> = emptyList()

  private fun none(): Nothing = throw IndexOutOfBoundsException("Il layout e' vuoto.")

  override fun rowKind(row: Int): RowKind = none()

  override fun rowTop(row: Int): Int = none()

  override fun rowHeight(row: Int): Int = none()

  override fun itemsOf(row: Int): IntRange = none()

  override fun rowOfItem(position: Int): Int = none()

  override fun sectionOfRow(row: Int): Int = none()

  override fun itemLeft(position: Int): Int = none()

  override fun itemWidth(position: Int): Int = none()

  override fun itemAt(row: Int, x: Float): Int = -1

  override fun rowAtOffset(y: Int): Int = 0

  override fun viewIndexAt(position: Int): Int = none()

  override fun positionOfViewIndex(viewIndex: Int): Int = none()
}

/** Il motore che non impagina niente. */
object NoGridLayoutEngine : GridLayoutEngine {
  override fun layout(v: TimelineView, s: GridSpec): GridLayout = EmptyGridLayout(s)
}
