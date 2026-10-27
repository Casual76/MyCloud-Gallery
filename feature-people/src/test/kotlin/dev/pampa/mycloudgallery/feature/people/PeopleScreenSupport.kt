package dev.pampa.mycloudgallery.feature.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.people.ui.PeopleHost
import org.junit.After

/**
 * La base dei test di comportamento e di accessibilita' delle schermate delle persone (Fase 8, P9): le scene dentro [PeopleHost] (i fogli si aprono solo con un
 * host), i controlli su cui si tocca, i bersagli da 48 dp, le sovrapposizioni, le frasi di TalkBack e le sue azioni personalizzate.
 */
abstract class PeopleScreenTest : PeopleComposeTest() {

  protected val scenes = PeopleScenes()

  @After
  fun closeScenes() {
    scenes.close()
  }

  protected fun showAll(fontScale: Float? = null, dark: Boolean = false, content: @Composable () -> Unit) =
    show(dark = dark, fontScale = fontScale) { PeopleHost { content() } }

  protected fun shows(text: String): Boolean = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  protected fun assertShows(scene: String, vararg texts: String) {
    for (text in texts) assertWithMessage("$scene: manca «$text»").that(shows(text)).isTrue()
  }

  protected fun assertDoesNotShow(scene: String, vararg texts: String) {
    for (text in texts) assertWithMessage("$scene: non deve esserci «$text»").that(shows(text)).isFalse()
  }

  // --- Bersagli e sovrapposizioni -------------------------------------------------------------------------------------------------

  /**
   * I nodi su cui si tocca, senza lo sfondo oscurato dei fogli (che copre tutto lo schermo) e senza il titolo di vetro dell'engine. Se un foglio e' aperto (un nodo con il
   * titolo di riquadro, come i fogli del design system) contano solo i controlli **dentro** l'ultimo: quelli della pagina sono coperti dal foglio e non si toccano.
   */
  protected fun controls(): List<SemanticsNode> {
    val all = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val pane = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes().lastOrNull()
    val inPane = if (pane == null) all else all.filter { node -> generateSequence(node) { it.parent }.any { it.id == pane.id } }
    return inPane.filter { node ->
      val b = node.boundsInRoot
      b.width * b.height < screen.width * screen.height * COVERS && b.width > 0f && b.height > 0f && label(node) != "Torna all'inizio"
    }
  }

  protected fun label(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
      ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
      ?: "?"

  protected fun assertTouchTargets(scene: String) {
    val density = composeRule.density.density
    for (node in controls()) {
      val b = node.touchBoundsInRoot
      val w = b.width / density
      val h = b.height / density
      assertWithMessage("$scene: «${label(node)}» e' ${"%.1f".format(w)}x${"%.1f".format(h)} dp, meno di 48").that(minOf(w, h)).isAtLeast(MIN_TOUCH_DP)
    }
  }

  private fun overlap(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 1f && h > 1f) w * h else 0f
  }

  private fun isAncestor(of: SemanticsNode, other: SemanticsNode): Boolean = generateSequence(of) { it.parent }.any { it.id == other.id }

  /** Due controlli non si sovrappongono mai, a meno che uno sia dentro l'altro (un campo di testo e il suo contenitore sono un controllo solo). */
  protected fun assertNoOverlappingControls(scene: String) {
    val nodes = controls()
    for (i in nodes.indices) for (j in i + 1 until nodes.size) {
      if (isAncestor(nodes[i], nodes[j]) || isAncestor(nodes[j], nodes[i])) continue
      val area = overlap(nodes[i].boundsInRoot, nodes[j].boundsInRoot)
      assertWithMessage("$scene: «${label(nodes[i])}» e «${label(nodes[j])}» si sovrappongono").that(area).isEqualTo(0f)
    }
  }

  /** Bersagli da 48 dp e nessuna sovrapposizione, a testo normale e a 1,3x. */
  protected fun checkScene(name: String, fontScale: Float?, scene: @Composable () -> Unit) = checkScenes(fontScale, name to scene)

  /**
   * Le stesse prove per piu' scene in un test solo: la composizione si fa una volta (`setContent` non si puo' richiamare) e le scene si danno il cambio.
   * A 1,3x i controlli non si sovrappongono, mai.
   */
  protected fun checkScenes(fontScale: Float?, vararg scenes: Pair<String, @Composable () -> Unit>) {
    val current = mutableStateOf(0)
    showAll(fontScale = fontScale) { scenes[current.value].second() }
    for ((index, scene) in scenes.withIndex()) {
      composeRule.runOnUiThread { current.value = index }
      composeRule.waitForIdle()
      val shown = if (fontScale != null) "${scene.first} 1,3x" else scene.first
      if (fontScale != null) assertNoOverlappingControls(shown)
      assertTouchTargets(shown)
    }
  }

  /**
   * Compone [content] una volta sola e restituisce il modo di cambiare lo stato mostrato: i test che guardano piu' stati della stessa schermata
   * non possono comporre due volte (`setContent` si chiama una volta per test).
   */
  protected fun <T> switching(initial: T, fontScale: Float? = null, dark: Boolean = false, content: @Composable (T) -> Unit): (T) -> Unit {
    val current = mutableStateOf(initial)
    showAll(fontScale = fontScale, dark = dark) { content(current.value) }
    return { next ->
      composeRule.runOnUiThread { current.value = next }
      composeRule.waitForIdle()
    }
  }

  // --- TalkBack ------------------------------------------------------------------------------------------------------------------------

  /** Le frasi che il lettore di schermo dice per i controlli su cui si tocca. */
  protected fun spokenControls(): List<String> =
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }

  /** Quanti nodi (clic o no) dicono una frase che comincia per [prefix]: le celle delle foto, per esempio. */
  protected fun countDescribed(prefix: String): Int =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
      .count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith(prefix) } == true }

  protected fun controlSpoken(startsWith: String): SemanticsNode =
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
      .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.startsWith(startsWith) == true }

  protected fun actionLabels(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  protected fun runAction(node: SemanticsNode, label: String) {
    val action = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().first { it.label == label }
    composeRule.runOnUiThread { action.action() }
    composeRule.waitForIdle()
  }

  private companion object {
    /** Il bersaglio minimo (l'arrotondamento dei pixel lascia mezzo dp). */
    const val MIN_TOUCH_DP = 47.5f

    /** Un controllo che copre almeno questa parte dello schermo e' lo sfondo di un foglio. */
    const val COVERS = 0.8f
  }
}
