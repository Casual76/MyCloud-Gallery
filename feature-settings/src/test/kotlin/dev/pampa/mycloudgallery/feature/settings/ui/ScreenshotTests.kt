package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Come si guarda una schermata: chiaro, scuro, o chiaro con il testo grande (130%). */
internal enum class Look(val slug: String, val dark: Boolean, val fontScale: Float?, val suffix: String) {
  Light("chiaro", dark = false, fontScale = null, suffix = ""),
  Dark("scuro", dark = true, fontScale = null, suffix = ""),
  LightLarge("chiaro", dark = false, fontScale = 1.3f, suffix = "-testo-grande"),
  ;

  override fun toString(): String = "$slug$suffix"
}

/**
 * Gli screenshot delle Impostazioni (P7): ogni schermata e ogni stato di [ShotCatalog], in chiaro e scuro, su
 * telefono (412 dp) e tablet (1280 dp), piu' il testo grande sul telefono per le schermate principali.
 *
 * `gradlew :feature-settings:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P7/` (il percorso e'
 * relativo alla cartella del modulo, che e' la directory di lavoro dei test). Nei normali `testDebugUnitTest`
 * si verifica solo che ogni schermata compongano, senza scrivere niente.
 *
 * Il tablet e' a 1,5x (hdpi) e solo per una schermata rappresentativa di ogni pagina ([ShotCatalog.phoneOnly] elenca le altre):
 * un PNG da 2560 px di una pagina quasi vuota pesa un mega, e sono centinaia.
 *
 * Le pagine si fotografano in una finestra **alta** (1800 dp sul telefono): una pagina delle Impostazioni
 * scorre, e un PNG di sola schermata taglierebbe proprio la parte sotto. I fogli di conferma, che salgono dal
 * fondo, hanno una finestra di altezza normale.
 */
internal abstract class ShotsBase(
  private val shot: Shot,
  private val look: Look,
  private val device: String,
) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (look.dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, look.fontScale ?: density.fontScale)) {
          SettingsHost { shot.content() }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/${shot.name}-${look.slug}-$device${look.suffix}.png")
  }

  companion object {
    const val DIR = "../docs/screenshots/2-P7"

    fun pages(): List<Shot> = ShotCatalog.all.filter { !it.sheet }

    fun sheets(): List<Shot> = ShotCatalog.all.filter { it.sheet }

    /** Chiaro e scuro per tutti; il testo grande solo per le schermate che lo chiedono. */
    fun phone(shots: List<Shot>): List<Array<Any>> = shots.flatMap { shot ->
      buildList {
        add(arrayOf<Any>(shot, Look.Light))
        add(arrayOf<Any>(shot, Look.Dark))
        if (shot.large) add(arrayOf<Any>(shot, Look.LightLarge))
      }
    }

    fun tablet(shots: List<Shot>): List<Array<Any>> = shots.flatMap { shot ->
      listOf(arrayOf<Any>(shot, Look.Light), arrayOf<Any>(shot, Look.Dark))
    }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
internal class PhonePageShots(shot: Shot, look: Look) : ShotsBase(shot, look, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = phone(pages())
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h915dp-xhdpi")
internal class PhoneSheetShots(shot: Shot, look: Look) : ShotsBase(shot, look, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = phone(sheets())
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class TabletPageShots(shot: Shot, look: Look) : ShotsBase(shot, look, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = tablet(pages().filter { it.name !in ShotCatalog.phoneOnly })
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-hdpi")
internal class TabletSheetShots(shot: Shot, look: Look) : ShotsBase(shot, look, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = tablet(sheets().filter { it.name !in ShotCatalog.phoneOnly })
  }
}
