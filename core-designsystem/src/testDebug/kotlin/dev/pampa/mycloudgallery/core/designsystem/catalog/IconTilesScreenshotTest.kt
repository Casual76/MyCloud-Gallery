package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I3 (fase I): la pagina del catalogo delle icone espressive, in chiaro e scuro, al telefono dell'utente (360 dp). Il PNG va in
 * `docs/screenshots/beta-grafica/I3/` con le altre prove del pacchetto (la pagina del gruppo resta anche nel giro di tutti i gruppi).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1600dp-xhdpi")
internal class IconTilesScreenshotTest(private val look: CatalogLook) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = look.settings()) {
        CatalogGroupPage(group = CatalogGroup.IconTiles)
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(CATALOG_SHOT_TAG).captureRoboImage("../docs/screenshots/beta-grafica/I3/componente-icone-${look}-telefono.png")
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = CatalogLook.all.map { arrayOf<Any>(it) }
  }
}
