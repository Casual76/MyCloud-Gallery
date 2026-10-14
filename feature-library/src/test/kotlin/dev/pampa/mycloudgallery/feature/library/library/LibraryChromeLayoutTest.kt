package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.contentUnder
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
 * Il **test dei bordi** della revisione grafica G1 (`docs/grafica-progetto.md` §3): nelle tre direzioni, a riposo e in
 * selezione, in chiaro e con il testo a 1,3x,
 *  - nessun pezzo di chrome ne tocca un altro (titolo, capsula dei comandi, barra, scrubber, barra delle azioni e la
 *    pillola, che qui e' un rettangolo grande quanto la pillola vera, nel suo posto);
 *  - ogni controllo toccabile della chrome e' almeno 48 x 48 dp;
 *  - in selezione la barra delle azioni sta nel posto della pillola (stesso fondo, stessa altezza) e la griglia non si
 *    sposta entrando in selezione.
 * Il vetro qui non si vede (Robolectric non sfoca): la leggibilita' e' in `GalleryLegibilityTest` e nelle misure
 * sull'emulatore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryPrototypeLayoutTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun show(design: LibraryDesign, fontScale: Float = 1f): LibraryTabState {
    val state = syntheticLibraryState(count = 400, debugSizes = listOf(0, 400))
    checkNotNull(state.debug).chooseDesign(design)
    composeRule.showThemed {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        Box(modifier = Modifier.fillMaxSize()) {
          LibraryTab(
            contentPadding = PaddingValues(bottom = FluidFloatingTabBarDefaults.ContentInset),
            backdropOut = {},
            onOpenViewer = {},
            onOpenSettings = {},
            statusFooter = { Text("stato") },
            state = state,
          )
          // La pillola di `:app`: c'e' fuori dalla selezione, nel suo posto e grande quanto lei (tre schede).
          if (!state.grid.selecting) {
            Box(
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin)
                .size(width = PillWidth, height = FluidFloatingTabBarDefaults.OpenHeight)
                .testTag(PillTag),
            )
          }
        }
      }
    }
    return state
  }

  private fun chrome(): List<SemanticsNode> = composeRule
    .onAllNodes(SemanticsMatcher("chrome G1") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith(GalleryChromeTagPrefix) }, useUnmergedTree = true)
    .fetchSemanticsNodes()
    .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  private fun SemanticsNode.tag(): String = config.getOrNull(SemanticsProperties.TestTag) ?: ""

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right - Slack && other.left < right - Slack && top < other.bottom - Slack && other.top < bottom - Slack

  private fun Rect.contains(other: Rect): Boolean =
    left <= other.left + Slack && top <= other.top + Slack && right >= other.right - Slack && bottom >= other.bottom - Slack

  /** Nessuna coppia di pezzi di chrome si sovrappone, a meno che uno non contenga l'altro (un tasto nella sua barra). */
  private fun assertNoOverlaps(where: String) {
    val nodes = chrome()
    assertWithMessage("$where: la chrome c'e'").that(nodes.map { it.tag() }).isNotEmpty()
    for (i in nodes.indices) {
      for (j in i + 1 until nodes.size) {
        val a = nodes[i].boundsInRoot
        val b = nodes[j].boundsInRoot
        if (a.contains(b) || b.contains(a)) continue
        assertWithMessage("$where: ${nodes[i].tag()} $a e ${nodes[j].tag()} $b si sovrappongono").that(a.overlaps(b)).isFalse()
      }
    }
  }

  /** Vero se il nodo, o uno dei suoi antenati, e' un pezzo di chrome G1 (non la griglia che le scorre sotto). */
  private fun SemanticsNode.inChrome(): Boolean {
    var node: SemanticsNode? = this
    while (node != null) {
      if (node.tag().startsWith(GalleryChromeTagPrefix)) return true
      node = node.parent
    }
    return false
  }

  /** Ogni nodo toccabile della chrome e' almeno 48 x 48 dp. */
  private fun assertTargets(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() } - Slack
    val clickable = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
    val inChrome = clickable.filter { it.inChrome() && it.tag() != "${GalleryChromeTagPrefix}scrubber" }
    assertWithMessage("$where: tasti nella chrome").that(inChrome).isNotEmpty()
    for (node in inChrome) {
      val b = node.boundsInRoot
      assertWithMessage("$where: bersaglio ${node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)} $b")
        .that(minOf(b.width, b.height)).isAtLeast(min)
    }
  }

  private fun check(design: LibraryDesign, fontScale: Float = 1f) {
    val state = show(design, fontScale)
    val label = "$design ${fontScale}x"
    assertNoOverlaps("$label a riposo")
    assertTargets("$label a riposo")

    val pill = composeRule.onAllNodes(SemanticsMatcher("pillola") { it.config.getOrNull(SemanticsProperties.TestTag) == PillTag }).fetchSemanticsNodes().single().boundsInRoot
    val list = state.grid.listState
    val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
    composeRule.runOnIdle { state.grid.startSelecting() }
    composeRule.waitForIdle()
    assertThat(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset).isEqualTo(before)
    assertNoOverlaps("$label in selezione")
    assertTargets("$label in selezione")
    val bar = chrome().single { it.tag() == "${GalleryChromeTagPrefix}azioni" }.boundsInRoot
    assertWithMessage("$label: la barra delle azioni finisce dove finiva la pillola").that(bar.bottom).isWithin(Slack).of(pill.bottom)
    assertWithMessage("$label: alta come la pillola").that(bar.height).isWithin(Slack).of(pill.height)
  }

  @Test
  fun aNessunaCompenetrazione() = check(LibraryDesign.A)

  @Test
  fun bNessunaCompenetrazione() = check(LibraryDesign.B)

  @Test
  fun cNessunaCompenetrazione() = check(LibraryDesign.C)

  @Test
  fun aConIlTestoGrande() = check(LibraryDesign.A, fontScale = 1.3f)

  @Test
  fun bConIlTestoGrande() = check(LibraryDesign.B, fontScale = 1.3f)

  @Test
  fun cConIlTestoGrande() = check(LibraryDesign.C, fontScale = 1.3f)

  /**
   * Il vetro adattivo legge che cosa passa sotto un rettangolo: anche dove sotto ci sono solo fessure o il vuoto di una
   * riga incompleta (qui: un rettangolo fuori da ogni cella) torna almeno la pagina, mai un intervallo vuoto. Prima della
   * correzione questo caso chiudeva l'app sulla Libreria sintetica.
   */
  @Test
  fun cSottoUnaFessuraCeLaPagina() {
    val state = show(LibraryDesign.C)
    val page = Color(0xFFFFFFFF)
    val text = Color(0xFF000000)
    composeRule.runOnIdle {
      for (y in 0 until 2400 step 40) {
        val range = state.grid.contentUnder(Rect(5_000f, y.toFloat(), 5_100f, y + 30f), page, text)
        assertThat(range.samples).isNotEmpty()
      }
    }
  }

  /** Senza le manopole di debug (la release) la Libreria e' quella di sempre: nessun prototipo. */
  @Test
  fun inReleaseLaLibreriaEQuellaDiSempre() {
    val state = syntheticLibraryState(count = 400)
    assertThat(state.debug).isNull()
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, statusFooter = {}, state = state)
    }
    assertThat(chrome()).isEmpty()
    composeRule.onAllNodes(SemanticsMatcher("barra") { it.config.getOrNull(SemanticsProperties.TestTag) == LibraryTopBarTag }).fetchSemanticsNodes().single()
  }

  private companion object {
    const val PillTag = "${GalleryChromeTagPrefix}pillola"
    val PillWidth = 264.dp

    /** Mezzo pixel: gli arrotondamenti del layout. */
    const val Slack = 0.5f
  }
}
