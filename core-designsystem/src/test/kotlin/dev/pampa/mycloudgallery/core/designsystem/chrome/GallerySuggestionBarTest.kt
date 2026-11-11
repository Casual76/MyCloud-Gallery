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
 * CU-2 / CS-3 (giri 1 e 2 della fase H) sulla barra vera, al telefono dell'utente (360 dp), col testo normale e ingrandito: le voci che si vedono sono
 * **intere** (come la barra delle parole della tastiera), mai con i puntini in testa; se nessuna entra intera ce n'e' una sola, con i puntini in coda.
 * TalkBack dice la voce intera e il tocco arriva alla voce giusta.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xxhdpi")
class GallerySuggestionBarTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val labels = listOf("compleanno della nonna", "Castiglione della Pescaia", "compleanno di Anna al mare")
  private val picked = ArrayList<Int>()

  private fun show(fontScale: Float, words: List<String> = labels) {
    composeRule.setContent {
      MyCloudGalleryTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          GallerySuggestionBar(labels = words, onPick = { picked += it }, backdrop = null, descriptions = words.map { "Cerca «$it»" })
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun shownTexts(): List<String> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

  private fun assertWholeOrSingle(texts: List<String>, words: List<String> = labels) {
    val ellipsis = SuggestionFit.Ellipsis
    assertThat(texts).isNotEmpty()
    texts.forEach { assertThat(it.startsWith(ellipsis)).isFalse() }
    if (texts.any { it.endsWith(ellipsis) }) {
      // Nessuna intera: una sola, l'inizio della prima.
      assertThat(texts).hasSize(1)
      assertThat(words.first()).startsWith(texts.single().removeSuffix(ellipsis))
    } else {
      assertThat(words).containsAtLeastElementsIn(texts)
    }
  }

  @Test
  fun aTestoNormale_vociIntere() {
    show(fontScale = 1f)
    val texts = shownTexts()
    assertWholeOrSingle(texts)
    // «compleanno della nonna», la piu' adatta, c'e' ed e' intera.
    assertThat(texts).contains("compleanno della nonna")
  }

  @Test
  fun aTestoGrande_vociIntere() {
    show(fontScale = 1.3f)
    assertWholeOrSingle(shownTexts())
  }

  @Test
  fun aTestoMoltoGrande_vociIntereOUnaSolaConIPuntiniInCoda() {
    show(fontScale = 2f, words = listOf("compleanno della nonna a Firenze", "Castiglione della Pescaia", "Natale"))
    assertWholeOrSingle(shownTexts(), listOf("compleanno della nonna a Firenze", "Castiglione della Pescaia", "Natale"))
  }

  @Test
  fun talkBackDiceLaVoceInteraEIlToccoArrivaAllaVoceGiusta() {
    show(fontScale = 1.3f)
    composeRule.onNodeWithContentDescription("Cerca «compleanno della nonna»").performClick()
    composeRule.waitForIdle()
    assertThat(picked).containsExactly(0)
  }

  @Test
  fun vociCorte_intere() {
    show(fontScale = 1f, words = listOf("mare ad agosto", "Roma", "Video"))
    assertThat(shownTexts()).containsExactly("mare ad agosto", "Roma", "Video").inOrder()
  }
}
