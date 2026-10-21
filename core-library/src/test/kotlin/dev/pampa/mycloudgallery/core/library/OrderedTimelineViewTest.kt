package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import org.junit.Test

/**
 * Fase 6 (P0, D28, §1.2 "core-library"): la vista in un ordine qualunque (i risultati della ricerca) si scorre come ogni vista. La
 * griglia (il motore dei layout vero) mette ogni elemento una volta sola e nell'ordine della vista; le posizioni si ritrovano dagli
 * id anche se non crescono con l'indice (nessuna ricerca binaria). Il viewer la scorre nella prova di feature-viewer
 * (`ViewerOrderedViewTest`).
 */
class OrderedTimelineViewTest {

  private val library = SyntheticLibrary(seed = 11, count = 300)
  private val snapshot = library.snapshot

  /** Un ordine che non e' quello del tempo: i pari dal piu' vecchio, poi i dispari dal piu' recente. */
  private val scrambled: IntArray = ((snapshot.size - 1 downTo 0).filter { it % 2 == 0 } + (0 until snapshot.size).filter { it % 2 == 1 })
    .toIntArray()

  @Test
  fun lePosizioniSiRitrovanoDagliIdAncheFuoriOrdine() {
    val view = IndexOrderedTimelineView(snapshot, scrambled)
    assertThat(view.size).isEqualTo(snapshot.size)
    assertThat(view.filter).isEqualTo(LibraryFilter.ALL)
    for (v in 0 until view.size) {
      val id = snapshot.idAt(view.snapshotIndex(v))
      assertThat(view.viewIndexOf(id)).isEqualTo(v)
    }
    // Davvero non in ordine di tempo: almeno una coppia consecutiva va all'indietro (e una in avanti).
    val indices = (0 until view.size).map { view.snapshotIndex(it) }
    assertThat(indices.zipWithNext().any { (a, b) -> b < a }).isTrue()
    assertThat(indices.zipWithNext().any { (a, b) -> b > a }).isTrue()
  }

  @Test
  fun ripetizioniEPosizioniFuoriDallIndiceSiScartano() {
    val view = IndexOrderedTimelineView(snapshot, intArrayOf(5, 3, 5, -1, snapshot.size, 3, 7))
    assertThat((0 until view.size).map { view.snapshotIndex(it) }).containsExactly(5, 3, 7).inOrder()
    assertThat(view.viewIndexOf(snapshot.idAt(4))).isEqualTo(-1)
  }

  @Test
  fun unIdCheLIndiceNonHaPiuNonEInVista() {
    val ids = listOf(snapshot.idAt(10), library.hidden.firstOrNull()?.id ?: snapshot.idAt(10), snapshot.idAt(2))
    val view = IndexOrderedTimelineView.of(snapshot, ids)
    assertThat((0 until view.size).map { snapshot.idAt(view.snapshotIndex(it)) }).containsExactly(snapshot.idAt(10), snapshot.idAt(2)).inOrder()
    library.hidden.forEach { assertThat(view.viewIndexOf(it.id)).isEqualTo(-1) }
  }

  /** La griglia: ogni posizione della vista e' in una riga di celle, una volta sola, nell'ordine della vista. */
  @Test
  fun laGrigliaMetteOgniElementoUnaVoltaNellOrdineDellaVista() {
    val view = IndexOrderedTimelineView(snapshot, scrambled)
    val engine = TimelineLayoutEngine()
    for (level in listOf(GridLevel.DAYS, GridLevel.entries.last())) {
      val layout = engine.layout(view, GridSpec(level, GridStyle.SQUARE, columns = 4, widthPx = 1080, gapPx = 3, densityDp = 2.625f))
      val seen = mutableListOf<Int>()
      for (row in 0 until layout.rowCount) {
        if (layout.rowKind(row) != RowKind.CELLS) continue
        for (position in layout.itemsOf(row)) seen += layout.viewIndexAt(position)
      }
      assertThat(seen.sorted()).isEqualTo((0 until view.size).toList())
      for (v in 0 until view.size) assertThat(layout.viewIndexAt(layout.positionOfViewIndex(v))).isEqualTo(v)
    }
  }

  /** Il viewer di prova (`FakeViewerItems`) accetta la vista ordinata come ogni altra. */
  @Test
  fun gliElementiDelViewerDiProvaUsanoLaVistaOrdinata() {
    val items = library.viewerItems()
    val ordered = IndexOrderedTimelineView(snapshot, scrambled)
    (items as dev.pampa.mycloudgallery.core.library.testing.FakeViewerItems).publish(ordered)
    assertThat(items.view.value).isSameInstanceAs(ordered)
    val first = snapshot.idAt(ordered.snapshotIndex(0))
    assertThat(items.ref(first)).isNotNull()
  }

  /** D34: il filtro Documenti accetta solo le celle con il bit derivato. */
  @Test
  fun ilFiltroDocumentiLeggeIlBitDerivato() {
    assertThat(LibraryFilter.DOCUMENTS.accepts(MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.DOCUMENT)).isTrue()
    assertThat(LibraryFilter.DOCUMENTS.accepts(MediaKind.PHOTO, MediaSource.SCREENSHOT, 0)).isFalse()
    assertThat(LibraryFilter.fromCode("documents")).isEqualTo(LibraryFilter.DOCUMENTS)
  }
}
