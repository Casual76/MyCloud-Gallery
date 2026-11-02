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
 * Fase 10, P8 (D23, D25-D27, §2.7 "Cerca", D28): **ogni** scena del catalogo degli screenshot di Cerca (a riposo, scrivendo, risultati, nessun
 * risultato, avvisi, errore, Ricerca e modelli, attivazione) letta come la legge TalkBack, a testo 1,0 e poi a 2,0 sulla stessa scena: nome e ruolo per
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

/** Le regole di Cerca che non si leggono da una scena sola: il campo, i risultati, il loro conteggio. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
internal class SearchSemanticsTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun root(): SemanticsNode = rule.onRoot().fetchSemanticsNode()

  @Test
  fun ilCampoDiRicercaHaLaSuaEtichetta() {
    rule.setSearchContent { SearchScenes.Content(Scene.IDLE) }
    val fields = A11yAssertions.nodes(root()).filter { SemanticsActions.SetText in it.config }
    assertThat(fields).isNotEmpty()
    // Il nome viene dall'etichetta (o dal segnaposto): mai un campo muto.
    fields.forEach { assertThat(A11yAssertions.spoken(it) + listOfNotNull(it.config.getOrNullDescription())).isNotEmpty() }
  }

  @Test
  fun ilConteggioDeiRisultatiSiAnnunciaUnaVolta() {
    rule.setSearchContent { SearchScenes.Content(Scene.RESULTS_RECENT) }
    val live = A11yAssertions.nodes(root()).filter { A11yAssertions.liveRegion(it) != null && A11yAssertions.name(it).contains("risultat") }
    assertThat(live).hasSize(1)
  }

  @Test
  fun leSezioniDeiRisultatiSonoIntestazioni() {
    rule.setSearchContent { SearchScenes.Content(Scene.RESULTS_RECENT) }
    assertThat(A11yAssertions.headingNames(root()).any { it.contains("Risultati") }).isTrue()
  }

  private fun androidx.compose.ui.semantics.SemanticsConfiguration.getOrNullDescription(): String? =
    getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfARiposo() {
    rule.setSearchContent { SearchScenes.Content(Scene.IDLE) }
    rule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfRisultati() {
    rule.setSearchContent { SearchScenes.Content(Scene.RESULTS_RECENT) }
    rule.onRoot().checkRoboAccessibility()
  }
}

