package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di Cerca, della pagina Ricerca e modelli e della pagina di attivazione (Fase 6, P8, docs/screenshots/6-P8): ogni scena in chiaro e scuro, telefono e
 * tablet, piu' il testo a 1,3x sul telefono in chiaro. `gradlew :feature-search:recordRoborazziDebug --tests "*SearchScreenshot*"` scrive i PNG; nei normali
 * `testDebugUnitTest` verificano solo che le scene compongano e non scrivono niente.
 */
internal abstract class SearchScreenshotBase(
  private val scene: Scene,
  private val dark: Boolean,
  private val scale: Float?,
  private val device: String,
) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setSearchContent(dark = dark, fontScale = scale) { SearchScenes.Content(scene) }
    rule.waitForIdle()
    val name = "${scene.file}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("../docs/screenshots/6-P8/$name.png")
  }

  companion object {
    /** Ogni scena in chiaro e scuro; il testo grande solo in chiaro (telefono). */
    fun parameters(wide: Boolean, tall: Boolean): List<Array<Any?>> = Scene.entries.filter { it.tall == tall }.flatMap { scene ->
      buildList {
        add(arrayOf<Any?>(scene, false, null))
        add(arrayOf<Any?>(scene, true, null))
        if (!wide) add(arrayOf<Any?>(scene, false, 1.3f))
      }
    }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
internal class SearchPhoneScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : SearchScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-xhdpi")
internal class SearchTabletScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : SearchScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h2200dp-xhdpi")
internal class SearchPagesPhoneScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : SearchScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = true)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class SearchPagesTabletScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : SearchScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = true)
  }
}
