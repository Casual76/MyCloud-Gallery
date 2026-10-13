package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridAnchors
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridLayout
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.layout.TimelineScrubber
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import java.time.YearMonth
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * L'ancoraggio: **su 1.000 casi casuali l'elemento sotto il dito resta sotto il dito** cambiando livello, stile e
 * larghezza (telefono, tablet, finestra ridimensionata). E lo scrubber sulle somme prefisse.
 */
class AnchorAndScrubberTest {

  private val library = SyntheticLibrary(seed = 21, count = 30_000, placeIds = intArrayOf(1, 2, 3))
  private val view = FilteredTimelineView(library.index(), LibraryFilter.ALL)
  private val engine = TimelineLayoutEngine()

  private val layouts: List<TimelineGridLayout> = buildList {
    val widths = listOf(Triple(1080, 2.625f, intArrayOf(7, 5, 4, 3)), Triple(2560, 2f, intArrayOf(16, 12, 10, 8)), Triple(1600, 2f, intArrayOf(10, 8, 6, 5)))
    for ((width, density, columns) in widths) for (style in GridStyle.entries) for (level in GridLevel.entries) {
      add(engine.layout(view, GridSpec(level, style, columns[level.ordinal], width, gapPx = 3, densityDp = density)))
    }
  }

  private val viewport = 2_200

  @Test
  fun milleCasiCasualiLElementoRestaSottoIlDito() {
    val r = Random(1_000)
    var exact = 0
    var clampedAtTop = 0
    var cases = 0
    while (cases < 1_000) {
      val l1 = layouts[r.nextInt(layouts.size)]
      val l2 = layouts[r.nextInt(layouts.size)]
      if (l1 === l2) continue
      cases++
      // Il dito su una cella qualunque di l1, in un punto qualunque della cella, a un'altezza qualunque dello schermo.
      val row = (0 until l1.rowCount).random(r).let { if (l1.rowKind(it) == RowKind.HEADER) it + 1 else it }
      val first = l1.firstItemOf(row)
      val position = first + r.nextInt(l1.itemCountOf(row))
      val x = l1.itemLeft(position) + r.nextFloat() * (l1.itemWidth(position) - 1)
      val yInRow = r.nextFloat() * (l1.rowHeight(row) - 1)
      val screenY = r.nextFloat() * viewport
      val anchor = TimelineGridAnchors.anchorAt(l1, row, x, yInRow, screenY)
      assertThat(anchor.position).isEqualTo(position)

      val target = TimelineGridAnchors.place(l2, anchor)
      val scrollY = l2.rowTop(target.row) + target.offsetPx
      assertThat(target.offsetPx).isAtLeast(0)
      assertThat(target.offsetPx).isLessThan(l2.rowHeight(target.row) + l2.spec.gapPx)
      val row2 = l2.rowOfItem(position)
      val ideal = l2.rowTop(row2).toDouble() + anchor.fy.toDouble() * l2.rowHeight(row2) - screenY
      if (ideal < 0) {
        // In cima al contenuto non si puo' scorrere oltre: lo scorrimento e' 0 e la cella sta piu' in alto del dito.
        assertThat(scrollY).isEqualTo(0)
        clampedAtTop++
        continue
      }
      if (ideal > l2.totalHeight - 1) continue
      // Lo stesso punto della cella sta alla stessa altezza dello schermo (al pixel).
      assertWithMessage("caso $cases").that(abs(scrollY - ideal)).isAtMost(0.5 + 1e-6)
      // E sotto il dito, nel nuovo layout, c'e' la stessa cella: la riga del punto del dito e la cella a quell'ascissa.
      val fingerY = scrollY + screenY.toDouble()
      val inside = fingerY - l2.rowTop(row2)
      if (inside >= 1.0 && inside <= l2.rowHeight(row2) - 1.0) assertWithMessage("caso $cases").that(l2.rowAtOffset(fingerY.toInt())).isEqualTo(row2)
      else assertThat(abs(inside - anchor.fy.toDouble() * l2.rowHeight(row2))).isAtMost(0.5 + 1e-6)
      val fingerX = l2.itemLeft(position) + anchor.fx * (l2.itemWidth(position) - 1)
      assertThat(l2.itemAt(row2, fingerX)).isEqualTo(position)
      exact++
    }
    println("ANCHOR_CASES: esatti=$exact in cima=$clampedAtTop")
    assertThat(exact).isAtLeast(900)
  }

  @Test
  fun unPinchContinuoAttraversoMoltiLivelliNonPerdeLElemento() {
    val r = Random(7)
    repeat(100) {
      var l = layouts[r.nextInt(layouts.size)]
      val row = (0 until l.rowCount).random(r).let { if (l.rowKind(it) == RowKind.HEADER) it + 1 else it }
      val position = l.firstItemOf(row) + r.nextInt(l.itemCountOf(row))
      var anchor = TimelineGridAnchors.anchorAt(l, row, l.itemLeft(position) + 2f, 5f, 1_000f)
      repeat(10) {
        val next = layouts[r.nextInt(layouts.size)]
        val target = TimelineGridAnchors.place(next, anchor)
        val fingerY = next.rowTop(target.row) + target.offsetPx + anchor.screenY
        val fingerRow = next.rowAtOffset(fingerY.toInt())
        val x = next.itemLeft(position) + anchor.fx * next.itemWidth(position)
        val again = TimelineGridAnchors.anchorAt(next, fingerRow, x, fingerY - next.rowTop(fingerRow), anchor.screenY)
        if (next.rowTop(next.rowOfItem(position)) >= anchor.screenY) assertThat(again.position).isEqualTo(position)
        anchor = again.copy(position = position)
        l = next
      }
    }
  }

