package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import org.junit.Rule
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Cerca", D28): **ogni** scena del catalogo degli screenshot delle pagine della ricerca (Ricerca e modelli,
 * attivazione; la ricerca dentro la Libreria ha le sue prove in :app, H4) letta come la legge TalkBack, a testo 1,0 e poi a 2,0 sulla stessa scena: nome e ruolo per
 * ogni elemento attivo, bersagli da 48 dp misurati, nessun id o nome di file, testo non tagliato.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
internal class SearchAccessibilityTest(private val scene: Scene) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun root(): SemanticsNode = rule.onRoot().fetchSemanticsNode()

  @Test
  fun laScenaEPulitaATestoNormaleEDoppio() {
    val scale = mutableStateOf(1f)
    rule.setSearchContent {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { SearchScenes.Content(scene) }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      rule.waitForIdle()
      val found = A11yAssertions.check(root(), A11yConfig(density = rule.density))
      assertWithMessage("${scene.file} a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = Scene.entries.map { arrayOf<Any>(it) }
  }
}

/** Le regole della ricerca dentro la Libreria (H4) che non si leggono da una scena sola: i suggerimenti, i risultati, il loro conteggio. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
internal class SearchSemanticsTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib = library(count = 60)
  private val thumbs = TestThumbnails(lib)
  private val results = dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState.Results(
    emptyList(),
    lib.view(*(0 until 12).toList().toIntArray()),
    lib.view(30, 31),
    dev.pampa.mycloudgallery.core.search.contract.SearchCoverage.Complete,
    emptyList(),
  )

  private fun root(): SemanticsNode = rule.onRoot().fetchSemanticsNode()

  private fun showResults() = rule.setSearchContent {
    SearchResults(
      source = FixedSearchSource(query = "mare", state = results),
      thumbnails = thumbs.thumbnails,
      onOpen = { _, _, _ -> },
      contentPadding = androidx.compose.foundation.layout.PaddingValues(),
    )
  }

  private fun showSuggestions() = rule.setSearchContent {
    SearchSuggestions(
      source = FixedSearchSource(
        suggestions = listOf(
          dev.pampa.mycloudgallery.core.search.contract.Suggestion.Recent(recent(1, "mare ad agosto")),
          dev.pampa.mycloudgallery.core.search.contract.Suggestion.Term("Ferragosto"),
          dev.pampa.mycloudgallery.core.search.contract.Suggestion.Term("Natale"),
        ),
      ),
      handlers = NoSearchHandlers,
      backdrop = null,
    )
  }

  @Test
  fun ogniSuggerimentoEUnTastoCheDiceCosaCerca() {
    showSuggestions()
    val names = A11yAssertions.nodes(root()).filter { SemanticsActions.OnClick in it.config }.map { A11yAssertions.name(it) }
    for (expected in listOf("Cerca «mare ad agosto»", "Cerca «Ferragosto»", "Cerca «Natale»")) {
      assertWithMessage("$expected fra $names").that(names.any { it.contains(expected) }).isTrue()
    }
  }

  @Test
  fun ilConteggioDeiRisultatiSiAnnunciaUnaVolta() {
    showResults()
    val live = A11yAssertions.nodes(root()).filter { A11yAssertions.liveRegion(it) != null && A11yAssertions.name(it).contains("risultat") }
    assertThat(live).hasSize(1)
  }

  @Test
  fun ilSeparatoreDeiMenoProbabiliEUnIntestazione() {
    showResults()
    assertThat(A11yAssertions.headingNames(root()).any { it.contains("Meno probabili") }).isTrue()
  }

  // --- ATF (D28): il controllo automatico indipendente, sui pezzi della ricerca --------------------------------------------------------

  @Test
  fun atfSuggerimenti() {
    showSuggestions()
    rule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfRisultati() {
    showResults()
    rule.onRoot().checkRoboAccessibility()
  }
}
