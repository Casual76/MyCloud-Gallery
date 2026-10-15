package dev.pampa.mycloudgallery.feature.nas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersMode
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsScreen
import dev.pampa.mycloudgallery.feature.nas.operations.operationsUi
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceScreen
import dev.pampa.mycloudgallery.feature.nas.space.SpaceUi
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashScreen
import dev.pampa.mycloudgallery.feature.nas.trash.TrashActions
import dev.pampa.mycloudgallery.feature.nas.trash.TrashUi
import dev.pampa.mycloudgallery.feature.nas.trash.key
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il **test dei bordi** della chrome delle quattro pagine (G2, `docs/grafica-progetto.md` §3, P2, P3, P5, P6), sul telefono, con il
 * testo a 1,3x e sul tablet, a riposo e in selezione:
 *  - nessun pezzo di chrome ne tocca un altro (indietro, etichetta, capsula dei comandi, barra delle azioni);
 *  - ogni controllo toccabile della chrome e' almeno 48 x 48 dp;
 *  - la barra delle azioni sta nel posto della pillola (finisce dove finirebbe lei, alta uguale);
 *  - entrare in selezione non sposta la lista;
 *  - nessun fondale di vetro in piu' (uno per pagina).
 * Il vetro qui non si vede (Robolectric non sfoca): la leggibilita' e' in `NasContrastTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class NasLayoutTest : NasComposeBase() {

  private val areas = FakeNasAreas.defaultAreas()

  private fun chrome(): List<SemanticsNode> = composeRule
    .onAllNodes(SemanticsMatcher("chrome") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith(GalleryChromeTagPrefix) }, useUnmergedTree = true)
    .fetchSemanticsNodes()
    .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  private fun SemanticsNode.tag(): String = config.getOrNull(SemanticsProperties.TestTag) ?: ""

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right - Slack && other.left < right - Slack && top < other.bottom - Slack && other.top < bottom - Slack

  private fun Rect.contains(other: Rect): Boolean =
    left <= other.left + Slack && top <= other.top + Slack && right >= other.right - Slack && bottom >= other.bottom - Slack

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

  private fun SemanticsNode.inChrome(): Boolean {
    var node: SemanticsNode? = this
    while (node != null) {
      if (node.tag().startsWith(GalleryChromeTagPrefix)) return true
      node = node.parent
    }
    return false
  }

  private fun assertTargets(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() } - Slack
    val clickable = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
    val inChrome = clickable.filter { it.inChrome() }
    assertWithMessage("$where: tasti nella chrome").that(inChrome).isNotEmpty()
    for (node in inChrome) {
      val b = node.boundsInRoot
      assertWithMessage("$where: bersaglio ${node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)} $b")
        .that(minOf(b.width, b.height)).isAtLeast(min)
    }
  }

  /** La barra delle azioni finisce dove finirebbe la pillola (`BottomMargin` da terra) ed e' alta quanto lei. */
  private fun assertBarInThePillsPlace(where: String) {
    val bar = chrome().single { it.tag() == "${GalleryChromeTagPrefix}azioni" }.boundsInRoot
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val margin = with(composeRule.density) { FluidFloatingTabBarDefaults.BottomMargin.toPx() }
    val height = with(composeRule.density) { ActionBarHeight.toPx() }
    assertWithMessage("$where: la barra finisce dove finiva la pillola").that(bar.bottom).isWithin(Slack).of(root.bottom - margin)
    assertWithMessage("$where: alta come la pillola").that(bar.height).isWithin(Slack).of(height)
  }

  private fun rowBounds(text: String): Rect = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot

  private val noop = FoldersActions()

  private fun folders(selecting: Boolean = false, selection: Set<SharePath> = emptySet(), mode: FoldersMode = FoldersMode.Browse, readOnly: Boolean = false) = FoldersUiState(
    areas = areas,
    where = FolderWhere("test", SharePath.of("Viaggi")),
    listing = NasFolderState.Listing(NasFixtures.tripEntries(), readOnly, null),
    selecting = selecting,
    selection = selection,
    mode = mode,
  )

  // --- Cartelle NAS ------------------------------------------------------------------------------------------------------

  private fun checkFolders(fontScale: Float? = null) {
    val entries = NasFixtures.tripEntries()
    var state by mutableStateOf(folders())
    show(fontScale = fontScale) {
      NasFoldersScreen(state = state, actions = noop, monotonicMs = { 0L }, onBack = {})
    }
    assertNoOverlaps("Cartelle a riposo")
    assertTargets("Cartelle a riposo")
    val before = rowBounds("Roma 2025")
    state = folders(selecting = true, selection = setOf(entries[0].path, entries[3].path))
    composeRule.waitForIdle()
    assertWithMessage("La lista non si sposta entrando in selezione").that(rowBounds("Roma 2025")).isEqualTo(before)
    assertNoOverlaps("Cartelle in selezione")
    assertTargets("Cartelle in selezione")
    assertBarInThePillsPlace("Cartelle in selezione")

    state = folders(mode = FoldersMode.PickDestination(listOf(entries[2].toItem("test"))))
    composeRule.waitForIdle()
    assertWithMessage("La lista non si sposta scegliendo la destinazione").that(rowBounds("Roma 2025")).isEqualTo(before)
    assertNoOverlaps("Cartelle, scelta della destinazione")
    assertTargets("Cartelle, scelta della destinazione")
    assertBarInThePillsPlace("Cartelle, scelta della destinazione")
  }

  @Test
  fun cartelleNonHannoCompenetrazioni() = checkFolders()

  @Test
  fun cartelleConIlTestoGrande() = checkFolders(fontScale = LARGE_TEXT)

  @Test
  @Config(qualifiers = TABLET_QUALIFIERS)
  fun cartelleSulTablet() = checkFolders()

  @Test
  fun cartelleInSolaLetturaNonHannoCompenetrazioni() {
    show {
      NasFoldersScreen(state = folders(readOnly = true), actions = noop, monotonicMs = { 0L }, onBack = {})
    }
    assertNoOverlaps("Cartelle in sola lettura")
    assertTargets("Cartelle in sola lettura")
  }

  // --- Eliminati di recente del NAS ----------------------------------------------------------------------------------------

  private fun trash(selecting: Boolean = false, selection: Set<String> = emptySet()) =
    TrashUi(areas = areas, list = NasTrashState.Ready(NasFixtures.trashItems(), NasFixtures.NOW, true), selecting = selecting, selection = selection)

  private fun checkTrash(fontScale: Float? = null) {
    val items = NasFixtures.trashItems()
    var state by mutableStateOf(trash())
    show(fontScale = fontScale) {
      NasTrashScreen(state = state, actions = TrashActions(), monotonicMs = { 0L }, onBack = {})
    }
    assertNoOverlaps("Eliminati a riposo")
    assertTargets("Eliminati a riposo")
    val before = rowBounds("IMG_20260921_184233.jpg")
    state = trash(selecting = true, selection = setOf(items[0].key(), items[1].key()))
    composeRule.waitForIdle()
    assertWithMessage("La lista non si sposta entrando in selezione").that(rowBounds("IMG_20260921_184233.jpg")).isEqualTo(before)
    assertNoOverlaps("Eliminati in selezione")
    assertTargets("Eliminati in selezione")
    assertBarInThePillsPlace("Eliminati in selezione")
  }

  @Test
  fun eliminatiNonHannoCompenetrazioni() = checkTrash()

  @Test
  fun eliminatiConIlTestoGrande() = checkTrash(fontScale = LARGE_TEXT)

  @Test
  @Config(qualifiers = TABLET_QUALIFIERS)
  fun eliminatiSulTablet() = checkTrash()

  // --- Spazio NAS e Operazioni in corso ------------------------------------------------------------------------------------------

  private fun checkSpace(fontScale: Float?) {
    show(fontScale = fontScale) {
      NasSpaceScreen(state = SpaceUi(areas, "test", NasSpaceState.Ready(NasFixtures.space())), onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {})
    }
    assertNoOverlaps("Spazio ${fontScale ?: 1f}x")
    assertTargets("Spazio ${fontScale ?: 1f}x")
  }

  @Test
  fun spazioNonHaCompenetrazioni() = checkSpace(null)

  @Test
  fun spazioConIlTestoGrande() = checkSpace(LARGE_TEXT)

  @Test
  @Config(qualifiers = TABLET_QUALIFIERS)
  fun spazioSulTablet() = checkSpace(null)

  private fun checkOperations(fontScale: Float?) {
    show(fontScale = fontScale) {
      NasOperationsScreen(state = operationsUi(NasFixtures.operations()), onStop = {}, onBack = {})
    }
    assertNoOverlaps("Operazioni ${fontScale ?: 1f}x")
    assertTargets("Operazioni ${fontScale ?: 1f}x")
  }

  @Test
  fun operazioniNonHannoCompenetrazioni() = checkOperations(null)

  @Test
  fun operazioniConIlTestoGrande() = checkOperations(LARGE_TEXT)

  @Test
  @Config(qualifiers = TABLET_QUALIFIERS)
  fun operazioniSulTablet() = checkOperations(null)

  // --- Tutti i bersagli, non solo quelli della chrome ----------------------------------------------------------------------------

  /** Ogni cosa che si tocca nella pagina (righe, "Ferma", "Riprova", la "x" di un avviso, le pillole che aprono) e' alta almeno 48 dp. */
  private fun assertEveryTargetIsBigEnough(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() } - Slack
    val nodes = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
      .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    assertWithMessage("$where: ci sono bersagli").that(nodes).isNotEmpty()
    for (node in nodes) {
      val b = node.boundsInRoot
      assertWithMessage("$where: bersaglio ${node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)} $b")
        .that(b.height).isAtLeast(min)
    }
  }

  @Test
  fun cartelleConAvvisiEOperazioniHannoBersagliDa48() {
    show(fontScale = LARGE_TEXT) {
      NasFoldersScreen(
        state = folders().copy(
          listing = NasFolderState.Offline(NasFixtures.tripEntries(), NasFixtures.NOW - 3_600_000L),
          activeOperations = 2,
          notice = dev.pampa.mycloudgallery.feature.nas.ui.UiNotice(R.string.nas_started, dev.pampa.mycloudgallery.feature.nas.ui.NasTone.Calm, opensOperations = true),
        ),
        actions = noop, monotonicMs = { 0L }, onBack = {},
      )
    }
    assertEveryTargetIsBigEnough("Cartelle con avvisi")
  }

  @Test
  fun eliminatiOperazioniESpazioHannoBersagliDa48() {
    show {
      NasTrashScreen(state = trash(), actions = TrashActions(), monotonicMs = { 0L }, onBack = {})
    }
    assertEveryTargetIsBigEnough("Eliminati")
  }

  @Test
  fun operazioniHannoBersagliDa48() {
    show(fontScale = LARGE_TEXT) { NasOperationsScreen(state = operationsUi(NasFixtures.operations()), onStop = {}, onBack = {}) }
    assertEveryTargetIsBigEnough("Operazioni")
  }

  @Test
  fun spazioHaBersagliDa48() {
    show { NasSpaceScreen(SpaceUi(areas, "test", NasSpaceState.Ready(NasFixtures.space(quota = 1L, wd = true))), {}, {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = true) }
    assertEveryTargetIsBigEnough("Spazio")
  }

  @Test
  fun iFogliDiConfermaHannoBottoniDa48() {
    show {
      dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheetContent(plan = NasFixtures.purgePlan(), areas = areas, tapsDone = 1, onConfirm = {}, onCancel = {})
    }
    assertEveryTargetIsBigEnough("Foglio di conferma")
  }

  // --- Un fondale per pagina ------------------------------------------------------------------------------------------------------

  /**
   * P4, "nessun fondale a schermo intero in piu'": ogni pagina registra **un** fondale (`glassBackdropSource`), quello della
   * pagina, dentro `NasPage`; nessun'altra schermata, riga o foglio ne registra un secondo.
   */
  @Test
  fun nessunFondaleASchermoInteroInPiu() {
    val root = java.io.File("src/main/kotlin/dev/pampa/mycloudgallery/feature/nas")
    val counts = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { file ->
      file.name to file.readLines().map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        .sumOf { line -> Regex("""\.glassBackdropSource\(|rememberGlassBackdrop\(""").findAll(line).count() }
    }
    assertThat(counts["NasChrome.kt"]).isEqualTo(2)
    assertThat(counts.filterKeys { it != "NasChrome.kt" }.filterValues { it > 0 }).isEmpty()
  }

  private companion object {
    /** Mezzo pixel: gli arrotondamenti del layout. */
    const val Slack = 0.5f
  }
}