  @Test
  fun ancoraggioInOLogNERitornoDalViewer() {
    val l = layouts.first { it.spec.level == GridLevel.ALL && it.spec.style == GridStyle.JUSTIFIED }
    val l2 = layouts.first { it.spec.level == GridLevel.DAYS && it.spec.style == GridStyle.SQUARE }
    val r = Random(3)
    val ms = TimelineIndexTest.timeMs {
      repeat(100_000) {
        val row = r.nextInt(l.rowCount)
        val a = TimelineGridAnchors.anchorAt(l, row, r.nextFloat() * 1080, 10f, 900f)
        TimelineGridAnchors.place(l2, a)
      }
    }
    println("ANCHOR_BUDGET: 100k ancoraggi = ${ms}ms")
    assertThat(ms).isLessThan(3_000)
    // Il "porta in vista" al centro.
    val p = l.itemCount / 2
    val center = TimelineGridAnchors.center(l, p, viewport)
    val top = l.rowTop(center.row) + center.offsetPx
    val rowOf = l.rowOfItem(p)
    assertThat(abs(top + viewport / 2 - (l.rowTop(rowOf) + l.rowHeight(rowOf) / 2))).isAtMost(1)
    // Il dito su un'intestazione si ancora alla prima cella della sezione; in uno spazio, alla cella piu' vicina.
    val days = layouts.first { it.spec.level == GridLevel.DAYS }
    val header = (0 until days.rowCount).first { days.rowKind(it) == RowKind.HEADER && it > 10 }
    assertThat(TimelineGridAnchors.anchorAt(days, header, 50f, 3f, 400f).position).isEqualTo(days.firstItemOf(header + 1))
    val cells = header + 1
    val gapX = days.itemLeft(days.firstItemOf(cells)) + days.itemWidth(days.firstItemOf(cells)) + 1f
    assertThat(TimelineGridAnchors.anchorAt(days, cells, gapX, 3f, 400f).position).isIn(listOf(days.firstItemOf(cells), days.firstItemOf(cells) + 1))
  }

  @Test
  fun lAncoraSegueLElementoInUnAltraVistaOIndice() {
    val l = layouts.first { it.spec.level == GridLevel.MONTHS && it.spec.style == GridStyle.SQUARE }
    val position = l.itemCount - 500
    val anchor = TimelineGridAnchors.anchorAt(l, l.rowOfItem(position), l.itemLeft(position) + 1f, 1f, 300f)
    val id = TimelineGridAnchors.idAt(l, view, position)
    // Un indice nuovo con 10 elementi in meno fra i piu' vecchi: la posizione cambia, l'elemento no.
    val smaller = FilteredTimelineView(TimelinePatcher.remove(library.index(), (view.size - 10 until view.size).map { view.snapshot.idAt(it) }), LibraryFilter.ALL)
    val l2 = engine.layout(smaller, l.spec)
    val moved = TimelineGridAnchors.retarget(anchor, l, view, l2, smaller)
    assertThat(moved.position).isEqualTo(position - 10)
    assertThat(TimelineGridAnchors.idAt(l2, smaller, moved.position)).isEqualTo(id)
  }

  @Test
  fun loScrubberUsaLeSommePrefisseEDiceIlMeseDellaRiga() {
    for (l in layouts.take(8)) {
      assertThat(TimelineScrubber.rowAt(l, 0f)).isEqualTo(0)
      assertThat(TimelineScrubber.rowAt(l, 1f)).isEqualTo(l.rowCount - 1)
      assertThat(TimelineScrubber.rowAt(l, -3f)).isEqualTo(0)
      var last = 0
      for (k in 0..1_000) {
        val row = TimelineScrubber.rowAt(l, k / 1_000f)
        assertThat(row).isAtLeast(last)
        last = row
      }
      for (row in 0 until l.rowCount step 11) {
        assertThat(TimelineScrubber.rowAt(l, TimelineScrubber.fractionOf(l, row))).isEqualTo(row)
        // La bolla: il mese della prima cella della riga (per un'intestazione, della sua sezione).
        val p = l.firstItemOf(row)
        val day = view.snapshot.localDayAt(view.snapshotIndex(l.viewIndexAt(p)))
        assertThat(TimelineScrubber.label(l, row)).isEqualTo(YearMonth.of(day / 10_000, day / 100 % 100))
      }
      // I mesi si susseguono senza tornare indietro (i piu' vecchi in alto) e cambiano a ogni inizio di mese.
      val starts = TimelineScrubber.monthStarts(l)
      assertThat(starts.first()).isEqualTo(0)
      val labels = starts.map { TimelineScrubber.label(l, it) }
      assertThat(labels.zipWithNext().count { (a, b) -> b < a }).isAtMost(labels.size / 50)
      assertThat(labels.distinct().size).isAtLeast(150)
    }
  }
}
