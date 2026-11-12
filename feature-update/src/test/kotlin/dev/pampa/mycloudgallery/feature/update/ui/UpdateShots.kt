package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto U: gli screenshot della pagina Aggiornamenti e dell'avviso, per la revisione grafica. `gradlew :feature-update:recordRoborazziDebug` li
 * scrive in `docs/screenshots/beta-grafica/U/`; nei normali `testDebugUnitTest` si verifica solo che compongano. Telefono (360 dp) chiaro e scuro, il
 * testo a 1,3x, il tablet (Tab S9, 800 dp di larghezza).
 */
internal abstract class UpdateShotsBase(private val device: String, private val fontScale: Float = 1f) {

  @get:Rule
  val composeRule = createComposeRule()

  protected fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
      }
    }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    composeRule.onRoot().captureRoboImage("$DIR/aggiornamenti-$name-$look-$device.png")
  }

  protected fun page(name: String, state: UpdatesState, dark: Boolean) =
    shoot(name, dark) { UpdatesScreen(state = state, actions = UpdateActions.None, onBack = {}) }

  /** L'avviso com'e' nell'app: in basso, sopra una pagina vuota. */
  protected fun prompt(name: String, state: UpdatesState, dark: Boolean) = shoot(name, dark) {
    FluidScreenSurface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptCard(state = state, actions = UpdateActions.None, modifier = Modifier.align(Alignment.BottomCenter))
      }
    }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/U"
  }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h800dp-xhdpi")
internal class UpdatePhoneShots : UpdateShotsBase("telefono") {
  @Test fun aggiornataChiaro() = page("aggiornata", UpdateSamples.upToDate, dark = false)

  @Test fun aggiornataScuro() = page("aggiornata", UpdateSamples.upToDate, dark = true)

  @Test fun primaDelControlloChiaro() = page("prima", UpdateSamples.idle, dark = false)

  @Test fun nuovaChiaro() = page("nuova", UpdateSamples.found, dark = false)

  @Test fun nuovaScuro() = page("nuova", UpdateSamples.found, dark = true)

  @Test fun nuovaBetaChiaro() = page("nuova-beta", UpdateSamples.foundBeta, dark = false)

  @Test fun datiMobiliChiaro() = page("dati-mobili", UpdateSamples.metered, dark = false)

  @Test fun scaricaChiaro() = page("scarica", UpdateSamples.downloading, dark = false)

  @Test fun scaricaScuro() = page("scarica", UpdateSamples.downloading, dark = true)

  @Test fun confermaChiaro() = page("conferma-android", UpdateSamples.awaiting, dark = false)

  @Test fun permessoChiaro() = page("permesso", UpdateSamples.permission, dark = false)

  @Test fun firmaDiversaChiaro() = page("firma-diversa", UpdateSamples.signature, dark = false)

  @Test fun nonRaggiungibileChiaro() = page("non-raggiungibile", UpdateSamples.unavailable, dark = false)

  @Test fun pubblicataMaleChiaro() = page("pubblicata-male", UpdateSamples.unusable, dark = false)

  @Test fun avvisoChiaro() = prompt("avviso", UpdateSamples.found, dark = false)

  @Test fun avvisoScuro() = prompt("avviso", UpdateSamples.found, dark = true)

  @Test fun avvisoScaricaChiaro() = prompt("avviso-scarica", UpdateSamples.downloading, dark = false)

  @Test fun avvisoPermessoChiaro() = prompt("avviso-permesso", UpdateSamples.permission, dark = false)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h800dp-xhdpi")
internal class UpdateLargeTextShots : UpdateShotsBase("telefono-testo-grande", fontScale = 1.3f) {
  @Test fun nuovaChiaro() = page("nuova", UpdateSamples.found, dark = false)

  @Test fun scaricaScuro() = page("scarica", UpdateSamples.downloading, dark = true)

  @Test fun avvisoChiaro() = prompt("avviso", UpdateSamples.found, dark = false)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w800dp-h1280dp-xhdpi")
internal class UpdateTabletShots : UpdateShotsBase("tablet") {
  @Test fun nuovaChiaro() = page("nuova", UpdateSamples.found, dark = false)

  @Test fun nuovaScuro() = page("nuova", UpdateSamples.found, dark = true)

  @Test fun avvisoChiaro() = prompt("avviso", UpdateSamples.found, dark = false)
}
