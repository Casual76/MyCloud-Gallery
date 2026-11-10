package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.emptyLibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dello scaffold della Fase 3 (P0): la Libreria **a tutto schermo** sui fake (la griglia sintetica da
 * 2.000 elementi), la Libreria vuota (la release prima del grafo vero), i livelli Anni e Mesi, lo stile giustificato,
 * il viewer finto sopra la pillola e le altre schede con la pillola che resta (K3a: le Raccolte vere, non un segnaposto). Chiaro e scuro,
 * telefono dell'utente (360 dp) e tablet (1280 dp), piu' il testo grande sul telefono.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*LibraryScaffold*"` scrive i PNG in `docs/screenshots/3-P0/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano (e che il viewer si apra e si chiuda).
 */
internal abstract class LibraryScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  /** La `ComponentActivity` di Compose non e' nel manifest dell'app (vedi ScaffoldScreenshotBase): la si registra a mano. */
  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun show(fontScale: Float?, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** Lo scaffold delle prove (K3a, [GalleryScaffold]): le schede vere, la Libreria sullo [state] dato. */
  private fun shoot(name: String, fontScale: Float? = null, tab: GalleryTab = GalleryTab.Library, state: LibraryTabState, afterShow: () -> Unit = {}) {
    val scenes = ScaffoldScenes(library = state)
    show(fontScale) { GalleryScaffold(scenes, tab) }
    afterShow()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  @Test
  fun libreria() = shoot("libreria", state = syntheticLibraryTabState())

  @Test
  fun libreriaTestoGrande() = shoot("libreria", fontScale = LARGE_TEXT, state = syntheticLibraryTabState())

  @Test
  fun libreriaAnni() = shoot("libreria-anni", state = syntheticLibraryTabState(level = GridLevel.YEARS))

  @Test
  fun libreriaMesi() = shoot("libreria-mesi", state = syntheticLibraryTabState(level = GridLevel.MONTHS))

  @Test
  fun libreriaGiustificata() = shoot("libreria-giustificata", state = syntheticLibraryTabState(style = GridStyle.JUSTIFIED))

  @Test
  fun libreriaVuota() = shoot("libreria-vuota", state = emptyLibraryTabState())

  @Test
  fun viewerSopraLaPillola() = shoot("viewer", state = syntheticLibraryTabState()) {
    // Si tocca la prima cella disegnata: il viewer finto si apre sopra la griglia e sopra la pillola.
    composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)[0].performClick()
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun altreSchedeConLaPillolaChePersiste() = shoot("raccolte", tab = GalleryTab.Collections, state = syntheticLibraryTabState())

  companion object {
    const val DIR = "../docs/screenshots/3-P0"
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class LibraryScaffoldPhoneScreenshotTest(dark: Boolean) : LibraryScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class LibraryScaffoldTabletScreenshotTest(dark: Boolean) : LibraryScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
