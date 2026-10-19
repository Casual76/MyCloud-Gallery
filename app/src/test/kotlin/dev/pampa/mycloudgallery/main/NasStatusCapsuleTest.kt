package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.flow.MutableStateFlow
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
 * NA-9 (verifica funzionale): la capsula del NAS nel piede della Libreria ("Lavori fermi") portava l'azione della rete (WireGuard o la
 * password) invece dei dettagli del NAS. Adesso ogni capsula fa la sua cosa: la rete la sua azione, il NAS la Diagnostica.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class NasStatusCapsuleTest {

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

  private fun frozenLibrary(): LibraryTabState {
    val base = syntheticLibraryTabState(count = 200)
    return LibraryTabState(
      grid = base.grid,
      viewerItems = base.viewerItems,
      viewerImages = base.viewerImages,
      photoCount = 0,
      videoCount = 0,
      timeline = base.timeline,
      preferences = base.preferences,
      phone = base.phone,
      nas = MutableStateFlow(LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 1_000L)),
    )
  }

  @Test
  fun na9_laCapsulaDelNasApreIDettagliDelNasNonL_azioneDellaRete() {
    var network = 0
    var nas = 0
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        MainScaffold(
          status = GlobalStatus.VPN_OFF,
          selectedTab = dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab.Library,
          onSelectTab = {},
          onStatusClick = { network++ },
          onOpenSettings = {},
          onNasStatusClick = { nas++ },
          libraryState = frozenLibrary(),
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Lavori fermi: cambiamenti anomali", substring = true).performClick()

    assertThat(nas).isEqualTo(1)
    assertThat(network).isEqualTo(0)
  }
}
