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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.library.collections.CollectionGridScreen
import dev.pampa.mycloudgallery.feature.library.collections.CollectionKind
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.contentUnder
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il **test dei bordi** della chrome delle griglie (G2, `docs/grafica-progetto.md` §3, §12): nella Libreria (telefono, testo
 * a 1,3x, tablet) e nelle Raccolte, a riposo e in selezione,
 *  - nessun pezzo di chrome ne tocca un altro (indietro, etichetta, capsula dei comandi, scrubber, barra delle azioni e
 *    la pillola, che qui e' un rettangolo grande quanto la pillola vera, nel suo posto) (P2);
 *  - ogni controllo toccabile della chrome e' almeno 48 x 48 dp (P3);
 *  - in selezione la barra delle azioni sta nel posto della pillola, alta uguale (P5), e la griglia non si sposta (P6);
 *  - nessun fondale a schermo intero in piu' (P4): le pagine a griglia ne registrano uno, la griglia nessuno.
 * Il vetro qui non si vede (Robolectric non sfoca): la leggibilita' e' in `GalleryLegibilityTest` e nelle misure
 * sull'emulatore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryChromeLayoutTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun showLibrary(fontScale: Float = 1f): LibraryTabState {
    val state = syntheticLibraryState(count = 400, debugSizes = listOf(0, 400))
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
          if (!state.grid.selecting) Pill()
        }
      }
    }
    return state
  }

  @androidx.compose.runtime.Composable
  private fun androidx.compose.foundation.layout.BoxScope.Pill() {
    Box(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin)
        .size(width = PillWidth, height = FluidFloatingTabBarDefaults.OpenHeight)
        .testTag(PillTag),
    )
  }

  private fun chrome(): List<SemanticsNode> = composeRule
    .onAllNodes(SemanticsMatcher("chrome") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith(GalleryChromeTagPrefix) }, useUnmergedTree = true)
    .fetchSemanticsNodes()
    .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  private fun SemanticsNode.tag(): String = config.getOrNull(SemanticsProperties.TestTag) ?: ""

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right - Slack && other.left < right - Slack && top < other.bottom - Slack && other.top < bottom - Slack

  private fun Rect.contains(other: Rect): Boolean =
    left <= other.left + Slack && top <= other.top + Slack && right >= other.right - Slack && bottom >= other.bottom - Slack

  /** Nessuna coppia di pezzi di chrome si sovrappone, a meno che uno non contenga l'altro (un tasto nella sua capsula). */
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

  /** Vero se il nodo, o uno dei suoi antenati, e' un pezzo di chrome (non la griglia che le scorre sotto). */
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

  /** Una cella intera a meta' schermo: la sua descrizione e dove sta (per vedere che la griglia non si sposta). */
  private fun probeCell(): Pair<String, Rect> {
    val cells = composeRule.onAllNodes(SemanticsMatcher("cella") { (it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: "").matches(CellPattern) })
      .fetchSemanticsNodes()
    fun SemanticsNode.description(): String = config[SemanticsProperties.ContentDescription].first()
    // Una descrizione sola sullo schermo (due scatti nello stesso minuto e posto si direbbero uguali).
    val unique = cells.groupBy { it.description() }.filterValues { it.size == 1 }.values.map { it.single() }
    val middle = unique.minBy { kotlin.math.abs(it.boundsInRoot.center.y - 900f) }
    return middle.description() to middle.boundsInRoot
  }

  private fun boundsOfCell(description: String): Rect = composeRule
    .onAllNodes(SemanticsMatcher("cella $description") { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == description })
    .fetchSemanticsNodes().single().boundsInRoot

  /** A riposo e in selezione: bordi, bersagli, la barra nel posto della pillola, la griglia ferma. */
  private fun checkPage(label: String, pillBottom: Float, pillHeight: Float, select: () -> Unit) {
    assertNoOverlaps("$label a riposo")
    assertTargets("$label a riposo")
    val (cell, before) = probeCell()
    select()
    composeRule.waitForIdle()
    assertWithMessage("$label: la griglia non si sposta entrando in selezione").that(boundsOfCell(cell)).isEqualTo(before)
    assertNoOverlaps("$label in selezione")
    assertTargets("$label in selezione")
    val bar = chrome().single { it.tag() == "${GalleryChromeTagPrefix}azioni" }.boundsInRoot
    assertWithMessage("$label: la barra delle azioni finisce dove finiva la pillola").that(bar.bottom).isWithin(Slack).of(pillBottom)
    assertWithMessage("$label: alta come la pillola").that(bar.height).isWithin(Slack).of(pillHeight)
  }

  private fun checkLibrary(fontScale: Float = 1f) {
    val state = showLibrary(fontScale)
    val pill = composeRule.onAllNodes(SemanticsMatcher("pillola") { it.config.getOrNull(SemanticsProperties.TestTag) == PillTag }).fetchSemanticsNodes().single().boundsInRoot
    checkPage("Libreria ${fontScale}x", pill.bottom, pill.height) {
      composeRule.runOnIdle {
        state.grid.startSelecting()
        state.grid.selection.toggle(checkNotNull(state.grid.idAt(0)))
      }
    }
  }

  @Test
  fun laLibreriaNonHaCompenetrazioni() = checkLibrary()

  @Test
  fun laLibreriaConIlTestoGrande() = checkLibrary(fontScale = LargeText)

  @Test
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  fun laLibreriaSulTablet() = checkLibrary()

  /**
   * Le Raccolte hanno la stessa chrome (indietro, etichetta, "Seleziona") e la barra delle azioni nel posto della pillola,
   * che a schermo intero non c'e': in fondo, alla stessa altezza da terra.
   */
  @Test
  fun leRaccolteHannoLaStessaChrome() {
    val world = FakeCollectionsWorld()
    val environment = fakeGridEnvironment()
    val items = world.source.items(CollectionKind.FAVORITES.filter!!)
    composeRule.showThemed {
      CollectionGridScreen(
        kind = CollectionKind.FAVORITES,
        items = items,
        environment = environment,
        onFavorite = { _, _ -> },
        onHide = {},
        onOpenViewer = {},
        onBack = {},
      )
    }
    composeRule.waitForIdle()
    val height = with(composeRule.density) { FluidFloatingTabBarDefaults.OpenHeight.toPx() }
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val margin = with(composeRule.density) { FluidFloatingTabBarDefaults.BottomMargin.toPx() }
    checkPage("Preferiti", root.bottom - margin, height) {
      composeRule.onNodeWithContentDescription("Seleziona").performClick()
      composeRule.waitForIdle()
      val (cell, _) = probeCell()
      composeRule.onNodeWithContentDescription(cell).performClick()
    }
    assertThat(chrome().map { it.tag() }).contains("${GalleryChromeTagPrefix}titolo")
  }

  /**
   * Il vetro adattivo legge che cosa passa sotto un rettangolo: anche dove sotto ci sono solo fessure o il vuoto di una
   * riga incompleta (qui: un rettangolo fuori da ogni cella) torna almeno la pagina, mai un intervallo vuoto. In G1 questo
   * caso chiudeva l'app sulla Libreria sintetica.
   */
  @Test
  fun sottoUnaFessuraCeLaPagina() {
    val state = showLibrary()
    val page = Color(0xFFFFFFFF)
    val text = Color(0xFF000000)
    composeRule.runOnIdle {
      for (y in 0 until 2400 step 40) {
        val range = state.grid.contentUnder(Rect(5_000f, y.toFloat(), 5_100f, y + 30f), page, text)
        assertThat(range.samples).isNotEmpty()
      }
    }
  }

  /**
   * P4, "nessun fondale a schermo intero in piu'": una pagina a griglia registra **un** fondale (`glassBackdropSource`),
   * quello della pagina; la griglia e la sua chrome nessuno (in Fase 3 la griglia ne registrava un secondo per
   * l'intestazione agganciata e lo scrubber). L'host dei pop-up della Libreria ne ha uno suo solo quando `:app` non ne da'
   * uno (i test della sola scheda).
   */
  @Test
  fun nessunFondaleASchermoInteroInPiu() {
    val root = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library")
    fun sources(file: String): Int = File(root, file).readLines()
      .map { it.substringBefore("//") }
      .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
      .sumOf { line -> Regex("""\.glassBackdropSource\(""").findAll(line).count() }
    val grid = File(root, "grid").listFiles().orEmpty().filter { it.extension == "kt" }
    assertThat(grid).isNotEmpty()
    for (file in grid) assertWithMessage("grid/${file.name}").that(sources("grid/${file.name}")).isEqualTo(0)
    assertWithMessage("Libreria").that(sources("library/LibraryScreen.kt")).isEqualTo(1)
    assertWithMessage("host dei pop-up della Libreria (solo senza :app)").that(sources("library/LibraryTab.kt")).isEqualTo(1)
    assertWithMessage("Raccolte").that(sources("collections/CollectionGridPage.kt")).isEqualTo(1)
    assertWithMessage("Eliminati di recente").that(sources("trash/RecentlyDeletedScreen.kt")).isEqualTo(1)
    assertWithMessage("Nascosti chiusi").that(sources("hidden/HiddenScreen.kt")).isEqualTo(1)
  }

  private companion object {
    const val PillTag = "${GalleryChromeTagPrefix}pillola"
    val PillWidth = 264.dp
    const val LargeText = 1.3f
    val CellPattern = Regex("^(Foto|Video|Foto in movimento|Foto RAW|Immagine animata), .*")

    /** Mezzo pixel: gli arrotondamenti del layout. */
    const val Slack = 0.5f
  }
}
