package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import java.util.concurrent.Executor
import kotlinx.coroutines.asCoroutineDispatcher
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
    shownState = state
    return state
  }

  private var shownState: LibraryTabState? = null

  /**
   * Tocca la data. Con [waitReady] aspetta anche i giorni con foto della vista (si contano su `Default`, che l'attesa di Compose non vede) e la
   * dissolvenza che li accende; senza, il calendario si guarda com'e' appena aperto.
   */
  private fun openCalendar(waitReady: Boolean = true) {
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performClick()
    composeRule.waitForIdle()
    if (waitReady) {
      val grid = shownState!!.grid
      composeRule.waitUntil(5_000) { grid.days.current(grid.view) != null }
      composeRule.waitForIdle()
    }
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
    // Un anno vecchio sta in fondo alla scelta rapida, che scorre: prima lo si porta in vista, come col dito.
    composeRule.onNodeWithTag(monthTag(month)).performScrollTo().performClick()
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
    // Un anno vecchio sta in fondo alla scelta rapida, che scorre: prima lo si porta in vista, come col dito.
    composeRule.onNodeWithTag(monthTag(month)).performScrollTo().performClick()
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

  // --- H3, giro 1 (CU-10, CG-4): il calendario si apre gia' disegnato e non ricalcola i giorni a ogni apertura --------------------------

  /** Il primo giorno con foto del mese [month] secondo [days]. */
  private fun firstDayWithPhotos(days: GridDays, month: YearMonth): LocalDate = (1..month.lengthOfMonth()).map(month::atDay).first(days::has)

  @Test
  fun laSecondaAperturaNonRicontaIGiorni() {
    val state = show()
    openCalendar()
    composeRule.waitUntil(5_000) { state.grid.days.current(state.grid.view) != null }
    val days = state.grid.days.current(state.grid.view)!!
    assertThat(state.grid.days.computations).isEqualTo(1)
    // Si sceglie un giorno (il calendario si chiude) e si riapre: i giorni sono gia' pronti, il giorno si tocca subito.
    composeRule.onNodeWithTag(dayTag(firstDayWithPhotos(days, days.months.last()))).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(CalendarTag).assertDoesNotExist()
    openCalendar()
    val newest = days.months.last()
    composeRule.onNodeWithTag(dayTag(firstDayWithPhotos(days, newest))).assertHasClickAction()
    assertThat(state.grid.days.computations).isEqualTo(1)
  }

  @Test
  fun conIlCatalogoCambiatoSiApreConIGiorniNuovi() {
    val state = show()
    openCalendar()
    composeRule.waitUntil(5_000) { state.grid.days.current(state.grid.view) != null }
    val before = state.grid.days.current(state.grid.view)!!
    composeRule.onNodeWithTag(dayTag(firstDayWithPhotos(before, before.months.last()))).performClick()
    composeRule.waitForIdle()
    // Il catalogo cambia (qui: un'altra vista della stessa Libreria, come dopo una lettura del telefono).
    val newer = state.timeline!!.view(LibraryFilter.VIDEOS).value
    composeRule.runOnIdle { state.grid.view = newer }
    composeRule.waitForIdle()
    openCalendar()
    composeRule.waitUntil(5_000) { state.grid.days.current(newer) != null }
    composeRule.waitForIdle()
    val after = state.grid.days.current(newer)!!
    assertThat(state.grid.days.computations).isEqualTo(2)
    // Il calendario (aperto sul mese che l'etichetta dice adesso) mostra i giorni della vista nuova: ogni giorno che si tocca ha dei video, e
    // almeno uno si tocca.
    val clickable = composeRule.onAllNodes(SemanticsMatcher("giorno del calendario") { node ->
      node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("calendario-giorno-") == true && SemanticsActions.OnClick in node.config
    }).fetchSemanticsNodes().map { LocalDate.parse(it.config[SemanticsProperties.TestTag].removePrefix("calendario-giorno-")) }
    assertThat(clickable).isNotEmpty()
    assertThat(clickable.all(after::has)).isTrue()
  }

  @Test
  fun senzaGiorniProntiSiDisegnaSubitoIlMeseNessunaRotella() {
    val state = show()
    // I giorni non arrivano mai: il conteggio aspetta su un esecutore che non esegue niente.
    state.grid.days.openDispatcher = Executor { }.asCoroutineDispatcher()
    openCalendar(waitReady = false)
    assertThat(state.grid.days.latest).isNull()
    // Il mese c'e' gia': titolo, frecce e la griglia dei giorni (almeno 28), tutti spenti e non toccabili.
    composeRule.onNodeWithTag(TitleTag).assertExists()
    composeRule.onNodeWithTag(PreviousTag).assertExists()
    val dayNodes = composeRule.onAllNodes(SemanticsMatcher("giorno del calendario") { node ->
      node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("calendario-giorno-") == true
    }).fetchSemanticsNodes()
    assertThat(dayNodes.size).isAtLeast(28)
    assertThat(dayNodes.none { SemanticsActions.OnClick in it.config }).isTrue()
  }
}
