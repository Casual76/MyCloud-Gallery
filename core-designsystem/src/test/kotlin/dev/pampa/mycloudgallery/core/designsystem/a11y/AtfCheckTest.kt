package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.checkRoboAccessibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatusIndicator
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D28, §5.3 punto 2): il controllo ATF (Accessibility Test Framework, via `roborazzi-accessibility-check`) sui componenti
 * del design system. Come per i moduli, e' al livello di serie (errore): un errore fa fallire la prova. Ogni modulo ha le sue prove `atf*`
 * sulle schermate principali (e sulle voci dei cataloghi di Impostazioni e Onboarding); questa le ha sui pezzi comuni.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AtfCheckTest {

  val composeRule = createComposeRule()

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

  @Test
  fun iComponentiDiBaseNonHannoErroriAtf() {
    composeRule.setContent {
      MyCloudGalleryTheme {
        Column(Modifier.fillMaxSize()) {
          GalleryBarButton(icon = Icons.Rounded.Favorite, contentDescription = "Preferito", onClick = {})
          GalleryStatusIndicator(status = GalleryStatus.AllSafe, onClick = {})
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().checkRoboAccessibility()
  }
}
