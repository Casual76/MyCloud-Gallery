package dev.pampa.mycloudgallery.feature.library.grid

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridAnchors
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import java.time.LocalDate
import java.util.SplittableRandom
import kotlin.math.abs
import org.junit.Test

/**
 * La geometria della griglia su JVM: hit test, finestra delle righe, trasformazione del pinch e ancoraggio. E' cio' che
 * la griglia fa a ogni gesto e a ogni cambio di layout: aritmetica sul layout, nessuna misura.
 */
class GridGeometryTest {

  private val library = SyntheticLibrary(seed = 3L, count = 3_000)
  private val view = library.view()
  private val engine = TimelineLayoutEngine()
  private val columns = mapOf(GridLevel.YEARS to 7, GridLevel.MONTHS to 5, GridLevel.DAYS to 4, GridLevel.ALL to 3)

  private fun layout(level: GridLevel, style: GridStyle = GridStyle.SQUARE, width: Int = 1_080): GridLayout =
    engine.layout(view, GridSpec(level, style, columns.getValue(level), width, gapPx = 5, densityDp = 2.625f))

  private val viewport = GridViewport(width = 1_080, height = 2_400, padTop = 260, padBottom = 300)

  @Test
  fun lHitTestTrovaLaCellaSottoIlDitoENonLeIntestazioniNeGliSpazi() {
    val l = layout(GridLevel.DAYS)
    val row = (0 until l.rowCount).first { l.rowKind(it) == RowKind.CELLS && l.itemsOf(it).count() == 4 && it > 40 }
    val contentTop = l.rowTop(row) - 500
    val screenY = viewport.padTop + (l.rowTop(row) - contentTop) + l.rowHeight(row) / 2f
    for (p in l.itemsOf(row)) {
      val x = l.itemLeft(p) + l.itemWidth(p) / 2f
      assertThat(GridGeometry.positionAt(l, x, screenY, contentTop, viewport)).isEqualTo(p)
    }
    // Lo spazio fra due celle e un'intestazione non sono celle.
    val gapX = (l.itemLeft(l.itemsOf(row).first) + l.itemWidth(l.itemsOf(row).first) + 2).toFloat()
    assertThat(GridGeometry.positionAt(l, gapX, screenY, contentTop, viewport)).isEqualTo(-1)
    val header = (0 until l.rowCount).first { l.rowKind(it) == RowKind.HEADER && it > 10 }
    val headerY = viewport.padTop + (l.rowTop(header) - contentTop) + 2f
    val headerContentTop = l.rowTop(header) - 100
    assertThat(GridGeometry.positionAt(l, 10f, viewport.padTop + 100f + 2f, headerContentTop, viewport)).isEqualTo(-1)
    assertThat(headerY).isNotNaN()
  }

  @Test
  fun laPosizioneDellaListaDiventaUnaCoordinataDelContenuto() {
    val l = layout(GridLevel.MONTHS)
    assertThat(GridGeometry.contentTop(l, GridScroll(10, 7))).isEqualTo(l.rowTop(10) + 7)
    // Il piede (l'elemento dopo l'ultima riga) sta dopo l'altezza totale e uno spazio.
    assertThat(GridGeometry.contentTop(l, GridScroll(l.rowCount, 0))).isEqualTo(l.totalHeight + l.spec.gapPx)
  }

  @Test
  fun leRigheDiUnaFasciaSonoQuelleCheLaToccano() {
    val l = layout(GridLevel.YEARS)
    val contentTop = l.rowTop(30)
    val rows = GridGeometry.rowsBetween(l, 0f, viewport.height.toFloat(), contentTop, viewport)
    val firstTop = GridGeometry.rowScreenTop(l, rows.first, contentTop, viewport)
    val lastTop = GridGeometry.rowScreenTop(l, rows.last, contentTop, viewport)
    assertThat(firstTop).isAtMost(0)
    assertThat(firstTop + l.rowHeight(rows.first) + l.spec.gapPx).isAtLeast(0)
    assertThat(lastTop).isLessThan(viewport.height)
    assertThat(lastTop + l.rowHeight(rows.last) + l.spec.gapPx).isAtLeast(viewport.height)
  }

