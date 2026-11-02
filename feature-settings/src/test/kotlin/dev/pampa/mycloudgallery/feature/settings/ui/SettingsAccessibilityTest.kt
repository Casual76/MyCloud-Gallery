package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yRule
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Onboarding, famiglia, QR" e "Fogli di conferma", D28): **ogni** schermata e ogni stato del catalogo delle
 * Impostazioni ([ShotCatalog], gli stessi degli screenshot di P7 della Fase 2), letti come li legge TalkBack, a testo 1,0 e 2,0: nome e ruolo per
 * ogni elemento attivo, bersagli da 48 dp misurati, nessun id o nome di file, testo non tagliato. Una finestra alta (come gli screenshot): una
 * pagina che scorre si legge intera.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
internal class SettingsAccessibilityTest(private val shot: Shot) {

  @get:Rule
  val composeRule = createComposeRule()

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun check(fontScale: Float?) {
    composeRule.setContent {
      MyCloudGalleryTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
          SettingsHost { shot.content() }
        }
      }
    }
    composeRule.waitForIdle()
    // Eccezione scritta: la pagina delle licenze (Informazioni) **e'** l'elenco degli indirizzi delle licenze (https://...): sono il suo contenuto,
    // non un identificativo tecnico di un media. La regola LEAK si esenta li' e solo li'.
    val config = A11yConfig(
      density = composeRule.density,
      exempt = { rule, _ -> rule == A11yRule.LEAK && shot.name.startsWith("informazioni-licenze") },
    )
    val found = A11yAssertions.check(root(), config)
    assertWithMessage("${shot.name} a testo ${fontScale ?: 1.0f}: " + found.joinToString(" | ")).that(found).isEmpty()
  }

  @Test
  fun aTestoNormale() = check(null)

  @Test
  fun aTestoDoppio() = check(2.0f)

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = ShotCatalog.all.map { arrayOf<Any>(it) }
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atf() {
    composeRule.setContent {
      MyCloudGalleryTheme { SettingsHost { shot.content() } }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().checkRoboAccessibility()
  }

}

