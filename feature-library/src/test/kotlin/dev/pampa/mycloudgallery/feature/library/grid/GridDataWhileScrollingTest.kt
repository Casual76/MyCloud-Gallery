package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase H, D3 (11/11, l'utente sull'S25 appena installata l'app): «scorro rapidamente e l'applicazione blocca lo scorrimento non appena lascio il
 * dito»; dopo qualche minuto (finita la prima lettura) passa.
 *
 * La causa: ogni vista nuova del catalogo entrava nella griglia con un `requestScrollToItem` (l'ancora che tiene fermo cio' che si guarda), e in
 * foundation 1.10.5 `requestScrollToItem` **ferma lo scorrimento in corso** (`stopScroll()` se `isScrollInProgress`, verificato sul bytecode).
 * Durante la prima lettura le viste arrivano a raffica: ogni slancio moriva. Qui uno "slancio" (uno scorrimento che dura) e una vista nuova che
 * arriva nel mezzo: lo slancio continua fino in fondo, e la vista nuova entra appena la griglia si ferma, ancorata.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class GridDataWhileScrollingTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private var scope: CoroutineScope? = null

  private fun show(state: MediaGridState, views: MutableStateFlow<TimelineView>? = null, clock: () -> Long = { 0L }) {
    composeRule.showThemed {
      scope = rememberCoroutineScope()
      if (views != null) LaunchedEffect(state) { state.followViews(views, doseMillis = DoseMillis, now = clock) }
      MediaGrid(state = state, contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp))
    }
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      state.userScrolled = true
      state.listState.requestScrollToItem(state.layout!!.rowCount / 2, 0)
    }
    composeRule.waitForIdle()
  }

  /** Uno "slancio": uno scorrimento verso i piu' vecchi che dura [frames] fotogrammi. Segna se finisce o se qualcuno lo ferma. */
  private class Fling {
    var finished = false
    var stopped = false
  }

  private fun fling(state: MediaGridState, frames: Int): Fling {
    val fling = Fling()
    composeRule.runOnUiThread {
      scope!!.launch {
        try {
          state.listState.scroll {
            repeat(frames) {
              scrollBy(-12f)
              withFrameNanos { }
            }
          }
          fling.finished = true
        } catch (e: CancellationException) {
          fling.stopped = true
          throw e
        }
      }
    }
    return fling
  }

  private fun frames(n: Int) = repeat(n) { composeRule.mainClock.advanceTimeByFrame() }

  @Test
  fun unaVistaNuovaNonFermaLoSlancioEdEntraAGrigliaFerma() {
    val (state, library) = syntheticGrid(count = 2_000)
    show(state)
    val before = state.layout
    composeRule.mainClock.autoAdvance = false
    val fling = fling(state, frames = 40)
    frames(3)
    assertThat(state.listState.isScrollInProgress).isTrue()

    // Una vista nuova arriva nel mezzo dello slancio (la prima lettura del telefono che continua).
    val newer = library.view(LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    composeRule.runOnUiThread { state.view = newer }
    frames(10)
    assertThat(fling.stopped).isFalse()
    assertThat(state.listState.isScrollInProgress).isTrue()
    // Lo schermo mostra ancora il layout di prima: lo scambio aspetta la griglia ferma.
    assertThat(state.layout).isSameInstanceAs(before)

    frames(40)
    composeRule.mainClock.autoAdvance = true
    composeRule.waitForIdle()
    assertThat(fling.finished).isTrue()
    assertThat(fling.stopped).isFalse()
    // Ferma la griglia, la vista nuova e' entrata.
    assertThat(state.shownView).isSameInstanceAs(newer)
  }

  @Test
  fun mentreSiScorreLeVisteSiDosanoEAGrigliaFermaEntraLUltima() {
    val (state, library) = syntheticGrid(count = 2_000)
    val first = state.view
    val views = MutableStateFlow(first)
    show(state, views, clock = { composeRule.mainClock.currentTime })
    composeRule.mainClock.autoAdvance = false
    val fling = fling(state, frames = 220)
    frames(3)

    // Una raffica di viste mentre si scorre: nessuna entra subito...
    val a = library.view(LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    val b = library.view(LibraryFilter.VIDEOS)
    val c = library.view(LibraryFilter.FAVORITES)
    composeRule.runOnUiThread { views.value = a }
    frames(2)
    composeRule.runOnUiThread { views.value = b }
    frames(2)
    assertThat(state.view).isSameInstanceAs(first)
    // ... finche' passa la dose (una sola, l'ultima arrivata).
    frames((DoseMillis / FrameMillis).toInt() + 2)
    assertThat(state.view).isSameInstanceAs(b)
    composeRule.runOnUiThread { views.value = c }
    frames(2)
    assertThat(state.view).isSameInstanceAs(b)
    assertThat(fling.stopped).isFalse()

    // A griglia ferma l'ultima entra subito.
    frames(220)
    composeRule.mainClock.autoAdvance = true
    composeRule.waitForIdle()
    assertThat(fling.finished).isTrue()
    assertThat(state.view).isSameInstanceAs(c)
  }

  @Test
  fun aGrigliaFermaUnaVistaNuovaEntraSubito() {
    val (state, library) = syntheticGrid(count = 2_000)
    val views = MutableStateFlow(state.view)
    show(state, views, clock = { composeRule.mainClock.currentTime })
    val next = library.view(LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    composeRule.runOnIdle { views.value = next }
    composeRule.waitForIdle()
    assertThat(state.view).isSameInstanceAs(next)
    assertThat(state.shownView).isSameInstanceAs(next)
  }

  private companion object {
    const val DoseMillis = 1_000L
    const val FrameMillis = 16L
  }
}
