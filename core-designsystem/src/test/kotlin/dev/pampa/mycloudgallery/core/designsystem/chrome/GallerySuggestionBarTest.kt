package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CU-2 / CS-3 (giro 1 della fase H) sulla barra vera, al telefono dell'utente (360 dp) e con il testo ingrandito: le voci che si vedono non si confondono
 * mai fra loro (prima «comple… | Castigli… | comple…»), TalkBack dice la voce intera e il tocco arriva alla voce giusta.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xxhdpi")
class GallerySuggestionBarTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val labels = listOf("compleanno della nonna a Firenze", "Castiglione della Pescaia", "compleanno di Anna al mare")
  private val picked = ArrayList<Int>()

  private fun show(fontScale: Float) {
    composeRule.setContent {
      MyCloudGalleryTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          GallerySuggestionBar(labels = labels, onPick = { picked += it }, backdrop = null, descriptions = labels.map { "Cerca «$it»" })
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun shownTexts(): List<String> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

  private fun assertDistinguishable(texts: List<String>) {
    assertThat(texts).isNotEmpty()
    assertThat(texts.toSet()).hasSize(texts.size)
    // Nessuna voce che si vede potrebbe essere un'altra delle voci date.
    texts.forEach { text ->
      val could = labels.filter { SuggestionFit.matches(text, it) }
      assertThat(could).hasSize(1)
    }
  }

  @Test
  fun aTestoGrande_leVociSiDistinguono() {
    show(fontScale = 1.3f)
    assertDistinguishable(shownTexts())
  }

  @Test
  fun aTestoMoltoGrande_leVociSiDistinguonoOEscono() {
    show(fontScale = 2f)
    assertDistinguishable(shownTexts())
  }

  @Test
  fun talkBackDiceLaVoceInteraEIlToccoArrivaAllaVoceGiusta() {
    show(fontScale = 1.3f)
    composeRule.onNodeWithContentDescription("Cerca «compleanno di Anna al mare»").performClick()
    composeRule.waitForIdle()
    assertThat(picked).containsExactly(2)
  }

  @Test
  fun vociCorte_intere() {
    composeRule.setContent {
      MyCloudGalleryTheme { GallerySuggestionBar(labels = listOf("mare ad agosto", "Roma", "Video"), onPick = {}, backdrop = null) }
    }
    composeRule.waitForIdle()
    assertThat(shownTexts()).containsExactly("mare ad agosto", "Roma", "Video").inOrder()
  }
}
