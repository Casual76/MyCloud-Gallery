package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalMediaDragPort
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalMediaDragStarter
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDrag
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragPort
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragShadow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragStarter
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P6, D20, E-T12, E-T18): il trascinamento verso un'altra app dalla griglia della Libreria, **senza il sistema che trascina** (porta e avvio finti che registrano). Col tocco si trascina
 * la **selezione** con la pressione lunga su una cella gia' selezionata; su una cella non selezionata la pressione lunga resta la selezione di sempre. Col mouse si porta via la cella (o la
 * selezione) trascinando. Il tocco rapido, la selezione e il resto non cambiano; una griglia senza origine (i Nascosti, gli Eliminati di recente) non trascina mai.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GridDragTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RecordingPort : MediaDragPort {
    val requests = mutableListOf<Pair<String, List<String>>>()

    override suspend fun prepare(origin: String, ids: List<String>): MediaDrag {
      requests += origin to ids
      val clip = ClipData(ClipDescription("", arrayOf("image/jpeg")), ClipData.Item(Uri.parse("content://x.documents/document/aaaaaaaaaaaaaaaa")))
      return MediaDrag(clip, 257, ids.size) {}
    }
  }

  private class RecordingStarter : MediaDragStarter {
    val shadows = mutableListOf<MediaDragShadow>()

    override fun start(drag: MediaDrag, shadow: MediaDragShadow): Boolean {
      shadows += shadow
      return true
    }
  }

  private val port = RecordingPort()
  private val starter = RecordingStarter()
  private val loader = ControlledThumbnailLoader()

  private fun show(state: MediaGridState, origin: DragOrigin? = DragOrigin.LIBRARY) {
    composeRule.showThemed {
      CompositionLocalProvider(LocalMediaDragPort provides port, LocalMediaDragStarter provides starter) {
        MediaGrid(state = state, contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp), dragOrigin = origin)
      }
    }
  }

  private fun grid() = syntheticGrid(count = 400, loader = loader).first

  /** Il centro di una cella intera sullo schermo: [column] nella fila al centro (come la prova del comportamento della griglia). */
  private fun cell(state: MediaGridState, column: Int = 1, rowFromCenter: Int = 0): Pair<MediaId, Offset> {
    val l = state.layout!!
    val v = state.viewport
    val top = state.contentTop(l)
    val rows = GridGeometry.rowsBetween(l, v.padTop.toFloat(), (v.height - v.padBottom).toFloat(), top, v).filter { row ->
      val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
      l.rowKind(row) == RowKind.CELLS && l.itemsOf(row).count() > column && screenTop >= v.padTop && screenTop + l.rowHeight(row) <= v.height - v.padBottom
    }
    check(rows.isNotEmpty()) { "Nessuna fila con ${column + 1} celle sullo schermo." }
    val center = state.rowAtCenter(l)
    val sorted = rows.sortedBy { kotlin.math.abs(it - center) }
    val row = rows.getOrNull(rows.indexOf(sorted.first()) + rowFromCenter) ?: sorted.first()
    val position = l.itemsOf(row).first + column
    val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
    return state.idAt(position)!! to Offset(l.itemLeft(position) + l.itemWidth(position) / 2f, screenTop + l.rowHeight(row) / 2f)
  }

  /** La pressione lunga, nel tempo degli eventi (la griglia) e in quello delle coroutine (il trascinamento), e poi il dito si alza. */
  private fun longPress(point: Offset, release: Boolean = true) {
    composeRule.onRoot().performTouchInput {
      down(point)
      advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
      move()
    }
    // Il dito resta giu' mentre il tempo passa (la griglia ha un ciclo di fotogrammi mentre seleziona: con il dito giu' Compose non e' mai fermo, quindi niente `waitForIdle` qui).
    composeRule.mainClock.advanceTimeBy(700)
    if (release) composeRule.onRoot().performTouchInput { up() }
    composeRule.waitForIdle()
  }

  /** La pressione lunga della sola griglia (nessun trascinamento in gioco): tutto in un blocco, come la griglia la misura sul tempo degli eventi. */
  private fun gridLongPress(point: Offset) {
    composeRule.onRoot().performTouchInput {
      down(point)
      advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
      move()
      up()
    }
    composeRule.waitForIdle()
  }

  private fun select(state: MediaGridState, ids: List<MediaId>) {
    composeRule.runOnIdle {
      state.startSelecting()
      ids.forEach { state.selection.toggle(it) }
    }
    composeRule.waitForIdle()
  }

  private fun selected(state: MediaGridState): Set<MediaId> = state.selection.selected.value.ids

  @Test
  fun laPressioneLungaSuUnaCellaNonSelezionataSelezionaEBasta() {
    val state = grid()
    show(state)
    val (id, point) = cell(state)

    gridLongPress(point)

    assertThat(state.selecting).isTrue()
    assertThat(selected(state)).containsExactly(id)
    assertThat(port.requests).isEmpty()
    assertThat(starter.shadows).isEmpty()
  }

  @Test
  fun laPressioneLungaSuUnaCellaSelezionataTrascinaLaSelezioneNellOrdineDellaVista() {
    val state = grid()
    show(state)
    val (a, _) = cell(state, column = 0)
    val (b, pointB) = cell(state, column = 1)
    val (c, _) = cell(state, column = 0, rowFromCenter = 1)
    // Si seleziona in un ordine diverso da quello della vista: il trascinamento e' sempre nell'ordine della vista.
    select(state, listOf(c, a, b))

    longPress(pointB)

    val expected = listOf(a, b, c).sortedBy { state.view.viewIndexOf(it) }.map { it.value }
    assertThat(port.requests).containsExactly("library" to expected)
    assertThat(starter.shadows).hasSize(1)
    assertThat(starter.shadows.single().count).isEqualTo(3)
    // La griglia non ha toccato la selezione (ne' l'ha tolta, ne' l'ha trattata come un tocco).
    assertThat(selected(state)).containsExactly(a, b, c)
    assertThat(state.selecting).isTrue()
  }

  @Test
  fun unToccoRapidoSuUnaCellaSelezionataLaDeselezionaComeSempre() {
    val state = grid()
    show(state)
    val (a, pointA) = cell(state, column = 0)
    val (b, _) = cell(state, column = 1)
    select(state, listOf(a, b))

    composeRule.onRoot().performTouchInput { click(pointA) }
    composeRule.waitForIdle()

    assertThat(selected(state)).containsExactly(b)
    assertThat(port.requests).isEmpty()
  }

  @Test
  fun lOmbraELaMiniaturaLocaleDellaCellaPremuta() {
    val state = grid()
    show(state)
    val (a, pointA) = cell(state, column = 0)
    val (b, _) = cell(state, column = 1)
    val bitmap = ImageBitmap(8, 8)
    loader.deliver(state.thumbKey(a), bitmap)
    select(state, listOf(a, b))

    longPress(pointA)

    assertThat(starter.shadows.single().thumbnail).isSameInstanceAs(bitmap)
  }

  @Test
  fun senzaOrigineLaGrigliaNonTrascinaMai() {
    val state = grid()
    show(state, origin = null)
    val (a, pointA) = cell(state, column = 0)
    val (b, _) = cell(state, column = 1)
    select(state, listOf(a, b))

    gridLongPress(pointA)

    assertThat(port.requests).isEmpty()
    assertThat(starter.shadows).isEmpty()
    composeRule.onRoot().performMouseInput { moveTo(pointA); press(MouseButton.Primary); moveBy(Offset(60f, 0f)); release(MouseButton.Primary) }
    composeRule.waitForIdle()
    assertThat(port.requests).isEmpty()
  }

  @Test
  fun ilMouseTrascinaLaCellaSenzaEntrareInSelezione() {
    val state = grid()
    show(state)
    val (id, point) = cell(state)

    composeRule.onRoot().performMouseInput { moveTo(point); press(MouseButton.Primary); moveBy(Offset(60f, 0f)) }
    composeRule.waitForIdle()

    assertThat(port.requests).containsExactly("library" to listOf(id.value))
    assertThat(state.selecting).isFalse()
    assertThat(selected(state)).isEmpty()
    composeRule.onRoot().performMouseInput { release(MouseButton.Primary) }
  }

  @Test
  fun ilMouseSuUnaCellaSelezionataPortaViaLaSelezione() {
    val state = grid()
    show(state)
    val (a, pointA) = cell(state, column = 0)
    val (b, _) = cell(state, column = 1)
    select(state, listOf(a, b))

    composeRule.onRoot().performMouseInput { moveTo(pointA); press(MouseButton.Primary); moveBy(Offset(60f, 0f)) }
    composeRule.waitForIdle()

    assertThat(port.requests.single().second.toSet()).isEqualTo(setOf(a.value, b.value))
    assertThat(selected(state)).containsExactly(a, b)
    composeRule.onRoot().performMouseInput { release(MouseButton.Primary) }
  }
}
