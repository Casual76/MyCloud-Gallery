package dev.pampa.mycloudgallery.feature.settings.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della sezione "Libreria" delle Impostazioni (Fase 6, P14, docs/screenshots/6-P14): l'interruttore "Animazione delle foto
 * in movimento" acceso e spento, chiaro e scuro, telefono e tablet, piu' il testo a 1,3x sul telefono.
 * `gradlew :feature-settings:recordRoborazziDebug --tests "*MotionAnimationShots*"`; nei normali `testDebugUnitTest` verificano solo che
 * le pagine compongano e non scrivono niente.
 */
internal abstract class MotionAnimationShotsBase(
  private val on: Boolean,
  private val dark: Boolean,
  private val scale: Float?,
  private val device: String,
) {

  @get:Rule
  val rule = createComposeRule()

  @Test
  fun capture() {
    val state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale ?: density.fontScale)) {
          SettingsHost {
            SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, motionAnimation = on, onMotionAnimation = {})
          }
        }
      }
    }
    rule.waitForIdle()
    val name = "impostazioni-libreria-animazione-${if (on) "accesa" else "spenta"}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("../docs/screenshots/6-P14/$name.png")
  }

  companion object {
    /** Acceso e spento, chiaro e scuro; il testo grande solo da acceso, chiaro. */
    fun phone(): List<Array<Any?>> = listOf(
      arrayOf(true, false, null),
      arrayOf(true, true, null),
      arrayOf(false, false, null),
      arrayOf(false, true, null),
      arrayOf(true, false, 1.3f),
    )

    fun tablet(): List<Array<Any?>> = listOf(
      arrayOf(true, false, null),
      arrayOf(true, true, null),
      arrayOf(false, false, null),
      arrayOf(false, true, null),
    )
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
internal class MotionAnimationPhoneShots(on: Boolean, dark: Boolean, scale: Float?) : MotionAnimationShotsBase(on, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "acceso={0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = phone()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class MotionAnimationTabletShots(on: Boolean, dark: Boolean, scale: Float?) : MotionAnimationShotsBase(on, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "acceso={0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = tablet()
  }
}
