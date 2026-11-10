package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.catalog.CATALOG_SHOT_TAG
import dev.pampa.mycloudgallery.core.designsystem.catalog.CatalogGroup
import dev.pampa.mycloudgallery.core.designsystem.catalog.CatalogGroupPage
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina "Viewer" del catalogo (P8) negli screenshot della Fase 3: chiaro e scuro, telefono e tablet.
 * `gradlew :core-designsystem:recordRoborazziDebug --tests "*ViewerCatalogScreenshot*"` scrive in `docs/screenshots/3-P8/`.
 */
internal abstract class ViewerCatalogScreenshotBase(private val dark: Boolean, private val device: String) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        CatalogGroupPage(group = CatalogGroup.Viewer)
      }
    }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    composeRule.onNodeWithTag(CATALOG_SHOT_TAG).captureRoboImage("../docs/screenshots/3-P8/catalogo-viewer-$look-$device.png")
  }

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h4800dp-xhdpi")
internal class ViewerCatalogPhoneScreenshotTest(dark: Boolean) : ViewerCatalogScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h2400dp-xhdpi")
internal class ViewerCatalogTabletScreenshotTest(dark: Boolean) : ViewerCatalogScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
