package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GF-22 (review della beta): in cima alla Libreria il riepilogo («1.817 foto, 183 video») e' la seconda riga dell'etichetta e, sul telefono dell'utente (Galaxy S25,
 * **360 dp**) con i quattro tasti nella capsula, si leggeva «1.817 foto, 18…»: un conteggio tagliato dentro un numero.
 *
 * Ora il riepilogo si mostra **intero o non si mostra**: la forma intera, se no il totale con il plurale giusto («2.000 elementi») e, se nemmeno questo entra, niente
 * seconda riga (il riepilogo sta scritto anche in fondo alla griglia). Qui la Libreria vera, in cima, con i quattro tasti, a testo normale e a 1,3x, in chiaro e in
 * scuro: la seconda riga, se c'e', e' una delle due forme e non finisce mai con i puntini; TalkBack legge sempre il riepilogo per esteso.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryChromeSummaryFitTest(private val dark: Boolean, private val fontScale: Float) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun showLibrary(): LibraryTabState {
    val state = syntheticLibraryState(level = GridLevel.DAYS, nas = LibraryNasStatus.UpToDate(atNasMillis = 1_000L))
    composeRule.showThemed(dark = dark) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        Box(modifier = Modifier.fillMaxSize()) {
          LibraryTab(
            contentPadding = PaddingValues(bottom = FluidFloatingTabBarDefaults.ContentInset),
            backdropOut = {},
            onOpenViewer = {},
            onOpenSettings = {},
            state = state,
          )
        }
      }
    }
    settle()
    return state
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  private fun scrollToTop(state: LibraryTabState) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem(0, 0)
    }
    settle()
  }

  private fun tagged(name: String): List<SemanticsNode> =
    composeRule.onAllNodes(hasTestTag("$GalleryChromeTagPrefix$name"), useUnmergedTree = true).fetchSemanticsNodes()

  /** Le righe di testo dell'etichetta, dall'alto: la prima e' il titolo, la seconda (se c'e') il riepilogo. */
  private fun labelTexts(): List<SemanticsNode> = composeRule
    .onAllNodes(hasAnyAncestor(hasTestTag("${GalleryChromeTagPrefix}titolo")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
    .fetchSemanticsNodes()
    .sortedBy { it.boundsInRoot.top }

  private fun SemanticsNode.shownText(): String = config[SemanticsProperties.Text].joinToString("") { it.text }

  private fun SemanticsNode.layoutResult(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    checkNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
    return results.single()
  }

  private fun spokenLabel(): String {
    val heading = composeRule
      .onAllNodes(hasAnyAncestor(hasTestTag("${GalleryChromeTagPrefix}titolo")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
      .fetchSemanticsNodes()
      .single()
    val description = heading.config.getOrNull(SemanticsProperties.ContentDescription)
    return description?.joinToString(", ") ?: heading.config[SemanticsProperties.Text].joinToString(", ") { it.text }
  }

  @Test
  fun inCimaIlRiepilogoEUnaFormaInteraONiente() {
    val state = showLibrary()
    scrollToTop(state)
    val where = "in cima alla Libreria, scuro=$dark, testo ${fontScale}x"
    // I quattro tasti ci sono: e' il caso stretto della segnalazione.
    for (button in listOf("oggi", "nuvola", "seleziona", "vista")) assertWithMessage("$where: il tasto $button").that(tagged(button)).isNotEmpty()

    val texts = labelTexts()
    assertWithMessage("$where: il titolo").that(texts.first().shownText()).isEqualTo("Libreria")
    if (texts.size > 1) {
      val summary = texts.last()
      val layout = summary.layoutResult()
      assertWithMessage("$where: «${summary.shownText()}» sta su una riga").that(layout.lineCount).isEqualTo(1)
      assertWithMessage("$where: «${summary.shownText()}» non finisce con i puntini").that(layout.isLineEllipsized(0)).isFalse()
      assertWithMessage("$where: «${summary.shownText()}»").that(summary.shownText()).isIn(listOf(FullSummary, TotalForm))
    }
    // TalkBack legge sempre il riepilogo per esteso, intero o no quello a schermo.
    assertWithMessage("$where: TalkBack").that(spokenLabel()).isEqualTo("Libreria, $FullSummary")
    assertThat(texts).isNotEmpty()
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}, testo={1}x")
    fun data(): List<Array<Any>> = buildList {
      for (dark in listOf(false, true)) for (scale in listOf(1f, 1.3f)) add(arrayOf(dark, scale))
    }

    const val SettleMillis = 1_500L

    /** La Libreria sintetica dei test (`syntheticLibraryState`): 1.817 foto e 183 video. */
    const val FullSummary = "1.817 foto, 183 video"
    const val TotalForm = "2.000 elementi"
  }
}
