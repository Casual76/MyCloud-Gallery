package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase H, D10 (richiesta dell'utente del 31/10): «facendo un overscroll verso il basso dovrebbe essere possibile aggiornare la libreria». La
 * Libreria ha i piu' recenti in fondo: tirare oltre il fondo aggiorna (una volta), tirare a meta' Libreria scorre e basta, e senza il comando
 * (anteprime, variante di misura) il gesto non c'e'.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class LibraryPullRefreshTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RefreshCommands(private val available: Boolean = true) : LibraryCommands {
    var refreshes = 0
    override val canRefresh: Boolean get() = available
    override suspend fun refresh() {
      refreshes++
    }
  }

  private fun show(commands: RefreshCommands): LibraryTabState {
    val state = syntheticLibraryState(count = 600, commands = commands)
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(bottom = 96.dp), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, state = state)
    }
    composeRule.waitForIdle()
    return state
  }

  /** Un dito che sale piano per [distance] della schermata e si alza fermo (un tiro, non un lancio). */
  private fun pullUp(distance: Float) {
    composeRule.onRoot().performTouchInput {
      val start = Offset(width * 0.5f, height * 0.75f)
      down(start)
      val steps = 40
      for (i in 1..steps) {
        moveTo(start - Offset(0f, height * distance * i / steps))
        advanceEventTime(16)
      }
      // Fermo prima di alzarsi: la velocita' e' zero.
      repeat(6) { move(); advanceEventTime(16) }
      up()
    }
    composeRule.waitForIdle()
  }

  @Test
  fun tirandoOltreIlFondoLaLibreriaSiAggiornaUnaVolta() {
    val commands = RefreshCommands()
    show(commands)
    pullUp(0.6f)
    composeRule.mainClock.advanceTimeBy(3_000)
    composeRule.waitForIdle()
    assertThat(commands.refreshes).isEqualTo(1)
  }

  @Test
  fun unTiroCortoNonAggiorna() {
    val commands = RefreshCommands()
    show(commands)
    pullUp(0.05f)
    composeRule.mainClock.advanceTimeBy(3_000)
    composeRule.waitForIdle()
    assertThat(commands.refreshes).isEqualTo(0)
  }

  @Test
  fun aMetaLibreriaIlDitoScorreENonAggiorna() {
    val commands = RefreshCommands()
    val state = show(commands)
    composeRule.runOnIdle {
      state.grid.userScrolled = true
      state.grid.listState.requestScrollToItem(state.grid.layout!!.rowCount / 2, 0)
    }
    composeRule.waitForIdle()
    val before = state.grid.listState.firstVisibleItemIndex
    pullUp(0.3f)
    composeRule.mainClock.advanceTimeBy(3_000)
    composeRule.waitForIdle()
    assertThat(commands.refreshes).isEqualTo(0)
    assertThat(state.grid.listState.firstVisibleItemIndex).isGreaterThan(before)
  }

  @Test
  fun senzaIlComandoIlGestoNonCe() {
    val commands = RefreshCommands(available = false)
    show(commands)
    pullUp(0.6f)
    composeRule.mainClock.advanceTimeBy(3_000)
    composeRule.waitForIdle()
    assertThat(commands.refreshes).isEqualTo(0)
  }
}
