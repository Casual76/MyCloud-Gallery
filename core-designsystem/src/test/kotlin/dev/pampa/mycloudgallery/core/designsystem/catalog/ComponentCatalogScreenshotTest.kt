package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test su JVM: la pagina del catalogo, a tema Ametista, in chiaro e in scuro.
 *
 * In `testDebugUnitTest` verifica che la pagina componga (il titolo c'e'); per ottenere i PNG da
 * guardare nella revisione grafica:
 *
 *     gradlew :core-designsystem:recordRoborazziDebug
 *
 * che li scrive in `core-designsystem/build/outputs/roborazzi/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComponentCatalogScreenshotTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun catalogoInChiaro() = cattura("catalogo-chiaro", ThemeMode.LIGHT)

  @Test
  fun catalogoInScuro() = cattura("catalogo-scuro", ThemeMode.DARK)

  private fun cattura(nome: String, modo: ThemeMode) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = modo)) {
        ComponentCatalogPlaceholder(onBack = {})
      }
    }

    // Il titolo compare due volte: grande nella pagina e piccolo nella barra agganciata.
    composeRule.onAllNodesWithText("Catalogo componenti").assertCountEquals(2)
    composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$nome.png")
  }
}
