package dev.pampa.mycloudgallery.release

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
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
 * RL-1 della revisione funzionale della Fase 10: la radice non butta la navigazione quando lo stato del manifest passa da `Normal` a sola lettura (lo stato salvato letto
 * un attimo dopo il primo disegno, o un blocco `WRITES` a meta' sessione) ne' al ritorno. Prima `Normal` e `UpdateBanner` chiamavano il contenuto in due rami diversi e
 * Compose lo ricreava: pagina aperta, viewer e pila perse. In piu' (CA-1) in sola lettura il contenuto sa che le scritture le ferma il manifest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class ReleaseRootTest {

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

  @Test
  fun rl1_lArrivoDellaSolaLetturaNonRicreaIlContenuto_eLeSchermateSannoLaCausa() {
    var ui by mutableStateOf<ReleaseUi>(ReleaseUi.Normal)
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        ReleaseRoot(ui) {
          // Lo stato della navigazione, in piccolo: un valore salvato dentro il contenuto.
          var count by rememberSaveable { mutableIntStateOf(0) }
          Column {
            Text("conto $count", modifier = Modifier.clickable { count++ })
            Text("dal manifest ${LocalWritesHeldByRelease.current}")
          }
        }
      }
    }
    composeRule.onNodeWithText("conto 0").performClick()
    composeRule.onNodeWithText("conto 1").assertExists()
    composeRule.onNodeWithText("dal manifest false").assertExists()

    ui = ReleaseUi.UpdateBanner(ReleaseMessage.UPDATE)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("conto 1").assertExists()
    composeRule.onNodeWithText("dal manifest true").assertExists()

    // E indietro (un manifest con un `seq` piu' alto che toglie il blocco): il contenuto resta.
    ui = ReleaseUi.Normal
    composeRule.waitForIdle()
    composeRule.onNodeWithText("conto 1").assertExists()
    composeRule.onNodeWithText("dal manifest false").assertExists()
  }
}
