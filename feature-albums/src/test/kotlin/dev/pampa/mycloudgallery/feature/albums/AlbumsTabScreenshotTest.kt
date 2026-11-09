package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della scheda Album (fase grafica, G2b) per l'utente: la scheda piena (con un album smart e i condivisi), quasi vuota (un profilo del
 * tablet: restano i Preferiti che spiegano) e al primo ingresso con gli album non ancora letti (i fissi e le copertine ferme). Chiaro e scuro, telefono
 * (412 dp) e tablet (1280 dp, orizzontale); la scheda piena anche col testo grande sul telefono. Lo spazio della pillola in fondo e' quello vero.
 *
 * `gradlew :feature-albums:recordRoborazziDebug --tests "*AlbumsTab*ScreenshotTest*"` scrive i PNG in `docs/screenshots/beta-grafica/G2b/`.
 */
abstract class AlbumsTabShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, state: AlbumsTabState, expect: String? = null, fontScale: Float? = null) {
    show(dark = dark, fontScale = fontScale) { AlbumHost { screen(state)() } }
    if (expect != null) {
      assertWithMessage("manca «$expect» nella scena $name").that(composeRule.onAllNodesWithText(expect, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (device == "telefono" || fontScale == null) composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/G2b/$name-$look-$device$suffix.png")
  }

  private fun screen(state: AlbumsTabState): @Composable () -> Unit = {
    // Lo spazio che la pillola delle schede occupa in fondo (`GalleryTabBarDefaults.ContentInset` di `:app`, circa).
    AlbumsTabScreen(state, tab.scenes.thumbnails.thumbnails, NoAlbumsTabHandlers, contentPadding = PaddingValues(bottom = PILL_SPACE))
  }

  @Test
  fun schedaPiena() = shoot("scheda-album-piena", tab.full(), expect = "Preferiti")

  @Test
  fun schedaPienaTestoGrande() = shoot("scheda-album-piena", tab.full(), expect = "Fotocamera", fontScale = LARGE_TEXT)

  @Test
  fun schedaQuasiVuota() = shoot("scheda-album-quasi-vuota", tab.almostEmpty(), expect = "Tocca il cuore")

  @Test
  fun schedaInCaricamento() = shoot("scheda-album-caricamento", tab.loading(), expect = "Video")

  companion object {
    val PILL_SPACE = 96.dp

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class AlbumsTabPhoneScreenshotTest(dark: Boolean) : AlbumsTabShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class AlbumsTabTabletScreenshotTest(dark: Boolean) : AlbumsTabShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
