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
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryState
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntrySummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lo screenshot di P7 (Fase 8, D3): la pagina principale delle Impostazioni con la riga "Persone" accanto a "Ricerca e modelli" (la destinazione la scrive
 * `:app`: qui e' un segnaposto), in tre stati: spenta, da confermare su questo dispositivo e attiva. Chiaro e scuro, telefono dell'utente (360 dp) e tablet (1280 dp),
 * piu' il testo grande sul telefono. Una classe a parte: `PhonePageShots` e le sue sorelle riscrivono le immagini di P7 della Fase 2.
 *
 * `gradlew :feature-settings:recordRoborazziDebug --tests "*PeopleEntryShots*"` scrive i PNG in `docs/screenshots/8-P7/`. Nei normali
 * `testDebugUnitTest` si verifica solo che la schermata componga.
 */
internal abstract class PeopleEntryShotsBase(private val state: PeopleEntryState, private val look: Look, private val device: String) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (look.dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, look.fontScale ?: density.fontScale)) {
          SettingsHost {
            SettingsScreen(
              state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 4, pendingRequests = 0, pendingSteps = emptyList()),
              onBack = {},
              openDiagnostics = {},
              resumeStep = {},
              links = SettingsLinks(openSearch = {}, openPeople = {}),
              peopleSummary = PeopleEntrySummary(state),
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
    val slug = when (state) {
      PeopleEntryState.OFF -> "spenta"
      PeopleEntryState.AWAITING_NOTICE -> "da-confermare"
      PeopleEntryState.ON -> "attiva"
      else -> state.name.lowercase()
    }
    composeRule.onRoot().captureRoboImage("$DIR/impostazioni-voce-persone-$slug-${look.slug}-$device${look.suffix}.png")
  }

  companion object {
    const val DIR = "../docs/screenshots/8-P7"
    val STATES = listOf(PeopleEntryState.OFF, PeopleEntryState.AWAITING_NOTICE, PeopleEntryState.ON)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
internal class PhonePeopleEntryShots(state: PeopleEntryState, look: Look) : PeopleEntryShotsBase(state, look, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = PeopleEntryShotsBase.STATES.flatMap { state -> Look.entries.map { arrayOf<Any>(state, it) } }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class TabletPeopleEntryShots(state: PeopleEntryState, look: Look) : PeopleEntryShotsBase(state, look, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> = PeopleEntryShotsBase.STATES.flatMap { state -> listOf(Look.Light, Look.Dark).map { arrayOf<Any>(state, it) } }
  }
}