  @Test
  fun laTrasformazioneDelPinchSiInverte() {
    val rnd = SplittableRandom(7)
    repeat(200) {
      val t = GridTransform(rnd.nextDouble(0.0, 1_080.0).toFloat(), rnd.nextDouble(0.0, 2_400.0).toFloat(), rnd.nextDouble(0.6, 1.8).toFloat())
      val x = rnd.nextDouble(0.0, 1_080.0).toFloat()
      val y = rnd.nextDouble(0.0, 2_400.0).toFloat()
      val back = t.toGrid(t.toScreen(x, y).x, t.toScreen(x, y).y)
      assertThat(abs(back.x - x)).isLessThan(0.01f)
      assertThat(abs(back.y - y)).isLessThan(0.01f)
    }
    // L'origine resta ferma, qualunque sia la scala (e' il punto fra le dita).
    val t = GridTransform(400f, 900f, 0.8f)
    assertThat(t.toScreen(400f, 900f).x).isWithin(0.001f).of(400f)
    assertThat(t.toScreen(400f, 900f).y).isWithin(0.001f).of(900f)
  }

  /**
   * 1.000 casi a caso con l'ancoraggio di P5 (`TimelineGridAnchors`), come la griglia lo usa: dal punto sotto il dito nel
   * layout di partenza alla posizione della lista nel layout di arrivo (fra livelli e stili), il punto torna sotto il dito.
   */
  @Test
  fun lAncoraggioTieneIlPuntoSottoIlDitoFraLivelliEStili() {
    val rnd = SplittableRandom(11)
    val levels = GridLevel.entries
    var checked = 0
    repeat(1_000) {
      val from = layout(levels[rnd.nextInt(4)], if (rnd.nextBoolean()) GridStyle.SQUARE else GridStyle.JUSTIFIED)
      val to = layout(levels[rnd.nextInt(4)], if (rnd.nextBoolean()) GridStyle.SQUARE else GridStyle.JUSTIFIED)
      val row = rnd.nextInt(from.rowCount)
      if (from.rowKind(row) != RowKind.CELLS) return@repeat
      val first = from.firstItemIn(row)
      val p = first + rnd.nextInt(from.itemCountIn(row))
      val x = from.itemLeft(p) + rnd.nextDouble(0.0, from.itemWidth(p).toDouble()).toFloat()
      val yInRow = rnd.nextDouble(0.0, from.rowHeight(row).toDouble()).toFloat()
      val screenY = rnd.nextDouble(0.0, viewport.contentHeight.toDouble()).toFloat()
      val anchor = TimelineGridAnchors.anchorAt(from, row, x, yInRow, screenY)
      assertThat(anchor.position).isEqualTo(p)
      val target = TimelineGridAnchors.place(to, anchor)
      // La lista in arrivo: la riga [target.row] in cima, salita di [target.offsetPx]. Dov'e' il punto della cella?
      val contentTop = GridGeometry.contentTop(to, GridScroll(target.row, target.offsetPx))
      val targetRow = to.rowOfItem(p)
      val point = to.rowTop(targetRow) + anchor.fy * to.rowHeight(targetRow) - contentTop
      // In cima al contenuto lo scorrimento si ferma a 0: li' l'elemento sta piu' in alto del dito.
      if (contentTop > 0 && contentTop < to.totalHeight - 1) {
        assertThat(abs(point - screenY)).isAtMost(1f)
        checked++
      }
    }
    assertThat(checked).isGreaterThan(300)
  }

  @Test
  fun loScorrimentoAutomaticoEProporzionaleAllaProfondita() {
    val edge = 168f
    val max = 3_000f
    assertThat(autoScrollSpeed(1_200f, viewport, edge, max)).isEqualTo(0f)
    val nearBottom = viewport.height - viewport.padBottom - edge / 2
    assertThat(autoScrollSpeed(nearBottom, viewport, edge, max)).isWithin(1f).of(max / 2)
    assertThat(autoScrollSpeed(viewport.height.toFloat(), viewport, edge, max)).isEqualTo(max)
    assertThat(autoScrollSpeed(viewport.padTop + edge / 4, viewport, edge, max)).isWithin(1f).of(-max * 3 / 4)
  }

  @Test
  fun leParoleDellaGriglia() {
    assertThat(GridText.duration(42_000)).isEqualTo("0:42")
    assertThat(GridText.duration(725_000)).isEqualTo("12:05")
    assertThat(GridText.duration(3_800_000)).isEqualTo("1:03:20")
    assertThat(GridText.date(20240312)).isEqualTo(LocalDate.of(2024, 3, 12))
    assertThat(GridText.month(java.time.YearMonth.of(2024, 3))).isEqualTo("Marzo 2024")
  }

  @Test
  fun laTagliaDellaMiniaturaCopreIlLatoLungo() {
    assertThat(GridGeometry.bucketFor(150, 150).px).isEqualTo(160)
    assertThat(GridGeometry.bucketFor(266, 266).px).isEqualTo(320)
    assertThat(GridGeometry.bucketFor(500, 250).px).isEqualTo(400)
  }
}
