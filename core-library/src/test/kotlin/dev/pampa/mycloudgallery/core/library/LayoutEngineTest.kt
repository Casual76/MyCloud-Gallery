package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridLayout
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import kotlin.math.abs
import org.junit.Test

/** Il motore di layout: 4 livelli x 2 stili, invarianti su 100 mila elementi, l'oracolo di P0 e i budget. */
class LayoutEngineTest {

  private val library = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = IntArray(40) { 3_000_000 + it })
  private val view: TimelineView = FilteredTimelineView(library.index(), LibraryFilter.ALL)
  private val engine = TimelineLayoutEngine()

  /** Le colonne del telefono (412 dp) e del tablet (1280 dp) per livello, come `GalleryGridLevels.forWidth`. */
  private fun spec(level: GridLevel, style: GridStyle, tablet: Boolean = false, newestAtBottom: Boolean = true): GridSpec {
    val columns = if (tablet) intArrayOf(16, 12, 10, 8)[level.ordinal] else intArrayOf(7, 5, 4, 3)[level.ordinal]
    return if (tablet) GridSpec(level, style, columns, widthPx = 2560, gapPx = 4, densityDp = 2f, newestAtBottom = newestAtBottom)
    else GridSpec(level, style, columns, widthPx = 1080, gapPx = 3, densityDp = 2.625f, newestAtBottom = newestAtBottom)
  }

  @Test
  fun quattroLivelliPerDueStiliEntroIBudget() {
    val times = StringBuilder()
    var total = 0L
    for (style in GridStyle.entries) for (level in GridLevel.entries) {
      val s = spec(level, style)
      var layout: TimelineGridLayout? = null
      val ms = TimelineIndexTest.timeMs { layout = engine.layout(view, s) }
      total += ms
      times.append("$level/$style=${ms}ms(${layout!!.rowCount} righe, ${layout!!.estimatedBytes() / 1024}KiB) ")
      checkInvariants(layout!!, view)
    }
    // A JIT caldo: il ricalcolo dei quattro livelli a ogni nuovo indice.
    val warm = TimelineIndexTest.timeMs { for (level in GridLevel.entries) engine.layout(view, spec(level, GridStyle.JUSTIFIED)) }
    println("LAYOUT_BUDGET: totale8=${total}ms quattroLivelliGiustificatiCaldo=${warm}ms $times")
    assertThat(total).isLessThan(15_000)
    assertThat(warm).isLessThan(5_000)
  }

  @Test
  fun quadratoUgualeAllOracoloDiP0() {
    val small = SyntheticLibrary(seed = 3, count = 20_000, placeIds = intArrayOf(5, 6, 7, 8, 9))
    val v = FilteredTimelineView(small.index(), LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    for (level in GridLevel.entries) for (newestAtBottom in listOf(true, false)) {
      val s = spec(level, GridStyle.SQUARE, newestAtBottom = newestAtBottom)
      val mine = engine.layout(v, s)
      val oracle = SimpleGridLayoutEngine().layout(v, s)
      assertThat(mine.rowCount).isEqualTo(oracle.rowCount)
      assertThat(mine.totalHeight).isEqualTo(oracle.totalHeight)
      assertThat(mine.sections).isEqualTo(oracle.sections)
      for (r in 0 until mine.rowCount) {
        if (mine.rowTop(r) != oracle.rowTop(r) || mine.rowKind(r) != oracle.rowKind(r) || mine.itemsOf(r) != oracle.itemsOf(r) ||
          mine.sectionOfRow(r) != oracle.sectionOfRow(r)
        ) {
          assertWithMessage("riga $r di $level").that(mine.itemsOf(r)).isEqualTo(oracle.itemsOf(r))
          assertWithMessage("riga $r di $level").that(mine.rowTop(r)).isEqualTo(oracle.rowTop(r))
        }
      }
      for (p in 0 until mine.itemCount step 7) {
        assertThat(mine.itemLeft(p)).isEqualTo(oracle.itemLeft(p))
        assertThat(mine.viewIndexAt(p)).isEqualTo(oracle.viewIndexAt(p))
      }
    }
  }

  @Test
  fun righeGiustificateRiempionoLaLarghezzaConProporzioniVicine() {
    for (tablet in listOf(false, true)) for (level in GridLevel.entries) {
      val s = spec(level, GridStyle.JUSTIFIED, tablet)
      val layout = engine.layout(view, s)
      var rows = 0
      var near = 0
      for (r in 0 until layout.rowCount) {
        if (layout.rowKind(r) != RowKind.CELLS) continue
        val first = layout.firstItemOf(r)
        val last = first + layout.itemCountOf(r) - 1
        val right = layout.itemLeft(last) + layout.itemWidth(last)
        val endsSection = r + 1 == layout.rowCount || layout.sectionOfRow(r + 1) != layout.sectionOfRow(r)
        if (!endsSection) {
          // Una riga piena finisce esattamente sul bordo destro.
          assertThat(right).isEqualTo(s.widthPx)
          rows++
          // Quanto la riga ha dovuto stirare o stringere le proporzioni naturali.
          var natural = 0.0
          for (p in first..last) {
            val si = view.snapshotIndex(layout.viewIndexAt(p))
            natural += layout.cellPx * (view.snapshot.aspectMilliAt(si).coerceIn(500, 2000) / 1000.0)
          }
          val factor = (s.widthPx - s.gapPx * (last - first)) / natural
          if (abs(factor - 1.0) <= 0.25) near++
        } else {
          assertThat(right).isAtMost(s.widthPx)
        }
      }
      assertWithMessage("righe con fattore entro il 25% ($level, tablet=$tablet)").that(near.toDouble() / rows).isAtLeast(0.9)
    }
  }

  @Test
  fun recentiInFondoOInCimaEdIntestazioniDelLivello() {
    val bottom = engine.layout(view, spec(GridLevel.MONTHS, GridStyle.SQUARE, newestAtBottom = true))
    val top = engine.layout(view, spec(GridLevel.MONTHS, GridStyle.SQUARE, newestAtBottom = false))
    assertThat(bottom.viewIndexAt(bottom.itemCount - 1)).isEqualTo(0)
    assertThat(top.viewIndexAt(0)).isEqualTo(0)
    assertThat(bottom.sections.first().firstDay).isLessThan(bottom.sections.last().firstDay)
    val all = engine.layout(view, spec(GridLevel.ALL, GridStyle.SQUARE))
    assertThat((0 until all.rowCount).none { all.rowKind(it) == RowKind.HEADER }).isTrue()
    assertThat(all.sections.all { it.level == GridLevel.MONTHS }).isTrue()
    val years = engine.layout(view, spec(GridLevel.YEARS, GridStyle.SQUARE))
    assertThat(years.sections.size).isIn(15..17)
    assertThat(years.rowHeight(0)).isEqualTo(Math.round(48 * 2.625f))
    // I luoghi della sezione: i piu' frequenti, in ordine, e quanti diversi.
    val days = engine.layout(view, spec(GridLevel.DAYS, GridStyle.SQUARE))
    val withPlaces = days.sections.first { it.distinctPlaces >= 2 }
    assertThat(withPlaces.topPlaces.size).isAtMost(3)
    assertThat(withPlaces.topPlaces.size).isAtMost(withPlaces.distinctPlaces)
    // Una vista vuota da' un layout vuoto, non rotto.
    val nothing = FilteredTimelineView(TimelineIndexReader.fromRows(emptyList(), 0, emptyMap()), LibraryFilter.ALL)
    val empty = engine.layout(nothing, spec(GridLevel.DAYS, GridStyle.JUSTIFIED))
    assertThat(empty.rowCount).isEqualTo(0)
    assertThat(empty.totalHeight).isEqualTo(0)
    assertThat(empty.itemAt(0, 10f)).isEqualTo(-1)
    assertThat(empty.rowAtOffset(100)).isEqualTo(0)
  }

  companion object {
    /** Le invarianti di ogni layout: copertura, somme prefisse, sezioni, geometria orizzontale, ricerche. */
    fun checkInvariants(l: TimelineGridLayout, v: TimelineView) {
      val s = l.spec
      assertThat(l.itemCount).isEqualTo(v.size)
      var expectedFirst = 0
      var headers = 0
      for (r in 0 until l.rowCount) {
        if (r > 0) assertThat(l.rowTop(r)).isEqualTo(l.rowTop(r - 1) + l.rowHeight(r - 1) + s.gapPx)
        if (l.rowKind(r) == RowKind.HEADER) {
          headers++
          assertThat(l.itemCountOf(r)).isEqualTo(0)
          assertThat(l.itemAt(r, 5f)).isEqualTo(-1)
          continue
        }
        val first = l.firstItemOf(r)
        val count = l.itemCountOf(r)
        if (first != expectedFirst || count <= 0) assertWithMessage("riga $r").that(first).isEqualTo(expectedFirst)
        var edge = -1
        for (p in first until first + count) {
          if (l.rowOfItem(p) != r) assertWithMessage("rowOfItem($p)").that(l.rowOfItem(p)).isEqualTo(r)
          val left = l.itemLeft(p)
          val width = l.itemWidth(p)
          if (left <= edge || width < 1 || left + width > s.widthPx) {
            assertWithMessage("cella $p della riga $r: left=$left width=$width edge=$edge").fail()
          }
          if (l.itemAt(r, left + 0.5f) != p || l.itemAt(r, left + width - 0.5f) != p) assertWithMessage("itemAt($p)").fail()
          if (p > first && s.gapPx > 1 && l.itemAt(r, left - 1f) != -1) assertWithMessage("spazio prima di $p").fail()
          edge = left + width - 1
        }
        // Con le intestazioni una sezione nuova comincia sempre con la sua intestazione.
        if (r > 0 && s.level.hasHeaders && l.sectionOfRow(r) != l.sectionOfRow(r - 1)) assertWithMessage("sezione della riga $r").fail()
        if (r > 0 && l.sectionOfRow(r) < l.sectionOfRow(r - 1)) assertWithMessage("sezioni in ordine alla riga $r").fail()
        expectedFirst = first + count
      }
      assertThat(expectedFirst).isEqualTo(l.itemCount)
      if (l.rowCount > 0) assertThat(l.rowTop(l.rowCount - 1) + l.rowHeight(l.rowCount - 1)).isEqualTo(l.totalHeight)
      assertThat(l.sections.sumOf { it.count }).isEqualTo(v.size)
      assertThat(headers).isEqualTo(if (s.level.hasHeaders) l.sections.size else 0)
      // rowAtOffset e' l'inverso delle somme prefisse.
      for (r in 0 until l.rowCount step 97) {
        assertThat(l.rowAtOffset(l.rowTop(r))).isEqualTo(r)
        assertThat(l.rowAtOffset(l.rowTop(r) + l.rowHeight(r) - 1)).isEqualTo(r)
      }
    }
  }
}
