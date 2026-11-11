package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
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
 * Fase H, D1 (11/11, l'utente sull'S25): «quando faccio una gesture back cliccando prima dove c'e' una foto, la foto viene aperta».
 *
 * La causa: il sistema si prende il gesto dal bordo (l'Indietro, anche predittivo) e manda alla finestra un `ACTION_CANCEL`; Compose lo
 * consegna alla griglia come un rilascio **gia' consumato**, e la griglia lo leggeva come un tocco perche' guardava solo `pressed`. Qui le
 * stesse sequenze di eventi: pressione, un movimento sotto lo slop (il sistema decide prima della griglia), annullamento. Niente si apre,
 * niente si seleziona; un tocco normale apre ancora.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class GridSystemBackTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val opened = mutableListOf<MediaId>()

  private fun show(): MediaGridState {
    val (state, _) = syntheticGrid(count = 600)
    composeRule.showThemed {
      MediaGrid(state = state, contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp), onOpen = { opened += it })
    }
    composeRule.waitForIdle()
    return state
  }

  /** Il centro di una cella intera nell'area dei contenuti (e la sua posizione). */
  private fun cell(state: MediaGridState, nearLeftEdge: Boolean = false): Pair<Int, Offset> {
    val l = state.layout!!
    val v = state.viewport
    val top = state.contentTop(l)
    val row = (0 until l.rowCount).first { row ->
      val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
      l.rowKind(row) == RowKind.CELLS && screenTop >= v.padTop && screenTop + l.rowHeight(row) <= v.height - v.padBottom
    }
    val position = l.itemsOf(row).first
    val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
    val x = if (nearLeftEdge) 4f else l.itemLeft(position) + l.itemWidth(position) / 2f
    return position to Offset(x, screenTop + l.rowHeight(row) / 2f)
  }

  @Test
  fun unToccoNormaleApreLaFoto() {
    val state = show()
    val (position, point) = cell(state)
    composeRule.onRoot().performTouchInput {
      down(point)
      up()
    }
    composeRule.waitForIdle()
    assertThat(opened).containsExactly(state.idAt(position))
  }

  @Test
  fun lIndietroDalBordoSopraUnaFotoNonLaApre() {
    val state = show()
    // Il dito si posa sulla cella al bordo sinistro, va un poco a destra (meno dello slop: il sistema decide prima), e il sistema annulla.
    val (_, point) = cell(state, nearLeftEdge = true)
    composeRule.onRoot().performTouchInput {
      down(point)
      moveBy(Offset(viewConfiguration.touchSlop * 0.6f, 0f))
      cancel()
    }
    composeRule.waitForIdle()
    assertThat(opened).isEmpty()
    assertThat(state.selecting).isFalse()
  }

  @Test
  fun unAnnullamentoSenzaMovimentoNonApreNiente() {
    val state = show()
    val (_, point) = cell(state)
    composeRule.onRoot().performTouchInput {
      down(point)
      cancel()
    }
    composeRule.waitForIdle()
    assertThat(opened).isEmpty()
    // Dopo l'annullamento il gesto successivo e' di nuovo un tocco normale.
    composeRule.onRoot().performTouchInput {
      down(point)
      up()
    }
    composeRule.waitForIdle()
    assertThat(opened).hasSize(1)
  }

  @Test
  fun inSelezioneUnAnnullamentoNonCambiaLaSelezione() {
    val state = show()
    composeRule.runOnIdle { state.startSelecting() }
    composeRule.waitForIdle()
    val (_, point) = cell(state, nearLeftEdge = true)
    composeRule.onRoot().performTouchInput {
      down(point)
      moveBy(Offset(viewConfiguration.touchSlop * 0.6f, 0f))
      cancel()
    }
    composeRule.waitForIdle()
    assertThat(state.selection.selected.value.isEmpty).isTrue()
    assertThat(state.selecting).isTrue()
    assertThat(opened).isEmpty()
  }

  @Test
  fun unAnnullamentoPrimaDellaPressioneLungaNonEntraInSelezione() {
    val state = show()
    val (_, point) = cell(state)
    composeRule.onRoot().performTouchInput {
      down(point)
      advanceEventTime(viewConfiguration.longPressTimeoutMillis / 2)
      cancel()
    }
    // Si lascia passare piu' della pressione lunga: niente deve scattare dopo l'annullamento.
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(state.selecting).isFalse()
    assertThat(opened).isEmpty()
  }

  @Test
  fun laFasciaDeiGestiDiSistemaEQuellaDeiDueBordi() {
    // Fascia sinistra 30 px e destra 30 px su una finestra larga 1000.
    assertThat(inSideBand(10f, 1_000, 30, 30)).isTrue()
    assertThat(inSideBand(29f, 1_000, 30, 30)).isTrue()
    assertThat(inSideBand(30f, 1_000, 30, 30)).isFalse()
    assertThat(inSideBand(500f, 1_000, 30, 30)).isFalse()
    assertThat(inSideBand(980f, 1_000, 30, 30)).isTrue()
    // Con la navigazione a tre tasti le fasce laterali sono zero: nessun punto ci sta.
    assertThat(inSideBand(0f, 1_000, 0, 0)).isFalse()
    assertThat(inSideBand(999f, 1_000, 0, 0)).isFalse()
  }
}
