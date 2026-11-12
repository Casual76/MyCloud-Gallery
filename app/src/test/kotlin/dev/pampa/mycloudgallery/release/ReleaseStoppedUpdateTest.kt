package dev.pampa.mycloudgallery.release

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import dev.pampa.mycloudgallery.feature.update.ui.UpdateActions
import dev.pampa.mycloudgallery.feature.update.ui.UpdatePanel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto U (12/11/2026): la versione fermata dal manifest firmato NON blocca l'aggiornamento, e' proprio il rimedio. Con una versione nuova trovata la
 * pagina «Questa versione e' stata fermata» offre «Aggiorna» (la navigazione dell'app resta fuori, come prima); senza, «Controlla adesso».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h1800dp-xhdpi")
class ReleaseStoppedUpdateTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterActivity()).around(composeRule)

  private class RegisterActivity : TestWatcher() {
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

  private val offer = AvailableAppUpdate(
    version = "1.0.1",
    changelog = "",
    releaseTag = "stable-mycloud-gallery-v1.0.1",
    apkAsset = "mycloud-gallery-1.0.1.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.0.1", "mycloud-gallery-1.0.1.apk"),
    sizeBytes = 115_000_000L,
    sha256 = "a".repeat(64),
  )

  private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)

  @Test
  fun laVersioneFermataOffreAggiorna_eLaNavigazioneResteFuori() {
    val installs = mutableListOf<String>()
    val state = UpdatesState(installedVersion = "1.0.0", check = CheckState.Found(offer))
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        ReleaseRoot(ReleaseUi.Stopped(ReleaseMessage.SECURITY), update = { UpdatePanel(state, UpdateActions(install = { installs += "aggiorna" })) }) {
          Text("navigazione dell'app")
        }
      }
    }
    assertThat(composeRule.onAllNodesWithText(string(dev.pampa.mycloudgallery.R.string.release_stopped_title)).fetchSemanticsNodes()).isNotEmpty()
    composeRule.onNodeWithText("navigazione dell'app").assertDoesNotExist()
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.feature.update.R.string.update_action)).performClick()
    assertThat(installs).containsExactly("aggiorna")
    // La Diagnostica resta raggiungibile.
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.R.string.release_open_diagnostics)).assertExists()
  }

  @Test
  fun senzaUnaVersioneTrovataOffreControllaAdesso() {
    val checks = mutableListOf<String>()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        ReleaseRoot(ReleaseUi.Stopped(ReleaseMessage.UPDATE), update = { UpdatePanel(UpdatesState("1.0.0"), UpdateActions(check = { checks += "controlla" })) }) {
          Text("navigazione dell'app")
        }
      }
    }
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.feature.update.R.string.update_check_now)).performClick()
    assertThat(checks).containsExactly("controlla")
  }

  @Test
  fun fermataSenzaVersioneNuova_arrivaPresto_maiAppAggiornata() {
    // Giro 2, UU2-7: il kill switch ferma la versione prima che la correzione sia pubblicata. Sotto «fermata» non c'e' «App aggiornata ✓».
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val upToDate = UpdatesState("1.0.0", check = CheckState.UpToDate)
        ReleaseRoot(ReleaseUi.Stopped(ReleaseMessage.DATA_RISK), update = { UpdatePanel(upToDate, UpdateActions(), stopped = true) }) { Text("navigazione dell'app") }
      }
    }
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.feature.update.R.string.update_coming)).assertExists()
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.feature.update.R.string.update_up_to_date)).assertDoesNotExist()
  }

  @Test
  fun inSolaLettura_AggiornaChiudeAncheLAvvisoInAlto() {
    // Giro 2, UU2-8: una sola offerta. Dopo il tocco l'avanzamento lo mostra la scheda sopra la pillola, non resta il banner con il suo «Aggiorna».
    val installs = mutableListOf<String>()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        ReleaseRoot(ReleaseUi.UpdateBanner(ReleaseMessage.UPDATE), onUpdate = { installs += "aggiorna" }) { Text("navigazione dell'app") }
      }
    }
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.R.string.release_update_action)).performClick()
    assertThat(installs).containsExactly("aggiorna")
    composeRule.onNodeWithText(string(dev.pampa.mycloudgallery.R.string.release_update_action)).assertDoesNotExist()
    composeRule.onNodeWithText("navigazione dell'app").assertExists()
  }
}
