package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K3b (SOL-10, SOL-11): il foglio "Nuovo album" sopra la scheda Album, com'e' in produzione, negli stati nuovi: con la memoria piena ("Non salvato", il nome resta e si puo'
 * riprovare) e mentre sta salvando ("Crea" spento: due tocchi non fanno due album). Chiaro e scuro, telefono (360 x 780 dp, il Galaxy S25 dell'utente) e tablet.
 *
 * `gradlew :feature-albums:recordRoborazziDebug --tests "*AlbumsTabCreate*ScreenshotTest*"` scrive i PNG in `docs/screenshots/beta-grafica/K3b/`.
 */
abstract class AlbumsTabCreateShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: String, sheet: @Composable () -> Unit) {
    show(dark = dark) {
      AlbumHost {
        AlbumsTabScreen(tab.full(), tab.scenes.thumbnails.thumbnails, NoAlbumsTabHandlers, contentPadding = PaddingValues(bottom = 96.dp))
        sheet()
      }
    }
    assertWithMessage("manca «$expect» nella scena $name").that(composeRule.onAllNodesWithText(expect, substring = true).fetchSemanticsNodes()).isNotEmpty()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/K3b/$name-$look-$device.png")
  }

  @Test
  fun nuovoAlbumNonSalvato() = shoot("nuovo-album-non-salvato", expect = "memoria del telefono è piena") {
    CreateAlbumSheet(visible = true, problem = null, unavailable = false, onCreate = { _, _ -> }, onDismiss = {}, notSaved = true, initialTitle = "Vacanze 2026")
  }

  @Test
  fun nuovoAlbumStaSalvando() = shoot("nuovo-album-sta-salvando", expect = "Vacanze 2026") {
    CreateAlbumSheet(visible = true, problem = null, unavailable = false, onCreate = { _, _ -> }, onDismiss = {}, saving = true, initialTitle = "Vacanze 2026")
  }

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_360_QUALIFIERS)
class AlbumsTabCreatePhoneScreenshotTest(dark: Boolean) : AlbumsTabCreateShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class AlbumsTabCreateTabletScreenshotTest(dark: Boolean) : AlbumsTabCreateShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
