package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.LocalConfirmSheetShield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R7 della revisione 4B (sicurezza.md L34): mentre un foglio di conferma e' aperto, la finestra nasconde le sovrapposizioni delle altre
 * app e scarta i tocchi oscurati (la difesa di core-security, `OverlayShield`, legata da `:app` alla radice per ogni foglio del design
 * system); chiuso il foglio, la finestra torna com'era.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConfirmSheetShieldTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

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

  @Test
  fun r7_conIlFoglioApertoLaFinestraSiDifendeDalleSovrapposizioni() {
    var visible by mutableStateOf(false)
    compose.setContent {
      CompositionLocalProvider(LocalConfirmSheetShield provides ConfirmSheetShield) {
        GalleryConfirmSheet(visible = visible, onDismissRequest = {}, paneTitle = "Eliminare 2.000 elementi?") { BasicText("riepilogo") }
      }
    }
    compose.waitForIdle()
    val decor = compose.activity.window.decorView
    assertThat(decor.filterTouchesWhenObscured).isFalse()
    compose.runOnUiThread { visible = true }
    compose.waitForIdle()
    assertThat(decor.filterTouchesWhenObscured).isTrue()
    compose.runOnUiThread { visible = false }
    compose.waitForIdle()
    assertThat(decor.filterTouchesWhenObscured).isFalse()
  }
}
