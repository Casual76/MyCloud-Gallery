package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.SHOT_PHONE_QUALIFIERS
import dev.pampa.mycloudgallery.feature.library.collections.TABLET_QUALIFIERS
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P9 (Fase 7, D23, E-H2): la Libreria subito dopo "Nascondi" su elementi che i familiari vedono ancora (area comune, album
 * condivisi): l'avviso "I familiari continuano a vederlo / a vedere N di questi elementi" con la spiegazione che Nascosti protegge solo dentro
 * l'app. Chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo grande. Il vetro non si vede in Robolectric (L'avviso e' su
 * vetro): il testo e la posizione si.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*HiddenFamily*Screenshot*"` scrive i PNG in `docs/screenshots/7-P9/`. Nei normali
 * `testDebugUnitTest` si verifica solo che la schermata componga. (Una classe a parte: `LibraryScreenshotTest` riscrive le immagini di P7.)
 */
abstract class HiddenFamilyScreenshotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** "Nascondi" riesce e i familiari vedono ancora [family] elementi. */
  private class FamilyAware(private val family: Int) : LocalMediaActions {
    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun familyStillSees(ids: Set<MediaId>): Int = family
  }

  private fun state(family: Int): LibraryTabState {
    val first = syntheticLibraryState(count = 60)
    return LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = first.commands, actions = FamilyAware(family),
    )
  }

  private fun shoot(name: String, selected: Int, family: Int, fontScale: Float? = null) {
    val state = state(family)
    show(dark = dark, fontScale = fontScale) {
      Box(modifier = Modifier.fillMaxSize()) {
        LibraryTab(
          contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
          backdropOut = {},
          onOpenViewer = {},
          onOpenSettings = {},
          state = state,
        )
      }
    }
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      val view = state.grid.view
      state.grid.selection.selectAll((0 until selected).map { view.snapshot.idAt(view.snapshotIndex(it)) })
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  @Test
  fun avvisoPerPiuElementi() = shoot("nascondi-familiari-vedono", selected = 3, family = 2)

  @Test
  fun avvisoPerPiuElementiTestoGrande() = shoot("nascondi-familiari-vedono", selected = 3, family = 2, fontScale = LARGE_TEXT)

  @Test
  fun avvisoPerUnSoloElemento() = shoot("nascondi-familiari-vedono-uno", selected = 1, family = 1)

  companion object {
    const val DIR = "../docs/screenshots/7-P9"
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class HiddenFamilyPhoneScreenshotTest(dark: Boolean) : HiddenFamilyScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class HiddenFamilyTabletScreenshotTest(dark: Boolean) : HiddenFamilyScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
