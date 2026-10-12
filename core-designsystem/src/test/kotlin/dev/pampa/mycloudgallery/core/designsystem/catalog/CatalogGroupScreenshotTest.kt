package dev.pampa.mycloudgallery.core.designsystem.catalog

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Una pagina del catalogo per ogni gruppo, in chiaro e scuro (e con un secondo accento): le tre sono
 * le assi su cui si giudica un componente della galleria.
 *
 * La finestra e' piu' alta del contenuto (h4800dp) e il test cattura **il nodo della pagina**, non la
 * finestra: l'immagine e' alta quanto serve al gruppo, intero, senza scorrere. Se un gruppo crescesse
 * oltre la finestra, il test fallisce invece di consegnare uno screenshot tagliato.
 */
internal abstract class CatalogGroupScreenshotBase(
  private val group: CatalogGroup,
  private val look: CatalogLook,
  private val device: CatalogDevice,
) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = look.settings()) {
        CatalogGroupPage(group = group)
      }
    }
    composeRule.waitForIdle()

    val page = composeRule.onNodeWithTag(CATALOG_SHOT_TAG)
    val bounds = page.fetchSemanticsNode().boundsInRoot
    // La vista della composizione si avvolge al contenuto: l'altezza della finestra e' quella dello schermo.
    val screenHeight = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.heightPixels
    assertThat(bounds.height).isLessThan(screenHeight.toFloat() - 1f)
    page.captureRoboImage(CatalogShots.path(shotName(group, look, device)))
  }

  companion object {
    fun parameters(looks: List<CatalogLook>): List<Array<Any>> =
      CatalogGroup.entries.flatMap { group -> looks.map { look -> arrayOf<Any>(group, look) } }
  }
}

/** Telefono: 412 dp di larghezza, come il Galaxy S25. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h4800dp-xhdpi")
internal class CatalogGroupPhoneScreenshotTest(group: CatalogGroup, look: CatalogLook) :
  CatalogGroupScreenshotBase(group, look, CatalogDevice.Phone) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = parameters(CatalogLook.withSecondAccent)
  }
}

/** Tablet in orizzontale: 1280 dp di larghezza. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h2400dp-xhdpi")
internal class CatalogGroupTabletScreenshotTest(group: CatalogGroup, look: CatalogLook) :
  CatalogGroupScreenshotBase(group, look, CatalogDevice.Tablet) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = parameters(CatalogLook.all)
  }
}
