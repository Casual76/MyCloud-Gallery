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
 * GF-2 (review della beta): sul telefono dell'utente (Galaxy S25, **360 dp**) con i quattro tasti nella capsula ("torna in fondo",
 * nuvola, seleziona, vista) all'etichetta restano 96 dp di testo, e la data lunga finiva con i puntini: «Mercoledì …», «Lunedì 28
 * f…». Succedeva proprio scorrendo verso le foto vecchie, dove le date hanno anche l'anno.
 *
 * Ora l'etichetta sceglie la prima forma della data che entra (lunga, «Mer 20 nov 2024», «20 nov 2024» e, con il testo grande, le
 * due piu' strette): qui, in chiaro e in scuro, a testo normale e a 1,3x, ai livelli Giorni e Mesi, in piu' punti della Libreria,
 * **la data non finisce mai con i puntini** e TalkBack la legge per esteso.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryChromeDateFitTest(private val dark: Boolean, private val fontScale: Float, private val level: GridLevel) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun showLibrary(): LibraryTabState {
    val state = syntheticLibraryState(level = level, nas = LibraryNasStatus.UpToDate(atNasMillis = 1_000L))
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

  /** Il tempo che serve al ritmo, alla molla e alla soglia dell'etichetta per posarsi. */
  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  private fun scrollTo(state: LibraryTabState, fraction: Float) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem((grid.layout!!.rowCount * fraction).toInt(), 0)
    }
    settle()
  }

  private fun tagged(name: String): List<SemanticsNode> =
    composeRule.onAllNodes(hasTestTag("$GalleryChromeTagPrefix$name"), useUnmergedTree = true).fetchSemanticsNodes()

  /** Le righe di testo dell'etichetta, dall'alto: la prima e' la data. */
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

  /** Cio' che TalkBack legge dell'etichetta: la descrizione dell'intestazione se c'e', altrimenti il suo testo. */
  private fun spokenLabel(): String {
    val heading = composeRule
      .onAllNodes(hasAnyAncestor(hasTestTag("${GalleryChromeTagPrefix}titolo")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
      .fetchSemanticsNodes()
      .single()
    val description = heading.config.getOrNull(SemanticsProperties.ContentDescription)
    return description?.joinToString(", ") ?: heading.config[SemanticsProperties.Text].joinToString(", ") { it.text }
  }

  @Test
  fun laDataInAltoNonFinisceMaiConIPuntini() {
    val state = showLibrary()
    val shown = mutableListOf<String>()
    for (fraction in Fractions) {
      scrollTo(state, fraction)
      val where = "a $fraction della Libreria, livello $level, testo ${fontScale}x"
      // I quattro tasti ci sono: e' il caso stretto della segnalazione.
      for (button in listOf("oggi", "nuvola", "seleziona", "vista")) assertWithMessage("$where: il tasto $button").that(tagged(button)).isNotEmpty()

      val title = labelTexts().first()
      val layout = title.layoutResult()
      assertWithMessage("$where: «${title.shownText()}» sta su una riga").that(layout.lineCount).isEqualTo(1)
      assertWithMessage("$where: «${title.shownText()}» non finisce con i puntini (larga ${layout.size.width} px)").that(layout.isLineEllipsized(0)).isFalse()
      // La riga scritta sta dentro il nodo (`didOverflowWidth` non lo dice: con una riga sola vale anche per un testo che entra).
      assertWithMessage("$where: «${title.shownText()}» sta nel suo spazio").that(layout.getLineRight(0)).isAtMost(title.boundsInRoot.width + HalfPixel)
      shown += title.shownText()

      // TalkBack legge sempre la data per esteso, anche quando a schermo c'e' la forma corta.
      assertWithMessage("$where: TalkBack").that(spokenLabel()).containsMatch((if (level == GridLevel.DAYS) FullDay else FullMonth).toPattern())
    }
    // La premessa della prova: a 360 dp con quattro tasti la forma lunga non entra sempre (altrimenti la prova non proverebbe niente).
    if (level == GridLevel.DAYS) {
      assertWithMessage("date mostrate: $shown").that(shown.any { !it.contains(FullDay) }).isTrue()
    }
    assertThat(shown).isNotEmpty()
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}, testo={1}x, {2}")
    fun data(): List<Array<Any>> = buildList {
      for (dark in listOf(false, true)) for (scale in listOf(1f, LargeText)) for (level in listOf(GridLevel.DAYS, GridLevel.MONTHS)) add(arrayOf(dark, scale, level))
    }

    const val LargeText = 1.3f
    const val SettleMillis = 1_500L
    const val HalfPixel = 0.5f

    /** Punti della Libreria lontani dai piu' recenti ("torna in fondo" c'e') e con date di anni passati. */
    val Fractions = listOf(0.1f, 0.2f, 0.35f, 0.5f, 0.6f)

    private const val Months = "gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre"
    val FullDay = Regex("(Lunedì|Martedì|Mercoledì|Giovedì|Venerdì|Sabato|Domenica) \\d{1,2} ($Months)( \\d{4})?")
    val FullMonth = Regex("(${Months.split("|").joinToString("|") { it.replaceFirstChar(Char::titlecase) }}) \\d{4}")
  }
}
