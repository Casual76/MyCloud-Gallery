package dev.pampa.mycloudgallery.feature.settings.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto U: la voce «Aggiornamenti» delle Impostazioni. Compare solo se `:app` ha scritto dove porta; quando c'e' una versione nuova ha il segno e la
 * versione a destra (il «segno sulla voce» dell'avviso all'apertura); TalkBack sente che c'e' una versione nuova.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h2400dp-xhdpi")
class UpdateEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

  @Test
  fun laVoceCompareSoloSeCollegata_eConUnaVersioneNuovaLaDice() {
    var links by mutableStateOf(SettingsLinks())
    var summary by mutableStateOf<UpdateEntrySummary?>(UpdateEntrySummary(available = null))
    var clicks = 0
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsScreen(
          state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList()),
          onBack = {},
          openDiagnostics = {},
          resumeStep = {},
          links = links,
          updateSummary = summary,
        )
      }
    }
    val title = string(R.string.settings_updates)
    assertThat(rule.onAllNodesWithText(title).fetchSemanticsNodes()).isEmpty()

    links = SettingsLinks(openUpdates = { clicks++ })
    rule.waitForIdle()
    rule.onNode(hasContentDescription(title)).assertExists()
    rule.onNodeWithText(title).performClick()
    assertThat(clicks).isEqualTo(1)

    summary = UpdateEntrySummary(available = "1.1.0")
    rule.waitForIdle()
    rule.onNodeWithText("1.1.0").assertExists()
    rule.onNode(hasContentDescription(title + ", " + string(R.string.settings_updates_available, "1.1.0"))).assertExists()
  }
}
