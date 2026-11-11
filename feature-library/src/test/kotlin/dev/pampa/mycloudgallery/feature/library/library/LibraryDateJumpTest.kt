package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.feature.library.grid.GridDays
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase H, R2 (scelta dell'utente dell'11/11: «il calendario con i giorni»): toccando la data in alto si apre un calendario mese per mese; i
 * giorni con foto sono gli unici toccabili e portano la Libreria li'; il titolo apre la scelta rapida di anno e mese.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class LibraryDateJumpTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun show(level: GridLevel = GridLevel.DAYS): LibraryTabState {
    val state = syntheticLibraryState(count = 1_500, level = level)
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(bottom = 96.dp), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, state = state)
    }
    composeRule.waitForIdle()
    return state
  }

  private fun openCalendar() {
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(CalendarTag).assertExists()
  }

  /** Il giorno degli elementi nella prima riga di celle in cima all'area dei contenuti. */
  private fun dayAtTop(state: LibraryTabState): Int {
    val grid = state.grid
    val l = grid.layout!!
    var row = grid.rowAtTop(l)
    while (l.rowKind(row) != RowKind.CELLS) row++
    val position = l.itemsOf(row).first
    return grid.shownView.snapshot.localDayAt(grid.snapshotIndexAt(l, position))
  }

  @Test
  fun iGiorniConFotoVengonoDallaVista() {
    val state = syntheticLibraryState(count = 1_500)
    val view = state.grid.view
    val days = GridDays.of(view)
    val expected = (0 until view.size).map { view.snapshot.localDayAt(view.snapshotIndex(it)) }.toSortedSet()
    assertThat(days.months).isNotEmpty()
    assertThat(days.months).isInOrder()
    for (encoded in expected.take(40)) {
      val date = LocalDate.of(encoded / 10_000, (encoded / 100) % 100, encoded % 100)
      assertThat(days.has(date)).isTrue()
      assertThat(days.has(YearMonth.from(date))).isTrue()
      val range = days.viewRangeOf(date)!!
      assertThat(view.snapshot.localDayAt(view.snapshotIndex(range.first))).isEqualTo(encoded)
      assertThat(view.snapshot.localDayAt(view.snapshotIndex(range.last))).isEqualTo(encoded)
    }
    // Un giorno senza foto non c'e', e il calendario parte dal mese con foto piu' vicino che non lo supera.
    val first = days.months.first()
    assertThat(days.has(first.minusMonths(1).atDay(1))).isFalse()
    assertThat(days.startMonth(first.minusYears(3).atDay(1))).isEqualTo(first)
    assertThat(days.startMonth(null)).isEqualTo(days.months.last())
    assertThat(days.yearsNewestFirst).isInOrder(reverseOrder<Int>())
  }

  @Test
  fun unGiornoConFotoPortaLaLibreriaLiEChiudeIlCalendario() {
    val state = show()
    openCalendar()
    val days = GridDays.of(state.grid.view)
    // Un giorno vecchio (la griglia ha spazio sotto per portarlo in cima): il primo mese con foto, scelto con la scelta rapida.
    val month = days.months.first()
    composeRule.onNodeWithTag(TitleTag).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(monthTag(month)).performClick()
    composeRule.waitForIdle()
    val day = (1..month.lengthOfMonth()).map(month::atDay).first(days::has)
    composeRule.onNodeWithTag(dayTag(day)).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(CalendarTag).assertDoesNotExist()
    assertThat(dayAtTop(state)).isEqualTo(GridDays.encode(day))
    assertThat(state.grid.level).isEqualTo(GridLevel.DAYS)
  }

  @Test
  fun daAnniSiScendeAGiorniESiArrivaAlGiorno() {
    val state = show(level = GridLevel.YEARS)
    openCalendar()
    val days = GridDays.of(state.grid.view)
    val month = days.months.first()
    composeRule.onNodeWithTag(TitleTag).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(monthTag(month)).performClick()
    composeRule.waitForIdle()
    val day = (1..month.lengthOfMonth()).map(month::atDay).first(days::has)
    composeRule.onNodeWithTag(dayTag(day)).performClick()
    composeRule.waitForIdle()
    assertThat(state.grid.level).isEqualTo(GridLevel.DAYS)
    assertThat(state.preferences.level.value).isEqualTo(GridLevel.DAYS)
    assertThat(dayAtTop(state)).isEqualTo(GridDays.encode(day))
  }

  @Test
  fun iGiorniSenzaFotoSonoSpentiEIMesiSiScorrono() {
    val state = show()
    openCalendar()
    val days = GridDays.of(state.grid.view)
    // Si parte dal mese mostrato (in fondo alla Libreria: il piu' recente con foto); la freccia avanti e' spenta, quella indietro no.
    val newest = days.months.last()
    val empty = (1..newest.lengthOfMonth()).map(newest::atDay).firstOrNull { !days.has(it) }
    if (empty != null) {
      composeRule.onNodeWithTag(dayTag(empty)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }
    composeRule.onNodeWithTag(NextTag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    composeRule.onNodeWithTag(PreviousTag).performClick()
    composeRule.waitForIdle()
    val previous = days.months[days.months.size - 2]
    val inPrevious = (1..previous.lengthOfMonth()).map(previous::atDay).first(days::has)
    composeRule.onNodeWithTag(dayTag(inPrevious)).assertExists()
    // Con il dito: verso destra si torna indietro di un mese, verso sinistra avanti.
    composeRule.onNodeWithTag(PagerTag).performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    val inNewest = (1..newest.lengthOfMonth()).map(newest::atDay).first(days::has)
    composeRule.onNodeWithTag(dayTag(inNewest)).assertExists()
    composeRule.onNodeWithTag(PagerTag).performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(dayTag(inPrevious)).assertExists()
  }

  @Test
  fun inSelezioneLaDataNonApreIlCalendario() {
    val state = show()
    composeRule.runOnIdle { state.grid.startSelecting() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(CalendarTag).assertDoesNotExist()
  }
}
