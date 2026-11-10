package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il catalogo vivo, come lo vede chi lo apre: la finestra del dispositivo (telefono 360 x 780 dp,
 * tablet 1280 x 800 dp), l'indice dei gruppi e, dopo un tocco, la pagina di un gruppo.
 *
 * `gradlew :core-designsystem:recordRoborazziDebug` scrive i PNG in `docs/screenshots/1.2/`.
 */
internal abstract class CatalogShellScreenshotBase(
  private val look: CatalogLook,
  private val device: CatalogDevice,
) {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = look.settings()) {
        ComponentCatalog(onBack = {})
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun indice() {
    show()
    // Il titolo compare due volte: grande nella pagina e piccolo nella barra agganciata.
    composeRule.onAllNodesWithText("Catalogo componenti").assertCountEquals(2)
    composeRule.onRoot().captureRoboImage(CatalogShots.path("catalogo-indice-${look.slug}-${device.slug}"))
  }

  @Test
  fun gruppo() {
    show()
    composeRule.onNodeWithText("Celle della griglia").performClick()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage(CatalogShots.path("catalogo-gruppo-celle-${look.slug}-${device.slug}"))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
internal class CatalogShellPhoneScreenshotTest(look: CatalogLook) :
  CatalogShellScreenshotBase(look, CatalogDevice.Phone) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = CatalogLook.all.map { arrayOf<Any>(it) }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class CatalogShellTabletScreenshotTest(look: CatalogLook) :
  CatalogShellScreenshotBase(look, CatalogDevice.Tablet) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = CatalogLook.all.map { arrayOf<Any>(it) }
  }
}
