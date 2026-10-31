package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Mouse e tastiera nella griglia (Fase 10, P4, D15, E-T10): clic destro = la pressione prolungata, passaggio del puntatore = cella evidenziata, Invio = apri, Ctrl+A = tutto,
 * Canc = **apre il foglio di conferma** (mai un'eliminazione diretta). Il foglio e l'eliminazione vera li prova `LibraryDeleteSafetyTest`; qui si prova che il tasto arriva
 * al foglio e a nient'altro.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GridKeyboardTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val state = syntheticLibraryState(count = 400)
  private val opened = mutableListOf<MediaId>()
  private var deleteSheets = 0

  private fun show(withDelete: Boolean = true) {
    composeRule.showThemed {
      MediaGrid(
        state = state.grid,
        contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp),
        onOpen = { opened += it },
        onDeleteKey = if (withDelete) ({ deleteSheets++ }) else null,
      )
    }
  }

  /** Il centro della prima cella disegnata (nelle coordinate della radice, che sono quelle della griglia): le celle sono i soli nodi con un'azione di tocco. */
  private fun firstCellCenter(): Offset =
    composeRule.onAllNodes(hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot.center

  private fun idAt(point: Offset): MediaId = composeRule.runOnIdle { state.grid.idAt(state.grid.positionAt(point))!! }

  /** Il mouse tocca la griglia (prende il fuoco) e poi esce: nessuna cella evidenziata, i tasti arrivano comunque alla griglia. */
  private fun hoverNothing() {
    val point = firstCellCenter()
    composeRule.onRoot().performMouseInput {
      moveTo(point)
      exit()
    }
    composeRule.waitForIdle()
  }

  private fun hover(point: Offset) {
    composeRule.onRoot().performMouseInput { moveTo(point) }
    composeRule.waitForIdle()
  }

  private fun press(key: Key) {
    composeRule.onRoot().performKeyInput {
      keyDown(key)
      keyUp(key)
    }
    composeRule.waitForIdle()
  }

  /** Un tasto con Ctrl premuto (Ctrl+A). */
  private fun pressWithCtrl(key: Key) {
    composeRule.onRoot().performKeyInput {
      keyDown(Key.CtrlLeft)
      keyDown(key)
      keyUp(key)
      keyUp(Key.CtrlLeft)
    }
    composeRule.waitForIdle()
  }

  @Test
  fun invioApreLaCellaEvidenziata() {
    show()
    val point = firstCellCenter()
    val expected = idAt(point)

    hover(point)
    press(Key.Enter)

    assertThat(opened).containsExactly(expected)
  }

  @Test
  fun invioSenzaCellaEvidenziataNeSelezione_nonFaNiente() {
    show()
    // La griglia prende il fuoco quando il mouse la tocca; poi il mouse esce: nessuna cella evidenziata.
    hoverNothing()
    press(Key.Enter)
    assertThat(opened).isEmpty()
  }

  @Test
  fun ctrlASelezionaTuttaLaVista() {
    show()
    hover(firstCellCenter())

    pressWithCtrl(Key.A)

    assertThat(state.grid.selecting).isTrue()
    assertThat(state.grid.selection.selected.value.ids).hasSize(400)
  }

  @Test
  fun cancApreIlFoglioDiConferma_maNonEliminaNiente() {
    show()
    hover(firstCellCenter())
    pressWithCtrl(Key.A)
    val selectedBefore = state.grid.selection.selected.value.ids

    press(Key.Delete)

    // Il foglio si apre (una volta), la selezione e la vista sono intatte: il tasto non ha eliminato niente.
    assertThat(deleteSheets).isEqualTo(1)
    assertThat(state.grid.selection.selected.value.ids).isEqualTo(selectedBefore)
    assertThat(state.grid.view.size).isEqualTo(400)
  }

  @Test
  fun cancSuUnaCellaEvidenziataSenzaSelezione_laSelezionaEApreIlFoglio() {
    show()
    val point = firstCellCenter()
    val expected = idAt(point)
    hover(point)

    press(Key.Delete)

    assertThat(deleteSheets).isEqualTo(1)
    assertThat(state.grid.selecting).isTrue()
    assertThat(state.grid.selection.selected.value.ids).containsExactly(expected)
    assertThat(state.grid.view.size).isEqualTo(400)
  }

  @Test
  fun cancSenzaNienteDaEliminare_nonFaNiente() {
    show()
    hoverNothing()
    press(Key.Delete)
    assertThat(deleteSheets).isEqualTo(0)
    assertThat(state.grid.selecting).isFalse()
  }

  @Test
  fun inUnaGrigliaCheNonElimina_cancNonFaNiente() {
    show(withDelete = false)
    hover(firstCellCenter())
    pressWithCtrl(Key.A)

    press(Key.Delete)

    assertThat(deleteSheets).isEqualTo(0)
    assertThat(state.grid.view.size).isEqualTo(400)
  }

  @Test
  fun ilClicDestroEntraInSelezioneConLaCella_enonApre() {
    show()
    val point = firstCellCenter()
    val expected = idAt(point)

    composeRule.onRoot().performMouseInput { click(point, MouseButton.Secondary) }
    composeRule.waitForIdle()

    assertThat(opened).isEmpty()
    assertThat(state.grid.selecting).isTrue()
    assertThat(state.grid.selection.selected.value.ids).containsExactly(expected)
  }

  @Test
  fun ilClicDestroSuUnaCellaGiaSelezionata_nonLaDeseleziona() {
    show()
    val point = firstCellCenter()
    val expected = idAt(point)
    composeRule.onRoot().performMouseInput { click(point, MouseButton.Secondary) }
    composeRule.waitForIdle()

    composeRule.onRoot().performMouseInput { click(point, MouseButton.Secondary) }
    composeRule.waitForIdle()

    assertThat(state.grid.selection.selected.value.ids).containsExactly(expected)
  }

  @Test
  fun ilClicSinistroApreComeSempre() {
    show()
    val point = firstCellCenter()
    val expected = idAt(point)

    composeRule.onRoot().performMouseInput { click(point) }
    composeRule.waitForIdle()

    assertThat(opened).containsExactly(expected)
    assertThat(state.grid.selecting).isFalse()
  }

  @Test
  fun inSelezioneInvioApreL_UnicaSelezionata_enonSeSonoDue() {
    show()
    val point = firstCellCenter()
    val first = idAt(point)
    composeRule.onRoot().performMouseInput { click(point, MouseButton.Secondary) }
    composeRule.waitForIdle()
    // Il mouse esce dalla griglia: nessuna cella evidenziata, un solo selezionato.
    composeRule.onRoot().performMouseInput { moveTo(point); exit() }
    composeRule.waitForIdle()

    press(Key.Enter)
    assertThat(opened).containsExactly(first)

    // Con due selezionati e nessuna evidenziata l'invio non sceglie al posto dell'utente.
    composeRule.runOnIdle { state.grid.selection.selectAll(listOf(composeRule.runOnIdle { state.grid.idAt(5)!! })) }
    opened.clear()
    press(Key.Enter)
    assertThat(opened).isEmpty()
  }

  @Test
  fun iTastiNonNostriNonCambianoNiente() {
    show()
    hover(firstCellCenter())
    press(Key.A)
    press(Key.Backspace)
    press(Key.Escape)
    assertThat(state.grid.selecting).isFalse()
    assertThat(opened).isEmpty()
    assertThat(deleteSheets).isEqualTo(0)
  }
}
